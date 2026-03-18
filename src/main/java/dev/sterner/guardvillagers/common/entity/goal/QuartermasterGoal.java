package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.BellChestMappingState;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageBellChestPlacementHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cluster 3 — Quartermaster Goal (added to a Librarian villager after double-chest promotion).
 *
 * <p>The Quartermaster is a <em>proactive material accelerator</em> on top of the existing
 * librarian distribution goal.  It polls the village state every
 * {@link #CHECK_INTERVAL_TICKS} ticks and performs one of the following priority-ordered
 * actions:
 *
 * <ol>
 *   <li><b>Mason building wall</b> → haul stone from bell chest to mason's paired chest.</li>
 *   <li><b>Lumberjack crafting</b> → haul planks/wood from bell chest to lumberjack's chest.</li>
 *   <li><b>Village chest low</b> → haul from any over-stocked profession chest to bell chest.</li>
 * </ol>
 *
 * <p>The Quartermaster's paired chest is used as the transit buffer.  The bell chest is
 * resolved via {@link BellChestMappingState}.
 */
public class QuartermasterGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuartermasterGoal.class);

    private static final int CHECK_INTERVAL_TICKS = 300;
    private static final double SCAN_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;
    private static final double MOVE_SPEED = 0.55D;
    private static final double REACH_SQ = 3.0D * 3.0D;
    /** Minimum stone in mason chest before we top it up. */
    private static final int MASON_STONE_THRESHOLD = 32;
    /** Minimum planks in lumberjack chest before we top it up. */
    private static final int LUMBERJACK_PLANK_THRESHOLD = 16;
    /** Bell chest is considered "low" if total items < this. */
    private static final int BELL_CHEST_LOW_THRESHOLD = 32;
    /** Amount to transfer per haul trip. */
    private static final int HAUL_AMOUNT = 16;

    private final VillagerEntity villager;
    private final BlockPos jobPos;
    private final BlockPos chestPos;  // librarian's paired chest (double-chest second half)

    private long nextCheckTick = 0L;
    private Stage stage = Stage.IDLE;

    // Current active transfer
    private BlockPos sourcePos = null;
    private BlockPos destPos = null;
    private ItemStack transferStack = ItemStack.EMPTY;

    public QuartermasterGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos;
        this.chestPos = chestPos;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive()) return false;
        if (world.getTime() < nextCheckTick) return false;

        nextCheckTick = world.getTime() + CHECK_INTERVAL_TICKS;
        return tryPlanTransfer(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_SOURCE;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.IDLE;
        sourcePos = null;
        destPos = null;
        transferStack = ItemStack.EMPTY;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_SOURCE -> {
                if (sourcePos == null) { stage = Stage.DONE; return; }
                if (isNear(sourcePos)) {
                    stage = Stage.TAKE_FROM_SOURCE;
                } else {
                    moveTo(sourcePos);
                }
            }
            case TAKE_FROM_SOURCE -> {
                if (!takeFromInventory(world, sourcePos)) {
                    stage = Stage.DONE;
                    return;
                }
                stage = Stage.MOVE_TO_DEST;
            }
            case MOVE_TO_DEST -> {
                if (destPos == null) { stage = Stage.DONE; return; }
                if (isNear(destPos)) {
                    stage = Stage.INSERT_TO_DEST;
                } else {
                    moveTo(destPos);
                }
            }
            case INSERT_TO_DEST -> {
                insertToInventory(world, destPos);
                stage = Stage.DONE;
            }
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Transfer planning
    // -------------------------------------------------------------------------

    private boolean tryPlanTransfer(ServerWorld world) {
        BlockPos bellChestPos = resolveBellChestPos(world);

        // Priority 1: mason is building (low on stone) → top up from bell chest
        Optional<MasonGuardEntity> masonNeedingStone = findMasonNeedingStone(world);
        if (masonNeedingStone.isPresent() && bellChestPos != null) {
            int stoneInBell = countItem(world, bellChestPos, Items.COBBLESTONE);
            if (stoneInBell > 0) {
                sourcePos = bellChestPos;
                destPos = masonNeedingStone.get().getPairedChestPos();
                transferStack = new ItemStack(Items.COBBLESTONE, Math.min(HAUL_AMOUNT, stoneInBell));
                LOGGER.debug("QM {}: hauling stone from bell chest to mason {}", villager.getUuidAsString(), destPos.toShortString());
                return destPos != null;
            }
        }

        // Priority 2: lumberjack crafting (low on planks) → top up from bell chest.
        // Use any plank type (tag-based), pick the most abundant stack in the bell chest
        // so we don't artificially lock onto oak when e.g. birch is available.
        Optional<LumberjackGuardEntity> lumberjackNeedingPlanks = findLumberjackNeedingPlanks(world);
        if (lumberjackNeedingPlanks.isPresent() && bellChestPos != null) {
            ItemStack bestPlanks = findBestTagItem(world, bellChestPos, ItemTags.PLANKS);
            if (!bestPlanks.isEmpty()) {
                sourcePos = bellChestPos;
                destPos = lumberjackNeedingPlanks.get().getPairedChestPos();
                transferStack = bestPlanks.copyWithCount(Math.min(HAUL_AMOUNT, bestPlanks.getCount()));
                LOGGER.debug("QM {}: hauling {} planks from bell chest to lumberjack {}", villager.getUuidAsString(), bestPlanks.getItem(), destPos.toShortString());
                return destPos != null;
            }
        }

        // Priority 3: bell chest is low → find any profession chest with surplus and haul to bell chest
        if (bellChestPos != null) {
            int bellTotal = countAllItems(world, bellChestPos);
            if (bellTotal < BELL_CHEST_LOW_THRESHOLD) {
                Optional<BlockPos> surplusChest = findSurplusChest(world, bellChestPos);
                if (surplusChest.isPresent()) {
                    ItemStack topItem = findTopItem(world, surplusChest.get());
                    if (!topItem.isEmpty()) {
                        sourcePos = surplusChest.get();
                        destPos = bellChestPos;
                        transferStack = topItem.copyWithCount(Math.min(HAUL_AMOUNT, topItem.getCount()));
                        LOGGER.debug("QM {}: hauling surplus from {} to bell chest", villager.getUuidAsString(), sourcePos.toShortString());
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Scouts
    // -------------------------------------------------------------------------

    private Optional<MasonGuardEntity> findMasonNeedingStone(ServerWorld world) {
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        return world.getEntitiesByClass(MasonGuardEntity.class, box,
                mason -> mason.isAlive()
                        && mason.getPairedChestPos() != null
                        && !mason.getWallSegments().isEmpty()
                        && countItem(world, mason.getPairedChestPos(), Items.COBBLESTONE) < MASON_STONE_THRESHOLD
        ).stream().findFirst();
    }

    private Optional<LumberjackGuardEntity> findLumberjackNeedingPlanks(ServerWorld world) {
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        return world.getEntitiesByClass(LumberjackGuardEntity.class, box,
                lj -> lj.isAlive()
                        && lj.getPairedChestPos() != null
                        && countTagItems(world, lj.getPairedChestPos(), ItemTags.PLANKS) < LUMBERJACK_PLANK_THRESHOLD
        ).stream().findFirst();
    }

    private Optional<BlockPos> findSurplusChest(ServerWorld world, BlockPos bellChestPos) {
        // Find any chest near job-site villagers with more items than threshold.
        // Must exclude:
        //   - bellChestPos  (we're trying to fill it, not drain it further)
        //   - chestPos      (the QM's own transit buffer — draining it causes haul loops)
        //   - mason paired chests  (draining them undoes Priority 1 stone hauls)
        //   - lumberjack paired chests (draining them undoes Priority 2 plank hauls)
        Box box = new Box(jobPos).expand(SCAN_RANGE);

        // Build the protected set of specialist chests we must never drain.
        Set<BlockPos> protectedChests = new HashSet<>();
        if (bellChestPos != null) {
            protectedChests.add(bellChestPos);
            // If the bell chest is a double-chest, protect both halves. Otherwise the QM could
            // treat the other half as a surplus source and haul items from it into bellChestPos,
            // which both resolve to the same DoubleInventory — a no-op loop.
            findDoubleChestOtherHalf(world, bellChestPos).ifPresent(protectedChests::add);
        }
        protectedChests.add(chestPos);
        // Also protect the QM's own double-chest other half if present.
        findDoubleChestOtherHalf(world, chestPos).ifPresent(protectedChests::add);
        for (MasonGuardEntity mason : world.getEntitiesByClass(MasonGuardEntity.class, box, MasonGuardEntity::isAlive)) {
            if (mason.getPairedChestPos() != null) {
                protectedChests.add(mason.getPairedChestPos());
                findDoubleChestOtherHalf(world, mason.getPairedChestPos()).ifPresent(protectedChests::add);
            }
        }
        for (LumberjackGuardEntity lj : world.getEntitiesByClass(LumberjackGuardEntity.class, box, LumberjackGuardEntity::isAlive)) {
            if (lj.getPairedChestPos() != null) {
                protectedChests.add(lj.getPairedChestPos());
                findDoubleChestOtherHalf(world, lj.getPairedChestPos()).ifPresent(protectedChests::add);
            }
        }
        // Protect shepherd chests — they contain beds + wool + planks needed for bed economy.
        // Without this, QM Priority-3 hauls beds out of the shepherd chest into the bell chest.
        for (VillagerEntity shepherd : world.getEntitiesByClass(VillagerEntity.class, box,
                v -> v.isAlive() && v.getVillagerData().getProfession() == VillagerProfession.SHEPHERD)) {
            BlockPos jobSite = shepherd.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .filter(gp -> gp.dimension() == world.getRegistryKey())
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (jobSite != null) {
                JobBlockPairingHelper.findNearbyChest(world, jobSite).ifPresent(shepherdChest -> {
                    protectedChests.add(shepherdChest);
                    // Also protect the other half if it is a double-chest.
                    findDoubleChestOtherHalf(world, shepherdChest).ifPresent(protectedChests::add);
                });
            }
        }

        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, box, Entity::isAlive);
        for (VillagerEntity v : villagers) {
            if (v == villager) continue;
            // Check if they have a nearby chest with items
            for (BlockPos candidate : BlockPos.iterate(
                    v.getBlockPos().add(-3, -1, -3),
                    v.getBlockPos().add(3, 1, 3))) {
                if (!world.getBlockState(candidate).getBlock().equals(net.minecraft.block.Blocks.CHEST)) continue;
                BlockPos immutable = candidate.toImmutable();
                // Skip all protected chests (bell, QM transit, mason, lumberjack)
                if (protectedChests.contains(immutable)) continue;
                if (countAllItems(world, immutable) > BELL_CHEST_LOW_THRESHOLD * 2) {
                    return Optional.of(immutable);
                }
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    private boolean takeFromInventory(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty() || transferStack.isEmpty()) return false;
        Inventory inventory = inv.get();
        net.minecraft.item.Item item = transferStack.getItem();
        int needed = transferStack.getCount();
        int taken = 0;

        for (int i = 0; i < inventory.size() && taken < needed; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                int grab = Math.min(stack.getCount(), needed - taken);
                stack.decrement(grab);
                if (stack.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
                taken += grab;
            }
        }

        if (taken > 0) {
            transferStack = new ItemStack(item, taken);
            inventory.markDirty();
            return true;
        }
        return false;
    }

    private void insertToInventory(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty() || transferStack.isEmpty()) return;
        Inventory inventory = inv.get();
        ItemStack remaining = transferStack.copy();

        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing.isEmpty()) {
                if (!inventory.isValid(i, remaining)) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                inventory.setStack(i, remaining.copyWithCount(moved));
                remaining.decrement(moved);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getCount());
                    existing.increment(moved);
                    remaining.decrement(moved);
                }
            }
        }

        inventory.markDirty();
        // If dest chest was full, remaining items would be silently lost.
        // Drop them at the villager's feet so items are never destroyed.
        if (!remaining.isEmpty()) {
            LOGGER.info("QM {}: dest chest at {} full, dropping {} x {} at villager feet",
                    villager.getUuidAsString(), pos.toShortString(),
                    remaining.getCount(), remaining.getItem());
            ItemEntity drop = new ItemEntity(
                    world, villager.getX(), villager.getY(), villager.getZ(), remaining.copy());
            drop.setPickupDelay(10);
            world.spawnEntity(drop);
        }
        transferStack = ItemStack.EMPTY;
        LOGGER.debug("QM {}: delivered to {}", villager.getUuidAsString(), pos.toShortString());
    }

    /** Counts all items matching a tag across all slots in the chest at {@code pos}. */
    private int countTagItems(ServerWorld world, BlockPos pos, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.isIn(tag)) count += stack.getCount();
        }
        return count;
    }

    /**
     * Finds the largest single stack matching {@code tag} in the chest at {@code pos}.
     * Returns a copy, or {@link ItemStack#EMPTY} if none found.
     */
    private ItemStack findBestTagItem(ServerWorld world, BlockPos pos, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.isIn(tag) && stack.getCount() > best.getCount()) {
                best = stack;
            }
        }
        return best.isEmpty() ? ItemStack.EMPTY : best.copy();
    }

    private int countItem(ServerWorld world, BlockPos pos, net.minecraft.item.Item item) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private int countAllItems(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < inv.get().size(); i++) {
            count += inv.get().getStack(i).getCount();
        }
        return count;
    }

    private ItemStack findTopItem(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.getCount() > best.getCount()) {
                best = stack;
            }
        }
        return best.isEmpty() ? ItemStack.EMPTY : best.copy();
    }

    /**
     * If the chest at {@code pos} is one half of a double-chest, returns the position of the
     * other half. Returns empty for single chests or non-chest blocks.
     * Used to add both halves to protected sets so the QM never hauls within the same double-chest.
     */
    private Optional<BlockPos> findDoubleChestOtherHalf(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return Optional.empty();
        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) return Optional.empty();
        Direction facing = state.get(ChestBlock.FACING);
        Direction offsetDir = chestType == ChestType.LEFT
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();
        BlockPos otherHalf = pos.offset(offsetDir).toImmutable();
        BlockState otherState = world.getBlockState(otherHalf);
        if (otherState.getBlock() instanceof ChestBlock) {
            return Optional.of(otherHalf);
        }
        return Optional.empty();
    }

    private Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        // open=false: programmatic access must not trigger chest open-sound / lid animation.
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, false));
    }

    private BlockPos resolveBellChestPos(ServerWorld world) {
        return VillageBellChestPlacementHelper.getMappedChestPos(world, jobPos).orElse(null);
    }

    // -------------------------------------------------------------------------
    // Static utility — used by ArmorerIronRoutingGoal to defer when QM is present
    // -------------------------------------------------------------------------

    /**
     * Returns true if any living Librarian-profession villager with an active
     * QuartermasterGoal exists within {@code range} blocks of {@code anchor}.
     */
    public static boolean isAnyActive(ServerWorld world, BlockPos anchor, double range) {
        Box box = new Box(anchor).expand(range);
        List<net.minecraft.entity.passive.VillagerEntity> librarians = world.getEntitiesByClass(
                net.minecraft.entity.passive.VillagerEntity.class,
                box,
                v -> v.isAlive()
                        && v.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.LIBRARIAN);
        // We treat any alive librarian near the anchor as potential QM — lightweight check.
        return !librarians.isEmpty();
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= REACH_SQ;
    }

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE,
        MOVE_TO_SOURCE,
        TAKE_FROM_SOURCE,
        MOVE_TO_DEST,
        INSERT_TO_DEST,
        DONE
    }
}

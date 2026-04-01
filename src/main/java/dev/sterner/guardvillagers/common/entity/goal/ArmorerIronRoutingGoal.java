package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Fallback iron routing goal for the Armorer, active only when no Quartermaster
 * (double-chest librarian) is present in the village.
 *
 * <p>Priority order (highest to lowest):
 * <ol>
 *   <li>Mason has no pickaxe → route 3 iron to toolsmith if present, else to mason chest directly.</li>
 *   <li>Shepherd has no shears → route 2 iron to toolsmith if present, else to shepherd chest directly.</li>
 *   <li>Otherwise: iron stays in armorer chest for self-crafting armor.</li>
 * </ol>
 *
 * <p>When a Quartermaster is active this goal defers entirely — {@link QuartermasterGoal#isAnyActive}
 * returns true and {@link #canStart()} returns false.
 */
public class ArmorerIronRoutingGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArmorerIronRoutingGoal.class);

    /** How often to poll for routing opportunities. 600 = 30 s. */
    private static final int CHECK_INTERVAL_TICKS = 600;
    /** Iron ingots to route to the toolsmith so it can craft a pickaxe (needs 3). */
    private static final int IRON_FOR_PICKAXE = 3;
    /** Iron ingots to route to the toolsmith so it can craft shears (needs 2). */
    private static final int IRON_FOR_SHEARS = 2;
    /** Scan radius for finding peer villagers / guards. */
    private static final double DEFAULT_SCAN_RANGE = 300.0D;
    private static final double REACH_SQ = 3.5D * 3.5D;
    private static final double MOVE_SPEED = 0.55D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private long nextCheckTick = 0L;
    private Stage stage = Stage.IDLE;

    private BlockPos sourcePos;
    private BlockPos destPos;
    private int ironAmount;

    public ArmorerIronRoutingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    private double getScanRange() {
        int configured = GuardVillagersConfig.armorerFallbackScanRange;
        return configured > 0 ? configured : DEFAULT_SCAN_RANGE;
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive()) return false;
        if (villager.getVillagerData().getProfession() != VillagerProfession.ARMORER) return false;
        if (world.getTime() < nextCheckTick) return false;

        // Defer entirely when a Quartermaster is handling iron routing.
        if (QuartermasterGoal.isAnyActive(world, jobPos, getScanRange())) return false;

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
        ironAmount = 0;
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
                    stage = Stage.TAKE_IRON;
                } else {
                    moveTo(sourcePos);
                }
            }
            case TAKE_IRON -> {
                if (!takeIronFromSource(world)) {
                    stage = Stage.DONE;
                    return;
                }
                stage = Stage.MOVE_TO_DEST;
            }
            case MOVE_TO_DEST -> {
                if (destPos == null) { stage = Stage.DONE; return; }
                if (isNear(destPos)) {
                    stage = Stage.INSERT_IRON;
                } else {
                    moveTo(destPos);
                }
            }
            case INSERT_IRON -> {
                insertIronToDest(world);
                stage = Stage.DONE;
            }
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Transfer planning
    // -------------------------------------------------------------------------

    /**
     * Evaluates routing priorities and sets sourcePos/destPos/ironAmount if a
     * transfer is warranted. Returns true if a transfer was planned.
     *
     * <p>Priority 1: Mason needs a pickaxe — route iron to toolsmith (or mason).
     * <p>Priority 2: Shepherd needs shears — route iron to toolsmith (or shepherd).
     */
    private boolean tryPlanTransfer(ServerWorld world) {
        int ironInChest = countIronInChest(world, chestPos);
        if (ironInChest <= 0) return false;

        Box scanBox = new Box(jobPos).expand(getScanRange());

        // --- Priority 1: mason missing a pickaxe ---
        if (ironInChest >= IRON_FOR_PICKAXE) {
            Optional<MasonGuardEntity> masonWithoutPick = world.getEntitiesByClass(
                    MasonGuardEntity.class, scanBox,
                    mason -> mason.isAlive()
                            && mason.getPairedChestPos() != null
                            && !hasPickaxe(world, mason.getPairedChestPos())
            ).stream().findFirst();

            if (masonWithoutPick.isPresent()) {
                BlockPos toolsmithChest = findToolsmithChest(world, scanBox);
                if (toolsmithChest != null) {
                    sourcePos = chestPos;
                    destPos = toolsmithChest;
                    ironAmount = IRON_FOR_PICKAXE;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("ArmorerIronRouting: routing {} iron to toolsmith {} for mason pickaxe (no QM)",
                                ironAmount, destPos.toShortString());
                    }
                    return true;
                }
                // No toolsmith — route direct to mason chest
                sourcePos = chestPos;
                destPos = masonWithoutPick.get().getPairedChestPos();
                ironAmount = IRON_FOR_PICKAXE;
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("ArmorerIronRouting: routing {} iron direct to mason {} (no toolsmith, no QM)",
                            ironAmount, destPos.toShortString());
                }
                return true;
            }
        }

        // --- Priority 2: shepherd missing shears ---
        if (ironInChest >= IRON_FOR_SHEARS) {
            Optional<VillagerEntity> shepherdWithoutShears = world.getEntitiesByClass(
                    VillagerEntity.class, scanBox,
                    v -> v.isAlive()
                            && v.getVillagerData().getProfession() == VillagerProfession.SHEPHERD
                            && !hasShears(world, v)
            ).stream().findFirst();

            if (shepherdWithoutShears.isPresent()) {
                BlockPos toolsmithChest = findToolsmithChest(world, scanBox);
                if (toolsmithChest != null) {
                    sourcePos = chestPos;
                    destPos = toolsmithChest;
                    ironAmount = IRON_FOR_SHEARS;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("ArmorerIronRouting: routing {} iron to toolsmith {} for shepherd shears (no QM)",
                                ironAmount, destPos.toShortString());
                    }
                    return true;
                }
                // No toolsmith — route iron directly to shepherd chest
                BlockPos shepherdChest = findShepherdChest(world, shepherdWithoutShears.get(), scanBox);
                if (shepherdChest != null) {
                    sourcePos = chestPos;
                    destPos = shepherdChest;
                    ironAmount = IRON_FOR_SHEARS;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("ArmorerIronRouting: routing {} iron direct to shepherd {} (no toolsmith, no QM)",
                                ironAmount, destPos.toShortString());
                    }
                    return true;
                }
            }
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Scouts
    // -------------------------------------------------------------------------

    /** Returns the first toolsmith's paired chest within scan range, or null. */
    private BlockPos findToolsmithChest(ServerWorld world, Box scanBox) {
        return world.getEntitiesByClass(VillagerEntity.class, scanBox,
                v -> v.isAlive() && v.getVillagerData().getProfession() == VillagerProfession.TOOLSMITH
        ).stream()
                .map(v -> dev.sterner.guardvillagers.common.util.JobBlockPairingHelper.findNearbyChest(world, v.getBlockPos()).orElse(null))
                .filter(pos -> pos != null)
                .findFirst()
                .orElse(null);
    }

    /** Returns the shepherd's paired chest (nearest chest to their job site), or null. */
    private BlockPos findShepherdChest(ServerWorld world, VillagerEntity shepherd, Box scanBox) {
        return dev.sterner.guardvillagers.common.util.JobBlockPairingHelper
                .findNearbyChest(world, shepherd.getBlockPos())
                .orElse(null);
    }

    /** True if the inventory at {@code chestPos} contains any pickaxe. */
    private boolean hasPickaxe(ServerWorld world, BlockPos chest) {
        return getInventory(world, chest)
                .map(inv -> {
                    for (int i = 0; i < inv.size(); i++) {
                        if (inv.getStack(i).getItem() instanceof PickaxeItem) return true;
                    }
                    return false;
                }).orElse(false);
    }

    /** True if the shepherd has shears in their chest or inventory/hand. */
    private boolean hasShears(ServerWorld world, VillagerEntity shepherd) {
        // Check villager inventory and hand
        Inventory inv = shepherd.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).getItem() instanceof ShearsItem) return true;
        }
        if (shepherd.getMainHandStack().getItem() instanceof ShearsItem) return true;

        // Check their paired chest
        BlockPos chest = dev.sterner.guardvillagers.common.util.JobBlockPairingHelper
                .findNearbyChest(world, shepherd.getBlockPos()).orElse(null);
        if (chest == null) return false;
        return getInventory(world, chest).map(chestInv -> {
            for (int i = 0; i < chestInv.size(); i++) {
                if (chestInv.getStack(i).isOf(Items.SHEARS)) return true;
            }
            return false;
        }).orElse(false);
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    private int countIronInChest(ServerWorld world, BlockPos pos) {
        return getInventory(world, pos).map(inv -> {
            int count = 0;
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isOf(Items.IRON_INGOT)) count += stack.getCount();
            }
            return count;
        }).orElse(0);
    }

    private boolean takeIronFromSource(ServerWorld world) {
        Optional<Inventory> opt = getInventory(world, sourcePos);
        if (opt.isEmpty() || ironAmount <= 0) return false;
        Inventory inv = opt.get();
        int remaining = ironAmount;
        int taken = 0;
        for (int i = 0; i < inv.size() && taken < remaining; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !stack.isOf(Items.IRON_INGOT)) continue;
            int grab = Math.min(stack.getCount(), remaining - taken);
            stack.decrement(grab);
            if (stack.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
            taken += grab;
        }
        if (taken > 0) {
            ironAmount = taken;
            inv.markDirty();
            return true;
        }
        return false;
    }

    private void insertIronToDest(ServerWorld world) {
        Optional<Inventory> opt = getInventory(world, destPos);
        if (opt.isEmpty() || ironAmount <= 0) return;
        Inventory inv = opt.get();
        ItemStack toInsert = new ItemStack(Items.IRON_INGOT, ironAmount);

        for (int i = 0; i < inv.size() && !toInsert.isEmpty(); i++) {
            ItemStack existing = inv.getStack(i);
            if (existing.isEmpty()) {
                if (!inv.isValid(i, toInsert)) continue;
                int moved = Math.min(toInsert.getCount(), toInsert.getMaxCount());
                inv.setStack(i, toInsert.copyWithCount(moved));
                toInsert.decrement(moved);
            } else if (existing.isOf(Items.IRON_INGOT)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, toInsert.getCount());
                    existing.increment(moved);
                    toInsert.decrement(moved);
                }
            }
        }

        inv.markDirty();
        LOGGER.debug("ArmorerIronRouting: delivered {} iron to {}", ironAmount - toInsert.getCount(), destPos.toShortString());
        ironAmount = 0;
    }

    private Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, false));
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= REACH_SQ;
    }

    // -------------------------------------------------------------------------
    // Enum
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE, MOVE_TO_SOURCE, TAKE_IRON, MOVE_TO_DEST, INSERT_IRON, DONE
    }
}

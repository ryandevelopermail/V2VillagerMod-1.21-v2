package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

public final class ToolsmithDemandPlanner {
    // 64 blocks — generous enough to reach profession job blocks across a typical village
    // layout without relying on villagers being near the toolsmith at scan time.
    // Previously 24 blocks, which was too tight for fisherman barrels placed across a village.
    public static final double RECIPIENT_SCAN_RANGE = 64.0D;

    private ToolsmithDemandPlanner() {
    }

    public static DemandSnapshot buildSnapshot(ServerWorld world, VillagerEntity toolsmith, Inventory sourceInventory) {
        EnumMap<ToolType, ToolDemand> demandByType = new EnumMap<>(ToolType.class);

        // Handle all non-fisherman tool types via the standard VillagerEntity recipient path.
        for (ToolType toolType : ToolType.values()) {
            if (toolType == ToolType.FISHING_ROD) {
                continue; // handled separately below
            }
            List<DistributionRecipientHelper.RecipientRecord> recipients = findRecipients(world, toolsmith, toolType);
            List<RecipientDemand> rankedRecipients = rankRecipients(world, recipients, toolType);
            int sourceStock = countItemInInventory(sourceInventory, toolType.exactItem(), toolType.itemClass());
            int demandDeficit = recipients.size() - (sourceStock / toolType.targetPerRecipient());
            demandByType.put(toolType, new ToolDemand(toolType, sourceStock, recipients.size(), demandDeficit, rankedRecipients));
        }

        // FISHING_ROD: FishermanGuardEntity extends GuardEntity, not VillagerEntity.
        // Scan for them directly and store ranked entries in a separate list within DemandSnapshot.
        List<FishermanGuardEntry> fishermanEntries = findFishermanGuardEntries(world, toolsmith);
        int rodStock = countItemInInventory(sourceInventory, ToolType.FISHING_ROD.exactItem(), ToolType.FISHING_ROD.itemClass());
        List<FishermanGuardEntry> rankedFishermen = fishermanEntries.stream()
                .filter(e -> e.deficit(world) > 0)
                .toList(); // already sorted by squaredDistance in findFishermanGuardEntries
        int fishermanDeficit = fishermanEntries.size() - (rodStock / ToolType.FISHING_ROD.targetPerRecipient());
        demandByType.put(ToolType.FISHING_ROD, new ToolDemand(ToolType.FISHING_ROD, rodStock, fishermanEntries.size(), fishermanDeficit, List.of()));

        return new DemandSnapshot(demandByType, rankedFishermen);
    }

    private static List<DistributionRecipientHelper.RecipientRecord> findRecipients(ServerWorld world, VillagerEntity toolsmith, ToolType toolType) {
        List<DistributionRouteEngine.ProfessionRoute> routes = switch (toolType) {
            case HOE -> List.of(route(VillagerProfession.FARMER));
            case PICKAXE -> List.of(route(VillagerProfession.MASON));
            case SHEARS -> List.of(route(VillagerProfession.SHEPHERD));
            // FISHING_ROD: FishermanGuardEntity is not a VillagerEntity — never reached here.
            default -> List.of();
        };
        return new ArrayList<>(DistributionRouteEngine.findEligibleRecipients(world, toolsmith, RECIPIENT_SCAN_RANGE, routes));
    }

    /**
     * Finds FishermanGuardEntity instances near the toolsmith. Returns {@link FishermanGuardEntry}
     * objects (which ARE NOT RecipientRecord — they carry a GuardEntity reference).
     * Callers that care about the FISHING_ROD path must use this method directly; the standard
     * RecipientRecord list never contains fisherman guards.
     */
    public static List<FishermanGuardEntry> findFishermanGuardEntries(ServerWorld world, VillagerEntity toolsmith) {
        List<FishermanGuardEntry> entries = new ArrayList<>();
        Box scanBox = new Box(toolsmith.getBlockPos()).expand(RECIPIENT_SCAN_RANGE);
        for (FishermanGuardEntity fisherman : world.getEntitiesByClass(FishermanGuardEntity.class, scanBox,
                candidate -> candidate.isAlive() && !candidate.isBaby())) {
            BlockPos jobPos = fisherman.getPairedJobPos();
            if (jobPos == null) {
                continue;
            }
            BlockPos chestPos = resolveFishermanStoragePos(world, fisherman, jobPos);
            if (chestPos == null) {
                continue;
            }
            entries.add(new FishermanGuardEntry(fisherman, jobPos.toImmutable(), chestPos.toImmutable(),
                    toolsmith.squaredDistanceTo(fisherman)));
        }
        entries.sort(Comparator.comparingDouble(FishermanGuardEntry::squaredDistance)
                .thenComparing(e -> e.guard().getUuid(), java.util.UUID::compareTo));
        return entries;
    }

    @Nullable
    private static BlockPos resolveFishermanStoragePos(ServerWorld world, FishermanGuardEntity fisherman, BlockPos jobPos) {
        BlockPos pairedChestPos = fisherman.getPairedChestPos();
        if (isValidFishermanStorage(world, pairedChestPos, jobPos) && pairedChestPos != null) {
            return pairedChestPos.toImmutable();
        }

        // Recovery path for stale pairings or missing chest memory:
        // resolve a nearby storage candidate, preferring chest/trapped chest over barrels.
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        BlockPos bestPos = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos candidate : BlockPos.iterate(jobPos.add(-range, -range, -range), jobPos.add(range, range, range))) {
            if (candidate.equals(jobPos) || !jobPos.isWithinDistance(candidate, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
                continue;
            }
            BlockState state = world.getBlockState(candidate);
            if (!JobBlockPairingHelper.isPairingBlock(state) || !getStorageInventory(world, candidate).isPresent()) {
                continue;
            }

            int priority = storagePriority(state);
            double distance = jobPos.getSquaredDistance(candidate);
            if (priority < bestPriority || (priority == bestPriority && distance < bestDistance)) {
                bestPriority = priority;
                bestDistance = distance;
                bestPos = candidate.toImmutable();
            }
        }

        return bestPos;
    }

    private static boolean isValidFishermanStorage(ServerWorld world, @Nullable BlockPos chestPos, BlockPos jobPos) {
        if (chestPos == null || chestPos.equals(jobPos)) {
            return false;
        }
        if (!jobPos.isWithinDistance(chestPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
            return false;
        }
        return getStorageInventory(world, chestPos).isPresent();
    }

    private static int storagePriority(BlockState state) {
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
            return 0;
        }
        if (state.isOf(Blocks.BARREL)) {
            return 1;
        }
        return 2;
    }

    /** Lightweight entry for a FishermanGuardEntity recipient — avoids the VillagerEntity requirement of RecipientRecord. */
    public record FishermanGuardEntry(FishermanGuardEntity guard, BlockPos jobPos, BlockPos chestPos, double squaredDistance) {
        public int rodStockInChest(ServerWorld world) {
            return countItemInRecipientStorage(world, chestPos, ToolType.FISHING_ROD);
        }

        public int deficit(ServerWorld world) {
            return Math.max(0, ToolType.FISHING_ROD.targetPerRecipient() - rodStockInChest(world));
        }
    }

    private static DistributionRouteEngine.ProfessionRoute route(VillagerProfession profession) {
        return ProfessionDefinitions.get(profession)
                .flatMap(def -> def.expectedJobBlocks().stream().findFirst())
                .map(block -> new DistributionRouteEngine.ProfessionRoute(profession, block, false))
                .orElseThrow(() -> new IllegalStateException("Missing profession definition for " + profession));
    }

    private static List<RecipientDemand> rankRecipients(ServerWorld world, List<DistributionRecipientHelper.RecipientRecord> recipients, ToolType toolType) {
        List<RecipientDemand> ranked = new ArrayList<>();
        for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
            int stock = countItemInRecipientStorage(world, recipient.chestPos(), toolType);
            int deficit = Math.max(0, toolType.targetPerRecipient() - stock);
            if (deficit <= 0) {
                continue;
            }
            ranked.add(new RecipientDemand(recipient, stock, deficit));
        }

        ranked.sort(Comparator
                .comparingInt(RecipientDemand::deficit).reversed()
                .thenComparingDouble(recipient -> recipient.record().sourceSquaredDistance())
                .thenComparing(recipient -> recipient.record().recipient().getUuid(), java.util.UUID::compareTo));
        return List.copyOf(ranked);
    }

    private static int countItemInRecipientStorage(ServerWorld world, BlockPos storagePos, ToolType toolType) {
        Optional<Inventory> inventory = getStorageInventory(world, storagePos);
        return inventory.map(value -> countItemInInventory(value, toolType.exactItem(), toolType.itemClass())).orElse(0);
    }

    private static Optional<Inventory> getStorageInventory(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, false));
        }
        if (state.getBlock() instanceof BarrelBlock) {
            BlockEntity blockEntity = world.getBlockEntity(position);
            if (blockEntity instanceof Inventory inventory) {
                return Optional.of(inventory);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static int countItemInInventory(Inventory inventory, @Nullable Item exactItem, @Nullable Class<?> itemClass) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if ((exactItem != null && stack.isOf(exactItem)) || (itemClass != null && itemClass.isInstance(stack.getItem()))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public enum ToolType {
        PICKAXE("pickaxe", PickaxeItem.class, null, 1, 0),
        FISHING_ROD("fishing rod", null, Items.FISHING_ROD, 1, 1),
        HOE("hoe", HoeItem.class, null, 1, 2),
        SHEARS("shears", ShearsItem.class, null, 1, 3),
        SHOVEL("shovel", ShovelItem.class, null, 1, 4);

        private final String label;
        private final Class<?> itemClass;
        private final Item exactItem;
        private final int targetPerRecipient;
        private final int fallbackPriority;

        ToolType(String label, Class<?> itemClass, Item exactItem, int targetPerRecipient, int fallbackPriority) {
            this.label = label;
            this.itemClass = itemClass;
            this.exactItem = exactItem;
            this.targetPerRecipient = targetPerRecipient;
            this.fallbackPriority = fallbackPriority;
        }

        @Nullable
        public static ToolType fromStack(ItemStack stack) {
            if (stack.isEmpty()) {
                return null;
            }
            if (stack.getItem() instanceof PickaxeItem) {
                return PICKAXE;
            }
            if (stack.isOf(Items.FISHING_ROD)) {
                return FISHING_ROD;
            }
            if (stack.getItem() instanceof HoeItem) {
                return HOE;
            }
            if (stack.getItem() instanceof ShearsItem) {
                return SHEARS;
            }
            if (stack.getItem() instanceof ShovelItem) {
                return SHOVEL;
            }
            return null;
        }

        public String label() {
            return label;
        }

        public Class<?> itemClass() {
            return itemClass;
        }

        public Item exactItem() {
            return exactItem;
        }

        public int targetPerRecipient() {
            return targetPerRecipient;
        }

        public int fallbackPriority() {
            return fallbackPriority;
        }
    }

    public record RecipientDemand(DistributionRecipientHelper.RecipientRecord record, int currentStock, int deficit) {
    }

    public record ToolDemand(ToolType toolType, int sourceStock, int recipientCount, int demandDeficit,
                             List<RecipientDemand> rankedRecipients) {
    }

    public record DemandSnapshot(EnumMap<ToolType, ToolDemand> demandByType, List<FishermanGuardEntry> rankedFishermanEntries) {
        public ToolDemand demandFor(ToolType toolType) {
            return demandByType.get(toolType);
        }

        public int deficitFor(ToolType toolType) {
            ToolDemand demand = demandFor(toolType);
            return demand == null ? 0 : demand.demandDeficit();
        }

        public List<RecipientDemand> rankedRecipientsFor(ToolType toolType) {
            // FISHING_ROD recipients are FishermanGuardEntry — use rankedFishermanEntries() for that path.
            if (toolType == ToolType.FISHING_ROD) {
                return List.of();
            }
            ToolDemand demand = demandFor(toolType);
            return demand == null ? List.of() : demand.rankedRecipients();
        }

        public String compactSummary() {
            ToolDemand pickaxe = demandByType.get(ToolType.PICKAXE);
            ToolDemand rod = demandByType.get(ToolType.FISHING_ROD);
            ToolDemand hoe = demandByType.get(ToolType.HOE);
            ToolDemand shears = demandByType.get(ToolType.SHEARS);
            return "demand snapshot [pickaxe=" + pickaxe.demandDeficit() + "/" + pickaxe.recipientCount()
                    + ", rod=" + rod.demandDeficit() + "/" + rod.recipientCount()
                    + ", hoe=" + hoe.demandDeficit() + "/" + hoe.recipientCount()
                    + ", shears=" + shears.demandDeficit() + "/" + shears.recipientCount() + "]";
        }
    }
}

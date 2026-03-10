package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.behavior.LumberjackChestTriggerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class LumberjackChestTriggerController {
    private static final long EVALUATION_INTERVAL_TICKS = 100L;
    private static final long RULE_COOLDOWN_TICKS = 40L;
    private static final double VILLAGE_EXPANSION_SCAN_RADIUS = 300.0D;
    private static final long VILLAGE_EXPANSION_SCAN_INTERVAL_TICKS = 400L;
    private static final long VILLAGE_EXPANSION_SCAN_JITTER_TICKS = 120L;

    private static final List<TriggerRule> RULES = List.of(
            new TriggerRule("craft_equip_best_axe", 100,
                    LumberjackChestTriggerController::shouldCraftOrEquipAxe,
                    LumberjackChestTriggerController::craftOrEquipBestAxe),
            new TriggerRule("craft_place_furnace_modifier", 200,
                    LumberjackChestTriggerController::shouldCraftOrPlaceFurnace,
                    LumberjackChestTriggerController::craftOrPlaceFurnace),
            new TriggerRule("feed_furnace_workflow", 300,
                    LumberjackChestTriggerController::shouldFeedFurnaceWorkflow,
                    LumberjackChestTriggerController::feedFurnaceWorkflow)
    ).stream().sorted(Comparator.comparingInt(TriggerRule::priority)).toList();

    private LumberjackChestTriggerController() {
    }

    public static void tick(ServerWorld world, LumberjackGuardEntity guard) {
        if (!guard.isAlive()) {
            LumberjackChestTriggerBehavior.clearChestWatcher(guard);
            return;
        }

        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos != null) {
            LumberjackChestTriggerBehavior.updateChestWatcher(world, guard, chestPos);
        } else {
            LumberjackChestTriggerBehavior.clearChestWatcher(guard);
        }

        long now = world.getTime();
        if (now >= guard.getNextVillageExpansionScanTick()) {
            runVillageExpansionScan(world, guard);
            long jitter = world.random.nextInt((int) VILLAGE_EXPANSION_SCAN_JITTER_TICKS + 1);
            guard.setNextVillageExpansionScanTick(now + VILLAGE_EXPANSION_SCAN_INTERVAL_TICKS + jitter);
        }

        if (!guard.isTriggerEvaluationRequested() && now < guard.getNextTriggerEvaluationTick()) {
            return;
        }

        evaluateRules(world, guard);
        guard.clearTriggerEvaluationRequest();
        guard.setNextTriggerEvaluationTick(now + EVALUATION_INTERVAL_TICKS);
    }

    private static void evaluateRules(ServerWorld world, LumberjackGuardEntity guard) {
        TriggerContext context = new TriggerContext(world, guard, resolveChestInventory(world, guard));
        long now = world.getTime();

        for (TriggerRule rule : RULES) {
            if (!rule.predicate().test(context)) {
                continue;
            }
            if (guard.getLastTriggerRuleId().equals(rule.id()) && now - guard.getLastTriggerActionTick() < RULE_COOLDOWN_TICKS) {
                continue;
            }
            if (rule.action().test(context)) {
                guard.recordTriggerAction(now, rule.id());
                return;
            }
        }
    }

    private static boolean shouldCraftOrEquipAxe(TriggerContext context) {
        if (isAxe(context.guard().getMainHandStack())) {
            return false;
        }
        if (hasAnyAxeAvailable(context)) {
            return true;
        }
        return canCraftAxe(context, Items.NETHERITE_INGOT)
                || canCraftAxe(context, Items.DIAMOND)
                || canCraftAxe(context, Items.IRON_INGOT)
                || canCraftAxe(context, Items.COBBLESTONE)
                || canCraftAxe(context, Items.OAK_PLANKS);
    }

    private static boolean craftOrEquipBestAxe(TriggerContext context) {
        for (Item axe : List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE)) {
            if (equipAxeFromStorage(context, axe)) {
                return true;
            }
        }

        if (craftAndEquipAxe(context, Items.NETHERITE_INGOT, Items.NETHERITE_AXE)) {
            return true;
        }
        if (craftAndEquipAxe(context, Items.DIAMOND, Items.DIAMOND_AXE)) {
            return true;
        }
        if (craftAndEquipAxe(context, Items.IRON_INGOT, Items.IRON_AXE)) {
            return true;
        }
        if (craftAndEquipAxe(context, Items.COBBLESTONE, Items.STONE_AXE)) {
            return true;
        }
        return craftAndEquipWoodAxe(context);
    }

    private static boolean shouldCraftOrPlaceFurnace(TriggerContext context) {
        BlockPos modifierPos = context.guard().getPairedFurnaceModifierPos();
        if (modifierPos != null && context.world().getBlockState(modifierPos).isOf(Blocks.FURNACE)) {
            return false;
        }
        if (modifierPos != null && !context.world().getBlockState(modifierPos).isAir()) {
            context.guard().setPairedFurnaceModifierPos(null);
            return false;
        }
        return countByItem(context, Items.COBBLESTONE) >= 8;
    }

    private static boolean craftOrPlaceFurnace(TriggerContext context) {
        BlockPos tablePos = context.guard().getPairedCraftingTablePos();
        if (tablePos == null) {
            return false;
        }
        if (!consumeByItem(context, Items.COBBLESTONE, 8)) {
            return false;
        }

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = tablePos.offset(direction);
            BlockPos below = candidate.down();
            if (!context.world().getBlockState(candidate).isAir()) {
                continue;
            }
            if (!context.world().getBlockState(below).isSolidBlock(context.world(), below)) {
                continue;
            }
            if (context.world().setBlockState(candidate, Blocks.FURNACE.getDefaultState())) {
                context.guard().setPairedFurnaceModifierPos(candidate);
                return true;
            }
        }

        addToBuffer(context.guard(), new ItemStack(Items.FURNACE));
        return true;
    }

    private static boolean shouldFeedFurnaceWorkflow(TriggerContext context) {
        BlockPos modifierPos = context.guard().getPairedFurnaceModifierPos();
        if (modifierPos == null || !context.world().getBlockState(modifierPos).isOf(Blocks.FURNACE)) {
            return false;
        }
        return countMatching(context, stack -> stack.isIn(ItemTags.LOGS)) > 0 || hasFurnaceOutput(context.world(), modifierPos);
    }

    private static boolean feedFurnaceWorkflow(TriggerContext context) {
        BlockPos modifierPos = context.guard().getPairedFurnaceModifierPos();
        if (modifierPos == null) {
            return false;
        }
        if (!(context.world().getBlockEntity(modifierPos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        boolean changed = false;
        ItemStack output = furnace.getStack(2);
        if (!output.isEmpty()) {
            addToBuffer(context.guard(), output.copy());
            furnace.setStack(2, ItemStack.EMPTY);
            changed = true;
        }

        if (furnace.getStack(0).isEmpty()) {
            ItemStack logs = takeOneMatching(context, stack -> stack.isIn(ItemTags.LOGS));
            if (!logs.isEmpty()) {
                furnace.setStack(0, logs);
                changed = true;
            }
        }

        if (furnace.getStack(1).isEmpty()) {
            ItemStack fuel = takeOneMatching(context, stack -> stack.isIn(ItemTags.LOGS));
            if (!fuel.isEmpty()) {
                furnace.setStack(1, fuel);
                changed = true;
            }
        }

        if (changed) {
            furnace.markDirty();
        }
        return changed;
    }

    private static void runVillageExpansionScan(ServerWorld world, LumberjackGuardEntity guard) {
        TriggerContext context = new TriggerContext(world, guard, resolveChestInventory(world, guard));
        if (tryPlaceChestForEligibleV1Villager(context)) {
            guard.recordTriggerAction(world.getTime(), "scan_place_chest_for_v1");
            return;
        }
        if (tryPlaceCraftingTableForEligibleV2Villager(context)) {
            guard.recordTriggerAction(world.getTime(), "scan_place_crafting_table_for_v2");
        }
    }

    private static boolean tryPlaceChestForEligibleV1Villager(TriggerContext context) {
        if (countByItem(context, Items.CHEST) <= 0 && countByItem(context, Items.TRAPPED_CHEST) <= 0) {
            return false;
        }

        for (VillagerEntity villager : collectNearbyVillagers(context.world(), context.guard())) {
            if (!isEligibleV1Villager(context.world(), villager)) {
                continue;
            }
            BlockPos jobPos = resolveVillagerJobSite(context.world(), villager);
            if (jobPos == null || JobBlockPairingHelper.findNearbyChest(context.world(), jobPos).isPresent()) {
                continue;
            }

            BlockPos placePos = findPlacementNear(context.world(), jobPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
            if (placePos == null) {
                continue;
            }

            if (countByItem(context, Items.CHEST) > 0 && consumeByItem(context, Items.CHEST, 1)) {
                if (context.world().setBlockState(placePos, Blocks.CHEST.getDefaultState())) {
                    JobBlockPairingHelper.handlePairingBlockPlacement(context.world(), placePos, context.world().getBlockState(placePos));
                    return true;
                }
                addToInventoryOrBuffer(context, new ItemStack(Items.CHEST));
            }

            if (countByItem(context, Items.TRAPPED_CHEST) > 0 && consumeByItem(context, Items.TRAPPED_CHEST, 1)) {
                if (context.world().setBlockState(placePos, Blocks.TRAPPED_CHEST.getDefaultState())) {
                    JobBlockPairingHelper.handlePairingBlockPlacement(context.world(), placePos, context.world().getBlockState(placePos));
                    return true;
                }
                addToInventoryOrBuffer(context, new ItemStack(Items.TRAPPED_CHEST));
            }
        }

        return false;
    }

    private static boolean tryPlaceCraftingTableForEligibleV2Villager(TriggerContext context) {
        if (countByItem(context, Items.CRAFTING_TABLE) <= 0 && countMatching(context, stack -> stack.isIn(ItemTags.PLANKS)) < 4) {
            return false;
        }

        for (VillagerEntity villager : collectNearbyVillagers(context.world(), context.guard())) {
            if (!isEligibleV2VillagerMissingCraftingTable(context.world(), villager)) {
                continue;
            }

            BlockPos jobPos = resolveVillagerJobSite(context.world(), villager);
            if (jobPos == null) {
                continue;
            }

            BlockPos placePos = findPlacementNear(context.world(), jobPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
            if (placePos == null) {
                continue;
            }

            ItemStack tableStack = takeOneByItem(context, Items.CRAFTING_TABLE);
            if (tableStack.isEmpty()) {
                if (!consumeMatching(context, stack -> stack.isIn(ItemTags.PLANKS), 4)) {
                    continue;
                }
                tableStack = new ItemStack(Items.CRAFTING_TABLE);
            }

            if (context.world().setBlockState(placePos, Blocks.CRAFTING_TABLE.getDefaultState())) {
                JobBlockPairingHelper.handleCraftingTablePlacement(context.world(), placePos);
                return true;
            }

            addToInventoryOrBuffer(context, tableStack);
        }

        return false;
    }

    private static ArrayList<VillagerEntity> collectNearbyVillagers(ServerWorld world, LumberjackGuardEntity guard) {
        return new ArrayList<>(world.getEntitiesByClass(
                VillagerEntity.class,
                new Box(guard.getBlockPos()).expand(VILLAGE_EXPANSION_SCAN_RADIUS),
                VillagerEntity::isAlive
        ));
    }

    private static boolean isEligibleV1Villager(ServerWorld world, VillagerEntity villager) {
        if (!villager.isAlive() || villager.isBaby()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.NONE
                || villager.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.NITWIT) {
            return false;
        }

        BlockPos jobPos = resolveVillagerJobSite(world, villager);
        if (jobPos == null) {
            return false;
        }
        return ProfessionDefinitions.isExpectedJobBlock(villager.getVillagerData().getProfession(), world.getBlockState(jobPos));
    }

    private static boolean isEligibleV2VillagerMissingCraftingTable(ServerWorld world, VillagerEntity villager) {
        if (!isEligibleV1Villager(world, villager)) {
            return false;
        }

        BlockPos jobPos = resolveVillagerJobSite(world, villager);
        if (jobPos == null) {
            return false;
        }

        return JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()
                && findNearbyCraftingTable(world, jobPos) == null;
    }

    private static BlockPos resolveVillagerJobSite(ServerWorld world, VillagerEntity villager) {
        return villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension() == world.getRegistryKey())
                .map(GlobalPos::pos)
                .map(BlockPos::toImmutable)
                .orElse(null);
    }

    private static BlockPos findNearbyCraftingTable(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (center.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
                    && world.getBlockState(checkPos).isOf(Blocks.CRAFTING_TABLE)) {
                return checkPos.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos findPlacementNear(ServerWorld world, BlockPos center, double range) {
        int blockRange = (int) Math.ceil(range);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos candidate : BlockPos.iterate(center.add(-blockRange, -1, -blockRange), center.add(blockRange, 1, blockRange))) {
            if (!center.isWithinDistance(candidate, range)) {
                continue;
            }
            if (!world.getBlockState(candidate).isAir()) {
                continue;
            }

            BlockPos below = candidate.down();
            if (!world.getBlockState(below).isSolidBlock(world, below)) {
                continue;
            }

            double distance = center.getSquaredDistance(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.toImmutable();
            }
        }

        return best;
    }

    private static boolean hasAnyAxeAvailable(TriggerContext context) {
        for (Item axe : List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE)) {
            if (countByItem(context, axe) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean canCraftAxe(TriggerContext context, Item material) {
        int sticks = countByItem(context, Items.STICK);
        if (sticks < 2) {
            return false;
        }
        if (material == Items.OAK_PLANKS) {
            return countMatching(context, stack -> stack.isIn(ItemTags.PLANKS)) >= 3;
        }
        return countByItem(context, material) >= 3;
    }

    private static boolean craftAndEquipAxe(TriggerContext context, Item material, Item axeOutput) {
        if (!canCraftAxe(context, material)) {
            return false;
        }
        boolean consumedMaterial = material == Items.OAK_PLANKS
                ? consumeMatching(context, stack -> stack.isIn(ItemTags.PLANKS), 3)
                : consumeByItem(context, material, 3);
        if (!consumedMaterial || !consumeByItem(context, Items.STICK, 2)) {
            return false;
        }
        context.guard().equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(axeOutput));
        return true;
    }

    private static boolean craftAndEquipWoodAxe(TriggerContext context) {
        return craftAndEquipAxe(context, Items.OAK_PLANKS, Items.WOODEN_AXE);
    }

    private static boolean equipAxeFromStorage(TriggerContext context, Item axe) {
        ItemStack pulled = takeOneByItem(context, axe);
        if (pulled.isEmpty()) {
            return false;
        }
        ItemStack previous = context.guard().getMainHandStack();
        if (!previous.isEmpty()) {
            addToBuffer(context.guard(), previous.copy());
        }
        context.guard().equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, pulled);
        return true;
    }

    private static boolean isAxe(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof AxeItem;
    }

    private static boolean hasFurnaceOutput(ServerWorld world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }
        return !furnace.getStack(2).isEmpty();
    }

    private static Inventory resolveChestInventory(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null || !world.getBlockState(chestPos).isOf(Blocks.CHEST)) {
            return null;
        }
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private static int countByItem(TriggerContext context, Item item) {
        return countMatching(context, stack -> stack.isOf(item));
    }

    private static int countMatching(TriggerContext context, Predicate<ItemStack> predicate) {
        return countMatching(context.chestInventory(), predicate) + countMatching(context.guard().getGatheredStackBuffer(), predicate);
    }

    private static int countMatching(Inventory inventory, Predicate<ItemStack> predicate) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countMatching(List<ItemStack> stacks, Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean consumeByItem(TriggerContext context, Item item, int amount) {
        return consumeMatching(context, stack -> stack.isOf(item), amount);
    }

    private static boolean consumeMatching(TriggerContext context, Predicate<ItemStack> predicate, int amount) {
        int remaining = consumeFromInventory(context.chestInventory(), predicate, amount);
        remaining = consumeFromBuffer(context.guard().getGatheredStackBuffer(), predicate, remaining);
        return remaining <= 0;
    }

    private static ItemStack takeOneByItem(TriggerContext context, Item item) {
        return takeOneMatching(context, stack -> stack.isOf(item));
    }

    private static ItemStack takeOneMatching(TriggerContext context, Predicate<ItemStack> predicate) {
        ItemStack pulled = takeOneFromInventory(context.chestInventory(), predicate);
        if (!pulled.isEmpty()) {
            return pulled;
        }
        return takeOneFromBuffer(context.guard().getGatheredStackBuffer(), predicate);
    }

    private static ItemStack takeOneFromInventory(Inventory inventory, Predicate<ItemStack> predicate) {
        if (inventory == null) {
            return ItemStack.EMPTY;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return split;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeOneFromBuffer(List<ItemStack> buffer, Predicate<ItemStack> predicate) {
        for (int i = 0; i < buffer.size(); i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            buffer.removeIf(ItemStack::isEmpty);
            return split;
        }
        return ItemStack.EMPTY;
    }

    private static int consumeFromInventory(Inventory inventory, Predicate<ItemStack> predicate, int amount) {
        if (inventory == null) {
            return amount;
        }
        int remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            remaining -= moved;
        }
        if (remaining != amount) {
            inventory.markDirty();
        }
        return remaining;
    }

    private static int consumeFromBuffer(List<ItemStack> buffer, Predicate<ItemStack> predicate, int amount) {
        int remaining = amount;
        for (int i = 0; i < buffer.size() && remaining > 0; i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            remaining -= moved;
        }
        buffer.removeIf(ItemStack::isEmpty);
        return remaining;
    }

    private static void addToInventoryOrBuffer(TriggerContext context, ItemStack incoming) {
        Inventory inventory = context.chestInventory();
        if (inventory != null) {
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack existing = inventory.getStack(slot);
                if (existing.isEmpty()) {
                    inventory.setStack(slot, incoming.copy());
                    inventory.markDirty();
                    return;
                }
                if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                    int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                    existing.increment(move);
                    incoming.decrement(move);
                    if (incoming.isEmpty()) {
                        inventory.markDirty();
                        return;
                    }
                }
            }
        }

        if (!incoming.isEmpty()) {
            addToBuffer(context.guard(), incoming);
        }
    }

    private static void addToBuffer(LumberjackGuardEntity guard, ItemStack incoming) {
        List<ItemStack> buffer = guard.getGatheredStackBuffer();
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(move);
                incoming.decrement(move);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }

        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private record TriggerRule(String id, int priority, Predicate<TriggerContext> predicate, Predicate<TriggerContext> action) {
    }

    private record TriggerContext(ServerWorld world, LumberjackGuardEntity guard, Inventory chestInventory) {
    }
}

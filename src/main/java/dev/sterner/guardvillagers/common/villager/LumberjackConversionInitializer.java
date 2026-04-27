package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardChopTreesGoal;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Shared post-conversion initializer for lumberjack guards.
 */
public final class LumberjackConversionInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackConversionInitializer.class);

    private LumberjackConversionInitializer() {
    }

    public static void initializePostConversion(ServerWorld world,
                                                LumberjackGuardEntity guard,
                                                BlockPos tablePos,
                                                String source,
                                                @Nullable List<ItemStack> initialDrops) {
        guard.setPairedCraftingTablePos(tablePos);
        if (GuardVillagersConfig.lumberjackAutoPairNearbyChestOnConvert) {
            JobBlockPairingHelper.findNearbyChest(world, tablePos).ifPresent(guard::setPairedChestPos);
        }

        guard.setBootstrapComplete(false);
        guard.setActiveSession(false);
        guard.setSessionTargetsRemaining(0);
        guard.setConsecutiveNoTreeSessions(0);
        guard.getSelectedTreeTargets().clear();

        guard.startChopCountdown(world.getTime(), 0L);
        boolean recoveryScheduled = LumberjackGuardChopTreesGoal.scheduleSingleTreeRecoverySession(world, guard);

        SeedResult seedResult = seedInitialDrops(world, guard, initialDrops == null ? Collections.emptyList() : initialDrops);

        LOGGER.info("lumberjack-conversion-init source={} guard={} table={} chest={} startup_mode=immediate-constrained-tree recovery_scheduled={} " +
                        "countdown_active={} countdown_start={} countdown_total={} next_chop_tick={} active_session={} session_targets={} bootstrap_complete={} " +
                        "seeded_stacks={} seeded_items={} seeded_chest_items={} seeded_buffer_items={} buffer_stacks={} buffer_items={}",
                source,
                guard.getUuidAsString(),
                tablePos.toShortString(),
                guard.getPairedChestPos() == null ? "none" : guard.getPairedChestPos().toShortString(),
                recoveryScheduled,
                guard.isChopCountdownActive(),
                guard.getChopCountdownStartTick(),
                guard.getChopCountdownTotalTicks(),
                guard.getNextChopTick(),
                guard.isActiveSession(),
                guard.getSessionTargetsRemaining(),
                guard.isBootstrapComplete(),
                seedResult.seededStacks(),
                seedResult.seededItems(),
                seedResult.seededChestItems(),
                seedResult.seededBufferItems(),
                guard.getGatheredStackBuffer().size(),
                countItems(guard.getGatheredStackBuffer()));
    }

    private static SeedResult seedInitialDrops(ServerWorld world, LumberjackGuardEntity guard, List<ItemStack> drops) {
        if (drops.isEmpty()) {
            return SeedResult.EMPTY;
        }

        int seededStacks = 0;
        int seededItems = 0;
        int seededChestItems = 0;
        int seededBufferItems = 0;
        Inventory pairedChestInventory = resolvePairedChestInventory(world, guard.getPairedChestPos());

        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            seededStacks++;
            seededItems += stack.getCount();
            ItemStack remaining = stack.copy();
            if (pairedChestInventory != null) {
                remaining = insertIntoInventory(pairedChestInventory, remaining);
                seededChestItems += (stack.getCount() - remaining.getCount());
            }
            if (!remaining.isEmpty()) {
                addToBuffer(guard.getGatheredStackBuffer(), remaining);
                seededBufferItems += remaining.getCount();
            }
        }
        if (pairedChestInventory != null) {
            pairedChestInventory.markDirty();
        }

        return new SeedResult(seededStacks, seededItems, seededChestItems, seededBufferItems);
    }

    private static int countItems(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static Inventory resolvePairedChestInventory(ServerWorld world, BlockPos chestPos) {
        if (chestPos == null) {
            return null;
        }

        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private static ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, remaining);
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
                if (transfer > 0) {
                    existing.increment(transfer);
                    remaining.decrement(transfer);
                    inventory.setStack(slot, existing);
                }
            }
        }
        return remaining;
    }

    private static void addToBuffer(List<ItemStack> buffer, ItemStack incoming) {
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(transfer);
                incoming.decrement(transfer);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }
        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private record SeedResult(int seededStacks, int seededItems, int seededChestItems, int seededBufferItems) {
        private static final SeedResult EMPTY = new SeedResult(0, 0, 0, 0);
    }
}

package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.GearGradeComparator;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class FletcherDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(FletcherDistributionGoal.class);
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;
    private static final RecipientScope RECIPIENT_SCOPE = RecipientScope.GUARDS_ONLY;

    public FletcherDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.isIn(ItemTags.ARROWS);
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (!findRecipientForStack(world, stack).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }

            List<RecipientRecord> recipients = findRecipientForStack(world, stack);
            if (recipients.isEmpty()) {
                continue;
            }

            RecipientRecord recipient = recipients.getFirst();
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();

            pendingItem = extracted;
            pendingTargetId = recipient.guard().getUuid();
            pendingTargetPos = recipient.guard().getBlockPos();

            LOGGER.info("Fletcher {} selected {} for guard {} at {} [{}]",
                    villager.getUuidAsString(),
                    pendingItem.getItem().toString(),
                    recipient.guard().getUuidAsString(),
                    recipient.guard().getBlockPos().toShortString(),
                    RECIPIENT_SCOPE);
            return true;
        }
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        List<RecipientRecord> recipients = findRecipientForStack(world, pendingItem);
        if (recipients.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null) {
            for (RecipientRecord recipient : recipients) {
                if (recipient.guard().getUuid().equals(pendingTargetId)) {
                    pendingTargetPos = recipient.guard().getBlockPos();
                    return true;
                }
            }
        }

        RecipientRecord recipient = recipients.getFirst();
        pendingTargetId = recipient.guard().getUuid();
        pendingTargetPos = recipient.guard().getBlockPos();
        LOGGER.debug("Fletcher {} retargeted pending {} to guard {} at {}",
                villager.getUuidAsString(),
                pendingItem.getItem(),
                recipient.guard().getUuidAsString(),
                pendingTargetPos.toShortString());
        return true;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingItem.isEmpty() || pendingTargetId == null) {
            return false;
        }
        if (!(world.getEntity(pendingTargetId) instanceof GuardEntity guard) || !guard.isAlive()) {
            return false;
        }

        if (isRangedWeapon(pendingItem)) {
            ItemStack currentMainHand = guard.getMainHandStack();
            if (!canEquipRangedWeapon(guard, pendingItem, currentMainHand)) {
                LOGGER.debug("Fletcher {} skipped weapon transfer {} -> guard {} (current main hand: {})",
                        villager.getUuidAsString(),
                        pendingItem.getItem(),
                        guard.getUuidAsString(),
                        currentMainHand.getItem());
                return true;
            }

            guard.equipStack(EquipmentSlot.MAINHAND, pendingItem.copy());
            LOGGER.info("Fletcher {} equipped guard {} with {} (replaced {})",
                    villager.getUuidAsString(),
                    guard.getUuidAsString(),
                    pendingItem.getItem(),
                    currentMainHand.getItem());
            return true;
        }

        if (isArrow(pendingItem)) {
            ItemStack currentMainHand = guard.getMainHandStack();
            if (!usesBowOrCrossbow(currentMainHand)) {
                LOGGER.debug("Fletcher {} skipped arrow transfer to guard {} (main hand: {})",
                        villager.getUuidAsString(),
                        guard.getUuidAsString(),
                        currentMainHand.getItem());
                return true;
            }

            ItemStack remaining = insertStack(guard.guardInventory, pendingItem);
            guard.guardInventory.markDirty();
            if (remaining.isEmpty()) {
                LOGGER.info("Fletcher {} transferred {} to guard {} inventory",
                        villager.getUuidAsString(),
                        pendingItem.getItem(),
                        guard.getUuidAsString());
                return true;
            }

            pendingItem = remaining;
            return false;
        }

        return false;
    }

    @Override
    protected Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, ItemStack stack) {
        return Optional.empty();
    }

    @Override
    protected boolean isStandAvailableForPendingItem(ServerWorld world, ArmorStandEntity stand) {
        return false;
    }

    @Override
    protected boolean placePendingItemOnStand(ServerWorld world, ArmorStandEntity stand) {
        return false;
    }

    @Override
    protected void clearPendingTargetState() {
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.FLETCHER;
    }

    private List<RecipientRecord> findRecipientForStack(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of();
        }

        if (RECIPIENT_SCOPE == RecipientScope.GUARDS_AND_VILLAGERS) {
            LOGGER.debug("Fletcher {} recipient scope includes villagers, but only guards are currently supported for {}",
                    villager.getUuidAsString(),
                    stack.getItem());
        }

        Box scanBox = new Box(villager.getBlockPos()).expand(RECIPIENT_SCAN_RANGE);
        return world.getEntitiesByClass(GuardEntity.class, scanBox, this::isValidGuardRecipient)
                .stream()
                .filter(guard -> canReceiveStack(guard, stack))
                .map(guard -> new RecipientRecord(guard, villager.squaredDistanceTo(guard)))
                .sorted(Comparator.comparingDouble(RecipientRecord::sourceSquaredDistance)
                        .thenComparing(record -> record.guard().getUuid(), java.util.UUID::compareTo))
                .toList();
    }

    private boolean isValidGuardRecipient(GuardEntity guard) {
        return guard.isAlive();
    }

    private boolean canReceiveStack(GuardEntity guard, ItemStack stack) {
        if (isRangedWeapon(stack)) {
            return canEquipRangedWeapon(guard, stack, guard.getMainHandStack());
        }

        if (isArrow(stack)) {
            return usesBowOrCrossbow(guard.getMainHandStack()) && canInsertArrow(guard.guardInventory, stack);
        }

        return false;
    }

    private boolean canEquipRangedWeapon(GuardEntity guard, ItemStack candidate, ItemStack currentMainHand) {
        if (!(candidate.getItem() instanceof RangedWeaponItem) || !guard.canUseRangedWeapon(candidate.getItem())) {
            return false;
        }
        return GearGradeComparator.isUpgrade(candidate, currentMainHand, EquipmentSlot.MAINHAND);
    }

    private boolean canInsertArrow(Inventory inventory, ItemStack stack) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (inventory.isValid(slot, stack)) {
                    return true;
                }
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
                continue;
            }
            if (existing.getCount() < existing.getMaxCount() && inventory.isValid(slot, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRangedWeapon(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    private boolean isArrow(ItemStack stack) {
        return stack.isIn(ItemTags.ARROWS);
    }

    private boolean usesBowOrCrossbow(ItemStack mainHand) {
        return mainHand.getItem() instanceof BowItem || mainHand.getItem() instanceof CrossbowItem;
    }

    private enum RecipientScope {
        GUARDS_ONLY,
        GUARDS_AND_VILLAGERS
    }

    private record RecipientRecord(GuardEntity guard, double sourceSquaredDistance) {
    }
}

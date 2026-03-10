package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.GearGradeComparator;
import dev.sterner.guardvillagers.common.util.IngredientDemandResolver;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import java.util.UUID;

public class FletcherDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(FletcherDistributionGoal.class);
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;
    private static final long UNDELIVERABLE_RETRY_DELAY_TICKS = 40L;
    private static final RecipientScope RECIPIENT_SCOPE = RecipientScope.GUARDS_ONLY;
    private ItemStack recentlyUndeliverable = ItemStack.EMPTY;
    private long retryUndeliverableAfterTick;

    public FletcherDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.isIn(ItemTags.ARROWS)
                || stack.isOf(Items.STICK);
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        if (canStartOverflowTransfer(world, inventory, this::isDistributableItem)) {
            return true;
        }
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
        if (trySelectOverflowTransfer(world, inventory, this::isDistributableItem)) {
            LOGGER.info("Fletcher {} selected {} for librarian overflow at {}",
                    villager.getUuidAsString(),
                    pendingItem.getItem(),
                    pendingTargetPos.toShortString());
            return true;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (isCoolingDownUndeliverable(world, stack)) {
                continue;
            }

            List<TransferTarget> recipients = findRecipientForStack(world, stack);
            if (recipients.isEmpty()) {
                continue;
            }

            TransferTarget recipient = recipients.getFirst();
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();

            pendingItem = extracted;
            pendingTargetId = recipient.targetId();
            pendingTargetPos = recipient.targetPos();

            LOGGER.info("Fletcher {} selected {} for {} {} at {} [{}]",
                    villager.getUuidAsString(),
                    pendingItem.getItem().toString(),
                    recipient.type(),
                    recipient.targetId(),
                    recipient.targetPos().toShortString(),
                    RECIPIENT_SCOPE);
            return true;
        }
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (refreshOverflowTarget(world, this::isDistributableItem)) {
            return true;
        }
        List<TransferTarget> recipients = findRecipientForStack(world, pendingItem);
        if (recipients.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null) {
            for (TransferTarget recipient : recipients) {
                if (recipient.targetId().equals(pendingTargetId)) {
                    pendingTargetPos = recipient.targetPos();
                    return true;
                }
            }
        }

        TransferTarget recipient = recipients.getFirst();
        pendingTargetId = recipient.targetId();
        pendingTargetPos = recipient.targetPos();
        LOGGER.debug("Fletcher {} retargeted pending {} to {} {} at {}",
                villager.getUuidAsString(),
                pendingItem.getItem(),
                recipient.type(),
                recipient.targetId(),
                pendingTargetPos.toShortString());
        return true;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingOverflowTransfer) {
            return executeOverflowTransfer(world);
        }
        if (pendingItem.isEmpty() || pendingTargetId == null) {
            return false;
        }
        if (pendingItem.isOf(Items.STICK)) {
            Optional<Inventory> targetInventory = getChestInventoryAt(world, pendingTargetPos);
            if (targetInventory.isEmpty()) {
                return false;
            }
            ItemStack remaining = insertStack(targetInventory.get(), pendingItem);
            targetInventory.get().markDirty();
            if (remaining.isEmpty()) {
                return true;
            }
            pendingItem = remaining;
            return false;
        }

        if (!(world.getEntity(pendingTargetId) instanceof GuardEntity guard) || !guard.isAlive()) {
            return false;
        }

        if (isRangedWeapon(pendingItem)) {
            ItemStack currentMainHand = guard.getMainHandStack();
            if (!canEquipRangedWeapon(guard, pendingItem, currentMainHand)) {
                markUndeliverable(world, pendingItem);
                LOGGER.debug("Fletcher {} skipped weapon transfer {} -> guard {} (current main hand: {})",
                        villager.getUuidAsString(),
                        pendingItem.getItem(),
                        guard.getUuidAsString(),
                        currentMainHand.getItem());
                return false;
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
                markUndeliverable(world, pendingItem);
                LOGGER.debug("Fletcher {} skipped arrow transfer to guard {} (main hand: {})",
                        villager.getUuidAsString(),
                        guard.getUuidAsString(),
                        currentMainHand.getItem());
                return false;
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

    private boolean isCoolingDownUndeliverable(ServerWorld world, ItemStack candidate) {
        return !recentlyUndeliverable.isEmpty()
                && world.getTime() < retryUndeliverableAfterTick
                && ItemStack.areItemsAndComponentsEqual(recentlyUndeliverable, candidate);
    }

    private void markUndeliverable(ServerWorld world, ItemStack stack) {
        recentlyUndeliverable = stack.copy();
        retryUndeliverableAfterTick = world.getTime() + UNDELIVERABLE_RETRY_DELAY_TICKS;
    }

    @Override
    protected Optional<OverflowRecipientType> getOverflowRecipientType() {
        return Optional.of(OverflowRecipientType.LIBRARIAN);
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.FLETCHER;
    }

    private List<TransferTarget> findRecipientForStack(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of();
        }

        if (stack.isOf(Items.STICK)) {
            return IngredientDemandResolver.findVillagersNeedingSticks(world, villager, RECIPIENT_SCAN_RANGE, pos -> getChestInventoryAt(world, pos))
                    .stream()
                    .map(recipient -> new TransferTarget(
                            recipient.recipient().recipient().getUuid(),
                            recipient.recipient().chestPos(),
                            recipient.recipient().sourceSquaredDistance(),
                            "villager"))
                    .toList();
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
                .map(guard -> new TransferTarget(guard.getUuid(), guard.getBlockPos(), villager.squaredDistanceTo(guard), "guard"))
                .sorted(Comparator.comparingDouble(TransferTarget::sourceSquaredDistance)
                        .thenComparing(TransferTarget::targetId, UUID::compareTo))
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
        if (!(candidate.getItem() instanceof RangedWeaponItem rangedWeaponItem) || !guard.canUseRangedWeapon(rangedWeaponItem)) {
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

    private record TransferTarget(UUID targetId, BlockPos targetPos, double sourceSquaredDistance, String type) {
    }
}

package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.LeatherworkerWorkMetrics;
import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LeatherworkerDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeatherworkerDistributionGoal.class);
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;

    /**
     * Crafted output whitelist for leatherworker-to-librarian distribution.
     *
     * Strategy:
     * - Include leather and common leatherworker-crafted leather products.
     * - Include book-related products expected to be useful for librarians.
     */
    private final Set<UUID> pendingRejectedRecipients = new HashSet<>();

    public LeatherworkerDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return isSupportedDistributionGood(stack);
    }

    public static boolean isSupportedDistributionGood(ItemStack stack) {
        boolean whitelisted = !stack.isEmpty() && DistributableWhitelist.ITEMS.contains(stack.getItem());
        boolean leatherArmor = !stack.isEmpty()
                && stack.getItem() instanceof ArmorItem armorItem
                && armorItem.getMaterial() == ArmorMaterials.LEATHER;
        return isSupportedDistributionShape(whitelisted, leatherArmor);
    }

    static boolean isSupportedDistributionShape(boolean whitelisted, boolean leatherArmor) {
        return whitelisted || leatherArmor;
    }

    /** Defers Minecraft item-registry access until the real stack predicate is used. */
    private static final class DistributableWhitelist {
        private static final Set<Item> ITEMS = Set.of(
                Items.LEATHER,
                Items.RABBIT_HIDE,
                Items.SADDLE,
                Items.ITEM_FRAME,
                Items.GLOW_ITEM_FRAME,
                Items.BOOK,
                Items.WRITABLE_BOOK,
                Items.WRITTEN_BOOK,
                Items.ENCHANTED_BOOK
        );
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (!findRecipientForStack(world, stack, Set.of()).isEmpty()) {
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

            List<DistributionRecipientHelper.RecipientRecord> recipients = findRecipientForStack(world, stack, Set.of());
            if (recipients.isEmpty()) {
                continue;
            }

            DistributionRecipientHelper.RecipientRecord recipient = recipients.getFirst();
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();

            pendingItem = extracted;
            pendingTargetId = recipient.recipient().getUuid();
            pendingTargetPos = recipient.chestPos();
            pendingUniversalRoute = false;
            pendingOverflowTransfer = false;
            pendingRejectedRecipients.clear();
            return true;
        }
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return false;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients = findRecipientForStack(world, pendingItem, pendingRejectedRecipients);
        if (recipients.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null && !pendingRejectedRecipients.contains(pendingTargetId)) {
            for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
                if (recipient.recipient().getUuid().equals(pendingTargetId)) {
                    pendingTargetPos = recipient.chestPos();
                    return true;
                }
            }
        }

        DistributionRecipientHelper.RecipientRecord recipient = recipients.getFirst();
        pendingTargetId = recipient.recipient().getUuid();
        pendingTargetPos = recipient.chestPos();
        return true;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingItem.isEmpty() || pendingTargetPos == null) {
            return false;
        }

        Optional<Inventory> targetInventory = getChestInventory(world, pendingTargetPos);
        if (targetInventory.isEmpty()) {
            markPendingRecipientRejected();
            return false;
        }

        ItemStack remaining = insertStack(targetInventory.get(), pendingItem);
        targetInventory.get().markDirty();
        if (remaining.isEmpty()) {
            return true;
        }

        pendingItem = remaining;
        markPendingRecipientRejected();
        return false;
    }

    @Override
    protected void clearPendingTargetState() {
        pendingRejectedRecipients.clear();
    }

    @Override
    protected void onTransferCompleted(
            ServerWorld world,
            ItemStack transferred,
            @org.jetbrains.annotations.Nullable UUID targetId,
            BlockPos targetPos,
            TransferRoute route
    ) {
        if (route != TransferRoute.DIRECT || !isSupportedDistributionGood(transferred)) {
            return;
        }
        boolean storageValid = getChestInventory(world, targetPos).isPresent();
        boolean recipientValid = isValidCompletedRecipient(world, transferred, targetId, targetPos);
        if (isConfirmedDirectDelivery(
                route,
                isSupportedDistributionGood(transferred),
                recipientValid,
                storageValid)) {
            LeatherworkerWorkMetrics.recordGoodsDelivered(
                    world,
                    villager.getUuid(),
                    transferred.getCount());
        }
    }

    static boolean isConfirmedDirectDelivery(
            TransferRoute route,
            boolean supportedGood,
            boolean recipientValid,
            boolean storageValid
    ) {
        return route == TransferRoute.DIRECT && supportedGood && recipientValid && storageValid;
    }

    private boolean isValidCompletedRecipient(
            ServerWorld world,
            ItemStack transferred,
            UUID targetId,
            BlockPos targetPos
    ) {
        if (targetId == null) {
            return false;
        }
        List<DistributionRecipientHelper.RecipientRecord> recipients;
        if (isFrame(transferred)) {
            recipients = java.util.stream.Stream.concat(
                            DistributionRecipientHelper.findEligibleV2CartographerRecipients(
                                    world, villager, RECIPIENT_SCAN_RANGE).stream(),
                            DistributionRecipientHelper.findEligibleLibrarianRecipients(
                                    world, villager, RECIPIENT_SCAN_RANGE).stream())
                    .toList();
        } else {
            recipients = DistributionRecipientHelper.findEligibleLibrarianRecipients(
                    world, villager, RECIPIENT_SCAN_RANGE);
        }
        return recipients.stream().anyMatch(recipient -> recipient.recipient() != null
                && recipient.recipient().isAlive()
                && recipient.recipient().getUuid().equals(targetId)
                && recipient.chestPos().equals(targetPos));
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.LEATHERWORKER;
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

    private List<DistributionRecipientHelper.RecipientRecord> findRecipientForStack(ServerWorld world, ItemStack stack, Set<UUID> excludedRecipients) {
        if (stack.isEmpty() || !isDistributableItem(stack)) {
            return List.of();
        }

        // Item frames go to v2 cartographers (for map display walls) first; librarians are an explicit fallback.
        // All other leatherworker items route to librarians only.
        List<DistributionRecipientHelper.RecipientRecord> candidates;
        if (isFrame(stack)) {
            List<DistributionRecipientHelper.RecipientRecord> allCartographers =
                    DistributionRecipientHelper.findEligibleCartographerRecipients(world, villager, RECIPIENT_SCAN_RANGE);
            List<DistributionRecipientHelper.RecipientRecord> v2Cartographers =
                    DistributionRecipientHelper.findEligibleV2CartographerRecipients(world, villager, RECIPIENT_SCAN_RANGE);
            List<DistributionRecipientHelper.RecipientRecord> librarians =
                    DistributionRecipientHelper.findEligibleLibrarianRecipients(world, villager, RECIPIENT_SCAN_RANGE);
            candidates = resolveItemFrameRecipients(
                    stack,
                    allCartographers,
                    v2Cartographers,
                    librarians,
                    LOGGER,
                    villager.getUuidAsString()
            );
        } else {
            candidates = DistributionRecipientHelper.findEligibleLibrarianRecipients(world, villager, RECIPIENT_SCAN_RANGE);
        }

        return candidates.stream()
                .filter(recipient -> !excludedRecipients.contains(recipientId(recipient)))
                .filter(recipient -> canRecipientAccept(world, recipient.chestPos(), stack))
                .toList();
    }

    private static boolean isFrame(ItemStack stack) {
        return stack.isOf(Items.ITEM_FRAME) || stack.isOf(Items.GLOW_ITEM_FRAME);
    }

    public static int countFrameDemandReadOnly(ServerWorld world, VillagerEntity source) {
        List<DistributionRecipientHelper.RecipientRecord> allCartographers =
                DistributionRecipientHelper.findEligibleCartographerRecipients(world, source, RECIPIENT_SCAN_RANGE);
        Set<UUID> v2Ids = DistributionRecipientHelper.findEligibleV2CartographerRecipients(
                        world, source, RECIPIENT_SCAN_RANGE).stream()
                .filter(recipient -> recipient.recipient() != null)
                .map(recipient -> recipient.recipient().getUuid())
                .collect(Collectors.toSet());
        List<FrameDemandView> views = allCartographers.stream()
                .filter(recipient -> recipient.recipient() != null)
                .map(recipient -> new FrameDemandView(
                        recipient.recipient().getUuid(),
                        v2Ids.contains(recipient.recipient().getUuid()),
                        v2Ids.contains(recipient.recipient().getUuid())
                                ? getChestInventoryReadOnly(world, recipient.chestPos())
                                        .map(LeatherworkerDistributionGoal::countItemFrames)
                                        .orElse(CartographerMapWallGoal.FRAMES_NEEDED)
                                : 0))
                .toList();
        return countUniqueFrameDemand(views, CartographerMapWallGoal.FRAMES_NEEDED);
    }

    static int countUniqueFrameDemand(List<FrameDemandView> recipients, int framesNeeded) {
        java.util.Map<UUID, Integer> framesByRecipient = new java.util.HashMap<>();
        for (FrameDemandView recipient : recipients) {
            if (recipient.eligibleV2()) {
                framesByRecipient.merge(
                        recipient.recipientId(),
                        Math.max(0, recipient.itemFrames()),
                        Math::max);
            }
        }
        long total = 0L;
        for (int frames : framesByRecipient.values()) {
            total += Math.max(0, framesNeeded - frames);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static int countItemFrames(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.ITEM_FRAME)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static Optional<Inventory> getChestInventoryReadOnly(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, false));
    }

    static List<DistributionRecipientHelper.RecipientRecord> resolveItemFrameRecipients(
            ItemStack stack,
            List<DistributionRecipientHelper.RecipientRecord> allCartographers,
            List<DistributionRecipientHelper.RecipientRecord> v2Cartographers,
            List<DistributionRecipientHelper.RecipientRecord> librarians,
            Logger logger,
            String leatherworkerId
    ) {
        if (!(stack.isOf(Items.ITEM_FRAME) || stack.isOf(Items.GLOW_ITEM_FRAME))) {
            return librarians;
        }
        return resolveItemFrameRecipients(
                true,
                allCartographers,
                v2Cartographers,
                librarians,
                LeatherworkerDistributionGoal::recipientId,
                recipient -> logger.debug(
                        "Leatherworker {} rejected non-v2 cartographer recipient={} jobPos={} chestPos={} for stack={}",
                        leatherworkerId,
                        recipientId(recipient),
                        recipient.jobPos().toShortString(),
                        recipient.chestPos().toShortString(),
                        stack.getItem().toString()));
    }

    static <T> List<T> resolveItemFrameRecipients(
            boolean frame,
            List<T> allCartographers,
            List<T> v2Cartographers,
            List<T> librarians,
            Function<T, UUID> id,
            Consumer<T> rejectedCartographer
    ) {
        if (!frame) {
            return List.copyOf(librarians);
        }
        Set<UUID> v2Ids = v2Cartographers.stream().map(id).collect(Collectors.toSet());
        allCartographers.stream()
                .filter(recipient -> !v2Ids.contains(id.apply(recipient)))
                .forEach(rejectedCartographer);
        return java.util.stream.Stream.concat(v2Cartographers.stream(), librarians.stream()).toList();
    }

    private static UUID recipientId(DistributionRecipientHelper.RecipientRecord recipient) {
        if (recipient.recipient() != null) {
            return recipient.recipient().getUuid();
        }
        return UUID.nameUUIDFromBytes((recipient.jobPos().toShortString() + "|" + recipient.chestPos().toShortString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean canRecipientAccept(ServerWorld world, BlockPos chestPosition, ItemStack stack) {
        Optional<Inventory> targetInventory = getChestInventory(world, chestPosition);
        if (targetInventory.isEmpty()) {
            return false;
        }

        for (int slot = 0; slot < targetInventory.get().size(); slot++) {
            ItemStack existing = targetInventory.get().getStack(slot);
            if (existing.isEmpty()) {
                if (targetInventory.get().isValid(slot, stack)) {
                    return true;
                }
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
                continue;
            }
            if (!targetInventory.get().isValid(slot, stack)) {
                continue;
            }
            if (existing.getCount() < existing.getMaxCount()) {
                return true;
            }
        }

        return false;
    }

    private void markPendingRecipientRejected() {
        if (pendingTargetId != null) {
            pendingRejectedRecipients.add(pendingTargetId);
        }
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos position) {
        return getChestInventoryReadOnly(world, position);
    }

    record FrameDemandView(UUID recipientId, boolean eligibleV2, int itemFrames) {
    }
}

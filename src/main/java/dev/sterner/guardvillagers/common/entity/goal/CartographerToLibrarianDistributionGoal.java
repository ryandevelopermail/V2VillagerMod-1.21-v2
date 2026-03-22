package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.Optional;

/**
 * Previously distributed cartographer FILLED_MAP items to librarians.
 * This goal is now fully disabled — cartographers retain all filled maps in their
 * paired chest for the copy/display workflow (map wall placement when 8 maps
 * + 4 item frames are ready).
 *
 * <p>The goal registration is preserved in CartographerBehavior so the goal
 * infrastructure remains wired; no maps will ever be distributed out.
 */
public class CartographerToLibrarianDistributionGoal extends AbstractInventoryDistributionGoal {

    public CartographerToLibrarianDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return false;
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        return false;
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        return false;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        return false;
    }

    @Override
    protected void clearPendingTargetState() {
    }

    @Override
    protected Optional<OverflowRecipientType> getOverflowRecipientType() {
        return Optional.empty();
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.CARTOGRAPHER;
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
}

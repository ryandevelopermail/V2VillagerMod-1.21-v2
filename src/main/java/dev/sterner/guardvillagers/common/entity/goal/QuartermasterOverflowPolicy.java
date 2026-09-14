package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

/**
 * Defines which surplus items central storage may collect.
 *
 * <p>The Quartermaster is the sole owner of generic overflow intake into central storage.
 * This is the outer safelist; source-profession reclaim rules and reserves further narrow
 * what a particular chest may release. Profession goals keep their explicit
 * producer-to-consumer routes, including deliberately item-scoped Librarian dependencies.
 */
final class QuartermasterOverflowPolicy {
    private QuartermasterOverflowPolicy() {
    }

    static boolean canCollect(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isIn(ItemTags.WOOL)
                || stack.isIn(ItemTags.SAPLINGS)) {
            return true;
        }

        return stack.isOf(Items.COBBLESTONE)
                || stack.isOf(Items.STONE)
                || stack.isOf(Items.GRAVEL)
                || stack.isOf(Items.SAND)
                || stack.isOf(Items.WHEAT)
                || stack.isOf(Items.WHEAT_SEEDS)
                || stack.isOf(Items.HAY_BLOCK)
                || stack.isOf(Items.COAL)
                || stack.isOf(Items.CHARCOAL)
                || stack.isOf(Items.STICK);
    }
}

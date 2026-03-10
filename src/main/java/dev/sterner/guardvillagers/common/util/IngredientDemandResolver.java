package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public final class IngredientDemandResolver {
    private static final int MAX_STICK_BUFFER_PER_RECIPIENT = 16;
    private static final List<StickDemandConsumer> STICK_DEMAND_CONSUMERS = List.of(
            // Farmer hoe recipes (all variants): 2 sticks each.
            new StickDemandConsumer(
                    DistributionRecipientHelper::findEligibleFarmerRecipients,
                    2,
                    List.of(
                            stack -> stack.isIn(ItemTags.PLANKS),
                            stack -> stack.isOf(Items.COBBLESTONE),
                            stack -> stack.isOf(Items.IRON_INGOT),
                            stack -> stack.isOf(Items.GOLD_INGOT),
                            stack -> stack.isOf(Items.DIAMOND)
                    ),
                    2
            ),
            // Shepherd banner recipes (all color variants): 1 stick each.
            new StickDemandConsumer(
                    DistributionRecipientHelper::findEligibleShepherdRecipients,
                    1,
                    List.of(
                            stack -> stack.isOf(Items.WHITE_WOOL),
                            stack -> stack.isOf(Items.ORANGE_WOOL),
                            stack -> stack.isOf(Items.MAGENTA_WOOL),
                            stack -> stack.isOf(Items.LIGHT_BLUE_WOOL),
                            stack -> stack.isOf(Items.YELLOW_WOOL),
                            stack -> stack.isOf(Items.LIME_WOOL),
                            stack -> stack.isOf(Items.PINK_WOOL),
                            stack -> stack.isOf(Items.GRAY_WOOL),
                            stack -> stack.isOf(Items.LIGHT_GRAY_WOOL),
                            stack -> stack.isOf(Items.CYAN_WOOL),
                            stack -> stack.isOf(Items.PURPLE_WOOL),
                            stack -> stack.isOf(Items.BLUE_WOOL),
                            stack -> stack.isOf(Items.BROWN_WOOL),
                            stack -> stack.isOf(Items.GREEN_WOOL),
                            stack -> stack.isOf(Items.RED_WOOL),
                            stack -> stack.isOf(Items.BLACK_WOOL)
                    ),
                    6
            )
    );

    private IngredientDemandResolver() {
    }

    public static List<StickDemandRecipient> findVillagersNeedingSticks(
            ServerWorld world,
            VillagerEntity source,
            double range,
            Function<BlockPos, Optional<Inventory>> chestInventoryResolver
    ) {
        List<StickDemandRecipient> recipients = new ArrayList<>();
        for (StickDemandConsumer consumer : STICK_DEMAND_CONSUMERS) {
            List<DistributionRecipientHelper.RecipientRecord> eligible = consumer.recipientFinder().find(world, source, range);
            for (DistributionRecipientHelper.RecipientRecord recipient : eligible) {
                Optional<Inventory> chestInventory = chestInventoryResolver.apply(recipient.chestPos());
                if (chestInventory.isEmpty()) {
                    continue;
                }

                int currentSticks = countMatching(chestInventory.get(), stack -> stack.isOf(Items.STICK));
                int demand = computeDemand(chestInventory.get(), consumer);
                if (demand <= 0) {
                    continue;
                }

                recipients.add(new StickDemandRecipient(recipient, currentSticks, demand));
            }
        }

        return recipients.stream()
                .sorted(Comparator.comparingInt(StickDemandRecipient::stickShortfall).reversed()
                        .thenComparingInt(StickDemandRecipient::currentStickCount)
                        .thenComparingDouble(recipient -> recipient.recipient().sourceSquaredDistance())
                        .thenComparing(recipient -> recipient.recipient().recipient().getUuid(), java.util.UUID::compareTo))
                .toList();
    }

    private static int computeDemand(Inventory inventory, StickDemandConsumer consumer) {
        int availableCrafts = 0;
        for (Predicate<ItemStack> ingredientMatcher : consumer.nonStickIngredientMatchers()) {
            availableCrafts += countMatching(inventory, ingredientMatcher) / consumer.nonStickIngredientCost();
        }

        if (availableCrafts <= 0) {
            return 0;
        }

        int desiredSticks = Math.min(MAX_STICK_BUFFER_PER_RECIPIENT, availableCrafts * consumer.sticksPerCraft());
        int currentSticks = countMatching(inventory, stack -> stack.isOf(Items.STICK));
        return Math.max(0, desiredSticks - currentSticks);
    }

    private static int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record StickDemandConsumer(
            RecipientFinder recipientFinder,
            int sticksPerCraft,
            List<Predicate<ItemStack>> nonStickIngredientMatchers,
            int nonStickIngredientCost
    ) {
    }

    @FunctionalInterface
    private interface RecipientFinder {
        List<DistributionRecipientHelper.RecipientRecord> find(ServerWorld world, VillagerEntity source, double range);
    }

    public record StickDemandRecipient(
            DistributionRecipientHelper.RecipientRecord recipient,
            int currentStickCount,
            int stickShortfall
    ) {
    }
}

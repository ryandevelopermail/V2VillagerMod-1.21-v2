package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.village.VillagerProfession;

import java.util.List;

/**
 * Shared recipient registry for wood-derived materials.
 *
 * Keeping these profiles in one place avoids divergence between lumberjack demand planning
 * and the universal routing pipeline.
 */
public final class WoodRecipientRegistry {
    private static final List<RecipientProfile> PLANK_RECIPIENTS = List.of(
            // Frequent bookshelf demand for lecterns/books makes librarians the highest plank sink.
            new RecipientProfile(VillagerProfession.LIBRARIAN, Blocks.LECTERN, false, 48, 1.8D),
            // Boats/chest boats require many planks and are crafted repeatedly by fishermen.
            new RecipientProfile(VillagerProfession.FISHERMAN, Blocks.BARREL, false, 40, 1.5D),
            // Farmers consume planks for wooden hoe crafting path.
            new RecipientProfile(VillagerProfession.FARMER, Blocks.COMPOSTER, false, 32, 1.15D),
            // Fletchers consume planks via target/crafting path.
            new RecipientProfile(VillagerProfession.FLETCHER, Blocks.FLETCHING_TABLE, false, 32, 1.2D),
            // Toolsmith weighted aggressively to satisfy active downstream tool deficits.
            new RecipientProfile(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE, false, 32, 1.6D)
    );

    private WoodRecipientRegistry() {
    }

    public static List<RecipientProfile> plankRecipients() {
        return PLANK_RECIPIENTS;
    }

    public static double plankDemandWeight(VillagerProfession profession) {
        return PLANK_RECIPIENTS.stream()
                .filter(profile -> profile.profession() == profession)
                .mapToDouble(RecipientProfile::demandWeight)
                .findFirst()
                .orElse(1.0D);
    }

    public record RecipientProfile(
            VillagerProfession profession,
            Block expectedJobBlock,
            boolean requiresCraftingTable,
            int targetStockCap,
            double demandWeight
    ) {
    }
}

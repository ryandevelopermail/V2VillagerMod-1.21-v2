package dev.sterner.guardvillagers.common.util;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeDemandIndexTest {

    @Test
    void collectMaterialsForRecipe_deduplicatesRepeatedIngredients() {
        List<Ingredient> repeatedPlanksRecipe = List.of(
                Ingredient.ofItems(Items.OAK_PLANKS),
                Ingredient.ofItems(Items.SPRUCE_PLANKS),
                Ingredient.ofItems(Items.BIRCH_PLANKS)
        );

        EnumSet<RecipeDemandIndex.DemandMaterial> materials = RecipeDemandIndex.collectMaterialsForRecipe(repeatedPlanksRecipe);

        assertEquals(EnumSet.of(RecipeDemandIndex.DemandMaterial.PLANKS), materials);
    }

    @Test
    void collectMaterialsForRecipe_keepsDistinctMaterials() {
        List<Ingredient> mixedRecipe = List.of(
                Ingredient.ofItems(Items.OAK_LOG),
                Ingredient.ofItems(Items.STICK),
                Ingredient.ofItems(Items.CHARCOAL)
        );

        EnumSet<RecipeDemandIndex.DemandMaterial> materials = RecipeDemandIndex.collectMaterialsForRecipe(mixedRecipe);

        assertEquals(
                EnumSet.of(
                        RecipeDemandIndex.DemandMaterial.LOGS,
                        RecipeDemandIndex.DemandMaterial.STICK,
                        RecipeDemandIndex.DemandMaterial.CHARCOAL
                ),
                materials
        );
    }

    @Test
    void strengthForOutput_weightsByCountWithCap() {
        assertEquals(1, RecipeDemandIndex.strengthForOutput(new ItemStack(Items.STICK, 1)));
        assertEquals(4, RecipeDemandIndex.strengthForOutput(new ItemStack(Items.ARROW, 4)));
        assertEquals(4, RecipeDemandIndex.strengthForOutput(new ItemStack(Items.ARROW, 16)));
    }

    @Test
    void resolveDynamicCap_limitsToolsmithPlankDemandToComponentBatch() {
        int capped = RecipeDemandIndex.resolveDynamicCap(
                VillagerProfession.TOOLSMITH,
                RecipeDemandIndex.DemandMaterial.PLANKS,
                24
        );

        assertEquals(3, capped);
    }

    @Test
    void resolveDynamicCap_keepsFarmerPlankDemandPathIntact() {
        int farmerCap = RecipeDemandIndex.resolveDynamicCap(
                VillagerProfession.FARMER,
                RecipeDemandIndex.DemandMaterial.PLANKS,
                28
        );

        assertEquals(28, farmerCap);
    }


    @Test
    void resolveDynamicCap_keepsPlankCapsForOtherProfessions() {
        assertEquals(48, RecipeDemandIndex.resolveDynamicCap(VillagerProfession.LIBRARIAN, RecipeDemandIndex.DemandMaterial.PLANKS, 48));
        assertEquals(40, RecipeDemandIndex.resolveDynamicCap(VillagerProfession.FISHERMAN, RecipeDemandIndex.DemandMaterial.PLANKS, 40));
        assertEquals(32, RecipeDemandIndex.resolveDynamicCap(VillagerProfession.FLETCHER, RecipeDemandIndex.DemandMaterial.PLANKS, 32));
    }

    @Test
    void resolveDynamicCap_leavesNonPlankToolsmithDemandUnchanged() {
        int stickCap = RecipeDemandIndex.resolveDynamicCap(
                VillagerProfession.TOOLSMITH,
                RecipeDemandIndex.DemandMaterial.STICK,
                24
        );

        assertEquals(24, stickCap);
    }

    @Test
    void resolveDynamicCap_leavesMasonDemandCapsUnchanged() {
        assertEquals(3, RecipeDemandIndex.resolveDynamicCap(VillagerProfession.MASON, RecipeDemandIndex.DemandMaterial.PLANKS, 3));
        assertEquals(2, RecipeDemandIndex.resolveDynamicCap(VillagerProfession.MASON, RecipeDemandIndex.DemandMaterial.STICK, 2));
    }

}

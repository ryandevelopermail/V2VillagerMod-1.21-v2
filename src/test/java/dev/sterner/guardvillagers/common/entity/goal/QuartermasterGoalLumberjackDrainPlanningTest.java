package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuartermasterGoalLumberjackDrainPlanningTest {

    private int originalReserveLogs;
    private int originalReservePlanks;
    private int originalReserveCharcoal;
    private boolean originalDrainEnabled;

    @BeforeEach
    void captureConfig() {
        originalReserveLogs = GuardVillagersConfig.quartermasterLumberjackReserveLogs;
        originalReservePlanks = GuardVillagersConfig.quartermasterLumberjackReservePlanks;
        originalReserveCharcoal = GuardVillagersConfig.quartermasterLumberjackReserveCharcoal;
        originalDrainEnabled = GuardVillagersConfig.quartermasterLumberjackDrainEnabled;

        GuardVillagersConfig.quartermasterLumberjackReserveLogs = 0;
        GuardVillagersConfig.quartermasterLumberjackReservePlanks = 0;
        GuardVillagersConfig.quartermasterLumberjackReserveCharcoal = 0;
        GuardVillagersConfig.quartermasterLumberjackDrainEnabled = true;
    }

    @AfterEach
    void restoreConfig() {
        GuardVillagersConfig.quartermasterLumberjackReserveLogs = originalReserveLogs;
        GuardVillagersConfig.quartermasterLumberjackReservePlanks = originalReservePlanks;
        GuardVillagersConfig.quartermasterLumberjackReserveCharcoal = originalReserveCharcoal;
        GuardVillagersConfig.quartermasterLumberjackDrainEnabled = originalDrainEnabled;
    }

    @Test
    void demandAwareReserve_keepsImmediateV1Inputs() throws Exception {
        QuartermasterGoal goal = new QuartermasterGoal(mock(VillagerEntity.class), BlockPos.ORIGIN, BlockPos.ORIGIN);
        SimpleInventory chest = new SimpleInventory(4);
        chest.setStack(0, new ItemStack(Items.OAK_PLANKS, 22));
        chest.setStack(1, new ItemStack(Items.OAK_LOG, 8));

        List<?> legs = invokePlan(goal, chest, LumberjackChestTriggerController.UpgradeDemand.v1Chest());
        int drainedPlanks = countTransferred(legs, Items.OAK_PLANKS);
        int drainedLogs = countTransferred(legs, Items.OAK_LOG);

        assertEquals(12, drainedPlanks, "Should keep 10 planks (8 cost + 2 safety buffer)");
        assertEquals(8, drainedLogs, "Logs can still drain when plank floor is already satisfied");
    }

    @Test
    void demandAwareReserve_keepsConvertibleLogs_whenPlanksLow() throws Exception {
        QuartermasterGoal goal = new QuartermasterGoal(mock(VillagerEntity.class), BlockPos.ORIGIN, BlockPos.ORIGIN);
        SimpleInventory chest = new SimpleInventory(4);
        chest.setStack(0, new ItemStack(Items.OAK_PLANKS, 8));
        chest.setStack(1, new ItemStack(Items.OAK_LOG, 4));

        List<?> legs = invokePlan(goal, chest, LumberjackChestTriggerController.UpgradeDemand.v1Chest());

        assertEquals(0, countTransferred(legs, Items.OAK_PLANKS));
        assertEquals(3, countTransferred(legs, Items.OAK_LOG), "Should keep 1 log to cover missing planks");
    }

    @Test
    void demandAwareReserve_keepsImmediateV3StickInputs() throws Exception {
        QuartermasterGoal goal = new QuartermasterGoal(mock(VillagerEntity.class), BlockPos.ORIGIN, BlockPos.ORIGIN);
        SimpleInventory chest = new SimpleInventory(4);
        chest.setStack(0, new ItemStack(Items.STICK, 12));

        List<?> legs = invokePlan(goal, chest, LumberjackChestTriggerController.UpgradeDemand.v3FenceGate());

        assertEquals(8, countTransferred(legs, Items.STICK), "Should keep 4 sticks for immediate fence-gate demand");
    }

    @Test
    void activeRecipeExclusions_useTagPredicates_forModdedWoodCompatibility() throws Exception {
        QuartermasterGoal goal = new QuartermasterGoal(mock(VillagerEntity.class), BlockPos.ORIGIN, BlockPos.ORIGIN);
        Method method = QuartermasterGoal.class.getDeclaredMethod(
                "buildLumberjackActiveRecipeExclusions",
                LumberjackChestTriggerController.UpgradeDemand.class
        );
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Predicate<ItemStack> exclusions = (Predicate<ItemStack>) method.invoke(goal, LumberjackChestTriggerController.UpgradeDemand.v2CraftingTable());

        ItemStack moddedPlankStack = mock(ItemStack.class);
        when(moddedPlankStack.isIn(ItemTags.PLANKS)).thenReturn(true);
        ItemStack moddedLogStack = mock(ItemStack.class);
        when(moddedLogStack.isIn(ItemTags.LOGS)).thenReturn(true);

        assertTrue(exclusions.test(moddedPlankStack));
        assertTrue(exclusions.test(moddedLogStack));
        assertFalse(exclusions.test(new ItemStack(Items.COBBLESTONE, 1)));
    }

    @Test
    void drainPlanning_disabledToggle_skipsSweepBeforePlanningLegs() throws Exception {
        GuardVillagersConfig.quartermasterLumberjackDrainEnabled = false;
        QuartermasterGoal goal = new QuartermasterGoal(mock(VillagerEntity.class), BlockPos.ORIGIN, BlockPos.ORIGIN);
        ServerWorld world = mock(ServerWorld.class);

        Method method = QuartermasterGoal.class.getDeclaredMethod("tryPlanLumberjackDrainSweepIfDue", ServerWorld.class);
        method.setAccessible(true);

        boolean planned = (boolean) method.invoke(goal, world);

        assertFalse(planned);
        verifyNoInteractions(world);
    }

    @SuppressWarnings("unchecked")
    private static List<?> invokePlan(QuartermasterGoal goal,
                                      Inventory inventory,
                                      LumberjackChestTriggerController.UpgradeDemand demand) throws Exception {
        Method method = QuartermasterGoal.class.getDeclaredMethod(
                "planLumberjackChestDrainLegs",
                BlockPos.class,
                Inventory.class,
                LumberjackChestTriggerController.UpgradeDemand.class,
                Predicate.class
        );
        method.setAccessible(true);
        return (List<?>) method.invoke(goal, BlockPos.ORIGIN, inventory, demand, (Predicate<ItemStack>) stack -> false);
    }

    private static int countTransferred(List<?> legs, net.minecraft.item.Item item) throws Exception {
        int total = 0;
        for (Object leg : legs) {
            Method transferStack = leg.getClass().getDeclaredMethod("transferStack");
            transferStack.setAccessible(true);
            ItemStack stack = (ItemStack) transferStack.invoke(leg);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}

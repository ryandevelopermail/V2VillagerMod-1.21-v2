package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LumberjackFurnaceModifierGoalTest {

    @Test
    void evaluateServiceStateCore_flagsFuelLowWhenCharcoalBelowSelfCap() {
        LumberjackFurnaceModifierGoal.ServiceState state = LumberjackFurnaceModifierGoal.evaluateServiceStateCore(
                16,
                0,
                new ItemStack(Items.OAK_LOG, 64),
                new ItemStack(Items.CHARCOAL, 11),
                ItemStack.EMPTY,
                true
        );

        assertTrue(state.actionable());
        assertEquals("fuel_low", state.reason());
    }

    @Test
    void evaluateServiceStateCore_doesNotStayFuelLowAtSelfFuelCap() {
        LumberjackFurnaceModifierGoal.ServiceState state = LumberjackFurnaceModifierGoal.evaluateServiceStateCore(
                16,
                0,
                new ItemStack(Items.OAK_LOG, 64),
                new ItemStack(Items.CHARCOAL, 12),
                ItemStack.EMPTY,
                true
        );

        assertFalse(state.actionable());
        assertEquals("fuel_saturated", state.reason());
    }

    @Test
    void evaluateServiceStateCore_routesOutputEvenWhenSelfFuelCapMet() {
        LumberjackFurnaceModifierGoal.ServiceState state = LumberjackFurnaceModifierGoal.evaluateServiceStateCore(
                16,
                0,
                new ItemStack(Items.OAK_LOG, 64),
                new ItemStack(Items.CHARCOAL, 12),
                new ItemStack(Items.CHARCOAL, 4),
                true
        );

        assertTrue(state.actionable());
        assertEquals("route_output", state.reason());
    }

    @Test
    void refillFuelFromCharcoalOutput_keepsSelfFuelAtCapAndRoutesExcessToChest() throws Exception {
        List<ItemStack> gatheredBuffer = new ArrayList<>();
        LumberjackGuardEntity guard = mock(LumberjackGuardEntity.class);
        when(guard.getGatheredStackBuffer()).thenReturn(gatheredBuffer);

        LumberjackFurnaceModifierGoal goal = new LumberjackFurnaceModifierGoal(guard);
        AbstractFurnaceBlockEntity furnace = mockFurnace(
                new ItemStack(Items.OAK_LOG, 8),
                new ItemStack(Items.CHARCOAL, 10),
                new ItemStack(Items.CHARCOAL, 8)
        );
        SimpleInventory chest = new SimpleInventory(3);

        invokeRefill(goal, chest, furnace);

        assertEquals(12, furnace.getStack(1).getCount());
        assertTrue(furnace.getStack(2).isEmpty());
        assertEquals(6, chest.getStack(0).getCount());
        assertTrue(chest.getStack(0).isOf(Items.CHARCOAL));
    }

    @Test
    void refillFuelFromCharcoalOutput_preservesStartupBurnWhenFuelEmpty() throws Exception {
        List<ItemStack> gatheredBuffer = new ArrayList<>();
        LumberjackGuardEntity guard = mock(LumberjackGuardEntity.class);
        when(guard.getGatheredStackBuffer()).thenReturn(gatheredBuffer);

        LumberjackFurnaceModifierGoal goal = new LumberjackFurnaceModifierGoal(guard);
        AbstractFurnaceBlockEntity furnace = mockFurnace(
                new ItemStack(Items.OAK_LOG, 5),
                ItemStack.EMPTY,
                new ItemStack(Items.CHARCOAL, 3)
        );
        SimpleInventory chest = new SimpleInventory(3);

        invokeRefill(goal, chest, furnace);

        assertEquals(3, furnace.getStack(1).getCount());
        assertTrue(furnace.getStack(2).isEmpty());
        assertTrue(chest.getStack(0).isEmpty());
    }

    private static void invokeRefill(LumberjackFurnaceModifierGoal goal, SimpleInventory chest, AbstractFurnaceBlockEntity furnace) throws Exception {
        Method method = LumberjackFurnaceModifierGoal.class.getDeclaredMethod(
                "refillFuelFromCharcoalOutput",
                net.minecraft.inventory.Inventory.class,
                AbstractFurnaceBlockEntity.class
        );
        method.setAccessible(true);
        method.invoke(goal, chest, furnace);
    }

    private static AbstractFurnaceBlockEntity mockFurnace(ItemStack input, ItemStack fuel, ItemStack output) {
        AbstractFurnaceBlockEntity furnace = mock(AbstractFurnaceBlockEntity.class);
        ItemStack[] slots = new ItemStack[]{input, fuel, output};
        when(furnace.getStack(anyInt())).thenAnswer(invocation -> slots[invocation.getArgument(0)]);
        doAnswer(invocation -> {
            int slot = invocation.getArgument(0);
            ItemStack stack = invocation.getArgument(1);
            slots[slot] = stack;
            return null;
        }).when(furnace).setStack(anyInt(), org.mockito.ArgumentMatchers.any(ItemStack.class));
        return furnace;
    }
}

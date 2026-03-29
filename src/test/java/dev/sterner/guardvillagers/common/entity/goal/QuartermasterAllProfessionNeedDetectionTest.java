package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the Quartermaster's expanded need-detection logic covering all 13 v2 professions,
 * the event-wakeup mechanism, and the reduced polling interval.
 */
class QuartermasterAllProfessionNeedDetectionTest {

    // -------------------------------------------------------------------------
    // Polling interval and event wakeup
    // -------------------------------------------------------------------------

    @Test
    void checkIntervalTicks_isReducedTo100() throws Exception {
        Field field = QuartermasterGoal.class.getDeclaredField("CHECK_INTERVAL_TICKS");
        field.setAccessible(true);
        int value = (int) field.get(null);
        assertEquals(100, value, "CHECK_INTERVAL_TICKS should be 100 (5 seconds) for fast responses");
    }

    @Test
    void requestImmediateCheck_setsImmediateCheckPending() throws Exception {
        BlockPos jobPos = new BlockPos(0, 64, 0);
        BlockPos chestPos = new BlockPos(1, 64, 0);
        VillagerEntity villager = mock(VillagerEntity.class);
        QuartermasterGoal goal = new QuartermasterGoal(villager, jobPos, chestPos);

        // Confirm flag starts false
        assertFalse(immediateCheckPending(goal), "immediateCheckPending should start false");

        goal.requestImmediateCheck();

        assertTrue(immediateCheckPending(goal), "requestImmediateCheck() should set immediateCheckPending to true");
    }

    // -------------------------------------------------------------------------
    // Guard entity scouts — Butcher
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void findButcherNeedingFuel_returnsEmpty_whenNoButchersInRange() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getEntitiesByClass(eq(ButcherGuardEntity.class), any(Box.class), any(Predicate.class)))
                .thenReturn(List.of());

        Optional<BlockPos> result = invokeButcherFuel(makeGoal(), world);

        assertTrue(result.isEmpty(), "Should return empty when no butchers exist in range");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findButcherNeedingFuel_returnsChestPos_whenButcherHasNoFuel() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos butcherChest = new BlockPos(5, 64, 5);

        ButcherGuardEntity butcher = mock(ButcherGuardEntity.class);
        when(butcher.isAlive()).thenReturn(true);
        when(butcher.getPairedChestPos()).thenReturn(butcherChest);
        // No chest block state → countItem returns 0 for coal and charcoal → 0 < BUTCHER_FUEL_THRESHOLD (8)
        when(world.getBlockState(butcherChest)).thenReturn(net.minecraft.block.Blocks.AIR.getDefaultState());

        when(world.getEntitiesByClass(eq(ButcherGuardEntity.class), any(Box.class), any(Predicate.class)))
                .thenAnswer(inv -> {
                    Predicate<ButcherGuardEntity> pred = inv.getArgument(2);
                    return pred.test(butcher) ? List.of(butcher) : List.of();
                });

        Optional<BlockPos> result = invokeButcherFuel(makeGoal(), world);

        assertTrue(result.isPresent(), "Should return butcher chest pos when fuel count < threshold");
        assertEquals(butcherChest, result.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findButcherNeedingFuel_returnsEmpty_whenButcherHasNoPairedChest() throws Exception {
        ServerWorld world = mock(ServerWorld.class);

        ButcherGuardEntity butcher = mock(ButcherGuardEntity.class);
        when(butcher.isAlive()).thenReturn(true);
        when(butcher.getPairedChestPos()).thenReturn(null);

        when(world.getEntitiesByClass(eq(ButcherGuardEntity.class), any(Box.class), any(Predicate.class)))
                .thenAnswer(inv -> {
                    Predicate<ButcherGuardEntity> pred = inv.getArgument(2);
                    return pred.test(butcher) ? List.of(butcher) : List.of();
                });

        Optional<BlockPos> result = invokeButcherFuel(makeGoal(), world);

        assertTrue(result.isEmpty(), "Should return empty when butcher has no paired chest");
    }

    // -------------------------------------------------------------------------
    // Guard entity scouts — Fisherman
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void findFishermanNeedingRod_returnsEmpty_whenNoFishermanInRange() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getEntitiesByClass(eq(FishermanGuardEntity.class), any(Box.class), any(Predicate.class)))
                .thenReturn(List.of());

        Optional<BlockPos> result = invokeFishermanRod(makeGoal(), world);

        assertTrue(result.isEmpty(), "Should return empty when no fisherman guards exist in range");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findFishermanNeedingRod_returnsChestPos_whenFishermanHasNoRod() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos fishermanChest = new BlockPos(8, 64, 8);

        FishermanGuardEntity fisherman = mock(FishermanGuardEntity.class);
        when(fisherman.isAlive()).thenReturn(true);
        when(fisherman.getPairedChestPos()).thenReturn(fishermanChest);
        // No chest block → countItem returns 0 fishing rods → 0 < FISHERMAN_ROD_THRESHOLD (1)
        when(world.getBlockState(fishermanChest)).thenReturn(net.minecraft.block.Blocks.AIR.getDefaultState());

        when(world.getEntitiesByClass(eq(FishermanGuardEntity.class), any(Box.class), any(Predicate.class)))
                .thenAnswer(inv -> {
                    Predicate<FishermanGuardEntity> pred = inv.getArgument(2);
                    return pred.test(fisherman) ? List.of(fisherman) : List.of();
                });

        Optional<BlockPos> result = invokeFishermanRod(makeGoal(), world);

        assertTrue(result.isPresent(), "Should return fisherman chest pos when rod count < threshold");
        assertEquals(fishermanChest, result.get());
    }

    // -------------------------------------------------------------------------
    // Generic profession chest need scout
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void findProfessionChestNeedingItem_returnsEmpty_whenNoVillagersOfProfessionInRange() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getEntitiesByClass(eq(VillagerEntity.class), any(Box.class), any(Predicate.class)))
                .thenReturn(List.of());

        Optional<BlockPos> result = invokeProfessionChestNeed(
                makeGoal(), world, VillagerProfession.FARMER, net.minecraft.item.Items.WHEAT_SEEDS, 32);

        assertTrue(result.isEmpty(), "Should return empty when no farmers exist in range");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findProfessionChestNeedingItem_returnsEmpty_whenWrongProfessionInRange() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        // Only a CLERIC is in range, but we're asking for FARMER
        VillagerEntity cleric = mockVillager(VillagerProfession.CLERIC);

        when(world.getEntitiesByClass(eq(VillagerEntity.class), any(Box.class), any(Predicate.class)))
                .thenAnswer(inv -> {
                    Predicate<VillagerEntity> pred = inv.getArgument(2);
                    return pred.test(cleric) ? List.of(cleric) : List.of();
                });

        Optional<BlockPos> result = invokeProfessionChestNeed(
                makeGoal(), world, VillagerProfession.FARMER, net.minecraft.item.Items.WHEAT_SEEDS, 32);

        assertTrue(result.isEmpty(), "Should return empty when only a different profession is in range");
    }

    // -------------------------------------------------------------------------
    // Threshold constants spot-check
    // -------------------------------------------------------------------------

    @Test
    void butcherFuelThreshold_isEight() throws Exception {
        assertEquals(8, intConstant("BUTCHER_FUEL_THRESHOLD"));
    }

    @Test
    void fishermanRodThreshold_isOne() throws Exception {
        assertEquals(1, intConstant("FISHERMAN_ROD_THRESHOLD"));
    }

    @Test
    void farmerSeedThreshold_is32() throws Exception {
        assertEquals(32, intConstant("FARMER_SEED_THRESHOLD"));
    }

    @Test
    void shepherdPlankThreshold_is16() throws Exception {
        assertEquals(16, intConstant("SHEPHERD_PLANK_THRESHOLD"));
    }

    @Test
    void haulAmountRods_isTwo() throws Exception {
        assertEquals(2, intConstant("HAUL_AMOUNT_RODS"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static QuartermasterGoal makeGoal() {
        BlockPos jobPos = new BlockPos(0, 64, 0);
        BlockPos chestPos = new BlockPos(1, 64, 0);
        VillagerEntity villager = mock(VillagerEntity.class);
        when(villager.getUuidAsString()).thenReturn("test-qm");
        return new QuartermasterGoal(villager, jobPos, chestPos);
    }

    private static VillagerEntity mockVillager(VillagerProfession profession) {
        VillagerEntity v = mock(VillagerEntity.class);
        VillagerData data = mock(VillagerData.class);
        when(v.isAlive()).thenReturn(true);
        when(v.getVillagerData()).thenReturn(data);
        when(data.getProfession()).thenReturn(profession);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Optional<BlockPos> invokeButcherFuel(QuartermasterGoal goal, ServerWorld world) throws Exception {
        Method m = QuartermasterGoal.class.getDeclaredMethod("findButcherNeedingFuel", ServerWorld.class);
        m.setAccessible(true);
        return (Optional<BlockPos>) m.invoke(goal, world);
    }

    @SuppressWarnings("unchecked")
    private static Optional<BlockPos> invokeFishermanRod(QuartermasterGoal goal, ServerWorld world) throws Exception {
        Method m = QuartermasterGoal.class.getDeclaredMethod("findFishermanNeedingRod", ServerWorld.class);
        m.setAccessible(true);
        return (Optional<BlockPos>) m.invoke(goal, world);
    }

    @SuppressWarnings("unchecked")
    private static Optional<BlockPos> invokeProfessionChestNeed(
            QuartermasterGoal goal, ServerWorld world,
            VillagerProfession profession, net.minecraft.item.Item item, int threshold) throws Exception {
        Method m = QuartermasterGoal.class.getDeclaredMethod(
                "findProfessionChestNeedingItem",
                ServerWorld.class, VillagerProfession.class, net.minecraft.item.Item.class, int.class);
        m.setAccessible(true);
        return (Optional<BlockPos>) m.invoke(goal, world, profession, item, threshold);
    }

    private static boolean immediateCheckPending(QuartermasterGoal goal) throws Exception {
        Field f = QuartermasterGoal.class.getDeclaredField("immediateCheckPending");
        f.setAccessible(true);
        return (boolean) f.get(goal);
    }

    private static int intConstant(String fieldName) throws Exception {
        Field f = QuartermasterGoal.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (int) f.get(null);
    }
}

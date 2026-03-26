package dev.sterner.guardvillagers.common.villager.behavior;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VillagerFenceGateEscapeHelperDecisionLogicTest {

    @Test
    void does_not_activate_when_not_inside_pen() {
        boolean activates = VillagerFenceGateEscapeHelper.shouldActivateEscape(
                200L,
                100L,
                20,
                false,
                false);

        assertFalse(activates);
    }

    @Test
    void activates_when_inside_pen_with_reachable_gate_to_outside() {
        BlockPos interior = new BlockPos(0, 64, 0);
        BlockPos front = new BlockPos(1, 64, 0);
        BlockPos back = new BlockPos(-1, 64, 0);

        BlockPos outside = VillagerFenceGateEscapeHelper.resolveOutsideTarget(
                interior,
                front,
                back,
                pos -> Set.of(interior).contains(pos),
                pos -> !pos.equals(front));

        assertEquals(back, outside);
    }

    @Test
    void opens_gate_then_requests_outside_navigation_target() {
        BlockPos outside = new BlockPos(5, 64, 5);

        VillagerFenceGateEscapeHelper.EscapeActivationAction action =
                VillagerFenceGateEscapeHelper.createActivationAction(true, outside);

        assertTrue(action.shouldOpenGate());
        assertEquals(outside, action.outsideTarget());
    }

    @Test
    void closes_gate_only_if_helper_opened_it() {
        assertTrue(VillagerFenceGateEscapeHelper.shouldCloseGateAfterEscape(true, true));
    }

    @Test
    void does_not_close_gate_opened_by_other_workflows() {
        assertFalse(VillagerFenceGateEscapeHelper.shouldCloseGateAfterEscape(false, true));
    }

    @Test
    void does_not_run_when_existing_shepherd_or_farmer_gate_state_is_active() {
        boolean activates = VillagerFenceGateEscapeHelper.shouldActivateEscape(
                200L,
                100L,
                20,
                true,
                true);

        assertFalse(activates);
    }

    @Test
    void respects_cooldown_and_scan_radius_limits() {
        boolean cooldownBlocked = VillagerFenceGateEscapeHelper.shouldActivateEscape(
                110L,
                100L,
                20,
                true,
                false);

        assertFalse(cooldownBlocked);

        BlockPos villagerPos = new BlockPos(0, 64, 0);
        BlockPos inRange = new BlockPos(6, 64, 0);
        BlockPos outOfRange = new BlockPos(7, 64, 0);

        assertTrue(VillagerFenceGateEscapeHelper.isWithinScanRadius(villagerPos, inRange, VillagerFenceGateEscapeHelper.SCAN_RADIUS));
        assertFalse(VillagerFenceGateEscapeHelper.isWithinScanRadius(villagerPos, outOfRange, VillagerFenceGateEscapeHelper.SCAN_RADIUS));
    }
}

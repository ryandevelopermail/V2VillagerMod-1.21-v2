package dev.sterner.guardvillagers.common.util;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WallProjectPolicyResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(WallProjectPolicyResolver.class);
    private static final int ANCHOR_SEARCH_RADIUS = VillageGuardStandManager.BELL_EFFECT_RANGE;
    private static final Map<UUID, String> LAST_LOGGED_POLICY_BY_MASON = new HashMap<>();

    private WallProjectPolicyResolver() {
    }

    public static PolicyDecision resolve(ServerWorld world, BlockPos masonPos, UUID masonUuid) {
        Optional<BlockPos> anchorPosOpt = VillageAnchorState.get(world.getServer())
                .getNearestQmChest(world, masonPos, ANCHOR_SEARCH_RADIUS);
        if (anchorPosOpt.isEmpty()) {
            PolicyDecision decision = new PolicyDecision(null, false, false, false, PolicyMode.NORMAL);
            logResolvedPolicy(world, masonUuid, decision);
            return decision;
        }

        BlockPos anchorPos = anchorPosOpt.get();
        VillageWallProjectState projectState = VillageWallProjectState.get(world.getServer());
        boolean projectKnown = projectState.hasProject(world.getRegistryKey(), anchorPos);
        boolean projectComplete = projectKnown && projectState.isProjectComplete(world.getRegistryKey(), anchorPos);
        boolean withinPerimeter = projectState.getProjectBounds(world.getRegistryKey(), anchorPos)
                .map(bounds -> bounds.contains(masonPos))
                .orElse(false);
        boolean projectActive = projectKnown && !projectComplete;

        PolicyMode mode = PolicyMode.NORMAL;
        if (projectComplete && withinPerimeter) {
            mode = PolicyMode.BLOCK_WALLS;
        } else if (projectActive) {
            mode = PolicyMode.WALLS_ONLY;
        }

        PolicyDecision decision = new PolicyDecision(anchorPos, projectActive, projectComplete, withinPerimeter, mode);
        logResolvedPolicy(world, masonUuid, decision);
        return decision;
    }

    private static void logResolvedPolicy(ServerWorld world, UUID masonUuid, PolicyDecision decision) {
        String anchorId = decision.anchorPos() == null
                ? world.getRegistryKey().getValue() + ":none"
                : world.getRegistryKey().getValue() + ":" + decision.anchorPos().toShortString();
        String signature = anchorId + "|" + decision.mode().name();
        String previous = LAST_LOGGED_POLICY_BY_MASON.put(masonUuid, signature);
        if (!signature.equals(previous)) {
            LOGGER.debug("WallProjectPolicy anchor={} mason={} mode={}", anchorId, masonUuid, decision.mode());
        }
    }

    public enum PolicyMode {
        NORMAL,
        WALLS_ONLY,
        BLOCK_WALLS
    }

    public record PolicyDecision(BlockPos anchorPos,
                                 boolean projectActive,
                                 boolean projectComplete,
                                 boolean masonWithinPerimeter,
                                 PolicyMode mode) {
    }
}

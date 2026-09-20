package dev.sterner.guardvillagers.common.developer;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardCraftingGoal;
import dev.sterner.guardvillagers.common.network.DeveloperSetupStatusPacket;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.UnemployedLumberjackConversionHook;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;
import net.minecraft.world.ServerWorldAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeveloperSetupManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeveloperSetupManager.class);
    public static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int STAGE_TIMEOUT_TICKS = 20 * 20;
    static final int V1_INITIAL_RESTRAINT_TICKS = 20;
    private static final int V1_PAIR_TIMEOUT_TICKS = 20 * 45;
    private static final int V1_MAX_PENDING = DeveloperV1PlacementGrid.MAX_CONCURRENT;
    private static final int V1_ISOLATION_RADIUS = 1;
    private static final int CONVERSION_DELAY_TICKS = 10;
    private static final Map<UUID, SetupSession> SESSIONS = new HashMap<>();

    private DeveloperSetupManager() {
    }

    public static void requestSetup(ServerPlayerEntity player, DeveloperSetupRequest request) {
        if (!player.hasPermissionLevel(REQUIRED_PERMISSION_LEVEL)) {
            sendStatus(player, "Permission denied: operator level 2 is required.", 0, true, false);
            return;
        }
        if (request == null) {
            sendStatus(player, "Malformed developer setup request.", 0, true, false);
            return;
        }
        String validationError = request.validationError().orElse(null);
        if (validationError != null) {
            sendStatus(player, validationError, 0, true, false);
            return;
        }
        if (SESSIONS.containsKey(player.getUuid())) {
            sendStatus(player, "A developer setup is already running.", 0, true, false);
            return;
        }

        SetupSession session = new SetupSession(player, request);
        SESSIONS.put(player.getUuid(), session);
        sendStatus(player, "Setup queued at your current position.", 5, false, false);
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, SetupSession>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SetupSession> entry = iterator.next();
            SetupSession session = entry.getValue();
            try {
                session.tick(server);
            } catch (RuntimeException exception) {
                LOGGER.error("Developer setup failed for player {}", entry.getKey(), exception);
                session.fail("Setup failed: " + exception.getClass().getSimpleName());
            }
            if (session.isFinished()) {
                session.cleanup();
                iterator.remove();
            }
        }
    }

    public static void cancelAll() {
        SESSIONS.values().forEach(SetupSession::cancel);
        SESSIONS.clear();
    }

    private static void sendStatus(ServerPlayerEntity player, String message, int progress, boolean finished, boolean success) {
        if (ServerPlayNetworking.canSend(player, DeveloperSetupStatusPacket.ID)) {
            ServerPlayNetworking.send(player, new DeveloperSetupStatusPacket(message, progress, finished, success));
        }
    }

    private static final class SetupSession {
        private final UUID playerId;
        private final DeveloperSetupRequest request;
        private final ServerWorld world;
        private final BlockPos requestedOrigin;
        private final DeveloperSetupWorkflow workflow;
        private final DeveloperV1BatchProgress v1Batch;
        private final Set<BlockPos> attemptedTreeSites = new HashSet<>();
        private final DeveloperV1JobSiteAssignments<BlockPos> v1JobSites = new DeveloperV1JobSiteAssignments<>();
        private final Map<Integer, PendingV1Pair> pendingV1Pairs = new LinkedHashMap<>();
        private BlockPos setupOrigin;
        private BlockPos tablePos;
        private VillagerEntity villager;
        private LumberjackGuardEntity lumberjack;
        private boolean originalAiDisabled;
        private BlockPos createdChestPos;
        private boolean chestSupplied;
        private BlockPos createdFurnacePos;
        private BlockPos createdModifierPos;
        private int generatedTrees;
        private DeveloperSetupStage reportedStage;
        private boolean terminalStatusSent;
        private String lastV1Failure;
        private String v1FatalFailure;

        private SetupSession(ServerPlayerEntity player, DeveloperSetupRequest request) {
            this.playerId = player.getUuid();
            this.request = request;
            this.world = player.getServerWorld();
            this.requestedOrigin = player.getBlockPos().offset(player.getHorizontalFacing(), 4).toImmutable();
            if (request.setupType() == DeveloperSetupType.V1_PROFESSION) {
                this.v1Batch = new DeveloperV1BatchProgress(request.professionSelections());
                this.workflow = null;
            } else {
                this.v1Batch = null;
                this.workflow = new DeveloperSetupWorkflow(
                        request.setupType(),
                        request.needsInfrastructure(),
                        request.needsInventoryPopulation(),
                        request.generateMatureTrees(),
                        STAGE_TIMEOUT_TICKS
                );
            }
        }

        private void tick(MinecraftServer server) {
            if (v1Batch != null) {
                tickV1Batch(server);
                return;
            }
            if (workflow.stage().isTerminal()) {
                sendTerminalStatus(server);
                return;
            }

            DeveloperSetupWorkflow.Observation observation = switch (workflow.stage()) {
                case PREPARE -> prepare();
                case WAIT_FOR_PROFESSION -> waitForProfession();
                case PLACE_V2_BLOCKS -> placeV2Blocks();
                case WAIT_FOR_PAIRING -> waitForPairing();
                case PLACE_LUMBERJACK_INFRASTRUCTURE -> placeLumberjackInfrastructure();
                case POPULATE_LUMBERJACK_INVENTORY -> populateLumberjackInventory();
                case GENERATE_TREES -> generateTrees();
                default -> DeveloperSetupWorkflow.Observation.none();
            };
            workflow.tick(observation);
            reportStage(server);
            if (workflow.stage().isTerminal()) {
                sendTerminalStatus(server);
            }
        }

        private void tickV1Batch(MinecraftServer server) {
            if (v1FatalFailure != null || v1Batch.isComplete()) {
                sendV1TerminalStatus(server);
                return;
            }

            String completedSiteError = validateCompletedV1JobSites();
            if (completedSiteError != null) {
                v1FatalFailure = completedSiteError;
            } else {
                boolean changed = fillV1PendingWindow();
                if (v1FatalFailure == null) {
                    changed |= tickPendingV1Pairs();
                }
                if (v1FatalFailure == null) {
                    changed |= fillV1PendingWindow();
                }
                if (v1FatalFailure == null
                        && (v1Batch.pending() != pendingV1Pairs.size()
                        || v1Batch.pending() != v1JobSites.pendingCount())) {
                    v1FatalFailure = "V1 pending-pair ownership verification failed.";
                } else if (changed && v1FatalFailure == null && !v1Batch.isComplete()) {
                    sendV1Progress(server);
                }
            }

            if (v1FatalFailure != null || v1Batch.isComplete()) {
                sendV1TerminalStatus(server);
            }
        }

        private boolean fillV1PendingWindow() {
            boolean changed = false;
            while (v1FatalFailure == null && v1Batch.canStart(V1_MAX_PENDING)) {
                prepareV1Pair(v1Batch.startNext());
                changed = true;
            }
            return changed;
        }

        private void prepareV1Pair(DeveloperV1BatchProgress.Task task) {
            DeveloperProfession requestedProfession = task.profession();
            VillagerProfession expectedProfession = resolveV1Profession(requestedProfession);
            Block expectedJobBlock = resolveV1JobBlock(requestedProfession).orElse(null);
            if (expectedProfession == null || expectedJobBlock == null) {
                failV1TaskBeforeSpawn(task,
                        "No supported job-site mapping exists for " + requestedProfession.displayName() + ".");
                return;
            }

            BlockPos gridAnchor = requestedOrigin.add(
                    task.gridSlot().x(),
                    0,
                    task.gridSlot().z());
            V1SitePlan sitePlan = findV1SitePlan(
                    world,
                    gridAnchor,
                    requestedOrigin.getY(),
                    v1JobSites.reservedPositions(),
                    occupiedV1IsolationColumns());
            if (sitePlan == null) {
                failV1TaskBeforeSpawn(task,
                        "No safe job-site location was found for " + requestedProfession.displayName()
                                + " within " + DeveloperV1TerrainPlanner.MAX_SEARCH_RADIUS
                                + " blocks of its preferred grid position.");
                return;
            }
            BlockPos candidateJobPos = sitePlan.jobPos();
            if (candidateJobPos.getX() != gridAnchor.getX()
                    || candidateJobPos.getZ() != gridAnchor.getZ()
                    || candidateJobPos.getY() != requestedOrigin.getY()) {
                LOGGER.info("V1 developer placement relocated task={} profession={} preferred={} actual={} candidatesChecked={}",
                        task.index() + 1,
                        requestedProfession.displayName(),
                        gridAnchor.toShortString(),
                        candidateJobPos.toShortString(),
                        sitePlan.evaluatedCandidates());
            }
            if (!v1JobSites.reserve(task, candidateJobPos)) {
                v1FatalFailure = "V1 placement ownership collision for task " + (task.index() + 1) + ".";
                return;
            }
            logV1WorkstationLifecycle(task, candidateJobPos, "assignment_reserved", "none");
            if (!world.setBlockState(candidateJobPos, stableV1JobBlockState(expectedJobBlock), Block.NOTIFY_ALL)) {
                rollbackPendingV1JobSite(task.index(), candidateJobPos, expectedJobBlock);
                v1JobSites.rollback(task.index());
                failV1TaskBeforeSpawn(task, "Could not place the " + requestedProfession.displayName() + " job site.");
                return;
            }
            if (!v1JobSites.markWorkstationPlaced(task.index(), candidateJobPos)) {
                v1FatalFailure = "V1 workstation lifecycle verification failed for task "
                        + (task.index() + 1) + ".";
                return;
            }
            logV1WorkstationLifecycle(task, candidateJobPos, "workstation_placed", "none");

            V1IsolationPlan isolation = placeV1IsolationBarriers(sitePlan.isolation());
            if (isolation == null) {
                rollbackPendingV1JobSite(task.index(), candidateJobPos, expectedJobBlock);
                v1JobSites.rollback(task.index());
                failV1TaskBeforeSpawn(task, "Could not isolate the " + requestedProfession.displayName()
                        + " villager from other pending workstations.");
                return;
            }
            VillagerEntity pendingVillager = spawnVillager(world, isolation.spawnPos());
            if (pendingVillager == null) {
                removeV1IsolationBarriers(isolation.barrierPositions());
                rollbackPendingV1JobSite(task.index(), candidateJobPos, expectedJobBlock);
                v1JobSites.rollback(task.index());
                failV1TaskBeforeSpawn(task, "Could not spawn the " + requestedProfession.displayName() + " villager.");
                return;
            }
            pendingVillager.setPersistent();
            boolean pendingOriginalAiDisabled = pendingVillager.isAiDisabled();
            if (pendingOriginalAiDisabled) {
                pendingVillager.setAiDisabled(false);
            }
            if (!v1JobSites.attachVillager(task.index(), pendingVillager.getUuid())) {
                pendingVillager.setAiDisabled(pendingOriginalAiDisabled);
                removeV1IsolationBarriers(isolation.barrierPositions());
                DeveloperV1JobSiteAssignments.AssignmentState failureState =
                        v1JobSites.state(task.index());
                if (failureState != null && failureState.rollbackEligible()) {
                    pendingVillager.discard();
                    rollbackPendingV1JobSite(task.index(), candidateJobPos, expectedJobBlock);
                    v1JobSites.rollback(task.index());
                } else {
                    pendingVillager.getNavigation().stop();
                    LOGGER.debug("V1 failed attachment preserved spawned villager task={} profession={} assignmentState={} workstationPosition={} villagerUuid={} reason=destructive_cleanup_not_allowed workstationPreserved={} villagerPreserved=true",
                            task.index() + 1,
                            task.profession().displayName(),
                            failureState == null ? "missing" : failureState,
                            candidateJobPos.toShortString(),
                            pendingVillager.getUuid(),
                            world.getBlockState(candidateJobPos).isOf(expectedJobBlock));
                }
                v1FatalFailure = "V1 villager ownership collision for task " + (task.index() + 1) + ".";
                return;
            }
            logV1WorkstationLifecycle(
                    task,
                    candidateJobPos,
                    "villager_attached",
                    pendingVillager.getUuidAsString());
            pendingV1Pairs.put(task.index(), new PendingV1Pair(
                    task,
                    pendingVillager.getUuid(),
                    expectedProfession,
                    expectedJobBlock,
                    candidateJobPos,
                    isolation.spawnPos(),
                    isolation.barrierPositions(),
                    pendingOriginalAiDisabled));
        }

        private boolean tickPendingV1Pairs() {
            boolean changed = false;
            Iterator<Map.Entry<Integer, PendingV1Pair>> iterator = pendingV1Pairs.entrySet().iterator();
            while (iterator.hasNext() && v1FatalFailure == null) {
                PendingV1Pair pair = iterator.next().getValue();
                pair.progress.advanceTick();
                if (!(world.getEntity(pair.villagerId) instanceof VillagerEntity pendingVillager)
                        || !pendingVillager.isAlive()) {
                    failInvalidPendingV1Pair(pair,
                            pair.task.profession().displayName() + " villager was removed before pairing.");
                    iterator.remove();
                    changed = true;
                    continue;
                }

                if (pair.progress.shouldRestrain()) {
                    restrainPendingV1Villager(pair, pendingVillager);
                }
                if (!areV1IsolationBarriersIntact(pair.isolationBlocks)) {
                    failInvalidPendingV1Pair(pair,
                            "isolation_lost: temporary isolation barrier was removed before pairing.");
                    iterator.remove();
                    changed = true;
                    continue;
                }
                if (!world.getBlockState(pair.jobPos).isOf(pair.expectedJobBlock)) {
                    if (restoreAttachedV1Workstation(pair, pendingVillager)) {
                        changed = true;
                    } else {
                        failInvalidPendingV1Pair(pair,
                                "workstation_missing: owned workstation could not be safely restored.");
                        iterator.remove();
                        changed = true;
                        continue;
                    }
                }

                VillagerProfession acquired = pendingVillager.getVillagerData().getProfession();
                BlockPos claimedJobSite = pendingVillager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                        .map(globalPos -> globalPos.pos())
                        .orElse(null);
                boolean exactPair = acquired == pair.expectedProfession && pair.jobPos.equals(claimedJobSite);
                if (pair.progress.completeIfExactPair(exactPair)) {
                    if (!v1JobSites.complete(pair.task.index(), pair.villagerId, claimedJobSite)) {
                        v1FatalFailure = "V1 completed-pair ownership verification failed for task "
                                + (pair.task.index() + 1) + ".";
                        break;
                    }
                    logV1WorkstationLifecycle(
                            pair.task,
                            pair.jobPos,
                            "exact_pair_completed",
                            pair.villagerId.toString());
                    removeV1IsolationBarriers(pair.isolationBlocks);
                    releasePendingV1Villager(pair, pendingVillager);
                    v1Batch.finish(pair.task.index(), true);
                    iterator.remove();
                    changed = true;
                    continue;
                }
                boolean wrongJobSite = claimedJobSite != null && !pair.jobPos.equals(claimedJobSite);
                boolean wrongProfession = acquired != VillagerProfession.NONE && acquired != pair.expectedProfession;
                DeveloperV1PendingMismatchRecovery.Action mismatchAction =
                        pair.mismatchRecovery.observe(wrongJobSite, wrongProfession);
                if (mismatchAction == DeveloperV1PendingMismatchRecovery.Action.RECOVER) {
                    recoverPendingV1Mismatch(
                            pair,
                            pendingVillager,
                            acquired,
                            claimedJobSite,
                            wrongProfession);
                    changed = true;
                } else if (mismatchAction == DeveloperV1PendingMismatchRecovery.Action.WAIT_FOR_TIMEOUT
                        && !pair.mismatchLimitLogged) {
                    pair.mismatchLimitLogged = true;
                    LOGGER.debug("V1 pending mismatch recovery exhausted task={} expectedProfession={} expectedJobPos={} currentProfession={} claimedJobSite={} attempts={} wrongPoiCleared=false retried=false action=wait_for_preserved_timeout",
                            pair.task.index() + 1,
                            pair.expectedProfession,
                            pair.jobPos.toShortString(),
                            acquired,
                            claimedJobSite == null ? "none" : claimedJobSite.toShortString(),
                            pair.mismatchRecovery.recoveryAttempts());
                }
                if (pair.progress.timeOutIfExpired()) {
                    if (preserveTimedOutV1Pair(pair, "Timed out waiting for "
                            + pair.task.profession().displayName() + " profession acquisition after "
                            + pair.mismatchRecovery.recoveryAttempts()
                            + " mismatch recoveries; pair preserved for inspection.")) {
                        iterator.remove();
                        changed = true;
                    }
                }
            }
            return changed;
        }

        private void recoverPendingV1Mismatch(
                PendingV1Pair pair,
                VillagerEntity pendingVillager,
                VillagerProfession currentProfession,
                BlockPos claimedJobSite,
                boolean wrongProfession
        ) {
            boolean hadJobSite = pendingVillager.getBrain().hasMemoryModule(MemoryModuleType.JOB_SITE);
            boolean hadPotentialJobSite = pendingVillager.getBrain()
                    .hasMemoryModule(MemoryModuleType.POTENTIAL_JOB_SITE);
            if (hadJobSite) {
                pendingVillager.releaseTicketFor(MemoryModuleType.JOB_SITE);
                pendingVillager.getBrain().forget(MemoryModuleType.JOB_SITE);
            }
            if (hadPotentialJobSite) {
                pendingVillager.releaseTicketFor(MemoryModuleType.POTENTIAL_JOB_SITE);
                pendingVillager.getBrain().forget(MemoryModuleType.POTENTIAL_JOB_SITE);
            }

            boolean professionReset = DeveloperV1PendingMismatchRecovery.canResetTemporaryProfession(
                    wrongProfession,
                    pendingVillager.getVillagerData().getLevel(),
                    pendingVillager.getExperience());
            if (professionReset) {
                pendingVillager.setVillagerData(
                        pendingVillager.getVillagerData().withProfession(VillagerProfession.NONE));
            }
            pendingVillager.setVelocity(Vec3d.ZERO);
            pendingVillager.getNavigation().stop();
            if (pendingVillager.squaredDistanceTo(Vec3d.ofCenter(pair.spawnPos)) > 9.0D) {
                pendingVillager.refreshPositionAndAngles(
                        pair.spawnPos.getX() + 0.5D,
                        pair.spawnPos.getY(),
                        pair.spawnPos.getZ() + 0.5D,
                        pendingVillager.getYaw(),
                        pendingVillager.getPitch());
            }

            LOGGER.debug("V1 pending mismatch recovered task={} expectedProfession={} expectedJobPos={} currentProfession={} claimedJobSite={} attempts={} wrongPoiCleared={} potentialPoiCleared={} professionReset={} retried=true",
                    pair.task.index() + 1,
                    pair.expectedProfession,
                    pair.jobPos.toShortString(),
                    currentProfession,
                    claimedJobSite == null ? "none" : claimedJobSite.toShortString(),
                    pair.mismatchRecovery.recoveryAttempts(),
                    hadJobSite,
                    hadPotentialJobSite,
                    professionReset);
        }

        private void restrainPendingV1Villager(PendingV1Pair pair, VillagerEntity pendingVillager) {
            pendingVillager.setVelocity(Vec3d.ZERO);
            if (pendingVillager.squaredDistanceTo(Vec3d.ofCenter(pair.spawnPos)) > 9.0D) {
                pendingVillager.refreshPositionAndAngles(
                        pair.spawnPos.getX() + 0.5D,
                        pair.spawnPos.getY(),
                        pair.spawnPos.getZ() + 0.5D,
                        pendingVillager.getYaw(),
                        pendingVillager.getPitch());
                pendingVillager.getNavigation().stop();
            }
        }

        private boolean restoreAttachedV1Workstation(PendingV1Pair pair, VillagerEntity pendingVillager) {
            DeveloperV1JobSiteAssignments.AssignmentState state = v1JobSites.state(pair.task.index());
            BlockState currentState = world.getBlockState(pair.jobPos);
            boolean taskOwnsPosition = v1JobSites.isPending(pair.task.index(), pair.jobPos);
            if (!DeveloperV1JobSiteAssignments.canRestoreAttachedWorkstation(
                    state,
                    taskOwnsPosition,
                    isV1ReplaceableSpace(currentState))) {
                logPreservedV1Pair(
                        pair,
                        pendingVillager,
                        "workstation_restore_rejected",
                        false,
                        false);
                return false;
            }
            boolean restored = world.setBlockState(
                    pair.jobPos,
                    stableV1JobBlockState(pair.expectedJobBlock),
                    Block.NOTIFY_ALL);
            logPreservedV1Pair(
                    pair,
                    pendingVillager,
                    restored ? "workstation_restored" : "workstation_restore_failed",
                    restored,
                    restored);
            return restored;
        }

        private void failV1TaskBeforeSpawn(DeveloperV1BatchProgress.Task task, String message) {
            lastV1Failure = message;
            v1Batch.finish(task.index(), false);
        }

        private boolean failInvalidPendingV1Pair(PendingV1Pair pair, String reason) {
            VillagerEntity pendingVillager = world.getEntity(pair.villagerId) instanceof VillagerEntity found
                    ? found
                    : null;
            removeV1IsolationBarriers(pair.isolationBlocks);
            boolean preserved = v1JobSites.markUnresolved(pair.task.index());
            releasePendingV1Villager(pair, pendingVillager);
            logPreservedV1Pair(
                    pair,
                    pendingVillager,
                    reason,
                    world.getBlockState(pair.jobPos).isOf(pair.expectedJobBlock),
                    false);
            if (!preserved) {
                v1FatalFailure = "V1 attached pair could not transition to preserved unresolved state for task "
                        + (pair.task.index() + 1) + "; physical pair was left untouched.";
                return false;
            }
            lastV1Failure = reason;
            v1Batch.finish(pair.task.index(), false);
            return true;
        }

        private boolean preserveTimedOutV1Pair(PendingV1Pair pair, String message) {
            LOGGER.debug("V1 pending pair preserved at timeout task={} expectedProfession={} expectedJobPos={} attempts={} timeoutPreserved=true",
                    pair.task.index() + 1,
                    pair.expectedProfession,
                    pair.jobPos.toShortString(),
                    pair.mismatchRecovery.recoveryAttempts());
            return failInvalidPendingV1Pair(pair, "timeout: " + message);
        }

        private void releasePendingV1Villager(PendingV1Pair pair, VillagerEntity pendingVillager) {
            if (pendingVillager != null && pendingVillager.isAlive()) {
                pendingVillager.setAiDisabled(pair.originalAiDisabled);
                pendingVillager.getNavigation().stop();
            }
        }

        private void rollbackPendingV1JobSite(int taskIndex, BlockPos jobPos, Block expectedJobBlock) {
            DeveloperV1JobSiteAssignments.AssignmentState state = v1JobSites.state(taskIndex);
            DeveloperV1JobSiteAssignments.RollbackDecision decision =
                    v1JobSites.rollbackDecision(taskIndex, jobPos);
            DeveloperV1BatchProgress.Task task = taskIndex >= 0 && taskIndex < v1Batch.tasks().size()
                    ? v1Batch.tasks().get(taskIndex)
                    : null;
            LOGGER.debug("V1 workstation rollback requested task={} profession={} jobPos={} state={} decision={}",
                    taskIndex + 1,
                    task == null ? "unknown" : task.profession().displayName(),
                    jobPos == null ? "none" : jobPos.toShortString(),
                    state == null ? "missing" : state,
                    decision);
            if (decision != DeveloperV1JobSiteAssignments.RollbackDecision.ELIGIBLE) {
                LOGGER.debug("V1 workstation rollback skipped task={} profession={} jobPos={} state={} reason={}",
                        taskIndex + 1,
                        task == null ? "unknown" : task.profession().displayName(),
                        jobPos == null ? "none" : jobPos.toShortString(),
                        state == null ? "missing" : state,
                        decision);
                return;
            }
            if (!world.getBlockState(jobPos).isOf(expectedJobBlock)) {
                LOGGER.debug("V1 workstation rollback skipped task={} profession={} jobPos={} state={} reason=block_mismatch",
                        taskIndex + 1,
                        task == null ? "unknown" : task.profession().displayName(),
                        jobPos.toShortString(),
                        state);
                return;
            }
            if (world.removeBlock(jobPos, false)) {
                LOGGER.debug("V1 workstation removed task={} profession={} jobPos={} state={}",
                        taskIndex + 1,
                        task == null ? "unknown" : task.profession().displayName(),
                        jobPos.toShortString(),
                        state);
            }
        }

        private void logV1WorkstationLifecycle(
                DeveloperV1BatchProgress.Task task,
                BlockPos jobPos,
                String event,
                String detail
        ) {
            DeveloperV1JobSiteAssignments.AssignmentState state = v1JobSites.state(task.index());
            LOGGER.debug("V1 workstation lifecycle event={} task={} profession={} jobPos={} state={} detail={}",
                    event,
                    task.index() + 1,
                    task.profession().displayName(),
                    jobPos.toShortString(),
                    state == null ? "missing" : state,
                    detail);
        }

        private void logPreservedV1Pair(
                PendingV1Pair pair,
                VillagerEntity pendingVillager,
                String reason,
                boolean workstationPreserved,
                boolean workstationRestored
        ) {
            DeveloperV1JobSiteAssignments.AssignmentState state = v1JobSites.state(pair.task.index());
            boolean villagerPreserved = pendingVillager != null && pendingVillager.isAlive();
            LOGGER.debug("V1 attached pair preserved task={} profession={} assignmentState={} workstationPosition={} villagerUuid={} reason={} workstationPreserved={} workstationRestored={} villagerPreserved={}",
                    pair.task.index() + 1,
                    pair.task.profession().displayName(),
                    state == null ? "missing" : state,
                    pair.jobPos.toShortString(),
                    pair.villagerId,
                    reason,
                    workstationPreserved,
                    workstationRestored,
                    villagerPreserved);
        }

        private Set<Long> occupiedV1IsolationColumns() {
            Set<Long> occupied = new HashSet<>();
            for (BlockPos reserved : v1JobSites.reservedPositions()) {
                occupied.add(v1ColumnKey(reserved));
            }
            for (PendingV1Pair pair : pendingV1Pairs.values()) {
                occupied.add(v1ColumnKey(pair.spawnPos));
                occupied.add(v1ColumnKey(pair.jobPos));
                for (BlockPos barrier : pair.isolationBlocks) {
                    occupied.add(v1ColumnKey(barrier));
                }
            }
            return Set.copyOf(occupied);
        }

        private V1IsolationPlan placeV1IsolationBarriers(V1IsolationPlan isolation) {
            Set<BlockPos> protectedPositions = protectedV1TaskPositions();
            if (!DeveloperV1IsolationSafety.avoidsProtectedPositions(
                    isolation.barrierPositions(),
                    isolation.clearPositions(),
                    protectedPositions)) {
                LOGGER.debug("V1 isolation placement rejected because temporary positions overlap a reserved task position");
                return null;
            }
            for (BlockPos clearPos : isolation.clearPositions()) {
                BlockState state = world.getBlockState(clearPos);
                if (state.isAir()) {
                    continue;
                }
                if (!isV1ReplaceableSpace(state)) {
                    return null;
                }
                world.removeBlock(clearPos, false);
            }
            Set<BlockPos> placed = new HashSet<>();
            for (BlockPos barrierPos : isolation.barrierPositions()) {
                if (!isV1ReplaceableSpace(world.getBlockState(barrierPos))) {
                    removeV1IsolationBarriers(placed);
                    return null;
                }
                if (!world.setBlockState(barrierPos, Blocks.BARRIER.getDefaultState(), Block.NOTIFY_ALL)) {
                    removeV1IsolationBarriers(placed);
                    return null;
                }
                placed.add(barrierPos);
            }
            return new V1IsolationPlan(
                    isolation.spawnPos(),
                    Set.copyOf(placed),
                    Set.of(),
                    isolation.terrainAdjustmentCost());
        }

        private boolean areV1IsolationBarriersIntact(Set<BlockPos> barrierPositions) {
            return barrierPositions.stream().allMatch(pos -> world.getBlockState(pos).isOf(Blocks.BARRIER));
        }

        private void removeV1IsolationBarriers(Set<BlockPos> barrierPositions) {
            Set<BlockPos> protectedPositions = protectedV1TaskPositions();
            for (BlockPos barrierPos : barrierPositions) {
                if (!DeveloperV1IsolationSafety.canRemoveTemporaryPosition(barrierPos, protectedPositions)) {
                    LOGGER.debug("V1 isolation cleanup skipped protected task position {}", barrierPos.toShortString());
                    continue;
                }
                if (world.getBlockState(barrierPos).isOf(Blocks.BARRIER)) {
                    world.removeBlock(barrierPos, false);
                }
            }
        }

        private Set<BlockPos> protectedV1TaskPositions() {
            Set<BlockPos> protectedPositions = new HashSet<>(v1JobSites.reservedPositions());
            pendingV1Pairs.values().forEach(pair -> protectedPositions.add(pair.spawnPos));
            return Set.copyOf(protectedPositions);
        }

        private String validateCompletedV1JobSites() {
            for (DeveloperV1JobSiteAssignments.Assignment<BlockPos> assignment : v1JobSites.completedAssignments()) {
                DeveloperProfession profession = assignment.task().profession();
                VillagerProfession vanillaProfession = resolveV1Profession(profession);
                Block expectedBlock = resolveV1JobBlock(profession).orElse(null);
                if (vanillaProfession == null
                        || expectedBlock == null
                        || !world.getBlockState(assignment.position()).isOf(expectedBlock)) {
                    return "Completed V1 job site for task " + (assignment.task().index() + 1)
                            + " (" + profession.displayName() + ") no longer contains its expected workstation.";
                }
                if (!(world.getEntity(assignment.villagerId()) instanceof VillagerEntity owner)
                        || !owner.isAlive()
                        || owner.getVillagerData().getProfession() != vanillaProfession) {
                    return "Completed V1 pairing for task " + (assignment.task().index() + 1)
                            + " (" + profession.displayName() + ") was lost before the batch advanced.";
                }
            }
            return null;
        }

        private void sendV1Progress(MinecraftServer server) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                return;
            }
            int progress = 5 + (int) Math.floor(90.0D * v1Batch.processed() / Math.max(1, v1Batch.total()));
            sendStatus(player,
                    "Creating V1 villagers: " + v1Batch.successful() + " / " + v1Batch.total()
                            + " complete, " + v1Batch.pending() + " pending, " + v1Batch.failed() + " failed.",
                    progress,
                    false,
                    false);
        }

        private void sendV1TerminalStatus(MinecraftServer server) {
            if (terminalStatusSent) {
                return;
            }
            terminalStatusSent = true;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                return;
            }
            if (v1FatalFailure != null) {
                sendStatus(player, v1FatalFailure, 0, true, false);
            } else if (v1JobSites.completedCount() != v1Batch.successful()) {
                sendStatus(player, "V1 batch ownership verification failed.", 100, true, false);
            } else if (v1Batch.failed() == 0) {
                sendStatus(player, "Created " + v1Batch.successful() + " V1 villagers.", 100, true, true);
            } else {
                sendStatus(player,
                        "Created " + v1Batch.successful() + " of " + v1Batch.total()
                                + " V1 villagers; " + v1Batch.failed() + " failed"
                                + (v1JobSites.unresolvedCount() > 0
                                ? "; " + v1JobSites.unresolvedCount() + " unresolved pair(s) preserved for inspection"
                                : "")
                                + ". Last failure: " + lastV1Failure,
                        100,
                        true,
                        false);
            }
        }

        private DeveloperSetupWorkflow.Observation prepare() {
            setupOrigin = findSafeSurface(world, requestedOrigin, 0, 6);
            if (setupOrigin == null) {
                workflow.fail("No safe open setup location found near the player.");
                return DeveloperSetupWorkflow.Observation.none();
            }

            if (request.setupType() != DeveloperSetupType.PLAIN_VILLAGER) {
                tablePos = setupOrigin;
                if (!world.setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState(), Block.NOTIFY_ALL)) {
                    workflow.fail("Could not place the Lumberjack crafting table.");
                    return DeveloperSetupWorkflow.Observation.none();
                }
                BlockPos spawnPos = findSpawnBesideTable(world, tablePos);
                if (spawnPos == null) {
                    world.removeBlock(tablePos, false);
                    tablePos = null;
                    workflow.fail("No safe villager spawn position beside the crafting table.");
                    return DeveloperSetupWorkflow.Observation.none();
                }
                villager = spawnVillager(world, spawnPos);
            } else {
                villager = spawnVillager(world, setupOrigin);
            }

            if (villager == null) {
                rollbackUnclaimedTable();
                workflow.fail("Could not spawn the test villager.");
                return DeveloperSetupWorkflow.Observation.none();
            }
            villager.setPersistent();
            if (request.setupType() != DeveloperSetupType.PLAIN_VILLAGER) {
                originalAiDisabled = villager.isAiDisabled();
                villager.setAiDisabled(true);
                workflow.markSubjectRestrained();
            }
            return new DeveloperSetupWorkflow.Observation(true, false, false, false, false, false, false);
        }

        private DeveloperSetupWorkflow.Observation waitForProfession() {
            if (lumberjack == null && workflow.ticksInStage() >= CONVERSION_DELAY_TICKS) {
                lumberjack = UnemployedLumberjackConversionHook
                        .tryConvertForDeveloperSetup(world, villager, tablePos)
                        .orElse(null);
                if (lumberjack != null) {
                    workflow.markSubjectReleased();
                    villager = null;
                }
            }
            boolean ready = lumberjack != null && lumberjack.isAlive() && tablePos.equals(lumberjack.getPairedCraftingTablePos());
            return new DeveloperSetupWorkflow.Observation(false, ready, false, false, false, false, false);
        }

        private DeveloperSetupWorkflow.Observation placeV2Blocks() {
            if (lumberjack == null || !lumberjack.isAlive()) {
                workflow.fail("The Lumberjack was removed before V2 pairing.");
                return DeveloperSetupWorkflow.Observation.none();
            }
            if (lumberjack.getPairedChestPos() == null) {
                if (!chestSupplied) {
                    lumberjack.getGatheredStackBuffer().add(new ItemStack(Items.CHEST));
                    chestSupplied = true;
                }
                LumberjackGuardCraftingGoal.tryPlaceAndBindChestForRecovery(world, lumberjack, null);
            }
            createdChestPos = lumberjack.getPairedChestPos();
            return new DeveloperSetupWorkflow.Observation(false, false, createdChestPos != null, false, false, false, false);
        }

        private DeveloperSetupWorkflow.Observation waitForPairing() {
            boolean paired = lumberjack != null
                    && lumberjack.isAlive()
                    && createdChestPos != null
                    && createdChestPos.equals(lumberjack.getPairedChestPos())
                    && world.getBlockState(createdChestPos).isOf(Blocks.CHEST);
            return new DeveloperSetupWorkflow.Observation(false, false, false, paired, false, false, false);
        }

        private DeveloperSetupWorkflow.Observation placeLumberjackInfrastructure() {
            if (!isPairingIntact()) {
                workflow.fail("Lumberjack pairing was lost before optional infrastructure placement.");
                return DeveloperSetupWorkflow.Observation.none();
            }
            if (createdFurnacePos == null) {
                InfrastructureSite site = findFurnaceSite(world, tablePos, createdChestPos);
                if (site == null) {
                    workflow.fail("No safe furnace and Guard Stand Modifier site was found in the Lumberjack's paired zone.");
                    return DeveloperSetupWorkflow.Observation.none();
                }
                if (!world.setBlockState(site.furnacePos(), Blocks.FURNACE.getDefaultState(), Block.NOTIFY_ALL)) {
                    workflow.fail("Could not place the Lumberjack test furnace.");
                    return DeveloperSetupWorkflow.Observation.none();
                }
                if (!world.setBlockState(site.modifierPos(), GuardVillagers.GUARD_STAND_MODIFIER.getDefaultState(), Block.NOTIFY_ALL)) {
                    world.removeBlock(site.furnacePos(), false);
                    workflow.fail("Could not place the Guard Stand Modifier beside the Lumberjack test furnace.");
                    return DeveloperSetupWorkflow.Observation.none();
                }
                createdFurnacePos = site.furnacePos();
                createdModifierPos = site.modifierPos();
            }
            boolean ready = world.getBlockState(createdFurnacePos).isOf(Blocks.FURNACE)
                    && world.getBlockState(createdModifierPos).isOf(GuardVillagers.GUARD_STAND_MODIFIER);
            return new DeveloperSetupWorkflow.Observation(false, false, false, false, ready, false, false);
        }

        private DeveloperSetupWorkflow.Observation populateLumberjackInventory() {
            if (!isPairingIntact()) {
                workflow.fail("Lumberjack pairing was lost before chest inventory setup.");
                return DeveloperSetupWorkflow.Observation.none();
            }
            Inventory chestInventory = LumberjackGuardCraftingGoal.resolveChestInventoryForGuard(world, lumberjack);
            if (chestInventory == null) {
                workflow.fail("Could not access the paired Lumberjack chest for inventory setup.");
                return DeveloperSetupWorkflow.Observation.none();
            }
            Map<LumberjackInventoryPreset.Item, Integer> plan = LumberjackInventoryPreset.createPlan(
                    request.inventoryPreset(),
                    request.createFurnaceSetup(),
                    request.createShepherdSupply()
            );
            for (Map.Entry<LumberjackInventoryPreset.Item, Integer> entry : plan.entrySet()) {
                ItemStack remainder = insertIntoInventory(
                        chestInventory,
                        new ItemStack(resolvePresetItem(entry.getKey()), entry.getValue())
                );
                if (!remainder.isEmpty()) {
                    workflow.fail("The paired Lumberjack chest did not have enough room for the selected inventory preset.");
                    return DeveloperSetupWorkflow.Observation.none();
                }
            }
            chestInventory.markDirty();
            return new DeveloperSetupWorkflow.Observation(false, false, false, false, false, true, false);
        }

        private DeveloperSetupWorkflow.Observation generateTrees() {
            if (generatedTrees >= request.treeCount()) {
                return new DeveloperSetupWorkflow.Observation(false, false, false, false, false, false, true);
            }
            DeveloperTreeGenerator.Result result = DeveloperTreeGenerator.generateNext(world, setupOrigin, attemptedTreeSites);
            if (result == DeveloperTreeGenerator.Result.GENERATED) {
                generatedTrees++;
                return new DeveloperSetupWorkflow.Observation(
                        false, false, false, false, false, false, generatedTrees >= request.treeCount());
            }
            String reason = switch (result) {
                case NO_USABLE_CANDIDATE -> "no additional usable dirt-like candidate positions were found.";
                case PREFLIGHT_REJECTED -> "remaining candidate sites were rejected by solid trunk or canopy obstructions.";
                case VANILLA_GROWTH_FAILED -> "vanilla oak growth failed at all remaining valid candidate sites.";
                case GENERATED -> throw new IllegalStateException("Generated tree handled before failure reporting");
            };
            workflow.fail("Generated " + generatedTrees + " of " + request.treeCount() + " mature trees; " + reason);
            return DeveloperSetupWorkflow.Observation.none();
        }

        private void reportStage(MinecraftServer server) {
            if (workflow.stage() == reportedStage || workflow.stage().isTerminal()) {
                return;
            }
            reportedStage = workflow.stage();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                return;
            }
            switch (reportedStage) {
                case WAIT_FOR_PROFESSION -> sendStatus(player, "Crafting table placed; waiting for Lumberjack conversion...", 35, false, false);
                case PLACE_V2_BLOCKS -> sendStatus(player, "Lumberjack confirmed; creating V2 chest...", 60, false, false);
                case WAIT_FOR_PAIRING -> sendStatus(player, "Chest placed; verifying Lumberjack pairing...", 75, false, false);
                case PLACE_LUMBERJACK_INFRASTRUCTURE -> sendStatus(player, "Pairing confirmed; placing Lumberjack test infrastructure...", 80, false, false);
                case POPULATE_LUMBERJACK_INVENTORY -> sendStatus(player, "Loading the paired chest with the selected test inventory...", 85, false, false);
                case GENERATE_TREES -> sendStatus(player, "Generating safe mature trees...", 85, false, false);
                default -> {
                }
            }
        }

        private void sendTerminalStatus(MinecraftServer server) {
            if (terminalStatusSent) {
                return;
            }
            terminalStatusSent = true;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                return;
            }
            if (workflow.stage() == DeveloperSetupStage.COMPLETE) {
                String subject = request.setupType() == DeveloperSetupType.PLAIN_VILLAGER ? "villager" : "Lumberjack";
                sendStatus(player, "Created " + request.setupType().displayName() + " " + subject + " setup.", 100, true, true);
            } else {
                sendStatus(player, workflow.failureMessage(), 0, true, false);
            }
        }

        private void fail(String message) {
            if (v1Batch != null) {
                v1FatalFailure = message;
                cleanupPendingV1Pairs();
            } else {
                workflow.fail(message);
            }
        }

        private void cancel() {
            fail("Setup cancelled because the server is stopping.");
            cleanup();
        }

        private boolean isFinished() {
            if (v1Batch != null) {
                return (v1FatalFailure != null || v1Batch.isComplete()) && terminalStatusSent;
            }
            return workflow.stage().isTerminal() && terminalStatusSent;
        }

        private void cleanup() {
            if (v1Batch != null) {
                if (v1FatalFailure != null) {
                    cleanupPendingV1Pairs();
                }
                return;
            }
            if (workflow.subjectRestrained() && villager != null && villager.isAlive()) {
                villager.setAiDisabled(originalAiDisabled);
                villager.getNavigation().stop();
                workflow.markSubjectReleased();
            }
            if (workflow.stage() == DeveloperSetupStage.FAILED) {
                rollbackUnclaimedInfrastructure();
                rollbackUnclaimedTable();
                if (createdChestPos != null
                        && (lumberjack == null || !createdChestPos.equals(lumberjack.getPairedChestPos()))
                        && world.getBlockState(createdChestPos).isOf(Blocks.CHEST)) {
                    world.removeBlock(createdChestPos, false);
                }
            }
        }

        private void cleanupPendingV1Pairs() {
            for (PendingV1Pair pair : pendingV1Pairs.values()) {
                VillagerEntity pendingVillager = world.getEntity(pair.villagerId) instanceof VillagerEntity found
                        ? found
                        : null;
                boolean genuinelyPending = v1JobSites.isPending(pair.task.index(), pair.jobPos);
                removeV1IsolationBarriers(pair.isolationBlocks);
                if (genuinelyPending) {
                    boolean preserved = v1JobSites.markUnresolved(pair.task.index());
                    releasePendingV1Villager(pair, pendingVillager);
                    logPreservedV1Pair(
                            pair,
                            pendingVillager,
                            preserved ? "fatal_session_cleanup" : "fatal_session_cleanup_state_transition_rejected",
                            world.getBlockState(pair.jobPos).isOf(pair.expectedJobBlock),
                            false);
                } else {
                    releasePendingV1Villager(pair, pendingVillager);
                    logPreservedV1Pair(
                            pair,
                            pendingVillager,
                            "fatal_session_cleanup_assignment_not_pending",
                            world.getBlockState(pair.jobPos).isOf(pair.expectedJobBlock),
                            false);
                }
            }
            pendingV1Pairs.clear();
        }

        private void rollbackUnclaimedTable() {
            if (tablePos != null
                    && lumberjack == null
                    && world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE)) {
                world.removeBlock(tablePos, false);
                tablePos = null;
            }
        }

        private boolean isPairingIntact() {
            return lumberjack != null
                    && lumberjack.isAlive()
                    && createdChestPos != null
                    && createdChestPos.equals(lumberjack.getPairedChestPos())
                    && world.getBlockState(createdChestPos).isOf(Blocks.CHEST);
        }

        private void rollbackUnclaimedInfrastructure() {
            boolean claimed = lumberjack != null
                    && createdFurnacePos != null
                    && createdFurnacePos.equals(lumberjack.getPairedFurnaceModifierPos());
            if (claimed) {
                return;
            }
            if (createdModifierPos != null
                    && world.getBlockState(createdModifierPos).isOf(GuardVillagers.GUARD_STAND_MODIFIER)) {
                world.removeBlock(createdModifierPos, false);
            }
            if (createdFurnacePos != null && world.getBlockState(createdFurnacePos).isOf(Blocks.FURNACE)) {
                world.removeBlock(createdFurnacePos, false);
            }
        }

        private static final class PendingV1Pair {
            private final DeveloperV1BatchProgress.Task task;
            private final UUID villagerId;
            private final VillagerProfession expectedProfession;
            private final Block expectedJobBlock;
            private final BlockPos jobPos;
            private final BlockPos spawnPos;
            private final Set<BlockPos> isolationBlocks;
            private final boolean originalAiDisabled;
            private final DeveloperV1PendingPairProgress progress;
            private final DeveloperV1PendingMismatchRecovery mismatchRecovery;
            private boolean mismatchLimitLogged;

            private PendingV1Pair(
                    DeveloperV1BatchProgress.Task task,
                    UUID villagerId,
                    VillagerProfession expectedProfession,
                    Block expectedJobBlock,
                    BlockPos jobPos,
                    BlockPos spawnPos,
                    Set<BlockPos> isolationBlocks,
                    boolean originalAiDisabled
            ) {
                this.task = task;
                this.villagerId = villagerId;
                this.expectedProfession = expectedProfession;
                this.expectedJobBlock = expectedJobBlock;
                this.jobPos = jobPos;
                this.spawnPos = spawnPos;
                this.isolationBlocks = isolationBlocks;
                this.originalAiDisabled = originalAiDisabled;
                this.progress = new DeveloperV1PendingPairProgress(
                        V1_INITIAL_RESTRAINT_TICKS,
                        V1_PAIR_TIMEOUT_TICKS);
                this.mismatchRecovery = new DeveloperV1PendingMismatchRecovery();
            }
        }
    }

    static java.util.Optional<Block> resolveV1JobBlock(DeveloperProfession profession) {
        if (profession.supportsVanillaV1()) {
            VillagerProfession vanillaProfession = resolveV1Profession(profession);
            if (vanillaProfession == null) {
                return java.util.Optional.empty();
            }
            return ProfessionDefinitions.get(vanillaProfession)
                    .flatMap(definition -> definition.expectedJobBlocks().stream().findFirst());
        }
        Identifier blockId = profession.jobBlockId().map(Identifier::tryParse).orElse(null);
        if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Registries.BLOCK.get(blockId));
    }

    static VillagerProfession resolveV1Profession(DeveloperProfession profession) {
        return switch (profession) {
            case FARMER -> VillagerProfession.FARMER;
            case FISHERMAN -> VillagerProfession.FISHERMAN;
            case FLETCHER -> VillagerProfession.FLETCHER;
            case SHEPHERD -> VillagerProfession.SHEPHERD;
            case LIBRARIAN -> VillagerProfession.LIBRARIAN;
            case CARTOGRAPHER -> VillagerProfession.CARTOGRAPHER;
            case CLERIC -> VillagerProfession.CLERIC;
            case ARMORER -> VillagerProfession.ARMORER;
            case WEAPONSMITH -> VillagerProfession.WEAPONSMITH;
            case TOOLSMITH -> VillagerProfession.TOOLSMITH;
            case BUTCHER -> VillagerProfession.BUTCHER;
            case LEATHERWORKER -> VillagerProfession.LEATHERWORKER;
            case MASON -> VillagerProfession.MASON;
            case OCEANOGRAPHER, NETHERIAN, WOODWORKER, ENDERIAN, ENGINEER, FLORIST, HUNTER, MINER ->
                    resolveRegisteredV1Profession(profession);
            case LUMBERJACK -> null;
        };
    }

    private static VillagerProfession resolveRegisteredV1Profession(DeveloperProfession profession) {
        Identifier professionId = profession.professionId().map(Identifier::tryParse).orElse(null);
        if (professionId == null || !Registries.VILLAGER_PROFESSION.containsId(professionId)) {
            return null;
        }
        return Registries.VILLAGER_PROFESSION.get(professionId);
    }

    private static BlockState stableV1JobBlockState(Block block) {
        BlockState state = block.getDefaultState();
        if (block == Blocks.GRINDSTONE) {
            return state.with(Properties.BLOCK_FACE, BlockFace.FLOOR);
        }
        return state;
    }

    private static V1SitePlan findV1SitePlan(
            ServerWorld world,
            BlockPos anchor,
            int preferredY,
            Set<BlockPos> existingSites,
            Set<Long> occupiedIsolationColumns
    ) {
        java.util.List<DeveloperV1TerrainPlanner.Site> reservedSites = existingSites.stream()
                .map(pos -> new DeveloperV1TerrainPlanner.Site(pos.getX(), pos.getY(), pos.getZ()))
                .toList();
        DeveloperV1TerrainPlanner.SearchResult<V1IsolationPlan> result = DeveloperV1TerrainPlanner.select(
                anchor.getX(),
                anchor.getZ(),
                preferredY,
                reservedSites,
                (x, z) -> {
                    if (occupiedIsolationColumns.contains(v1ColumnKey(x, z))) {
                        return java.util.Optional.empty();
                    }
                    BlockPos candidate = v1SurfacePosition(world, x, z);
                    if (!isSafeV1PlacementPosition(world, candidate)) {
                        return java.util.Optional.empty();
                    }
                    V1IsolationPlan isolation = findV1IsolationPlan(
                            world, candidate, occupiedIsolationColumns);
                    if (isolation == null) {
                        return java.util.Optional.empty();
                    }
                    return java.util.Optional.of(new DeveloperV1TerrainPlanner.Candidate<>(
                            new DeveloperV1TerrainPlanner.Site(x, candidate.getY(), z),
                            isolation.terrainAdjustmentCost(),
                            isolation));
                });
        return result.selection()
                .map(selection -> new V1SitePlan(
                        new BlockPos(selection.site().x(), selection.site().y(), selection.site().z()),
                        selection.payload(),
                        result.evaluatedCandidates()))
                .orElse(null);
    }

    private static V1IsolationPlan findV1IsolationPlan(
            ServerWorld world,
            BlockPos jobPos,
            Set<Long> occupiedColumns
    ) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos horizontalSpawn = jobPos.offset(direction);
            BlockPos spawnPos = v1SurfacePosition(
                    world, horizontalSpawn.getX(), horizontalSpawn.getZ());
            if (Math.abs(spawnPos.getY() - jobPos.getY()) > 1
                    || occupiedColumns.contains(v1ColumnKey(spawnPos))
                    || !isSafeV1PlacementPosition(world, spawnPos)) {
                continue;
            }
            Set<BlockPos> barrierPositions = new HashSet<>();
            Set<BlockPos> clearPositions = new HashSet<>();
            clearPositions.add(spawnPos.toImmutable());
            clearPositions.add(spawnPos.up().toImmutable());
            boolean valid = true;
            for (int dx = -V1_ISOLATION_RADIUS; dx <= V1_ISOLATION_RADIUS && valid; dx++) {
                for (int dz = -V1_ISOLATION_RADIUS; dz <= V1_ISOLATION_RADIUS; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != V1_ISOLATION_RADIUS) {
                        continue;
                    }
                    int wallX = spawnPos.getX() + dx;
                    int wallZ = spawnPos.getZ() + dz;
                    if (wallX == jobPos.getX() && wallZ == jobPos.getZ()) {
                        BlockPos aboveJobSite = jobPos.up();
                        if (!world.getWorldBorder().contains(aboveJobSite)
                                || !isV1ReplaceableSpace(world.getBlockState(aboveJobSite))) {
                            valid = false;
                            break;
                        }
                        barrierPositions.add(aboveJobSite.toImmutable());
                        continue;
                    }
                    BlockPos wallBase = v1SurfacePosition(world, wallX, wallZ);
                    if (Math.abs(wallBase.getY() - spawnPos.getY()) > 1
                            || occupiedColumns.contains(v1ColumnKey(wallBase))
                            || !isSafeV1PlacementPosition(world, wallBase)) {
                        valid = false;
                        break;
                    }
                    barrierPositions.add(wallBase.toImmutable());
                    barrierPositions.add(wallBase.up().toImmutable());
                }
            }
            if (valid) {
                Set<BlockPos> adjustedPositions = new HashSet<>(barrierPositions);
                adjustedPositions.add(jobPos);
                adjustedPositions.addAll(clearPositions);
                int terrainAdjustmentCost = (int) adjustedPositions.stream()
                        .filter(pos -> !world.getBlockState(pos).isAir())
                        .count();
                return new V1IsolationPlan(
                        spawnPos.toImmutable(),
                        Set.copyOf(barrierPositions),
                        Set.copyOf(clearPositions),
                        terrainAdjustmentCost);
            }
        }
        return null;
    }

    private static BlockPos v1SurfacePosition(ServerWorld world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static boolean isSafeV1PlacementPosition(ServerWorld world, BlockPos pos) {
        BlockPos supportPos = pos.down();
        BlockState support = world.getBlockState(supportPos);
        return world.getWorldBorder().contains(pos)
                && world.getWorldBorder().contains(pos.up())
                && isV1ReplaceableSpace(world.getBlockState(pos))
                && isV1ReplaceableSpace(world.getBlockState(pos.up()))
                && support.isSolidBlock(world, supportPos)
                && !support.isIn(BlockTags.LOGS)
                && !support.isIn(BlockTags.LEAVES);
    }

    private static boolean isV1ReplaceableSpace(BlockState state) {
        return (state.isAir() || state.isReplaceable()) && state.getFluidState().isEmpty();
    }

    private static long v1ColumnKey(BlockPos pos) {
        return v1ColumnKey(pos.getX(), pos.getZ());
    }

    private static long v1ColumnKey(int x, int z) {
        return new BlockPos(x, 0, z).asLong();
    }

    private static InfrastructureSite findFurnaceSite(ServerWorld world, BlockPos tablePos, BlockPos chestPos) {
        for (int radius = 2; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos furnacePos = tablePos.add(dx, 0, dz);
                    BlockPos modifierPos = furnacePos.up();
                    BlockPos floorPos = furnacePos.down();
                    if (!furnacePos.isWithinDistance(tablePos, 4.0D)
                            || !furnacePos.isWithinDistance(chestPos, 4.0D)
                            || !world.getWorldBorder().contains(furnacePos)
                            || !world.getWorldBorder().contains(modifierPos)
                            || !world.getBlockState(furnacePos).isAir()
                            || !world.getBlockState(modifierPos).isAir()
                            || !world.getBlockState(floorPos).isSolidBlock(world, floorPos)
                            || !hasOpenHorizontalNeighbors(world, furnacePos, tablePos, chestPos)) {
                        continue;
                    }
                    return new InfrastructureSite(furnacePos.toImmutable(), modifierPos.toImmutable());
                }
            }
        }
        return null;
    }

    private static boolean hasOpenHorizontalNeighbors(
            ServerWorld world,
            BlockPos furnacePos,
            BlockPos tablePos,
            BlockPos chestPos
    ) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos adjacent = furnacePos.offset(direction);
            if (!adjacent.equals(tablePos)
                    && !adjacent.equals(chestPos)
                    && !world.getBlockState(adjacent).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static Item resolvePresetItem(LumberjackInventoryPreset.Item item) {
        return switch (item) {
            case OAK_LOG -> Items.OAK_LOG;
            case OAK_PLANKS -> Items.OAK_PLANKS;
            case STICK -> Items.STICK;
            case OAK_FENCE -> Items.OAK_FENCE;
            case OAK_FENCE_GATE -> Items.OAK_FENCE_GATE;
        };
    }

    private static ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                int transfer = Math.min(remaining.getCount(), remaining.getMaxCount());
                inventory.setStack(slot, remaining.copyWithCount(transfer));
                remaining.decrement(transfer);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
                if (transfer > 0) {
                    existing.increment(transfer);
                    inventory.setStack(slot, existing);
                    remaining.decrement(transfer);
                }
            }
        }
        return remaining;
    }

    private record InfrastructureSite(BlockPos furnacePos, BlockPos modifierPos) {
    }

    private static VillagerEntity spawnVillager(ServerWorld world, BlockPos pos) {
        VillagerEntity villager = EntityType.VILLAGER.create(world);
        if (villager == null) {
            return null;
        }
        villager.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, world.random.nextFloat() * 360.0F, 0.0F);
        villager.initialize((ServerWorldAccess) world, world.getLocalDifficulty(pos), SpawnReason.COMMAND, null);
        return world.spawnEntity(villager) ? villager : null;
    }

    private static BlockPos findSafeSurface(ServerWorld world, BlockPos anchor, int minRadius, int maxRadius) {
        for (int radius = minRadius; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = anchor.getX() + dx;
                    int z = anchor.getZ() + dz;
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isSafeOpenPosition(world, pos)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos findSpawnBesideTable(ServerWorld world, BlockPos tablePos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = tablePos.offset(direction, 2);
            if (isSafeOpenPosition(world, candidate) && candidate.isWithinDistance(tablePos, 3.0D)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isSafeOpenPosition(ServerWorld world, BlockPos pos) {
        return world.getWorldBorder().contains(pos)
                && world.getBlockState(pos).isAir()
                && world.getBlockState(pos.up()).isAir()
                && world.getBlockState(pos.down()).isSolidBlock(world, pos.down());
    }

    private record V1SitePlan(BlockPos jobPos, V1IsolationPlan isolation, int evaluatedCandidates) {
    }

    private record V1IsolationPlan(
            BlockPos spawnPos,
            Set<BlockPos> barrierPositions,
            Set<BlockPos> clearPositions,
            int terrainAdjustmentCost
    ) {
    }
}

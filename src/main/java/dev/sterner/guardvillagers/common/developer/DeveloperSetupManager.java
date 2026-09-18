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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
            BlockPos candidateJobPos = findV1JobSite(world, gridAnchor, v1JobSites.reservedPositions());
            if (candidateJobPos == null) {
                failV1TaskBeforeSpawn(task,
                        "No safe job-site location was found for " + requestedProfession.displayName() + ".");
                return;
            }
            if (!v1JobSites.reserve(task, candidateJobPos)) {
                v1FatalFailure = "V1 placement ownership collision for task " + (task.index() + 1) + ".";
                return;
            }
            if (!world.setBlockState(candidateJobPos, stableV1JobBlockState(expectedJobBlock), Block.NOTIFY_ALL)) {
                rollbackPendingV1JobSite(task.index(), candidateJobPos, expectedJobBlock);
                v1JobSites.rollback(task.index());
                failV1TaskBeforeSpawn(task, "Could not place the " + requestedProfession.displayName() + " job site.");
                return;
            }

            V1IsolationPlan isolation = placeV1IsolationBarriers(candidateJobPos);
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
                pendingVillager.discard();
                removeV1IsolationBarriers(isolation.barrierPositions());
                rollbackPendingV1JobSite(task.index(), candidateJobPos, expectedJobBlock);
                v1JobSites.rollback(task.index());
                v1FatalFailure = "V1 villager ownership collision for task " + (task.index() + 1) + ".";
                return;
            }
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
                pair.elapsedTicks++;
                if (!(world.getEntity(pair.villagerId) instanceof VillagerEntity pendingVillager)
                        || !pendingVillager.isAlive()) {
                    failInvalidPendingV1Pair(pair,
                            pair.task.profession().displayName() + " villager was removed before pairing.");
                    iterator.remove();
                    changed = true;
                    continue;
                }

                restrainPendingV1Villager(pair, pendingVillager);
                if (!areV1IsolationBarriersIntact(pair.isolationBlocks)) {
                    failInvalidPendingV1Pair(pair,
                            pair.task.profession().displayName() + " isolation barrier was removed before pairing.");
                    iterator.remove();
                    changed = true;
                    continue;
                }
                if (!world.getBlockState(pair.jobPos).isOf(pair.expectedJobBlock)) {
                    failInvalidPendingV1Pair(pair,
                            pair.task.profession().displayName() + " workstation was removed before pairing.");
                    iterator.remove();
                    changed = true;
                    continue;
                }

                VillagerProfession acquired = pendingVillager.getVillagerData().getProfession();
                BlockPos claimedJobSite = pendingVillager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                        .map(globalPos -> globalPos.pos())
                        .orElse(null);
                if (acquired == pair.expectedProfession && pair.jobPos.equals(claimedJobSite)) {
                    if (!v1JobSites.complete(pair.task.index(), pair.villagerId, claimedJobSite)) {
                        v1FatalFailure = "V1 completed-pair ownership verification failed for task "
                                + (pair.task.index() + 1) + ".";
                        break;
                    }
                    removeV1IsolationBarriers(pair.isolationBlocks);
                    releasePendingV1Villager(pair, pendingVillager);
                    v1Batch.finish(pair.task.index(), true);
                    iterator.remove();
                    changed = true;
                    continue;
                }
                if (claimedJobSite != null && !pair.jobPos.equals(claimedJobSite)) {
                    failInvalidPendingV1Pair(pair, pair.task.profession().displayName()
                            + " villager claimed another workstation at " + claimedJobSite.toShortString() + ".");
                    iterator.remove();
                    changed = true;
                    continue;
                }
                if (acquired != VillagerProfession.NONE && acquired != pair.expectedProfession) {
                    failInvalidPendingV1Pair(pair, pair.task.profession().displayName()
                            + " villager claimed a different profession (" + acquired + ").");
                    iterator.remove();
                    changed = true;
                    continue;
                }
                if (pair.elapsedTicks >= V1_PAIR_TIMEOUT_TICKS) {
                    if (preserveTimedOutV1Pair(pair, "Timed out waiting for "
                            + pair.task.profession().displayName() + " profession acquisition; pair preserved for inspection.")) {
                        iterator.remove();
                        changed = true;
                    }
                }
            }
            return changed;
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

        private void failV1TaskBeforeSpawn(DeveloperV1BatchProgress.Task task, String message) {
            lastV1Failure = message;
            v1Batch.finish(task.index(), false);
        }

        private void failInvalidPendingV1Pair(PendingV1Pair pair, String message) {
            lastV1Failure = message;
            VillagerEntity pendingVillager = world.getEntity(pair.villagerId) instanceof VillagerEntity found
                    ? found
                    : null;
            removeV1IsolationBarriers(pair.isolationBlocks);
            if (pendingVillager != null && pendingVillager.isAlive()) {
                pendingVillager.discard();
            }
            rollbackPendingV1JobSite(pair.task.index(), pair.jobPos, pair.expectedJobBlock);
            v1JobSites.rollback(pair.task.index());
            v1Batch.finish(pair.task.index(), false);
        }

        private boolean preserveTimedOutV1Pair(PendingV1Pair pair, String message) {
            if (!v1JobSites.markUnresolved(pair.task.index())) {
                v1FatalFailure = "V1 unresolved-pair ownership verification failed for task "
                        + (pair.task.index() + 1) + ".";
                return false;
            }
            lastV1Failure = message;
            VillagerEntity pendingVillager = world.getEntity(pair.villagerId) instanceof VillagerEntity found
                    ? found
                    : null;
            removeV1IsolationBarriers(pair.isolationBlocks);
            releasePendingV1Villager(pair, pendingVillager);
            v1Batch.finish(pair.task.index(), false);
            return true;
        }

        private void releasePendingV1Villager(PendingV1Pair pair, VillagerEntity pendingVillager) {
            if (pendingVillager != null && pendingVillager.isAlive()) {
                pendingVillager.setAiDisabled(pair.originalAiDisabled);
                pendingVillager.getNavigation().stop();
            }
        }

        private void rollbackPendingV1JobSite(int taskIndex, BlockPos jobPos, Block expectedJobBlock) {
            if (!v1JobSites.isPending(taskIndex, jobPos)) {
                return;
            }
            if (world.getBlockState(jobPos).isOf(expectedJobBlock)
                    && !isJobSiteClaimedByAnyVillager(world, jobPos)) {
                world.removeBlock(jobPos, false);
            }
        }

        private V1IsolationPlan placeV1IsolationBarriers(BlockPos jobPos) {
            V1IsolationPlan isolation = findV1IsolationPlan(world, jobPos);
            if (isolation == null) {
                return null;
            }
            Set<BlockPos> placed = new HashSet<>();
            for (BlockPos barrierPos : isolation.barrierPositions()) {
                if (!world.setBlockState(barrierPos, Blocks.BARRIER.getDefaultState(), Block.NOTIFY_ALL)) {
                    removeV1IsolationBarriers(placed);
                    return null;
                }
                placed.add(barrierPos);
            }
            return new V1IsolationPlan(isolation.spawnPos(), Set.copyOf(placed));
        }

        private boolean areV1IsolationBarriersIntact(Set<BlockPos> barrierPositions) {
            return barrierPositions.stream().allMatch(pos -> world.getBlockState(pos).isOf(Blocks.BARRIER));
        }

        private void removeV1IsolationBarriers(Set<BlockPos> barrierPositions) {
            for (BlockPos barrierPos : barrierPositions) {
                if (world.getBlockState(barrierPos).isOf(Blocks.BARRIER)) {
                    world.removeBlock(barrierPos, false);
                }
            }
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
                        || owner.getVillagerData().getProfession() != vanillaProfession
                        || !owner.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                        .map(globalPos -> globalPos.pos().equals(assignment.position()))
                        .orElse(false)) {
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
                if (pendingVillager != null && pendingVillager.isAlive()) {
                    pendingVillager.discard();
                }
                removeV1IsolationBarriers(pair.isolationBlocks);
                rollbackPendingV1JobSite(pair.task.index(), pair.jobPos, pair.expectedJobBlock);
                v1JobSites.rollback(pair.task.index());
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
            private int elapsedTicks;

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
            }
        }
    }

    static java.util.Optional<Block> resolveV1JobBlock(DeveloperProfession profession) {
        VillagerProfession vanillaProfession = resolveV1Profession(profession);
        if (vanillaProfession == null) {
            return java.util.Optional.empty();
        }
        return ProfessionDefinitions.get(vanillaProfession)
                .flatMap(definition -> definition.expectedJobBlocks().stream().findFirst());
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
            case LUMBERJACK -> null;
        };
    }

    private static BlockState stableV1JobBlockState(Block block) {
        BlockState state = block.getDefaultState();
        if (block == Blocks.GRINDSTONE) {
            return state.with(Properties.BLOCK_FACE, BlockFace.FLOOR);
        }
        return state;
    }

    private static BlockPos findV1JobSite(ServerWorld world, BlockPos anchor, Set<BlockPos> existingSites) {
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = anchor.getX() + dx;
                    int z = anchor.getZ() + dz;
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (!isSafeOpenPosition(world, candidate)
                            || findV1IsolationPlan(world, candidate) == null
                            || isTooCloseToV1JobSite(candidate, existingSites)) {
                        continue;
                    }
                    return candidate.toImmutable();
                }
            }
        }
        return null;
    }

    private static boolean isTooCloseToV1JobSite(BlockPos candidate, Set<BlockPos> existingSites) {
        for (BlockPos existing : existingSites) {
            int dx = candidate.getX() - existing.getX();
            int dz = candidate.getZ() - existing.getZ();
            if (dx * dx + dz * dz < 9) {
                return true;
            }
        }
        return false;
    }

    private static V1IsolationPlan findV1IsolationPlan(ServerWorld world, BlockPos jobPos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos spawnPos = jobPos.offset(direction);
            if (!isSafeOpenPosition(world, spawnPos)) {
                continue;
            }
            Set<BlockPos> barrierPositions = new HashSet<>();
            boolean valid = true;
            for (int dx = -V1_ISOLATION_RADIUS; dx <= V1_ISOLATION_RADIUS && valid; dx++) {
                for (int dz = -V1_ISOLATION_RADIUS; dz <= V1_ISOLATION_RADIUS; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != V1_ISOLATION_RADIUS) {
                        continue;
                    }
                    BlockPos base = spawnPos.add(dx, 0, dz);
                    if (base.equals(jobPos)) {
                        if (!world.getWorldBorder().contains(base.up()) || !world.getBlockState(base.up()).isAir()) {
                            valid = false;
                            break;
                        }
                        barrierPositions.add(base.up().toImmutable());
                        continue;
                    }
                    if (!world.getWorldBorder().contains(base)
                            || !world.getWorldBorder().contains(base.up())
                            || !world.getBlockState(base).isAir()
                            || !world.getBlockState(base.up()).isAir()
                            || !world.getBlockState(base.down()).isSolidBlock(world, base.down())) {
                        valid = false;
                        break;
                    }
                    barrierPositions.add(base.toImmutable());
                    barrierPositions.add(base.up().toImmutable());
                }
            }
            if (valid) {
                return new V1IsolationPlan(spawnPos.toImmutable(), Set.copyOf(barrierPositions));
            }
        }
        return null;
    }

    private static boolean isJobSiteClaimedByAnyVillager(ServerWorld world, BlockPos jobPos) {
        return !world.getEntitiesByClass(
                VillagerEntity.class,
                new Box(jobPos).expand(DeveloperV1PlacementGrid.VANILLA_JOB_SITE_SEARCH_RADIUS),
                candidate -> candidate.isAlive()
                        && candidate.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                        .map(globalPos -> globalPos.pos().equals(jobPos))
                        .orElse(false)
        ).isEmpty();
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

    private record V1IsolationPlan(BlockPos spawnPos, Set<BlockPos> barrierPositions) {
    }
}

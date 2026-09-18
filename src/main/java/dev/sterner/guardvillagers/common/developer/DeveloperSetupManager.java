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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeveloperSetupManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeveloperSetupManager.class);
    public static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int STAGE_TIMEOUT_TICKS = 20 * 20;
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
        private VillagerProfession expectedV1Profession;
        private Block expectedV1JobBlock;
        private BlockPos currentV1JobPos;
        private BlockPos currentV1SpawnPos;
        private int currentV1Ticks;
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
            if (v1Batch.stage() == DeveloperV1BatchProgress.Stage.PREPARE_CURRENT) {
                prepareCurrentV1(server);
            } else {
                waitForCurrentV1Profession(server);
            }
            if (v1FatalFailure != null || v1Batch.isComplete()) {
                sendV1TerminalStatus(server);
            }
        }

        private void prepareCurrentV1(MinecraftServer server) {
            String completedSiteError = validateCompletedV1JobSites();
            if (completedSiteError != null) {
                v1FatalFailure = completedSiteError;
                return;
            }

            DeveloperV1BatchProgress.Task currentTask = v1Batch.currentTask();
            DeveloperProfession requestedProfession = currentTask.profession();
            expectedV1Profession = resolveV1Profession(requestedProfession);
            expectedV1JobBlock = resolveV1JobBlock(requestedProfession).orElse(null);
            if (expectedV1Profession == null || expectedV1JobBlock == null) {
                failCurrentV1(server, "No supported job-site mapping exists for " + requestedProfession.displayName() + ".");
                return;
            }

            BlockPos gridAnchor = requestedOrigin.add(
                    currentTask.gridSlot().x(),
                    0,
                    currentTask.gridSlot().z());
            BlockPos candidateJobPos = findV1JobSite(world, gridAnchor, v1JobSites.reservedPositions());
            if (candidateJobPos == null) {
                failCurrentV1(server, "No safe job-site location was found for " + requestedProfession.displayName() + ".");
                return;
            }
            if (!v1JobSites.begin(currentTask, candidateJobPos)) {
                v1FatalFailure = "V1 placement ownership collision for task " + (currentTask.index() + 1) + ".";
                return;
            }
            currentV1JobPos = candidateJobPos;
            if (!world.setBlockState(currentV1JobPos, stableV1JobBlockState(expectedV1JobBlock), Block.NOTIFY_ALL)) {
                failCurrentV1(server, "Could not place the " + requestedProfession.displayName() + " job site.");
                return;
            }

            currentV1SpawnPos = findSpawnBesideTable(world, currentV1JobPos);
            if (currentV1SpawnPos == null) {
                rollbackCurrentV1JobSite();
                failCurrentV1(server, "No safe villager spawn position was found beside the "
                        + requestedProfession.displayName() + " job site.");
                return;
            }
            villager = spawnVillager(world, currentV1SpawnPos);
            if (villager == null) {
                rollbackCurrentV1JobSite();
                failCurrentV1(server, "Could not spawn the " + requestedProfession.displayName() + " villager.");
                return;
            }
            villager.setPersistent();
            originalAiDisabled = villager.isAiDisabled();
            if (originalAiDisabled) {
                villager.setAiDisabled(false);
            }
            currentV1Ticks = 0;
            v1Batch.markPrepared();
            sendV1Progress(server, "Waiting for " + requestedProfession.displayName() + " profession acquisition");
        }

        private void waitForCurrentV1Profession(MinecraftServer server) {
            if (villager == null || !villager.isAlive()) {
                failCurrentV1(server, v1Batch.currentProfession().displayName() + " villager was removed before pairing.");
                return;
            }
            currentV1Ticks++;
            restrainCurrentV1Villager();

            VillagerProfession acquired = villager.getVillagerData().getProfession();
            BlockPos claimedJobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .map(globalPos -> globalPos.pos())
                    .orElse(null);
            if (acquired == expectedV1Profession && currentV1JobPos.equals(claimedJobSite)) {
                completeCurrentV1(server);
                return;
            }
            if (acquired != VillagerProfession.NONE && acquired != expectedV1Profession) {
                failCurrentV1(server, v1Batch.currentProfession().displayName()
                        + " villager claimed a different profession (" + acquired + ").");
                return;
            }
            if (currentV1Ticks >= STAGE_TIMEOUT_TICKS) {
                failCurrentV1(server, "Timed out waiting for " + v1Batch.currentProfession().displayName()
                        + " profession acquisition.");
            }
        }

        private void restrainCurrentV1Villager() {
            villager.setVelocity(Vec3d.ZERO);
            if (currentV1SpawnPos != null
                    && villager.squaredDistanceTo(Vec3d.ofCenter(currentV1SpawnPos)) > 9.0D) {
                villager.refreshPositionAndAngles(
                        currentV1SpawnPos.getX() + 0.5D,
                        currentV1SpawnPos.getY(),
                        currentV1SpawnPos.getZ() + 0.5D,
                        villager.getYaw(),
                        villager.getPitch());
                villager.getNavigation().stop();
            }
        }

        private void completeCurrentV1(MinecraftServer server) {
            v1JobSites.completeCurrent(villager.getUuid());
            releaseCurrentV1Villager();
            villager = null;
            currentV1JobPos = null;
            currentV1SpawnPos = null;
            expectedV1Profession = null;
            expectedV1JobBlock = null;
            v1Batch.finishCurrent(true);
            sendV1Progress(server, "Created V1 villagers");
        }

        private void failCurrentV1(MinecraftServer server, String message) {
            lastV1Failure = message;
            releaseCurrentV1Villager();
            rollbackCurrentV1JobSite();
            v1JobSites.rollbackCurrent();
            villager = null;
            currentV1JobPos = null;
            currentV1SpawnPos = null;
            expectedV1Profession = null;
            expectedV1JobBlock = null;
            v1Batch.finishCurrent(false);
            sendV1Progress(server, message + " Continuing batch");
        }

        private void releaseCurrentV1Villager() {
            if (villager != null && villager.isAlive()) {
                villager.setAiDisabled(false);
                villager.getNavigation().stop();
            }
            v1Batch.markSubjectReleased();
        }

        private void rollbackCurrentV1JobSite() {
            if (currentV1JobPos == null
                    || expectedV1JobBlock == null
                    || !v1JobSites.isCurrent(currentV1JobPos)) {
                return;
            }
            if (world.getBlockState(currentV1JobPos).isOf(expectedV1JobBlock)
                    && !isJobSiteClaimedByAnyVillager(world, currentV1JobPos)) {
                world.removeBlock(currentV1JobPos, false);
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

        private void sendV1Progress(MinecraftServer server, String detail) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                return;
            }
            int progress = 5 + (int) Math.floor(90.0D * v1Batch.processed() / Math.max(1, v1Batch.total()));
            sendStatus(player,
                    "Creating V1 villagers: " + v1Batch.processed() + " / " + v1Batch.total() + ". " + detail + ".",
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
                                + " V1 villagers; " + v1Batch.failed() + " failed. Last failure: " + lastV1Failure,
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
                releaseCurrentV1Villager();
                rollbackCurrentV1JobSite();
                v1JobSites.rollbackCurrent();
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
                releaseCurrentV1Villager();
                if (v1FatalFailure != null) {
                    rollbackCurrentV1JobSite();
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
                    if (!isSafeOpenPosition(world, candidate) || isTooCloseToV1JobSite(candidate, existingSites)) {
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
            if (dx * dx + dz * dz < 25) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJobSiteClaimedByAnyVillager(ServerWorld world, BlockPos jobPos) {
        return !world.getEntitiesByClass(
                VillagerEntity.class,
                new Box(jobPos).expand(32.0D),
                candidate -> candidate.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
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
}

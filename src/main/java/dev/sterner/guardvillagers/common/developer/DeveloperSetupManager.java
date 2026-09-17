package dev.sterner.guardvillagers.common.developer;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardCraftingGoal;
import dev.sterner.guardvillagers.common.network.DeveloperSetupStatusPacket;
import dev.sterner.guardvillagers.common.villager.UnemployedLumberjackConversionHook;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
        private final Set<BlockPos> attemptedTreeSites = new HashSet<>();
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

        private SetupSession(ServerPlayerEntity player, DeveloperSetupRequest request) {
            this.playerId = player.getUuid();
            this.request = request;
            this.world = player.getServerWorld();
            this.requestedOrigin = player.getBlockPos().offset(player.getHorizontalFacing(), 4).toImmutable();
            this.workflow = new DeveloperSetupWorkflow(
                    request.setupType(),
                    request.needsInfrastructure(),
                    request.needsInventoryPopulation(),
                    request.generateMatureTrees(),
                    STAGE_TIMEOUT_TICKS
            );
        }

        private void tick(MinecraftServer server) {
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
                    request.createPenSetup()
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
            workflow.fail("Generated " + generatedTrees + " of " + request.treeCount() + " trees; no additional safe sites were found.");
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
            workflow.fail(message);
        }

        private void cancel() {
            workflow.fail("Setup cancelled because the server is stopping.");
            cleanup();
        }

        private boolean isFinished() {
            return workflow.stage().isTerminal() && terminalStatusSent;
        }

        private void cleanup() {
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

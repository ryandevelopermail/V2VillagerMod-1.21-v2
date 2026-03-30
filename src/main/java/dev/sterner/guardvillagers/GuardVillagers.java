package dev.sterner.guardvillagers;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.handler.JobBlockPlacementHandler;
import dev.sterner.guardvillagers.common.network.GuardData;
import dev.sterner.guardvillagers.common.network.GuardFollowPacket;
import dev.sterner.guardvillagers.common.network.GuardPatrolPacket;
import dev.sterner.guardvillagers.common.screenhandler.GuardVillagerScreenHandler;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.RecipeDemandIndex;
import dev.sterner.guardvillagers.common.util.TakeJobSiteInjectDiagnostics;
import dev.sterner.guardvillagers.common.util.VillageLumberjackSpawnManager;
import dev.sterner.guardvillagers.common.util.VillageMembershipTracker;
import dev.sterner.guardvillagers.common.util.VillagePenRegistry;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillagerBellTracker;
import dev.sterner.guardvillagers.common.util.VillagerBellTracker.BellVillageReport;
import dev.sterner.guardvillagers.common.util.VillageBellChestPlacementHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.GuardConversionHelper;
import dev.sterner.guardvillagers.common.villager.LumberjackPopulationBalancingService;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import dev.sterner.guardvillagers.common.villager.behavior.FishermanBehavior;
import dev.sterner.guardvillagers.compat.morevillagers.MoreVillagersBehaviorBridge;
import net.fabricmc.loader.api.FabricLoader;
import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.entity.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.TypeFilter;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.spawner.SpecialSpawner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class GuardVillagers implements ModInitializer {
    public static final String MODID = "guardvillagers";
    private static final Logger LOGGER = LoggerFactory.getLogger(GuardVillagers.class);
    private static final Map<RegistryKey<World>, Long> LAST_CONVERSION_EXECUTION_TICK = new HashMap<>();
    private static final Map<RegistryKey<World>, ConversionScheduleState> CONVERSION_SCHEDULE_STATES = new HashMap<>();
    private static final Map<RegistryKey<World>, PairingBootstrapState> PENDING_PAIRING_BOOTSTRAP = new HashMap<>();
    private static final long RESERVATION_RECONCILIATION_INTERVAL_TICKS = 300L;
    private static final int PAIRING_BOOTSTRAP_MAX_CHUNKS_PER_TICK = 4;
    private static final int PAIRING_BOOTSTRAP_MAX_ENTITIES_PER_TICK = 96;
    private static final int PAIRING_BOOTSTRAP_ACTIVE_RADIUS_CHUNKS = 3;
    private static final int PAIRING_BOOTSTRAP_FINAL_RADIUS_CHUNKS = 16;
    private static final long WORLD_LOAD_PHASE_WARN_THRESHOLD_MS = 250L;
    private static final long WORLD_LOAD_TOTAL_WARN_THRESHOLD_MS = 500L;
    private static final int CANDIDATE_CHUNK_SCAN_STARTUP_BUDGET = 1;
    private static final int CANDIDATE_CHUNK_SCAN_LIVE_BUDGET = 4;
    private static final int CANDIDATE_CHUNK_SCAN_BACKLOG_BUDGET = 8;
    private static final long CONVERSION_WARMUP_WORLD_AGE_THRESHOLD_TICKS = 200L;
    private static final int CONVERSION_WARMUP_DOWNSAMPLE_FACTOR = 6;
    private static final int CONVERSION_CATCH_UP_MAX_RUNS_PER_TICK = 2;
    private static final int CONVERSION_PENDING_RUN_CAP = 32;

    public static final ScreenHandlerType<GuardVillagerScreenHandler> GUARD_SCREEN_HANDLER =
            new ExtendedScreenHandlerType<>((syncId, inventory, data) -> new GuardVillagerScreenHandler(syncId, inventory, data), GuardData.PACKET_CODEC);

    public static final EntityType<GuardEntity> GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, GuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item GUARD_SPAWN_EGG = new SpawnEggItem(GUARD_VILLAGER, 5651507, 8412749, new Item.Settings());

    public static final EntityType<AxeGuardEntity> AXE_GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("axe_guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, AxeGuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item AXE_GUARD_SPAWN_EGG = new SpawnEggItem(AXE_GUARD_VILLAGER, 5651507, 9477598, new Item.Settings());

    public static final EntityType<ButcherGuardEntity> BUTCHER_GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("butcher_guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, ButcherGuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item BUTCHER_GUARD_SPAWN_EGG = new SpawnEggItem(BUTCHER_GUARD_VILLAGER, 5651507, 11250603, new Item.Settings());

    public static final EntityType<MasonGuardEntity> MASON_GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("mason_guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, MasonGuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item MASON_GUARD_SPAWN_EGG = new SpawnEggItem(MASON_GUARD_VILLAGER, 5651507, 12299222, new Item.Settings());

    public static final EntityType<FishermanGuardEntity> FISHERMAN_GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("fisherman_guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, FishermanGuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item FISHERMAN_GUARD_SPAWN_EGG = new SpawnEggItem(FISHERMAN_GUARD_VILLAGER, 5651507, 3368652, new Item.Settings());

    public static final EntityType<LumberjackGuardEntity> LUMBERJACK_GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("lumberjack_guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, LumberjackGuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item LUMBERJACK_GUARD_SPAWN_EGG = new SpawnEggItem(LUMBERJACK_GUARD_VILLAGER, 5651507, 8553352, new Item.Settings());
    public static final Block GUARD_STAND_MODIFIER = new Block(AbstractBlock.Settings.create().strength(2.0F).sounds(BlockSoundGroup.STONE));
    public static final Item GUARD_STAND_MODIFIER_ITEM = new BlockItem(GUARD_STAND_MODIFIER, new Item.Settings());
    public static final Block GUARD_STAND_ANCHOR = new Block(AbstractBlock.Settings.create().strength(2.0F).sounds(BlockSoundGroup.STONE));
    public static final Item GUARD_STAND_ANCHOR_ITEM = new BlockItem(GUARD_STAND_ANCHOR, new Item.Settings());

    public static Hand getHandWith(LivingEntity livingEntity, Predicate<Item> itemPredicate) {
        return itemPredicate.test(livingEntity.getMainHandStack().getItem()) ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    public static SoundEvent GUARD_AMBIENT = SoundEvent.of(id( "entity.guard.ambient"));
    public static SoundEvent GUARD_HURT = SoundEvent.of(id( "entity.guard.hurt"));
    public static SoundEvent GUARD_DEATH = SoundEvent.of(id("entity.guard.death"));

    public static Identifier id(String name){
        return Identifier.of(MODID, name);
    }

    public static void onBellRung(ServerWorld world, BlockPos bellPos) {
        // Tag all villagers within range with this primary bell (Cluster 1B)
        VillageMembershipTracker.tagVillagersNearBell(world, bellPos);

        BellVillageReport report = VillagerBellTracker.snapshotBellVillageReport(world, bellPos);
        VillagerBellTracker.logBellVillagerStats(world, bellPos, report);
        VillagerBellTracker.writeBellReportBooks(world, bellPos, report);
        VillagerBellTracker.directEmployedVillagersAndGuardsToStations(world, bellPos);
    }

    @Override
    public void onInitialize() {
        MidnightConfig.init(MODID, GuardVillagersConfig.class);
        FabricDefaultAttributeRegistry.register(GUARD_VILLAGER, GuardEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(AXE_GUARD_VILLAGER, GuardEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(BUTCHER_GUARD_VILLAGER, GuardEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(MASON_GUARD_VILLAGER, GuardEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(FISHERMAN_GUARD_VILLAGER, GuardEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(LUMBERJACK_GUARD_VILLAGER, GuardEntity.createAttributes());
        ProfessionDefinitions.registerAll();

        if (FabricLoader.getInstance().isModLoaded("morevillagers")) {
            MoreVillagersBehaviorBridge.register();
        }

        Registry.register(Registries.ITEM, id("guard_spawn_egg"), GUARD_SPAWN_EGG);
        Registry.register(Registries.ITEM, id("axe_guard_spawn_egg"), AXE_GUARD_SPAWN_EGG);
        Registry.register(Registries.ITEM, id("butcher_guard_spawn_egg"), BUTCHER_GUARD_SPAWN_EGG);
        Registry.register(Registries.ITEM, id("mason_guard_spawn_egg"), MASON_GUARD_SPAWN_EGG);
        Registry.register(Registries.ITEM, id("fisherman_guard_spawn_egg"), FISHERMAN_GUARD_SPAWN_EGG);
        Registry.register(Registries.ITEM, id("lumberjack_guard_spawn_egg"), LUMBERJACK_GUARD_SPAWN_EGG);
        Registry.register(Registries.BLOCK, id("guard_stand_modifier"), GUARD_STAND_MODIFIER);
        Registry.register(Registries.ITEM, id("guard_stand_modifier"), GUARD_STAND_MODIFIER_ITEM);
        Registry.register(Registries.BLOCK, id("guard_stand_anchor"), GUARD_STAND_ANCHOR);
        Registry.register(Registries.ITEM, id("guard_stand_anchor"), GUARD_STAND_ANCHOR_ITEM);
        Registry.register(Registries.SCREEN_HANDLER, id("guard_screen"), GUARD_SCREEN_HANDLER);
        Registry.register(Registries.SOUND_EVENT, id("entity.guard.ambient"), GUARD_AMBIENT);
        Registry.register(Registries.SOUND_EVENT, id( "entity.guard.hurt"), GUARD_HURT);
        Registry.register(Registries.SOUND_EVENT, id( "entity.guard.death"), GUARD_DEATH);

        PayloadTypeRegistry.playC2S().register(GuardFollowPacket.ID, GuardFollowPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(GuardPatrolPacket.ID, GuardPatrolPacket.PACKET_CODEC);

        PayloadTypeRegistry.playS2C().register(GuardFollowPacket.ID, GuardFollowPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(GuardPatrolPacket.ID, GuardPatrolPacket.PACKET_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GuardFollowPacket.ID, GuardFollowPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(GuardPatrolPacket.ID, GuardPatrolPacket::handle);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
            entries.add(GUARD_SPAWN_EGG);
            entries.add(AXE_GUARD_SPAWN_EGG);
            entries.add(BUTCHER_GUARD_SPAWN_EGG);
            entries.add(MASON_GUARD_SPAWN_EGG);
            entries.add(FISHERMAN_GUARD_SPAWN_EGG);
            entries.add(LUMBERJACK_GUARD_SPAWN_EGG);
            entries.add(GUARD_STAND_MODIFIER_ITEM);
            entries.add(GUARD_STAND_ANCHOR_ITEM);
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onDamage);
        UseEntityCallback.EVENT.register(this::villagerConvert);
        JobBlockPlacementHandler.register();
        UseItemCallback.EVENT.register(this::onUseItem);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof VillagerEntity villagerEntity) {
                if (world instanceof ServerWorld serverWorld) {
                    JobBlockPairingHelper.refreshVillagerPairings(serverWorld, villagerEntity);
                    VillagerConversionCandidateIndex.markCandidate(serverWorld, villagerEntity);
                }
                if (villagerEntity.isNatural()) {
                    var spawnChance = MathHelper.clamp(GuardVillagersConfig.spawnChancePerVillager, 0f, 1f);
                    if (world.random.nextFloat() < spawnChance) {
                        GuardEntity guardEntity = GUARD_VILLAGER.create(world);
                        guardEntity.spawnWithArmor = true;
                        guardEntity.initialize(world, world.getLocalDifficulty(villagerEntity.getBlockPos()), SpawnReason.NATURAL, null);
                        guardEntity.refreshPositionAndAngles(villagerEntity.getBlockPos(), 0.0f, 0.0f);

                        int i = GuardEntity.getRandomTypeForBiome(guardEntity.getWorld(), guardEntity.getBlockPos());
                        guardEntity.setGuardVariant(i);
                        guardEntity.setPersistent();
                        guardEntity.setCustomName(villagerEntity.getCustomName());
                        guardEntity.setCustomNameVisible(villagerEntity.isCustomNameVisible());
                        guardEntity.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
                        guardEntity.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
                        guardEntity.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
                        guardEntity.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
                        guardEntity.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
                        guardEntity.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);

                        world.spawnEntityAndPassengers(guardEntity);
                        if (world instanceof ServerWorld serverWorld) {
                            VillageGuardStandManager.handleGuardSpawn(serverWorld, guardEntity, villagerEntity);
                        }
                    }
                }
            }
            if (entity instanceof ButcherGuardEntity guardEntity && world instanceof ServerWorld serverWorld) {
                JobBlockPairingHelper.refreshButcherGuardPairings(serverWorld, guardEntity);
                rehydrateConvertedWorkerReservation(serverWorld, guardEntity, guardEntity.getPairedSmokerPos(), VillagerProfession.BUTCHER, "paired smoker");
            }
            if (entity instanceof MasonGuardEntity guardEntity && world instanceof ServerWorld serverWorld) {
                rehydrateConvertedWorkerReservation(serverWorld, guardEntity, guardEntity.getPairedJobPos(), VillagerProfession.MASON, "paired job");
            }
            if (entity instanceof FishermanGuardEntity guardEntity && world instanceof ServerWorld serverWorld) {
                rehydrateConvertedWorkerReservation(serverWorld, guardEntity, guardEntity.getPairedJobPos(), VillagerProfession.FISHERMAN, "paired job");
            }
            if (entity instanceof LumberjackGuardEntity guardEntity && world instanceof ServerWorld serverWorld) {
                rehydrateConvertedWorkerReservation(serverWorld, guardEntity, guardEntity.getPairedCraftingTablePos(), VillagerProfession.NONE, "paired crafting table");
            }
        });

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            VillagerConversionCandidateIndex.enqueueChunkScan(world, chunk.getPos().x, chunk.getPos().z);
            JobBlockPairingHelper.onChunkLoaded(world, chunk);
        });

        ServerWorldEvents.LOAD.register((server, world) -> {
            long worldLoadStartNanos = System.nanoTime();
            runTimedWorldLoadPhase(world, "pairing bootstrap", WORLD_LOAD_PHASE_WARN_THRESHOLD_MS,
                    () -> PENDING_PAIRING_BOOTSTRAP.put(world.getRegistryKey(), new PairingBootstrapState()));
            runTimedWorldLoadPhase(world, "bell/chest reconciliation", WORLD_LOAD_PHASE_WARN_THRESHOLD_MS,
                    () -> VillageBellChestPlacementHelper.reconcileWorldBellChestMappings(world));
            runTimedWorldLoadPhase(world, "reservation reconciliation", WORLD_LOAD_PHASE_WARN_THRESHOLD_MS,
                    () -> reconcileConvertedWorkerReservations(world, "world-load"));
            runTimedWorldLoadPhase(world, "recipe index build", WORLD_LOAD_PHASE_WARN_THRESHOLD_MS,
                    () -> RecipeDemandIndex.forWorld(world));
            logWorldLoadSummary(world, elapsedMsSince(worldLoadStartNanos), WORLD_LOAD_TOTAL_WARN_THRESHOLD_MS);
        });
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            LAST_CONVERSION_EXECUTION_TICK.remove(world.getRegistryKey());
            CONVERSION_SCHEDULE_STATES.remove(world.getRegistryKey());
            PENDING_PAIRING_BOOTSTRAP.remove(world.getRegistryKey());
            LumberjackPopulationBalancingService.onWorldUnload(world.getRegistryKey());
            RecipeDemandIndex.clearWorld(world);
            JobBlockPairingHelper.onWorldUnload(world);
            ProfessionDefinitions.onWorldUnload(world);
            FishermanBehavior.onWorldUnload(world);
            VillagerConversionCandidateIndex.clearWorld(world);
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            int invalidatedWorlds = 0;
            for (ServerWorld world : server.getWorlds()) {
                RecipeDemandIndex.clearWorld(world);
                invalidatedWorlds++;
            }
            LOGGER.info("[recipe-demand-index] invalidated {} world cache entries after datapack reload (success={})", invalidatedWorlds, success);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerWorld world : server.getWorlds()) {
                for (PlayerEntity player : world.getPlayers()) {
                    VillageGuardStandManager.handlePlayerNearby(world, player);
                }
                VillagerBellTracker.tickVillagerReports(world);
                // 1200 ticks = 60 s. Bell-chest reconciliation can place block states;
                // running it every 5 seconds was unnecessarily hot.
                if (world.getTime() % 1200L == 7L) {
                    VillageBellChestPlacementHelper.reconcileWorldBellChestMappings(world);
                }
                if (GuardVillagersConfig.villagerConversionFallbackSweepEnabled
                        && world.getTime() % Math.max(20, GuardVillagersConfig.villagerConversionCandidateMarkIntervalTicks) == 0L) {
                    ProfessionDefinitions.markFallbackCandidates(world);
                }
                processQueuedCandidateChunkMarking(world);
                LumberjackPopulationBalancingService.tick(world);
                VillageLumberjackSpawnManager.tick(world);
                VillagePenRegistry.tick(world);
                runConversionHooksOnSchedule(world);
                if (world.getTime() % RESERVATION_RECONCILIATION_INTERVAL_TICKS == 0L) {
                    reconcileConvertedWorkerReservations(world, "scheduled");
                }
                runPairingBootstrapStep(world);
                JobBlockPairingHelper.runBackgroundCatchUp(world);
                FishermanBehavior.tickDeferredFallbackScans(world);
            }
            TakeJobSiteInjectDiagnostics.warnIfInjectMissing(server.getWorlds());
        });
    }

    private static void processQueuedCandidateChunkMarking(ServerWorld world) {
        int queuedChunks = VillagerConversionCandidateIndex.getQueuedChunkScanCount(world);
        if (queuedChunks <= 0) {
            return;
        }

        boolean worldIsLive = !world.getPlayers().isEmpty();
        int budget = worldIsLive
                ? (queuedChunks > CANDIDATE_CHUNK_SCAN_LIVE_BUDGET
                ? CANDIDATE_CHUNK_SCAN_BACKLOG_BUDGET
                : CANDIDATE_CHUNK_SCAN_LIVE_BUDGET)
                : CANDIDATE_CHUNK_SCAN_STARTUP_BUDGET;

        VillagerConversionCandidateIndex.processQueuedChunkScans(world, budget);
    }


    private static void rehydrateConvertedWorkerReservation(ServerWorld world,
                                                            GuardEntity guard,
                                                            @Nullable BlockPos pairedPos,
                                                            VillagerProfession profession,
                                                            String source) {
        if (pairedPos == null) {
            return;
        }
        ConvertedWorkerJobSiteReservationManager.reserve(world, pairedPos, guard.getUuid(), profession, "rehydrate " + source);
        LOGGER.debug("rehydrated reservation: guard={} profession={} pos={} world={} source={}",
                guard.getUuidAsString(),
                profession,
                pairedPos.toShortString(),
                world.getRegistryKey().getValue(),
                source);
    }

    private static void runTimedWorldLoadPhase(ServerWorld world, String taskName, long warnThresholdMs, Runnable task) {
        long startNanos = System.nanoTime();
        task.run();
        long durationMs = elapsedMsSince(startNanos);
        if (durationMs >= warnThresholdMs) {
            LOGGER.warn("[world-load-timing] world={} task={} durationMs={} thresholdMs={}",
                    world.getRegistryKey().getValue(),
                    taskName,
                    durationMs,
                    warnThresholdMs);
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[world-load-timing] world={} task={} durationMs={}",
                    world.getRegistryKey().getValue(),
                    taskName,
                    durationMs);
        }
    }

    private static void logWorldLoadSummary(ServerWorld world, long totalDurationMs, long warnThresholdMs) {
        if (totalDurationMs >= warnThresholdMs) {
            LOGGER.warn("[world-load-timing] world={} task=summary durationMs={} thresholdMs={}",
                    world.getRegistryKey().getValue(),
                    totalDurationMs,
                    warnThresholdMs);
            return;
        }
        LOGGER.info("[world-load-timing] world={} task=summary durationMs={}",
                world.getRegistryKey().getValue(),
                totalDurationMs);
    }

    private static long elapsedMsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void runPairingBootstrapStep(ServerWorld world) {
        PairingBootstrapState state = PENDING_PAIRING_BOOTSTRAP.get(world.getRegistryKey());
        if (state == null) {
            return;
        }

        if (!state.prunedAnchors) {
            VillageAnchorState.get(world.getServer()).pruneInvalidAnchors(world);
            state.prunedAnchors = true;
            state.currentRadiusChunks = 0;
            state.pendingChunks.clear();
            state.enqueuedChunks.clear();
        }

        int remainingChunkBudget = PAIRING_BOOTSTRAP_MAX_CHUNKS_PER_TICK;
        int remainingEntityBudget = PAIRING_BOOTSTRAP_MAX_ENTITIES_PER_TICK;
        if (remainingChunkBudget <= 0 || remainingEntityBudget <= 0) {
            return;
        }

        enqueuePlayerRings(state, world, PAIRING_BOOTSTRAP_ACTIVE_RADIUS_CHUNKS);
        while (remainingChunkBudget > 0 && remainingEntityBudget > 0 && !state.pendingChunks.isEmpty()) {
            long packedChunk = state.pendingChunks.removeFirst();
            state.enqueuedChunks.remove(packedChunk);
            int chunkX = ChunkPos.getPackedX(packedChunk);
            int chunkZ = ChunkPos.getPackedZ(packedChunk);
            remainingEntityBudget -= hydratePairingsInChunk(world, chunkX, chunkZ, remainingEntityBudget);
            remainingChunkBudget--;
        }

        if (state.currentRadiusChunks < PAIRING_BOOTSTRAP_FINAL_RADIUS_CHUNKS) {
            state.currentRadiusChunks++;
        }

        if (state.currentRadiusChunks > PAIRING_BOOTSTRAP_ACTIVE_RADIUS_CHUNKS) {
            enqueuePlayerRings(state, world, state.currentRadiusChunks);
        }

        if (state.currentRadiusChunks >= PAIRING_BOOTSTRAP_FINAL_RADIUS_CHUNKS && state.pendingChunks.isEmpty()) {
            PENDING_PAIRING_BOOTSTRAP.remove(world.getRegistryKey());
        }
    }

    private static void enqueuePlayerRings(PairingBootstrapState state, ServerWorld world, int radiusChunks) {
        if (world.getPlayers().isEmpty()) {
            return;
        }
        for (PlayerEntity player : world.getPlayers()) {
            int centerChunkX = MathHelper.floor(player.getX()) >> 4;
            int centerChunkZ = MathHelper.floor(player.getZ()) >> 4;
            enqueueChunkRing(state, centerChunkX, centerChunkZ, radiusChunks);
        }
    }

    private static void enqueueChunkRing(PairingBootstrapState state, int centerChunkX, int centerChunkZ, int radiusChunks) {
        if (radiusChunks == 0) {
            enqueueChunk(state, centerChunkX, centerChunkZ);
            return;
        }
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            enqueueChunk(state, centerChunkX + dx, centerChunkZ - radiusChunks);
            enqueueChunk(state, centerChunkX + dx, centerChunkZ + radiusChunks);
        }
        for (int dz = -radiusChunks + 1; dz <= radiusChunks - 1; dz++) {
            enqueueChunk(state, centerChunkX - radiusChunks, centerChunkZ + dz);
            enqueueChunk(state, centerChunkX + radiusChunks, centerChunkZ + dz);
        }
    }

    private static void enqueueChunk(PairingBootstrapState state, int chunkX, int chunkZ) {
        long packedChunk = ChunkPos.toLong(chunkX, chunkZ);
        if (state.enqueuedChunks.add(packedChunk)) {
            state.pendingChunks.addLast(packedChunk);
        }
    }

    private static int hydratePairingsInChunk(ServerWorld world, int chunkX, int chunkZ, int remainingEntityBudget) {
        if (remainingEntityBudget <= 0) {
            return 0;
        }

        Chunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return 0;
        }

        BlockPos startPos = chunk.getPos().getStartPos();
        Box chunkBox = new Box(
                startPos.getX(), world.getBottomY(), startPos.getZ(),
                startPos.getX() + 16.0D, world.getTopY(), startPos.getZ() + 16.0D);
        int refreshed = 0;

        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, chunkBox, villager -> villager.isAlive() && villager.getVillagerData().getProfession() != VillagerProfession.NONE)) {
            if (refreshed >= remainingEntityBudget) {
                return refreshed;
            }
            JobBlockPairingHelper.refreshVillagerPairings(world, villager);
            refreshed++;
        }

        for (ButcherGuardEntity guard : world.getEntitiesByClass(ButcherGuardEntity.class, chunkBox, Entity::isAlive)) {
            if (refreshed >= remainingEntityBudget) {
                return refreshed;
            }
            JobBlockPairingHelper.refreshButcherGuardPairings(world, guard);
            refreshed++;
        }

        return refreshed;
    }

    private static final class ConversionScheduleState {
        private int pendingRuns;
        private long lastEnqueueTick = Long.MIN_VALUE;
        private final long warmupStartTick;
        private boolean warmupActive = true;
        private boolean warmupStartLogged;

        private ConversionScheduleState(long warmupStartTick) {
            this.warmupStartTick = warmupStartTick;
        }
    }

    private static final class PairingBootstrapState {
        private final Deque<Long> pendingChunks = new ArrayDeque<>();
        private final Set<Long> enqueuedChunks = new HashSet<>();
        private int currentRadiusChunks = 0;
        private boolean prunedAnchors;
    }

    private void runConversionHooksOnSchedule(ServerWorld world) {
        long worldTick = world.getTime();
        long executionInterval = Math.max(20, GuardVillagersConfig.villagerConversionExecutionIntervalTicks);
        RegistryKey<World> worldKey = world.getRegistryKey();
        ConversionScheduleState state = CONVERSION_SCHEDULE_STATES.computeIfAbsent(worldKey, ignored -> new ConversionScheduleState(worldTick));

        if (worldTick % executionInterval == 0L && state.lastEnqueueTick != worldTick) {
            state.lastEnqueueTick = worldTick;
            state.pendingRuns = Math.min(CONVERSION_PENDING_RUN_CAP, state.pendingRuns + 1);
        }

        if (state.warmupActive) {
            if (!state.warmupStartLogged) {
                state.warmupStartLogged = true;
                LOGGER.info("[conversion-schedule] world={} warmup-start tick={} downsampleFactor={} worldAgeThresholdTicks={}",
                        worldKey.getValue(), worldTick, CONVERSION_WARMUP_DOWNSAMPLE_FACTOR, CONVERSION_WARMUP_WORLD_AGE_THRESHOLD_TICKS);
            }

            boolean warmupReady = !world.getPlayers().isEmpty() || worldTick >= CONVERSION_WARMUP_WORLD_AGE_THRESHOLD_TICKS;
            if (!warmupReady) {
                long downsampleInterval = executionInterval * CONVERSION_WARMUP_DOWNSAMPLE_FACTOR;
                if (state.pendingRuns > 0 && worldTick % downsampleInterval == 0L) {
                    runConversionHooks(world, worldKey, 1);
                    state.pendingRuns--;
                }
                return;
            }

            state.warmupActive = false;
            long warmupDuration = Math.max(0L, worldTick - state.warmupStartTick);
            LOGGER.info("[conversion-schedule] world={} warmup-end tick={} durationTicks={} pendingBacklog={} playersOnline={} ageReady={}",
                    worldKey.getValue(), worldTick, warmupDuration, state.pendingRuns, !world.getPlayers().isEmpty(),
                    worldTick >= CONVERSION_WARMUP_WORLD_AGE_THRESHOLD_TICKS);
        }

        int runsToExecute = Math.min(state.pendingRuns, CONVERSION_CATCH_UP_MAX_RUNS_PER_TICK);
        if (runsToExecute <= 0) {
            return;
        }

        runConversionHooks(world, worldKey, runsToExecute);
        state.pendingRuns -= runsToExecute;
    }

    private static void runConversionHooks(ServerWorld world, RegistryKey<World> worldKey, int runCount) {
        for (int i = 0; i < runCount; i++) {
            ProfessionDefinitions.runConversionHooks(world);
            LAST_CONVERSION_EXECUTION_TICK.put(worldKey, world.getTime());
        }
    }


    /** Scan radius (blocks) for per-schedule guard reconciliation — covers normal village spread. */
    private static final double RECONCILIATION_SCAN_RADIUS = 800.0D;

    private static void reconcileConvertedWorkerReservations(ServerWorld world, String source) {
        ReconciliationStats stats = new ReconciliationStats();

        // Use a player-proximity box instead of world-bounds to avoid scanning the entire world.
        // Guards more than 800 blocks from all players will be reconciled on next world-load instead.
        Box scanBox = buildPlayerProximityBox(world, RECONCILIATION_SCAN_RADIUS);
        if (scanBox == null) {
            // No players online — skip this pass entirely.
            return;
        }

        for (ButcherGuardEntity guard : world.getEntitiesByClass(ButcherGuardEntity.class, scanBox, Entity::isAlive)) {
            reconcileGuardReservation(world, guard, guard.getPairedSmokerPos(), VillagerProfession.BUTCHER, source + " butcher", stats,
                    () -> guard.setPairedSmokerPos(null));
        }

        for (MasonGuardEntity guard : world.getEntitiesByClass(MasonGuardEntity.class, scanBox, Entity::isAlive)) {
            reconcileGuardReservation(world, guard, guard.getPairedJobPos(), VillagerProfession.MASON, source + " mason", stats,
                    () -> guard.setPairedJobPos(null));
        }

        for (FishermanGuardEntity guard : world.getEntitiesByClass(FishermanGuardEntity.class, scanBox, Entity::isAlive)) {
            reconcileGuardReservation(world, guard, guard.getPairedJobPos(), VillagerProfession.FISHERMAN, source + " fisherman", stats,
                    () -> guard.setPairedJobPos(null));
        }

        LOGGER.debug("Converted worker reservation reconciliation pass (world={}, source={}): added={}, removed={}",
                world.getRegistryKey().getValue(), source, stats.added, stats.removed);
    }

    /**
     * Returns a Box that encompasses all players in {@code world} expanded by {@code radius},
     * or {@code null} if there are no players online in that world.
     */
    @Nullable
    private static Box buildPlayerProximityBox(ServerWorld world, double radius) {
        var players = world.getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (var p : players) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new Box(minX - radius, minY - radius, minZ - radius,
                       maxX + radius, maxY + radius, maxZ + radius);
    }

    private static void reconcileGuardReservation(ServerWorld world,
                                                  GuardEntity guard,
                                                  @Nullable BlockPos pairedPos,
                                                  VillagerProfession profession,
                                                  String source,
                                                  ReconciliationStats stats,
                                                  Runnable clearPairing) {
        if (pairedPos == null) {
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(profession, world.getBlockState(pairedPos))) {
            if (ConvertedWorkerJobSiteReservationManager.removeReservation(world, pairedPos,
                    "reconcile invalid workstation " + source)) {
                stats.removed++;
            }
            clearPairing.run();
            return;
        }

        ConvertedWorkerJobSiteReservationManager.EnsureResult ensureResult = ConvertedWorkerJobSiteReservationManager.ensureReservation(
                world,
                pairedPos,
                guard.getUuid(),
                profession,
                "reconcile " + source);

        if (ensureResult == ConvertedWorkerJobSiteReservationManager.EnsureResult.ADDED
                || ensureResult == ConvertedWorkerJobSiteReservationManager.EnsureResult.ADDED_AFTER_INVALID_REMOVAL) {
            stats.added++;
        }
        if (ensureResult == ConvertedWorkerJobSiteReservationManager.EnsureResult.ADDED_AFTER_INVALID_REMOVAL
                || ensureResult == ConvertedWorkerJobSiteReservationManager.EnsureResult.REPLACED_EXISTING) {
            stats.removed++;
        }
    }

    private static final class ReconciliationStats {
        private int added;
        private int removed;
    }


    private boolean onDamage(LivingEntity entity, DamageSource source, float amount) {
        Entity attacker = source.getAttacker();
        if (entity == null || attacker == null)
            return true;
        boolean shouldDamage = true;
        boolean isVillager = entity.getType() == EntityType.VILLAGER || entity.getType() == GuardVillagers.GUARD_VILLAGER;
        boolean isGolem = isVillager || entity.getType() == EntityType.IRON_GOLEM;
        if (isGolem && attacker.getType() == GuardVillagers.GUARD_VILLAGER && !GuardVillagersConfig.guardArrowsHurtVillagers) {
            shouldDamage = false;
        }
        if (isVillager && attacker instanceof MobEntity) {
            List<MobEntity> list = attacker.getWorld().getNonSpectatingEntities(MobEntity.class, attacker.getBoundingBox().expand(GuardVillagersConfig.guardVillagerHelpRange, 5.0D, GuardVillagersConfig.guardVillagerHelpRange));
            for (MobEntity mob : list) {
                boolean type = mob.getType() == GUARD_VILLAGER || mob.getType() == EntityType.IRON_GOLEM;
                boolean trueSourceGolem = attacker.getType() == GUARD_VILLAGER || attacker.getType() == EntityType.IRON_GOLEM;
                if (!trueSourceGolem && type && mob.getTarget() == null)
                    mob.setTarget((MobEntity) attacker);
            }
        }
        return shouldDamage;
    }

    private ActionResult villagerConvert(PlayerEntity player, World world, Hand hand, Entity entity, @Nullable EntityHitResult entityHitResult) {
        return ActionResult.PASS;
    }

    private TypedActionResult<ItemStack> onUseItem(PlayerEntity player, World world, Hand hand) {
        ItemStack stackInHand = player.getStackInHand(hand);
        if (!world.isClient() && stackInHand.isOf(Items.GOAT_HORN)) {
            ServerWorld serverWorld = (ServerWorld) world;
            BlockPos targetPos = player.getBlockPos();
            long hornDuration = stackInHand.getMaxUseTime(player) + 20L * 15L;
            Box searchBox = new Box(targetPos).expand(GuardVillagersConfig.followRangeModifier);
            for (GuardEntity guard : serverWorld.getEntitiesByType(TypeFilter.instanceOf(GuardEntity.class), searchBox, Entity::isAlive)) {
                guard.setHornTarget(targetPos, hornDuration);
            }
            return TypedActionResult.success(stackInHand, false);
        }
        return TypedActionResult.pass(stackInHand);
    }

    private void convertVillager(VillagerEntity villagerEntity, PlayerEntity player, World world) {
        player.swingHand(Hand.MAIN_HAND);
        ItemStack itemstack = player.getEquippedStack(EquipmentSlot.MAINHAND);
        GuardEntity guard = GUARD_VILLAGER.create(world);
        if (guard == null)
            return;
        if (player.getWorld().isClient()) {
            ParticleEffect particleEffect = ParticleTypes.HAPPY_VILLAGER;
            for (int i = 0; i < 10; ++i) {
                double d0 = villagerEntity.getRandom().nextGaussian() * 0.02D;
                double d1 = villagerEntity.getRandom().nextGaussian() * 0.02D;
                double d2 = villagerEntity.getRandom().nextGaussian() * 0.02D;
                villagerEntity.getWorld().addParticle(particleEffect, villagerEntity.getX() + (double) (villagerEntity.getRandom().nextFloat() * villagerEntity.getWidth() * 2.0F) - (double) villagerEntity.getWidth(), villagerEntity.getY() + 0.5D + (double) (villagerEntity.getRandom().nextFloat() * villagerEntity.getWidth()),
                        villagerEntity.getZ() + (double) (villagerEntity.getRandom().nextFloat() * villagerEntity.getWidth() * 2.0F) - (double) villagerEntity.getWidth(), d0, d1, d2);
            }
        }
        if (world instanceof ServerWorld serverWorld) {
            GuardConversionHelper.copyVillagerIdentityAndPose(serverWorld, villagerEntity, guard);
        } else {
            guard.copyPositionAndRotation(villagerEntity);
            guard.headYaw = villagerEntity.headYaw;
            guard.refreshPositionAndAngles(villagerEntity.getX(), villagerEntity.getY(), villagerEntity.getZ(), villagerEntity.getYaw(), villagerEntity.getPitch());
            int i = GuardEntity.getRandomTypeForBiome(guard.getWorld(), guard.getBlockPos());
            guard.setGuardVariant(i);
            guard.setPersistent();
            guard.setCustomName(villagerEntity.getCustomName());
            guard.setCustomNameVisible(villagerEntity.isCustomNameVisible());
        }
        guard.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
        guard.equipStack(EquipmentSlot.MAINHAND, itemstack.copy());
        guard.guardInventory.setStack(5, itemstack.copy());
        GuardConversionHelper.applyStandardEquipmentDropChances(guard);
        world.spawnEntity(guard);
        if (world instanceof ServerWorld serverWorld) {
            VillageGuardStandManager.handleGuardSpawn(serverWorld, guard, villagerEntity);
        }
        GuardConversionHelper.cleanupVillagerAfterConversion(villagerEntity);
    }

    public static boolean hotvChecker(PlayerEntity player, GuardEntity guard) {
        return player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && GuardVillagersConfig.giveGuardStuffHotv
                || !GuardVillagersConfig.giveGuardStuffHotv || guard.getPlayerEntityReputation(player) > GuardVillagersConfig.reputationRequirement && !player.getWorld().isClient();
    }
}

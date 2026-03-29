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
import dev.sterner.guardvillagers.common.util.VillagerBellTracker;
import dev.sterner.guardvillagers.common.util.VillagerBellTracker.BellVillageReport;
import dev.sterner.guardvillagers.common.util.VillageBellChestPlacementHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.GuardConversionHelper;
import dev.sterner.guardvillagers.common.villager.LumberjackPopulationBalancingService;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.TypeFilter;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import net.minecraft.world.spawner.SpecialSpawner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class GuardVillagers implements ModInitializer {
    public static final String MODID = "guardvillagers";
    private static final Logger LOGGER = LoggerFactory.getLogger(GuardVillagers.class);
    private static final Map<RegistryKey<World>, Long> LAST_CONVERSION_EXECUTION_TICK = new HashMap<>();
    private static final long RESERVATION_RECONCILIATION_INTERVAL_TICKS = 300L;

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
            VillagerConversionCandidateIndex.markCandidatesInChunk(world, chunk.getPos().x, chunk.getPos().z);
            JobBlockPairingHelper.onChunkLoaded(world, chunk);
        });

        ServerWorldEvents.LOAD.register((server, world) -> {
            JobBlockPairingHelper.refreshWorldPairings(world);
            VillageBellChestPlacementHelper.reconcileWorldBellChestMappings(world);
            reconcileConvertedWorkerReservations(world, "world-load");
            RecipeDemandIndex.forWorld(world);
        });
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            LAST_CONVERSION_EXECUTION_TICK.remove(world.getRegistryKey());
            LumberjackPopulationBalancingService.onWorldUnload(world.getRegistryKey());
            RecipeDemandIndex.clearWorld(world);
            JobBlockPairingHelper.onWorldUnload(world);
            ProfessionDefinitions.onWorldUnload(world);
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
                LumberjackPopulationBalancingService.tick(world);
                VillageLumberjackSpawnManager.tick(world);
                VillagePenRegistry.tick(world);
                runConversionHooksOnSchedule(world);
                if (world.getTime() % RESERVATION_RECONCILIATION_INTERVAL_TICKS == 0L) {
                    reconcileConvertedWorkerReservations(world, "scheduled");
                }
                JobBlockPairingHelper.runBackgroundCatchUp(world);
            }
            TakeJobSiteInjectDiagnostics.warnIfInjectMissing(server.getWorlds());
        });
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

    private void runConversionHooksOnSchedule(ServerWorld world) {
        long worldTick = world.getTime();
        long executionInterval = Math.max(20, GuardVillagersConfig.villagerConversionExecutionIntervalTicks);
        if (worldTick % executionInterval != 0L) {
            return;
        }

        RegistryKey<World> worldKey = world.getRegistryKey();
        long lastRunTick = LAST_CONVERSION_EXECUTION_TICK.getOrDefault(worldKey, Long.MIN_VALUE);
        if (worldTick - lastRunTick < executionInterval) {
            return;
        }

        LAST_CONVERSION_EXECUTION_TICK.put(worldKey, worldTick);
        ProfessionDefinitions.runConversionHooks(world);
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

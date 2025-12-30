package dev.sterner.guardvillagers;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.handler.JobBlockPlacementHandler;
import dev.sterner.guardvillagers.common.network.GuardData;
import dev.sterner.guardvillagers.common.network.GuardFollowPacket;
import dev.sterner.guardvillagers.common.network.GuardPatrolPacket;
import dev.sterner.guardvillagers.common.screenhandler.GuardVillagerScreenHandler;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillagerBellTracker;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehaviors;
import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BellBlock;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.brain.MemoryModuleType;
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
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class GuardVillagers implements ModInitializer {
    public static final String MODID = "guardvillagers";

    public static final ScreenHandlerType<GuardVillagerScreenHandler> GUARD_SCREEN_HANDLER =
            new ExtendedScreenHandlerType<>((syncId, inventory, data) -> new GuardVillagerScreenHandler(syncId, inventory, data), GuardData.PACKET_CODEC);

    public static final EntityType<GuardEntity> GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, GuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item GUARD_SPAWN_EGG = new SpawnEggItem(GUARD_VILLAGER, 5651507, 8412749, new Item.Settings());

    public static final EntityType<AxeGuardEntity> AXE_GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("axe_guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, AxeGuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item AXE_GUARD_SPAWN_EGG = new SpawnEggItem(AXE_GUARD_VILLAGER, 5651507, 9477598, new Item.Settings());

    public static Hand getHandWith(LivingEntity livingEntity, Predicate<Item> itemPredicate) {
        return itemPredicate.test(livingEntity.getMainHandStack().getItem()) ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    public static SoundEvent GUARD_AMBIENT = SoundEvent.of(id( "entity.guard.ambient"));
    public static SoundEvent GUARD_HURT = SoundEvent.of(id( "entity.guard.hurt"));
    public static SoundEvent GUARD_DEATH = SoundEvent.of(id("entity.guard.death"));

    public static Identifier id(String name){
        return Identifier.of(MODID, name);
    }

    @Override
    public void onInitialize() {
        MidnightConfig.init(MODID, GuardVillagersConfig.class);
        FabricDefaultAttributeRegistry.register(GUARD_VILLAGER, GuardEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(AXE_GUARD_VILLAGER, GuardEntity.createAttributes());
        VillagerProfessionBehaviors.register();

        Registry.register(Registries.ITEM, id("guard_spawn_egg"), GUARD_SPAWN_EGG);
        Registry.register(Registries.ITEM, id("axe_guard_spawn_egg"), AXE_GUARD_SPAWN_EGG);
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
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onDamage);
        UseEntityCallback.EVENT.register(this::villagerConvert);
        JobBlockPlacementHandler.register();
        UseItemCallback.EVENT.register(this::onUseItem);
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) {
                BlockPos blockPos = hitResult.getBlockPos();
                if (world.getBlockState(blockPos).getBlock() instanceof BellBlock && world instanceof ServerWorld serverWorld) {
                    VillagerBellTracker.logBellVillagerStats(serverWorld, blockPos);
                    VillagerBellTracker.directEmployedVillagersAndGuardsToStations(serverWorld, blockPos);
                }
            }
            return ActionResult.PASS;
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof VillagerEntity villagerEntity) {
                if (world instanceof ServerWorld serverWorld) {
                    JobBlockPairingHelper.refreshVillagerPairings(serverWorld, villagerEntity);
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
        });

        ServerWorldEvents.LOAD.register((server, world) -> JobBlockPairingHelper.refreshWorldPairings(world));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerWorld world : server.getWorlds()) {
                for (PlayerEntity player : world.getPlayers()) {
                    VillageGuardStandManager.handlePlayerNearby(world, player);
                }
                VillagerBellTracker.tickVillagerReports(world);
            }
        });
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
        guard.copyPositionAndRotation(villagerEntity);
        guard.headYaw = villagerEntity.headYaw;
        guard.refreshPositionAndAngles(villagerEntity.getX(), villagerEntity.getY(), villagerEntity.getZ(), villagerEntity.getYaw(), villagerEntity.getPitch());
        guard.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
        guard.equipStack(EquipmentSlot.MAINHAND, itemstack.copy());
        guard.guardInventory.setStack(5, itemstack.copy());

        int i = GuardEntity.getRandomTypeForBiome(guard.getWorld(), guard.getBlockPos());
        guard.setGuardVariant(i);
        guard.setPersistent();
        guard.setCustomName(villagerEntity.getCustomName());
        guard.setCustomNameVisible(villagerEntity.isCustomNameVisible());
        guard.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);
        world.spawnEntity(guard);
        if (world instanceof ServerWorld serverWorld) {
            VillageGuardStandManager.handleGuardSpawn(serverWorld, guard, villagerEntity);
        }
        villagerEntity.releaseTicketFor(MemoryModuleType.HOME);
        villagerEntity.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villagerEntity.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villagerEntity.discard();
    }

    public static boolean hotvChecker(PlayerEntity player, GuardEntity guard) {
        return player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && GuardVillagersConfig.giveGuardStuffHotv
                || !GuardVillagersConfig.giveGuardStuffHotv || guard.getPlayerEntityReputation(player) > GuardVillagersConfig.reputationRequirement && !player.getWorld().isClient();
    }
}

package dev.sterner.guardvillagers.common.handler;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.SpecialModifier;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehaviorRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.item.ArmorStandItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.Optional;

public final class JobBlockPlacementHandler {
    private static final double ARMOR_STAND_PAIRING_RANGE = JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE;

    private JobBlockPlacementHandler() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(JobBlockPlacementHandler::onUseBlock);
    }

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return ActionResult.PASS;
        }

        ItemStack stack = player.getStackInHand(hand);
        boolean isArmorStand = stack.getItem() instanceof ArmorStandItem;
        boolean isPairingBlockItem = stack.getItem() instanceof BlockItem blockItem && JobBlockPairingHelper.isPairingBlock(blockItem.getBlock());
        boolean isCraftingTableItem = stack.getItem() instanceof BlockItem blockItem && JobBlockPairingHelper.isCraftingTable(blockItem.getBlock());
        boolean isBannerItem = stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock().getDefaultState().isIn(BlockTags.BANNERS);
        boolean isSpecialModifierItem = stack.getItem() instanceof BlockItem blockItem && JobBlockPairingHelper.isSpecialModifierBlock(blockItem.getBlock());

        if (!isArmorStand && !isPairingBlockItem && !isCraftingTableItem && !isSpecialModifierItem && !isBannerItem) {
            return ActionResult.PASS;
        }

        ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, stack, hitResult);
        BlockPos placementPos = serverWorld.getBlockState(placementContext.getBlockPos()).canReplace(placementContext) ? placementContext.getBlockPos() : placementContext.getBlockPos().offset(placementContext.getSide());

        serverWorld.getServer().execute(() -> handlePlacement(serverWorld, placementPos, isPairingBlockItem, isCraftingTableItem, isSpecialModifierItem, isBannerItem, isArmorStand));
        return ActionResult.PASS;
    }

    private static void handlePlacement(ServerWorld serverWorld, BlockPos placementPos, boolean checkPairingBlocks, boolean checkCraftingTable, boolean checkSpecialModifier, boolean checkBanner, boolean checkArmorStand) {
        if (checkPairingBlocks) {
            BlockState placedState = serverWorld.getBlockState(placementPos);
            if (JobBlockPairingHelper.isPairingBlock(placedState)) {
                JobBlockPairingHelper.handlePairingBlockPlacement(serverWorld, placementPos, placedState);
            }
        }

        if (checkCraftingTable) {
            BlockState placedState = serverWorld.getBlockState(placementPos);
            if (JobBlockPairingHelper.isCraftingTable(placedState)) {
                JobBlockPairingHelper.handleCraftingTablePlacement(serverWorld, placementPos);
            }
        }

        if (checkSpecialModifier) {
            BlockState placedState = serverWorld.getBlockState(placementPos);
            if (JobBlockPairingHelper.isSpecialModifierBlock(placedState.getBlock())) {
                JobBlockPairingHelper.handleSpecialModifierPlacement(serverWorld, placementPos, placedState);
                applySpecialModifierToNearbyGuards(serverWorld, placementPos, placedState);
            }
        }

        if (checkBanner) {
            BlockState placedState = serverWorld.getBlockState(placementPos);
            if (placedState.isIn(BlockTags.BANNERS)) {
                JobBlockPairingHelper.handleBannerPlacement(serverWorld, placementPos, placedState);
            }
        }

        if (checkArmorStand) {
            tryConvertVillagerWithArmorStand(serverWorld, placementPos);
        }
    }

    private static void tryConvertVillagerWithArmorStand(ServerWorld world, BlockPos placementPos) {
        boolean armorStandPlaced = !world.getEntitiesByClass(ArmorStandEntity.class, new Box(placementPos).expand(0.75D), Entity::isAlive).isEmpty();
        if (!armorStandPlaced) {
            return;
        }

        Optional<VillagerEntity> nearestVillager = findNearestUnemployedVillager(world, placementPos);
        if (nearestVillager.isEmpty()) {
            return;
        }

        VillagerEntity villager = nearestVillager.get();
        JobBlockPairingHelper.playPairingAnimation(world, placementPos, villager, placementPos);
        convertVillagerToGuard(world, villager);
    }

    private static Optional<VillagerEntity> findNearestUnemployedVillager(ServerWorld world, BlockPos placementPos) {
        return world.getEntitiesByClass(VillagerEntity.class, new Box(placementPos).expand(ARMOR_STAND_PAIRING_RANGE), JobBlockPlacementHandler::isUnemployedVillager)
                .stream()
                .min(Comparator.comparingDouble(v -> v.squaredDistanceTo(Vec3d.ofCenter(placementPos))));
    }

    private static boolean isUnemployedVillager(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession == VillagerProfession.NONE && !villager.isBaby();
    }

    private static void convertVillagerToGuard(ServerWorld world, VillagerEntity villager) {
        GuardEntity guard = GuardVillagers.GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        guard.setConvertedFromArmorStand(true);
        guard.spawnWithArmor = true;
        guard.initialize(world, world.getLocalDifficulty(villager.getBlockPos()), SpawnReason.CONVERSION, null);
        guard.refreshPositionAndAngles(villager.getX(), villager.getY(), villager.getZ(), villager.getYaw(), villager.getPitch());
        guard.headYaw = villager.headYaw;

        int variant = GuardEntity.getRandomTypeForBiome(guard.getWorld(), guard.getBlockPos());
        guard.setGuardVariant(variant);
        guard.setPersistent();
        guard.setCustomName(villager.getCustomName());
        guard.setCustomNameVisible(villager.isCustomNameVisible());
        guard.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);
        guard.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
        applySpecialModifierFromNearbyBlocks(world, guard);

        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }

    private static void applySpecialModifierToNearbyGuards(ServerWorld world, BlockPos placedPos, BlockState placedState) {
        Optional<SpecialModifier> modifier = VillagerProfessionBehaviorRegistry.getSpecialModifier(placedState.getBlock());
        if (modifier.isEmpty()) {
            return;
        }

        double range = modifier.get().range();
        Box searchBox = new Box(placedPos).expand(range);
        for (GuardEntity guard : world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive)) {
            if (guard.isConvertedFromArmorStand()) {
                applySpecialModifierToGuard(guard, modifier.get().block());
            }
        }
    }

    private static void applySpecialModifierFromNearbyBlocks(ServerWorld world, GuardEntity guard) {
        applyModifierFromNearbyBlocks(world, guard, GuardVillagers.GUARD_STAND_MODIFIER);
        applyModifierFromNearbyBlocks(world, guard, GuardVillagers.GUARD_STAND_ANCHOR);
    }

    private static void applyModifierFromNearbyBlocks(ServerWorld world, GuardEntity guard, Block modifierBlock) {
        Optional<SpecialModifier> modifier = VillagerProfessionBehaviorRegistry.getSpecialModifier(modifierBlock);
        if (modifier.isEmpty()) {
            return;
        }

        double range = modifier.get().range();
        int checkRange = (int) Math.ceil(range);
        BlockPos guardPos = guard.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(guardPos.add(-checkRange, -checkRange, -checkRange), guardPos.add(checkRange, checkRange, checkRange))) {
            if (!guardPos.isWithinDistance(pos, range)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(modifier.get().block())) {
                applySpecialModifierToGuard(guard, modifier.get().block());
                return;
            }
        }
    }

    private static void applySpecialModifierToGuard(GuardEntity guard, Block modifierBlock) {
        if (modifierBlock == GuardVillagers.GUARD_STAND_MODIFIER) {
            guard.setStandCustomizationEnabled(true);
        } else if (modifierBlock == GuardVillagers.GUARD_STAND_ANCHOR) {
            guard.setStandAnchorEnabled(true);
        }
    }
}

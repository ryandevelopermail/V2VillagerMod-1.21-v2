package dev.sterner.guardvillagers.common.util;

import com.google.common.collect.Sets;
import dev.sterner.guardvillagers.common.villager.SpecialModifier;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehaviorRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WallBannerBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import dev.sterner.guardvillagers.common.villager.FarmerBannerTracker;
import net.minecraft.world.event.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class JobBlockPairingHelper {
    public static final double JOB_BLOCK_PAIRING_RANGE = 3.0D;
    private static final double NEARBY_VILLAGER_SCAN_RANGE = 8.0D;
    private static final double FARMER_BANNER_PAIR_RANGE = 500.0D;
    private static final Set<Block> PAIRING_BLOCKS = Sets.newIdentityHashSet();
    private static final Logger LOGGER = LoggerFactory.getLogger(JobBlockPairingHelper.class);

    static {
        registerPairingBlock(Blocks.CHEST);
        registerPairingBlock(Blocks.TRAPPED_CHEST);
    }

    private JobBlockPairingHelper() {
    }

    public static void registerPairingBlock(Block block) {
        PAIRING_BLOCKS.add(block);
    }

    public static boolean isPairingBlock(BlockState state) {
        return isPairingBlock(state.getBlock());
    }

    public static boolean isPairingBlock(Block block) {
        return PAIRING_BLOCKS.contains(block);
    }

    public static boolean isSpecialModifierBlock(Block block) {
        return VillagerProfessionBehaviorRegistry.isSpecialModifierBlock(block);
    }

    public static void handlePairingBlockPlacement(ServerWorld world, BlockPos placedPos, BlockState placedState) {
        if (!isPairingBlock(placedState)) {
            return;
        }

        world.getEntitiesByClass(VillagerEntity.class, new Box(placedPos).expand(NEARBY_VILLAGER_SCAN_RANGE), JobBlockPairingHelper::isEmployedVillager)
                .forEach(villager -> tryPlayPairingAnimation(world, villager, placedPos));
    }

    public static void handleCraftingTablePlacement(ServerWorld world, BlockPos placedPos) {
        world.getEntitiesByClass(VillagerEntity.class, new Box(placedPos).expand(NEARBY_VILLAGER_SCAN_RANGE), JobBlockPairingHelper::isEmployedVillager)
                .forEach(villager -> tryPlayPairingAnimationWithCrafting(world, villager, placedPos));
    }

    public static void handleSpecialModifierPlacement(ServerWorld world, BlockPos placedPos, BlockState placedState) {
        Optional<SpecialModifier> modifier = VillagerProfessionBehaviorRegistry.getSpecialModifier(placedState.getBlock());
        if (modifier.isEmpty()) {
            return;
        }

        world.getEntitiesByClass(VillagerEntity.class, new Box(placedPos).expand(NEARBY_VILLAGER_SCAN_RANGE), JobBlockPairingHelper::isEmployedVillager)
                .forEach(villager -> tryPlayPairingAnimationWithSpecialModifier(world, villager, placedPos, modifier.get()));
    }

    private static void tryPlayPairingAnimation(ServerWorld world, VillagerEntity villager, BlockPos placedPos) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        if (globalPos.pos().isWithinDistance(placedPos, JOB_BLOCK_PAIRING_RANGE)) {
            playPairingAnimation(world, placedPos, villager, globalPos.pos());
            VillagerProfessionBehaviorRegistry.notifyChestPaired(world, villager, globalPos.pos(), placedPos);
        }
    }

    private static void tryPlayPairingAnimationWithCrafting(ServerWorld world, VillagerEntity villager, BlockPos placedPos) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        BlockPos jobPos = globalPos.pos();
        if (!jobPos.isWithinDistance(placedPos, JOB_BLOCK_PAIRING_RANGE)) {
            return;
        }

        Optional<BlockPos> nearbyChest = findNearbyChest(world, jobPos);
        if (nearbyChest.isEmpty()) {
            return;
        }

        if (!nearbyChest.get().isWithinDistance(placedPos, JOB_BLOCK_PAIRING_RANGE)) {
            return;
        }

        playPairingAnimation(world, placedPos, villager, jobPos);
        VillagerProfessionBehaviorRegistry.notifyCraftingTablePaired(world, villager, jobPos, nearbyChest.get(), placedPos);
    }

    private static void tryPlayPairingAnimationWithSpecialModifier(ServerWorld world, VillagerEntity villager, BlockPos placedPos, SpecialModifier modifier) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        BlockPos jobPos = globalPos.pos();
        if (!jobPos.isWithinDistance(placedPos, modifier.range())) {
            return;
        }

        Optional<BlockPos> nearbyChest = findNearbyChest(world, jobPos);
        if (nearbyChest.isEmpty()) {
            return;
        }

        if (!nearbyChest.get().isWithinDistance(placedPos, modifier.range())) {
            return;
        }

        playPairingAnimation(world, placedPos, villager, jobPos);
        VillagerProfessionBehaviorRegistry.notifySpecialModifierPaired(world, villager, jobPos, nearbyChest.get(), modifier, placedPos);
    }

    public static void handleBannerPlacement(ServerWorld world, BlockPos bannerPos, BlockState bannerState) {
        if (!isBannerOnFence(world, bannerPos, bannerState)) {
            return;
        }

        double range = FARMER_BANNER_PAIR_RANGE;
        int pairedCount = 0;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(bannerPos).expand(range), villager -> villager.isAlive() && villager.getVillagerData().getProfession() == VillagerProfession.FARMER)) {
            if (pairFarmerWithBanner(world, villager, bannerPos)) {
                pairedCount++;
            }
        }

        LOGGER.info("Banner {} paired with {} Farmer(s)", bannerPos.toShortString(), pairedCount);
    }

    public static void refreshVillagerPairings(ServerWorld world, VillagerEntity villager) {
        if (!isEmployedVillager(villager)) {
            return;
        }

        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return;
        }

        BlockPos jobPos = globalPos.pos();
        Optional<BlockPos> nearbyChest = findNearbyChest(world, jobPos);
        nearbyChest.ifPresent(chestPos -> VillagerProfessionBehaviorRegistry.notifyChestPaired(world, villager, jobPos, chestPos));

        if (nearbyChest.isPresent()) {
            Optional<BlockPos> craftingTablePos = findNearbyCraftingTable(world, jobPos);
            craftingTablePos.ifPresent(pos -> VillagerProfessionBehaviorRegistry.notifyCraftingTablePaired(world, villager, jobPos, nearbyChest.get(), pos));
        }

        if (villager.getVillagerData().getProfession() == VillagerProfession.FARMER) {
            Optional<BlockPos> bannerPos = findNearbyBanner(world, jobPos, 16);
            bannerPos.ifPresent(pos -> {
                if (pairFarmerWithBanner(world, villager, pos)) {
                    LOGGER.info("Banner {} paired with Farmer {} on refresh", pos.toShortString(), villager.getUuidAsString());
                }
            });
        }
    }

    private static boolean pairFarmerWithBanner(ServerWorld world, VillagerEntity villager, BlockPos bannerPos) {
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return false;
        }

        GlobalPos globalPos = jobSite.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return false;
        }

        BlockPos jobPos = globalPos.pos();
        if (villager.squaredDistanceTo(bannerPos.getX() + 0.5D, bannerPos.getY() + 0.5D, bannerPos.getZ() + 0.5D) > FARMER_BANNER_PAIR_RANGE * FARMER_BANNER_PAIR_RANGE) {
            return false;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.COMPOSTER)) {
            return false;
        }

        if (findNearbyChest(world, jobPos).isEmpty()) {
            return false;
        }

        playPairingAnimation(world, bannerPos, villager, jobPos);
        FarmerBannerTracker.setBanner(villager, bannerPos);
        return true;
    }

    private static boolean isBannerOnFence(ServerWorld world, BlockPos bannerPos, BlockState bannerState) {
        if (getBannerFenceBase(world, bannerPos, bannerState) != null) {
            return true;
        }
        return isInsideFencePen(world, bannerPos);
    }

    private static BlockPos getBannerFenceBase(ServerWorld world, BlockPos bannerPos, BlockState bannerState) {
        if (bannerState.getBlock() instanceof WallBannerBlock && bannerState.contains(WallBannerBlock.FACING)) {
            Direction facing = bannerState.get(WallBannerBlock.FACING);
            BlockPos attachedPos = bannerPos.offset(facing.getOpposite());
            BlockState attachedState = world.getBlockState(attachedPos);
            if (attachedState.getBlock() instanceof FenceBlock || attachedState.getBlock() instanceof FenceGateBlock) {
                return attachedPos;
            }
        }

        BlockState below = world.getBlockState(bannerPos.down());
        if (below.getBlock() instanceof FenceBlock || below.getBlock() instanceof FenceGateBlock) {
            return bannerPos.down();
        }

        return null;
    }

    private static boolean isInsideFencePen(ServerWorld world, BlockPos bannerPos) {
        int maxDistance = 16;
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (!hasFenceInDirection(world, bannerPos, direction, maxDistance)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFenceInDirection(ServerWorld world, BlockPos start, Direction direction, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos pos = start.offset(direction, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return true;
            }
        }
        return false;
    }

    private static Optional<BlockPos> findNearbyCraftingTable(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (center.isWithinDistance(checkPos, JOB_BLOCK_PAIRING_RANGE) && isCraftingTable(world.getBlockState(checkPos))) {
                return Optional.of(checkPos.toImmutable());
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findNearbyBanner(ServerWorld world, BlockPos center, int range) {
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            BlockState state = world.getBlockState(checkPos);
            if (!state.isIn(BlockTags.BANNERS)) {
                continue;
            }
            if (isBannerOnFence(world, checkPos, state)) {
                return Optional.of(checkPos.toImmutable());
            }
        }
        return Optional.empty();
    }

    public static void playPairingAnimation(ServerWorld world, BlockPos blockPos, LivingEntity villager, BlockPos jobPos) {
        if (villager instanceof VillagerEntity villagerEntity) {
            VillagerProfession profession = villagerEntity.getVillagerData().getProfession();
            Identifier professionId = Registries.VILLAGER_PROFESSION.getId(profession);
            Identifier blockId = Registries.BLOCK.getId(world.getBlockState(blockPos).getBlock());
            LOGGER.info("{} paired with {} at [{}] - {} ID: {}",
                    professionId,
                    blockId,
                    blockPos.toShortString(),
                    professionId,
                    villager.getId());
        }

        spawnHappyParticles(world, blockPos);
        spawnHappyParticles(world, jobPos);
        spawnHappyParticles(world, villager);

        world.playSound(null, blockPos, SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.BLOCKS, 0.75F, 1.0F);
        world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 0.85F, 1.0F);
        world.emitGameEvent(villager, GameEvent.BLOCK_CHANGE, blockPos);
    }

    private static boolean isEmployedVillager(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT && !villager.isBaby();
    }

    private static void spawnHappyParticles(ServerWorld world, BlockPos pos) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 12, 0.35D, 0.35D, 0.35D, 0.0D);
    }

    private static void spawnHappyParticles(ServerWorld world, LivingEntity entity) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, entity.getX(), entity.getBodyY(0.5D), entity.getZ(), 12, 0.35D, 0.5D, 0.35D, 0.0D);
    }

    public static boolean isCraftingTable(BlockState state) {
        return isCraftingTable(state.getBlock());
    }

    public static boolean isCraftingTable(Block block) {
        return block == Blocks.CRAFTING_TABLE;
    }

    public static Optional<BlockPos> findNearbyChest(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (center.isWithinDistance(checkPos, JOB_BLOCK_PAIRING_RANGE) && isPairingBlock(world.getBlockState(checkPos))) {
                return Optional.of(checkPos.toImmutable());
            }
        }
        return Optional.empty();
    }
}

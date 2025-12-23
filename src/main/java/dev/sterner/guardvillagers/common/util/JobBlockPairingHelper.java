package dev.sterner.guardvillagers.common.util;

import com.google.common.collect.Sets;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.event.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class JobBlockPairingHelper {
    public static final double JOB_BLOCK_PAIRING_RANGE = 3.0D;
    private static final double NEARBY_VILLAGER_SCAN_RANGE = 8.0D;
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

    public static void handlePairingBlockPlacement(ServerWorld world, BlockPos placedPos, BlockState placedState) {
        if (!isPairingBlock(placedState)) {
            return;
        }

        world.getEntitiesByClass(VillagerEntity.class, new Box(placedPos).expand(NEARBY_VILLAGER_SCAN_RANGE), JobBlockPairingHelper::isEmployedVillager)
                .forEach(villager -> tryPlayPairingAnimation(world, villager, placedPos));
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
        }
    }

    public static void playPairingAnimation(ServerWorld world, BlockPos blockPos, LivingEntity villager, BlockPos jobPos) {
        if (villager instanceof VillagerEntity villagerEntity) {
            VillagerProfession profession = villagerEntity.getVillagerData().getProfession();
            Identifier professionId = Registries.VILLAGER_PROFESSION.getId(profession);
            LOGGER.info("{} paired with chest [{}] - {} ID: {}",
                    professionId,
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
}

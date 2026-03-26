package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;

import java.util.Optional;

public final class QuartermasterPrerequisiteHelper {

    private QuartermasterPrerequisiteHelper() {
    }

    public static Result validate(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos pairedChestPos) {
        if (villager.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN) {
            return Result.invalid();
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.LIBRARIAN, world.getBlockState(jobPos))) {
            return Result.invalid();
        }

        BlockState pairedState = world.getBlockState(pairedChestPos);
        if (!(pairedState.getBlock() instanceof ChestBlock)) {
            return Result.invalid();
        }

        Optional<BlockPos> secondChest = findExpectedSecondChest(world, pairedChestPos, pairedState);
        if (secondChest.isEmpty()) {
            return Result.invalid();
        }

        return new Result(true, secondChest.get());
    }

    private static Optional<BlockPos> findExpectedSecondChest(ServerWorld world, BlockPos chestPos, BlockState state) {
        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) {
            return Optional.empty();
        }

        Direction facing = state.get(ChestBlock.FACING);
        Direction offset = chestType == ChestType.LEFT
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();

        BlockPos secondPos = chestPos.offset(offset).toImmutable();
        BlockState secondState = world.getBlockState(secondPos);
        if (!(secondState.getBlock() instanceof ChestBlock)) {
            return Optional.empty();
        }
        if (secondState.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return Optional.empty();
        }
        if (secondState.get(ChestBlock.FACING) != facing) {
            return Optional.empty();
        }

        return Optional.of(secondPos);
    }

    public record Result(boolean valid, BlockPos secondChestPos) {
        public static Result invalid() {
            return new Result(false, null);
        }
    }
}

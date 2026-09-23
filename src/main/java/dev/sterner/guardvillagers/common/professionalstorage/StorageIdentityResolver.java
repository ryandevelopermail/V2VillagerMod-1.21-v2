package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;

/** Canonicalizes barrels, single chests, and structurally valid normal/trapped double chests. */
public final class StorageIdentityResolver {
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);

    private StorageIdentityResolver() {
    }

    public static Optional<StorageIdentity> resolve(ServerWorld world, BlockPos memberPos) {
        return resolveMembers(world.getRegistryKey(), memberPos, pos -> describe(world.getBlockState(pos)));
    }

    static Optional<StorageIdentity> resolveMembers(
            RegistryKey<World> dimension,
            BlockPos memberPos,
            Function<BlockPos, StorageMember> memberLookup
    ) {
        BlockPos immutableMember = memberPos.toImmutable();
        StorageMember member = memberLookup.apply(immutableMember);
        if (member.kind() == StorageKind.BARREL) {
            return Optional.of(new StorageIdentity(dimension, immutableMember));
        }
        if (!member.kind().isChest()) {
            return Optional.empty();
        }

        ChestType chestType = member.chestType();
        if (chestType == ChestType.SINGLE) {
            return Optional.of(new StorageIdentity(dimension, immutableMember));
        }

        // Yarn 1.21.1 ChestBlock#getFacing(BlockState) returns the direction from this
        // half toward its partner, derived from FACING and CHEST_TYPE.
        Direction partnerDirection = member.partnerDirection();
        BlockPos partnerPos = immutableMember.offset(partnerDirection);
        StorageMember partner = memberLookup.apply(partnerPos);
        if (partner.kind() != member.kind()
                || partner.chestType() != chestType.getOpposite()
                || partner.facing() != member.facing()
                || partner.partnerDirection() != partnerDirection.getOpposite()) {
            return Optional.empty();
        }

        BlockPos canonical = POSITION_ORDER.compare(immutableMember, partnerPos) <= 0
                ? immutableMember
                : partnerPos.toImmutable();
        return Optional.of(new StorageIdentity(dimension, canonical));
    }

    private static StorageMember describe(BlockState state) {
        if (state.isOf(Blocks.BARREL)) {
            return StorageMember.barrel();
        }
        boolean trapped = state.isOf(Blocks.TRAPPED_CHEST);
        if (!state.isOf(Blocks.CHEST) && !trapped) {
            return StorageMember.invalid();
        }
        StorageKind kind = trapped
                ? StorageKind.TRAPPED_CHEST
                : StorageKind.NORMAL_CHEST;
        ChestType type = state.get(ChestBlock.CHEST_TYPE);
        return new StorageMember(
                kind,
                type,
                state.get(ChestBlock.FACING),
                type == ChestType.SINGLE ? null : ChestBlock.getFacing(state));
    }

    enum StorageKind {
        INVALID,
        BARREL,
        NORMAL_CHEST,
        TRAPPED_CHEST;

        boolean isChest() {
            return this == NORMAL_CHEST || this == TRAPPED_CHEST;
        }
    }

    record StorageMember(
            StorageKind kind,
            ChestType chestType,
            Direction facing,
            Direction partnerDirection
    ) {
        static StorageMember invalid() {
            return new StorageMember(StorageKind.INVALID, null, null, null);
        }

        static StorageMember barrel() {
            return new StorageMember(StorageKind.BARREL, null, null, null);
        }

        static StorageMember chest(StorageKind kind, ChestType type, Direction facing) {
            Direction partnerDirection = switch (type) {
                case SINGLE -> null;
                case LEFT -> facing.rotateYClockwise();
                case RIGHT -> facing.rotateYCounterclockwise();
            };
            return new StorageMember(kind, type, facing, partnerDirection);
        }
    }
}

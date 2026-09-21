package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.network.ProfessionalStorageSnapshotPacket;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehaviorRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Delegates the vanilla factory and adds metadata only for persistently paired storage. */
public final class ProfessionalStorageScreenHandlerFactory implements NamedScreenHandlerFactory {
    private final NamedScreenHandlerFactory delegate;
    private final ProfessionalStorageSnapshot snapshot;

    private ProfessionalStorageScreenHandlerFactory(
            NamedScreenHandlerFactory delegate,
            ProfessionalStorageSnapshot snapshot
    ) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    public static NamedScreenHandlerFactory wrap(
            World world,
            BlockPos openedPos,
            NamedScreenHandlerFactory delegate
    ) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return delegate;
        }
        List<ProfessionalStorageResolution> resolutions = ProfessionalStorageRegistry.query(serverWorld, openedPos);
        if (resolutions.isEmpty()) {
            return delegate;
        }
        StorageIdentity storage = StorageIdentityResolver.resolve(serverWorld, openedPos).orElse(null);
        ProfessionalStorageSnapshot.StorageType storageType = storageType(world.getBlockState(openedPos));
        if (storage == null || storageType == null) {
            return delegate;
        }

        boolean preserveCustomTitle = hasCustomName(world, openedPos);
        List<ProfessionalStorageSnapshotFactory.WorkerView> workers = resolutions.stream()
                .map(resolution -> new ProfessionalStorageSnapshotFactory.WorkerView(
                        resolution.pairing().role(),
                        resolution.workerAvailability(),
                        hasConfiguredBehavior(resolution.pairing().role())))
                .toList();
        return ProfessionalStorageSnapshotFactory.create(
                        -1,
                        storage,
                        storageType,
                        workers,
                        delegate.getDisplayName().getString(),
                        preserveCustomTitle,
                        ProfessionalStorageProfileProviders.createTabs(serverWorld, storage, resolutions))
                .<NamedScreenHandlerFactory>map(snapshot ->
                        new ProfessionalStorageScreenHandlerFactory(delegate, snapshot))
                .orElse(delegate);
    }

    @Override
    public Text getDisplayName() {
        return snapshot.customTitlePreserved()
                ? delegate.getDisplayName()
                : Text.literal(snapshot.title());
    }

    @Override
    @Nullable
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        ScreenHandler handler = delegate.createMenu(syncId, playerInventory, player);
        if (handler instanceof GenericContainerScreenHandler && player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(
                    serverPlayer,
                    new ProfessionalStorageSnapshotPacket(snapshot.withSyncId(syncId)));
        }
        return handler;
    }

    @Nullable
    private static ProfessionalStorageSnapshot.StorageType storageType(BlockState state) {
        if (state.isOf(Blocks.BARREL)) {
            return ProfessionalStorageSnapshot.StorageType.BARREL;
        }
        if (state.isOf(Blocks.TRAPPED_CHEST)) {
            return ProfessionalStorageSnapshot.StorageType.TRAPPED_CHEST;
        }
        if (state.isOf(Blocks.CHEST)) {
            return ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST;
        }
        return null;
    }

    private static boolean hasCustomName(World world, BlockPos openedPos) {
        BlockState state = world.getBlockState(openedPos);
        if (isCustomNamedContainer(world, openedPos)) {
            return true;
        }
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != net.minecraft.block.enums.ChestType.SINGLE) {
            return isCustomNamedContainer(world, openedPos.offset(ChestBlock.getFacing(state)));
        }
        return false;
    }

    private static boolean isCustomNamedContainer(World world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof LockableContainerBlockEntity container
                && container.hasCustomName();
    }

    private static boolean hasConfiguredBehavior(ProfessionalRoleId role) {
        if (role.equals(ProfessionalRoleId.BUTCHER_GUARD)
                || role.equals(ProfessionalRoleId.FISHERMAN_GUARD)
                || role.equals(ProfessionalRoleId.MASON_GUARD)
                || role.equals(ProfessionalRoleId.LUMBERJACK)
                || role.equals(ProfessionalRoleId.QUARTERMASTER)) {
            return true;
        }
        VillagerProfession profession = Registries.VILLAGER_PROFESSION.get(role.value());
        return profession != null
                && role.value().equals(Registries.VILLAGER_PROFESSION.getId(profession))
                && VillagerProfessionBehaviorRegistry.containsBehavior(profession);
    }
}

package dev.sterner.guardvillagers.common.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class MasonGuardEntity extends GuardEntity {
    private BlockPos pairedChestPos;
    private BlockPos pairedStonecutterPos;

    public MasonGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setPairedChestPos(BlockPos chestPos) {
        this.pairedChestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public void setPairedStonecutterPos(BlockPos stonecutterPos) {
        this.pairedStonecutterPos = stonecutterPos == null ? null : stonecutterPos.toImmutable();
    }

    public BlockPos getPairedChestPos() {
        return this.pairedChestPos;
    }

    public BlockPos getPairedStonecutterPos() {
        return this.pairedStonecutterPos;
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("PairedChestX")) {
            this.pairedChestPos = new BlockPos(nbt.getInt("PairedChestX"), nbt.getInt("PairedChestY"), nbt.getInt("PairedChestZ"));
        } else {
            this.pairedChestPos = null;
        }
        if (nbt.contains("PairedStonecutterX")) {
            this.pairedStonecutterPos = new BlockPos(nbt.getInt("PairedStonecutterX"), nbt.getInt("PairedStonecutterY"), nbt.getInt("PairedStonecutterZ"));
        } else {
            this.pairedStonecutterPos = null;
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.pairedChestPos != null) {
            nbt.putInt("PairedChestX", this.pairedChestPos.getX());
            nbt.putInt("PairedChestY", this.pairedChestPos.getY());
            nbt.putInt("PairedChestZ", this.pairedChestPos.getZ());
        }
        if (this.pairedStonecutterPos != null) {
            nbt.putInt("PairedStonecutterX", this.pairedStonecutterPos.getX());
            nbt.putInt("PairedStonecutterY", this.pairedStonecutterPos.getY());
            nbt.putInt("PairedStonecutterZ", this.pairedStonecutterPos.getZ());
        }
    }
}

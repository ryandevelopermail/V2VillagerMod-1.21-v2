package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageRow;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ProfessionalStorageSnapshotPacket(ProfessionalStorageSnapshot snapshot) implements CustomPayload {
    public static final Id<ProfessionalStorageSnapshotPacket> ID =
            new Id<>(Identifier.of("guardvillagers", "professional_storage_snapshot"));
    public static final PacketCodec<RegistryByteBuf, ProfessionalStorageSnapshotPacket> PACKET_CODEC =
            PacketCodec.of(ProfessionalStorageSnapshotPacket::encode, ProfessionalStorageSnapshotPacket::decode);

    private static void encode(ProfessionalStorageSnapshotPacket packet, RegistryByteBuf buffer) {
        ProfessionalStorageSnapshot snapshot = packet.snapshot();
        buffer.writeVarInt(snapshot.syncId());
        buffer.writeString(snapshot.dimensionId());
        buffer.writeBlockPos(snapshot.canonicalPos());
        buffer.writeEnumConstant(snapshot.storageType());
        buffer.writeEnumConstant(snapshot.roleState());
        buffer.writeVarInt(snapshot.workerCount());
        buffer.writeString(snapshot.title());
        buffer.writeBoolean(snapshot.customTitlePreserved());
        buffer.writeVarInt(snapshot.rows().size());
        for (ProfessionalStorageRow row : snapshot.rows()) {
            buffer.writeString(row.label());
            buffer.writeString(row.value());
            buffer.writeEnumConstant(row.tone());
        }
    }

    private static ProfessionalStorageSnapshotPacket decode(RegistryByteBuf buffer) {
        int syncId = buffer.readVarInt();
        String dimensionId = buffer.readString();
        BlockPos canonicalPos = buffer.readBlockPos();
        ProfessionalStorageSnapshot.StorageType storageType =
                buffer.readEnumConstant(ProfessionalStorageSnapshot.StorageType.class);
        ProfessionalStorageSnapshot.RoleState roleState =
                buffer.readEnumConstant(ProfessionalStorageSnapshot.RoleState.class);
        int workerCount = buffer.readVarInt();
        String title = buffer.readString();
        boolean customTitlePreserved = buffer.readBoolean();
        int rowCount = buffer.readVarInt();
        if (rowCount < 0 || rowCount > ProfessionalStorageSnapshot.MAX_ROWS) {
            throw new IllegalArgumentException("Invalid professional storage row count: " + rowCount);
        }
        List<ProfessionalStorageRow> rows = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            rows.add(new ProfessionalStorageRow(
                    buffer.readString(),
                    buffer.readString(),
                    buffer.readEnumConstant(ProfessionalStorageRow.Tone.class)));
        }
        return new ProfessionalStorageSnapshotPacket(new ProfessionalStorageSnapshot(
                syncId,
                dimensionId,
                canonicalPos,
                storageType,
                roleState,
                workerCount,
                title,
                customTitlePreserved,
                rows));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageRow;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageTab;
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
        buffer.writeVarInt(snapshot.tabs().size());
        for (ProfessionalStorageTab tab : snapshot.tabs()) {
            buffer.writeString(tab.id());
            buffer.writeString(tab.title());
            buffer.writeVarInt(tab.rows().size());
            for (ProfessionalStorageRow row : tab.rows()) {
                buffer.writeString(row.label());
                buffer.writeString(row.value());
                buffer.writeEnumConstant(row.tone());
            }
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
        int tabCount = buffer.readVarInt();
        if (tabCount < 1 || tabCount > ProfessionalStorageSnapshot.MAX_TABS) {
            throw new IllegalArgumentException("Invalid professional storage tab count: " + tabCount);
        }
        List<ProfessionalStorageTab> tabs = new ArrayList<>(tabCount);
        for (int tabIndex = 0; tabIndex < tabCount; tabIndex++) {
            String tabId = buffer.readString();
            String tabTitle = buffer.readString();
            int rowCount = buffer.readVarInt();
            if (rowCount < 0 || rowCount > ProfessionalStorageSnapshot.MAX_ROWS_PER_TAB) {
                throw new IllegalArgumentException("Invalid professional storage row count: " + rowCount);
            }
            List<ProfessionalStorageRow> rows = new ArrayList<>(rowCount);
            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                rows.add(new ProfessionalStorageRow(
                        buffer.readString(),
                        buffer.readString(),
                        buffer.readEnumConstant(ProfessionalStorageRow.Tone.class)));
            }
            tabs.add(new ProfessionalStorageTab(tabId, tabTitle, rows));
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
                tabs));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageRow;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageTab;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfessionalStorageSnapshotPacketTest {
    @Test
    void packetCodecRoundTripsSnapshot() {
        ProfessionalStorageSnapshot expected = new ProfessionalStorageSnapshot(
                37,
                "minecraft:overworld",
                new BlockPos(-12, 70, 44),
                ProfessionalStorageSnapshot.StorageType.TRAPPED_CHEST,
                ProfessionalStorageSnapshot.RoleState.SAME_ROLE_SHARED,
                2,
                "Farmers' Storage",
                false,
                List.of(
                        new ProfessionalStorageTab("overview", "Overview", List.of(
                                new ProfessionalStorageRow("Status", "Harvesting", ProfessionalStorageRow.Tone.PAIRED),
                                new ProfessionalStorageRow("Hoe available", "Yes", ProfessionalStorageRow.Tone.PAIRED))),
                        new ProfessionalStorageTab("statistics", "Statistics", List.of(
                                new ProfessionalStorageRow("Crops harvested", "17", ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow("Materials crafted", "4", ProfessionalStorageRow.Tone.WARNING)))));
        RegistryByteBuf buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);

        ProfessionalStorageSnapshotPacket.PACKET_CODEC.encode(buffer, new ProfessionalStorageSnapshotPacket(expected));
        ProfessionalStorageSnapshotPacket decoded = ProfessionalStorageSnapshotPacket.PACKET_CODEC.decode(buffer);

        assertEquals(expected, decoded.snapshot());
    }

    @Test
    void packetCodecRejectsTooManyTabsBeforeAllocation() {
        RegistryByteBuf buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        writeHeader(buffer);
        buffer.writeVarInt(ProfessionalStorageSnapshot.MAX_TABS + 1);

        assertThrows(IllegalArgumentException.class,
                () -> ProfessionalStorageSnapshotPacket.PACKET_CODEC.decode(buffer));
    }

    @Test
    void packetCodecRejectsTooManyRowsInOneTabBeforeAllocation() {
        RegistryByteBuf buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        writeHeader(buffer);
        buffer.writeVarInt(1);
        buffer.writeString("overview");
        buffer.writeString("Overview");
        buffer.writeVarInt(ProfessionalStorageSnapshot.MAX_ROWS_PER_TAB + 1);

        assertThrows(IllegalArgumentException.class,
                () -> ProfessionalStorageSnapshotPacket.PACKET_CODEC.decode(buffer));
    }

    private static void writeHeader(RegistryByteBuf buffer) {
        buffer.writeVarInt(1);
        buffer.writeString("minecraft:overworld");
        buffer.writeBlockPos(BlockPos.ORIGIN);
        buffer.writeEnumConstant(ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST);
        buffer.writeEnumConstant(ProfessionalStorageSnapshot.RoleState.SINGLE_WORKER);
        buffer.writeVarInt(1);
        buffer.writeString("Farmer Storage");
        buffer.writeBoolean(false);
    }
}

package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageRow;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                        new ProfessionalStorageRow("Profession", "Farmer", ProfessionalStorageRow.Tone.NORMAL),
                        new ProfessionalStorageRow("Workers", "2", ProfessionalStorageRow.Tone.NORMAL),
                        new ProfessionalStorageRow("Status", "Paired", ProfessionalStorageRow.Tone.PAIRED)));
        RegistryByteBuf buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);

        ProfessionalStorageSnapshotPacket.PACKET_CODEC.encode(buffer, new ProfessionalStorageSnapshotPacket(expected));
        ProfessionalStorageSnapshotPacket decoded = ProfessionalStorageSnapshotPacket.PACKET_CODEC.decode(buffer);

        assertEquals(expected, decoded.snapshot());
    }
}

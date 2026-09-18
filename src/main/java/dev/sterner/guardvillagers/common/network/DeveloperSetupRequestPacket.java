package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupRequest;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record DeveloperSetupRequestPacket(
        int setupType,
        List<DeveloperSetupRequestWireData.ProfessionEntry> professions,
        boolean createPairedChest,
        boolean createCraftingTable,
        boolean createFurnaceSetup,
        boolean createShepherdSupply,
        int inventoryPreset,
        boolean generateMatureTrees,
        int treeCount
) implements CustomPayload {
    private static final int MAX_WIRE_PROFESSION_ENTRIES = 64;
    public static final Id<DeveloperSetupRequestPacket> ID = new Id<>(GuardVillagers.id("developer_setup_request"));
    public static final PacketCodec<RegistryByteBuf, DeveloperSetupRequestPacket> PACKET_CODEC = PacketCodec.ofStatic(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.setupType);
                buffer.writeVarInt(packet.professions.size());
                for (DeveloperSetupRequestWireData.ProfessionEntry profession : packet.professions) {
                    buffer.writeVarInt(profession.professionId());
                    buffer.writeVarInt(profession.quantity());
                }
                buffer.writeBoolean(packet.createPairedChest);
                buffer.writeBoolean(packet.createCraftingTable);
                buffer.writeBoolean(packet.createFurnaceSetup);
                buffer.writeBoolean(packet.createShepherdSupply);
                buffer.writeVarInt(packet.inventoryPreset);
                buffer.writeBoolean(packet.generateMatureTrees);
                buffer.writeVarInt(packet.treeCount);
            },
            buffer -> new DeveloperSetupRequestPacket(
                    buffer.readVarInt(),
                    readProfessionEntries(buffer),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readVarInt()
            )
    );

    public DeveloperSetupRequestPacket(DeveloperSetupRequest request) {
        this(DeveloperSetupRequestWireData.fromRequest(request));
    }

    private DeveloperSetupRequestPacket(DeveloperSetupRequestWireData wireData) {
        this(
                wireData.setupType(),
                wireData.professions(),
                wireData.createPairedChest(),
                wireData.createCraftingTable(),
                wireData.createFurnaceSetup(),
                wireData.createShepherdSupply(),
                wireData.inventoryPreset(),
                wireData.generateMatureTrees(),
                wireData.treeCount()
        );
    }

    public Optional<DeveloperSetupRequest> decodeRequest() {
        return new DeveloperSetupRequestWireData(
                setupType,
                professions,
                createPairedChest,
                createCraftingTable,
                createFurnaceSetup,
                createShepherdSupply,
                inventoryPreset,
                generateMatureTrees,
                treeCount
        ).decodeRequest();
    }

    private static List<DeveloperSetupRequestWireData.ProfessionEntry> readProfessionEntries(RegistryByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_WIRE_PROFESSION_ENTRIES) {
            throw new IllegalArgumentException("Invalid developer profession entry count: " + count);
        }
        List<DeveloperSetupRequestWireData.ProfessionEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new DeveloperSetupRequestWireData.ProfessionEntry(buffer.readVarInt(), buffer.readVarInt()));
        }
        return List.copyOf(entries);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

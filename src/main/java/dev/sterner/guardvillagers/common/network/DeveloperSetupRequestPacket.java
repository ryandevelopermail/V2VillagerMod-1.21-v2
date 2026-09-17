package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.developer.DeveloperProfession;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupRequest;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupType;
import dev.sterner.guardvillagers.common.developer.LumberjackInventoryPreset;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.Optional;

public record DeveloperSetupRequestPacket(
        int setupType,
        int profession,
        boolean createPairedChest,
        boolean createCraftingTable,
        boolean createFurnaceSetup,
        boolean createPenSetup,
        int inventoryPreset,
        boolean generateMatureTrees,
        int treeCount
) implements CustomPayload {
    public static final Id<DeveloperSetupRequestPacket> ID = new Id<>(GuardVillagers.id("developer_setup_request"));
    public static final PacketCodec<RegistryByteBuf, DeveloperSetupRequestPacket> PACKET_CODEC = PacketCodec.ofStatic(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.setupType);
                buffer.writeVarInt(packet.profession);
                buffer.writeBoolean(packet.createPairedChest);
                buffer.writeBoolean(packet.createCraftingTable);
                buffer.writeBoolean(packet.createFurnaceSetup);
                buffer.writeBoolean(packet.createPenSetup);
                buffer.writeVarInt(packet.inventoryPreset);
                buffer.writeBoolean(packet.generateMatureTrees);
                buffer.writeVarInt(packet.treeCount);
            },
            buffer -> new DeveloperSetupRequestPacket(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
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
        this(
                request.setupType().networkId(),
                request.profession().networkId(),
                request.createPairedChest(),
                request.createCraftingTable(),
                request.createFurnaceSetup(),
                request.createPenSetup(),
                request.inventoryPreset().networkId(),
                request.generateMatureTrees(),
                request.treeCount()
        );
    }

    public Optional<DeveloperSetupRequest> decodeRequest() {
        Optional<DeveloperSetupType> decodedType = DeveloperSetupType.fromNetworkId(setupType);
        Optional<DeveloperProfession> decodedProfession = DeveloperProfession.fromNetworkId(profession);
        Optional<LumberjackInventoryPreset> decodedPreset = LumberjackInventoryPreset.fromNetworkId(inventoryPreset);
        if (decodedType.isEmpty() || decodedProfession.isEmpty() || decodedPreset.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DeveloperSetupRequest(
                decodedType.get(),
                decodedProfession.get(),
                createPairedChest,
                createCraftingTable,
                createFurnaceSetup,
                createPenSetup,
                decodedPreset.get(),
                generateMatureTrees,
                treeCount
        ));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

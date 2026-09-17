package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.developer.DeveloperProfession;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupRequest;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.Optional;

public record DeveloperSetupRequestPacket(
        int setupType,
        int profession,
        boolean createPairedChest,
        boolean createCraftingTable,
        boolean generateMatureTrees,
        int treeCount
) implements CustomPayload {
    public static final Id<DeveloperSetupRequestPacket> ID = new Id<>(GuardVillagers.id("developer_setup_request"));
    public static final PacketCodec<RegistryByteBuf, DeveloperSetupRequestPacket> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, DeveloperSetupRequestPacket::setupType,
            PacketCodecs.VAR_INT, DeveloperSetupRequestPacket::profession,
            PacketCodecs.BOOL, DeveloperSetupRequestPacket::createPairedChest,
            PacketCodecs.BOOL, DeveloperSetupRequestPacket::createCraftingTable,
            PacketCodecs.BOOL, DeveloperSetupRequestPacket::generateMatureTrees,
            PacketCodecs.VAR_INT, DeveloperSetupRequestPacket::treeCount,
            DeveloperSetupRequestPacket::new
    );

    public DeveloperSetupRequestPacket(DeveloperSetupRequest request) {
        this(
                request.setupType().networkId(),
                request.profession().networkId(),
                request.createPairedChest(),
                request.createCraftingTable(),
                request.generateMatureTrees(),
                request.treeCount()
        );
    }

    public Optional<DeveloperSetupRequest> decodeRequest() {
        Optional<DeveloperSetupType> decodedType = DeveloperSetupType.fromNetworkId(setupType);
        Optional<DeveloperProfession> decodedProfession = DeveloperProfession.fromNetworkId(profession);
        if (decodedType.isEmpty() || decodedProfession.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DeveloperSetupRequest(
                decodedType.get(),
                decodedProfession.get(),
                createPairedChest,
                createCraftingTable,
                generateMatureTrees,
                treeCount
        ));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

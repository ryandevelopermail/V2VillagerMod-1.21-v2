package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record DeveloperSetupStatusPacket(
        String message,
        int progressPercent,
        boolean finished,
        boolean success
) implements CustomPayload {
    public static final Id<DeveloperSetupStatusPacket> ID = new Id<>(GuardVillagers.id("developer_setup_status"));
    public static final PacketCodec<RegistryByteBuf, DeveloperSetupStatusPacket> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, DeveloperSetupStatusPacket::message,
            PacketCodecs.VAR_INT, DeveloperSetupStatusPacket::progressPercent,
            PacketCodecs.BOOL, DeveloperSetupStatusPacket::finished,
            PacketCodecs.BOOL, DeveloperSetupStatusPacket::success,
            DeveloperSetupStatusPacket::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record OpenDeveloperPanelPacket() implements CustomPayload {
    public static final Id<OpenDeveloperPanelPacket> ID = new Id<>(GuardVillagers.id("open_developer_panel"));
    public static final PacketCodec<RegistryByteBuf, OpenDeveloperPanelPacket> PACKET_CODEC =
            PacketCodec.unit(new OpenDeveloperPanelPacket());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

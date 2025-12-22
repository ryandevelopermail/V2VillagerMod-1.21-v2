package dev.sterner.guardvillagers.common.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

public record GuardData(int guardId) {
    public static final PacketCodec<RegistryByteBuf, GuardData> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER,
            GuardData::guardId,
            GuardData::new
    );
}

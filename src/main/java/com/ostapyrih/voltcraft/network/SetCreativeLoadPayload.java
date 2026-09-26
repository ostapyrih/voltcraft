package com.ostapyrih.voltcraft.network;

import com.ostapyrih.voltcraft.Voltcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * C2S Network payload for direct setpoint entry on Creative Load.
 */
public record SetCreativeLoadPayload(BlockPos pos, double targetValue) implements CustomPayload {

    public static final Id<SetCreativeLoadPayload> ID =
        new Id<>(Identifier.of(Voltcraft.MOD_ID, "set_creative_load"));

    public static final PacketCodec<ByteBuf, SetCreativeLoadPayload> CODEC =
        PacketCodec.tuple(
            BlockPos.PACKET_CODEC, SetCreativeLoadPayload::pos,
            PacketCodecs.DOUBLE, SetCreativeLoadPayload::targetValue,
            SetCreativeLoadPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

package com.ostapyrih.voltcraft.network;

import com.ostapyrih.voltcraft.Voltcraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * C2S Network payload for direct voltage setpoint entry on Creative Generator.
 */
public record SetCreativeGeneratorPayload(BlockPos pos, double voltage) implements CustomPayload {

    public static final Id<SetCreativeGeneratorPayload> ID =
        new Id<>(Identifier.of(Voltcraft.MOD_ID, "set_creative_generator"));

    public static final PacketCodec<ByteBuf, SetCreativeGeneratorPayload> CODEC =
        PacketCodec.tuple(
            BlockPos.PACKET_CODEC, SetCreativeGeneratorPayload::pos,
            PacketCodecs.DOUBLE, SetCreativeGeneratorPayload::voltage,
            SetCreativeGeneratorPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

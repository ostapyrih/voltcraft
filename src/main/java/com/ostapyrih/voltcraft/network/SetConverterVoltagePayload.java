package com.ostapyrih.voltcraft.network;

import com.ostapyrih.voltcraft.Voltcraft;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * C2S Network payload sent by ConverterScreen when the player adjusts target output voltage.
 */
public record SetConverterVoltagePayload(BlockPos pos, double targetVoltage) implements CustomPayload {

    public static final CustomPayload.Id<SetConverterVoltagePayload> ID =
        new CustomPayload.Id<>(Identifier.of(Voltcraft.MOD_ID, "set_converter_voltage"));

    public static final PacketCodec<RegistryByteBuf, SetConverterVoltagePayload> CODEC = PacketCodec.tuple(
        BlockPos.PACKET_CODEC, SetConverterVoltagePayload::pos,
        PacketCodecs.DOUBLE, SetConverterVoltagePayload::targetVoltage,
        SetConverterVoltagePayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

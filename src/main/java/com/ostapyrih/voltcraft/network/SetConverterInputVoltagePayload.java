package com.ostapyrih.voltcraft.network;

import com.ostapyrih.voltcraft.Voltcraft;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * C2S Network payload sent by ConverterScreen when the player selects nominal DC input voltage for inverters.
 */
public record SetConverterInputVoltagePayload(BlockPos pos, double nominalInputVoltage) implements CustomPayload {

    public static final CustomPayload.Id<SetConverterInputVoltagePayload> ID =
        new CustomPayload.Id<>(Identifier.of(Voltcraft.MOD_ID, "set_converter_input_voltage"));

    public static final PacketCodec<RegistryByteBuf, SetConverterInputVoltagePayload> CODEC = PacketCodec.tuple(
        BlockPos.PACKET_CODEC, SetConverterInputVoltagePayload::pos,
        PacketCodecs.DOUBLE, SetConverterInputVoltagePayload::nominalInputVoltage,
        SetConverterInputVoltagePayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

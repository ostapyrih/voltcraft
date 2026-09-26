package com.ostapyrih.voltcraft.network;

import com.ostapyrih.voltcraft.Voltcraft;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Client-to-Server network packet requesting an immediate manual protection trip reset
 * on a power converter block entity (Inverter, MPPT, DC-DC Converter, etc.).
 */
public record ResetConverterTripPayload(BlockPos pos) implements CustomPayload {
    public static final CustomPayload.Id<ResetConverterTripPayload> ID =
        new CustomPayload.Id<>(Identifier.of(Voltcraft.MOD_ID, "reset_converter_trip"));

    public static final PacketCodec<RegistryByteBuf, ResetConverterTripPayload> CODEC = PacketCodec.tuple(
        BlockPos.PACKET_CODEC, ResetConverterTripPayload::pos,
        ResetConverterTripPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

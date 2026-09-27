package com.ostapyrih.voltcraft.client;

import com.ostapyrih.voltcraft.client.screen.ConverterScreen;
import com.ostapyrih.voltcraft.client.screen.CreativeGeneratorScreen;
import com.ostapyrih.voltcraft.client.screen.CreativeLoadScreen;
import com.ostapyrih.voltcraft.client.screen.EuConverterScreen;
import com.ostapyrih.voltcraft.item.battery.BatteryCellItem;
import com.ostapyrih.voltcraft.screen.VoltcraftScreenHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public class VoltcraftClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HandledScreens.register(VoltcraftScreenHandlers.CONVERTER_SCREEN_HANDLER, ConverterScreen::new);
        HandledScreens.register(VoltcraftScreenHandlers.EU_CONVERTER_SCREEN_HANDLER, EuConverterScreen::new);
        HandledScreens.register(VoltcraftScreenHandlers.CREATIVE_GENERATOR_SCREEN_HANDLER, CreativeGeneratorScreen::new);
        HandledScreens.register(VoltcraftScreenHandlers.CREATIVE_LOAD_SCREEN_HANDLER, CreativeLoadScreen::new);
    }
}

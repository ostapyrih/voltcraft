package com.ostapyrih.voltcraft.client.screen;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.network.ResetConverterTripPayload;
import com.ostapyrih.voltcraft.network.SetConverterInputVoltagePayload;
import com.ostapyrih.voltcraft.network.SetConverterVoltagePayload;
import com.ostapyrih.voltcraft.screen.handler.ConverterScreenHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * Modern industrial instrument dashboard screen for power converters, inverters, transformers, rectifiers, and MPPT chargers.
 * Wide ergonomic HUD layout with live telemetry panels, discrete voltage presets for DC-DC converters,
 * nominal DC input selection for inverters, battery bank presets for MPPT charge controllers, and protection reset control.
 */
public class ConverterScreen extends HandledScreen<ConverterScreenHandler> {

    private ButtonWidget preset5Btn;
    private ButtonWidget preset12Btn;
    private ButtonWidget preset24Btn;
    private ButtonWidget preset48Btn;

    private ButtonWidget in12Btn;
    private ButtonWidget in24Btn;
    private ButtonWidget in48Btn;

    private ButtonWidget resetTripButton;

    private int currentTypeKind = -1;
    private boolean controlsInitialized = false;

    public ConverterScreen(ConverterScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 300;
        this.backgroundHeight = 208;
    }

    @Override
    protected void init() {
        super.init();
        rebuildControls();
    }

    private void rebuildControls() {
        this.clearChildren();
        int left = (this.width - this.backgroundWidth) / 2;
        int top = (this.height - this.backgroundHeight) / 2;

        int type = this.handler.getTypeKind();
        this.currentTypeKind = type;

        if (type == 0) {
            // DC-DC Converters: Exclusive preset voltage selections (5V, 12V, 24V, 48V)
            this.preset5Btn = ButtonWidget.builder(Text.literal("5V"), b -> setPresetVoltage(5.0, ConverterScreenHandler.BUTTON_PRESET_5V))
                .dimensions(left + 24, top + 128, 58, 22).build();
            this.addDrawableChild(this.preset5Btn);

            this.preset12Btn = ButtonWidget.builder(Text.literal("12V"), b -> setPresetVoltage(12.0, ConverterScreenHandler.BUTTON_PRESET_12V))
                .dimensions(left + 88, top + 128, 58, 22).build();
            this.addDrawableChild(this.preset12Btn);

            this.preset24Btn = ButtonWidget.builder(Text.literal("24V"), b -> setPresetVoltage(24.0, ConverterScreenHandler.BUTTON_PRESET_24V))
                .dimensions(left + 152, top + 128, 58, 22).build();
            this.addDrawableChild(this.preset24Btn);

            this.preset48Btn = ButtonWidget.builder(Text.literal("48V"), b -> setPresetVoltage(48.0, ConverterScreenHandler.BUTTON_PRESET_48V))
                .dimensions(left + 216, top + 128, 58, 22).build();
            this.addDrawableChild(this.preset48Btn);
        } else if (type == 1) {
            // DC-AC Inverters: Nominal DC input standard selection (12V, 24V, 48V)
            this.in12Btn = ButtonWidget.builder(Text.literal("12V In"), b -> setInverterInput(12.0, ConverterScreenHandler.BUTTON_INVERTER_IN_12V))
                .dimensions(left + 30, top + 128, 74, 22).build();
            this.addDrawableChild(this.in12Btn);

            this.in24Btn = ButtonWidget.builder(Text.literal("24V In"), b -> setInverterInput(24.0, ConverterScreenHandler.BUTTON_INVERTER_IN_24V))
                .dimensions(left + 112, top + 128, 74, 22).build();
            this.addDrawableChild(this.in24Btn);

            this.in48Btn = ButtonWidget.builder(Text.literal("48V In"), b -> setInverterInput(48.0, ConverterScreenHandler.BUTTON_INVERTER_IN_48V))
                .dimensions(left + 194, top + 128, 74, 22).build();
            this.addDrawableChild(this.in48Btn);
        } else if (type == ConverterScreenHandler.TYPE_CHARGE_CONTROLLER) {
            // MPPT Solar Charge Controller: Battery Bank presets (12V Bank, 24V Bank, 48V Bank)
            this.preset12Btn = ButtonWidget.builder(Text.literal("12V Bank"), b -> setPresetVoltage(12.0, ConverterScreenHandler.BUTTON_PRESET_12V))
                .dimensions(left + 30, top + 128, 74, 22).build();
            this.addDrawableChild(this.preset12Btn);

            this.preset24Btn = ButtonWidget.builder(Text.literal("24V Bank"), b -> setPresetVoltage(24.0, ConverterScreenHandler.BUTTON_PRESET_24V))
                .dimensions(left + 112, top + 128, 74, 22).build();
            this.addDrawableChild(this.preset24Btn);

            this.preset48Btn = ButtonWidget.builder(Text.literal("48V Bank"), b -> setPresetVoltage(48.0, ConverterScreenHandler.BUTTON_PRESET_48V))
                .dimensions(left + 194, top + 128, 74, 22).build();
            this.addDrawableChild(this.preset48Btn);
        }

        // Protection Trip Reset Button
        this.resetTripButton = ButtonWidget.builder(Text.literal("RESET TRIP"), b -> {
            ClientPlayNetworking.send(new ResetConverterTripPayload(this.handler.getPos()));
            sendButtonClick(ConverterScreenHandler.BUTTON_RESET_TRIP);
        }).dimensions(left + 75, top + 160, 150, 20).build();
        this.addDrawableChild(this.resetTripButton);

        updatePresetButtonLabels();
        this.controlsInitialized = true;
    }

    private void setPresetVoltage(double val, int buttonId) {
        ClientPlayNetworking.send(new SetConverterVoltagePayload(this.handler.getPos(), val));
        sendButtonClick(buttonId);
        this.handler.setTargetVoltageLocal(val);
        updatePresetButtonLabels();
    }

    private void setInverterInput(double val, int buttonId) {
        ClientPlayNetworking.send(new SetConverterInputVoltagePayload(this.handler.getPos(), val));
        sendButtonClick(buttonId);
        this.handler.setNominalInputVoltageLocal(val);
        updatePresetButtonLabels();
    }

    private void sendButtonClick(int buttonId) {
        if (this.client != null && this.client.interactionManager != null) {
            this.client.interactionManager.clickButton(this.handler.syncId, buttonId);
        }
    }

    private void updatePresetButtonLabels() {
        if (this.handler.getTypeKind() == 0) {
            int target = (int) Math.round(this.handler.getTargetVoltage());
            if (preset5Btn != null) preset5Btn.setMessage(Text.literal(target == 5 ? "► 5V ◄" : "5V"));
            if (preset12Btn != null) preset12Btn.setMessage(Text.literal(target == 12 ? "► 12V ◄" : "12V"));
            if (preset24Btn != null) preset24Btn.setMessage(Text.literal(target == 24 ? "► 24V ◄" : "24V"));
            if (preset48Btn != null) preset48Btn.setMessage(Text.literal(target == 48 ? "► 48V ◄" : "48V"));
        } else if (this.handler.getTypeKind() == 1) {
            int nom = (int) Math.round(this.handler.getNominalInputVoltage());
            if (in12Btn != null) in12Btn.setMessage(Text.literal(nom == 12 ? "► 12V In ◄" : "12V In"));
            if (in24Btn != null) in24Btn.setMessage(Text.literal(nom == 24 ? "► 24V In ◄" : "24V In"));
            if (in48Btn != null) in48Btn.setMessage(Text.literal(nom == 48 ? "► 48V In ◄" : "48V In"));
        } else if (this.handler.getTypeKind() == ConverterScreenHandler.TYPE_CHARGE_CONTROLLER) {
            int target = (int) Math.round(this.handler.getTargetVoltage());
            if (preset12Btn != null) preset12Btn.setMessage(Text.literal(target == 12 ? "► 12V Bank ◄" : "12V Bank"));
            if (preset24Btn != null) preset24Btn.setMessage(Text.literal(target == 24 ? "► 24V Bank ◄" : "24V Bank"));
            if (preset48Btn != null) preset48Btn.setMessage(Text.literal(target == 48 ? "► 48V Bank ◄" : "48V Bank"));
        }
    }

    @Override
    public void handledScreenTick() {
        super.handledScreenTick();
        if (!controlsInitialized || this.currentTypeKind != this.handler.getTypeKind()) {
            rebuildControls();
        } else {
            updatePresetButtonLabels();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Suppress default slot/inventory text
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int left = (this.width - this.backgroundWidth) / 2;
        int top = (this.height - this.backgroundHeight) / 2;

        // Outer Dark Steel Bezel
        context.fill(left, top, left + this.backgroundWidth, top + this.backgroundHeight, 0xFF14171A);
        context.drawStrokedRectangle(left, top, this.backgroundWidth, this.backgroundHeight, 0xFF38404A);

        // Header Title Bar
        context.fill(left + 2, top + 2, left + this.backgroundWidth - 2, top + 20, 0xFF1D2228);
        context.drawText(this.textRenderer, this.title, left + 8, top + 6, 0xFFFFD700, true);

        // Status LED Badge
        renderStatusBadge(context, left + this.backgroundWidth - 76, top + 4);

        // Column 1: INPUT Telemetry
        renderPanelBox(context, left + 10, top + 24, 88, 76, "▲ INPUT", 0xFF58A6FF);
        String inPortStr = "[" + this.handler.getInputPortDirection().asString().toUpperCase() + "]";
        context.drawText(this.textRenderer, inPortStr, left + 58, top + 28, 0xFF8B949E, false);
        context.drawText(this.textRenderer, String.format("%.1f V", this.handler.getInputVoltage()), left + 16, top + 42, 0xFF58A6FF, false);
        context.drawText(this.textRenderer, String.format("%.2f A", this.handler.getInputCurrent()), left + 16, top + 56, 0xFFFFA657, false);
        context.drawText(this.textRenderer, String.format("%.1f W", this.handler.getInputPower()), left + 16, top + 70, 0xFF7EE787, false);

        // Column 2: SYSTEM Telemetry
        renderPanelBox(context, left + 104, top + 24, 92, 76, "⚙ SYSTEM", 0xFFE6EDF3);
        String modeName = switch (this.handler.getTypeKind()) {
            case 0 -> "DC-DC Conv";
            case 1 -> this.handler.isGridTie() ? "Grid-Tie Inv" : "AC Inverter";
            case 2 -> "Transformer";
            case 3 -> "AC Rectifier";
            case ConverterScreenHandler.TYPE_CHARGE_CONTROLLER -> "MPPT Charger";
            default -> "Converter";
        };
        context.drawText(this.textRenderer, modeName, left + 110, top + 40, 0xFFF0F6FC, false);

        double eff = this.handler.getEfficiency();
        context.drawText(this.textRenderer, String.format("Eff: %.1f%%", eff), left + 110, top + 52, 0xFF56D364, false);

        double temp = this.handler.getTemperature();
        int tempColor = temp > 80.0 ? 0xFFFF7B72 : (temp > 50.0 ? 0xFFF1C40F : 0xFF7EE787);
        context.drawText(this.textRenderer, String.format("T: %.1f°C", temp), left + 110, top + 64, tempColor, false);

        if (this.handler.getTypeKind() == 1) {
            context.drawText(this.textRenderer, String.format("THD: %.1f%%", this.handler.getTHD()), left + 110, top + 76, 0xFF8B949E, false);
        }

        // Column 3: OUTPUT Telemetry
        renderPanelBox(context, left + 202, top + 24, 88, 76, "▼ OUTPUT", 0xFF7EE787);
        String outPortStr = "[" + this.handler.getOutputPortDirection().asString().toUpperCase() + "]";
        context.drawText(this.textRenderer, outPortStr, left + 250, top + 28, 0xFF8B949E, false);
        context.drawText(this.textRenderer, String.format("%.1f V", this.handler.getOutputVoltage()), left + 208, top + 42, 0xFF58A6FF, false);
        context.drawText(this.textRenderer, String.format("%.2f A", this.handler.getOutputCurrent()), left + 208, top + 56, 0xFFFFA657, false);
        context.drawText(this.textRenderer, String.format("%.1f W", this.handler.getOutputPower()), left + 208, top + 70, 0xFF7EE787, false);

        // Control Area Divider
        context.fill(left + 10, top + 104, left + this.backgroundWidth - 10, top + 105, 0xFF2C343E);

        if (this.handler.getTypeKind() == 0) {
            context.drawText(this.textRenderer, "Output Preset:", left + 16, top + 114, 0xFF8B949E, false);
            context.drawText(this.textRenderer, String.format("%.1f V DC", this.handler.getTargetVoltage()), left + 100, top + 114, 0xFFFFD700, false);
        } else if (this.handler.getTypeKind() == 1) {
            int nom = (int) Math.round(this.handler.getNominalInputVoltage());
            double minIn = nom <= 15 ? 10.0 : (nom <= 30 ? 20.0 : 40.0);
            double maxIn = nom <= 15 ? 16.5 : (nom <= 30 ? 33.0 : 66.0);
            context.drawText(this.textRenderer, "DC Input Mode:", left + 16, top + 114, 0xFF8B949E, false);
            context.drawText(this.textRenderer, String.format("%dV DC (%.0fV-%.0fV)", nom, minIn, maxIn), left + 100, top + 114, 0xFFFFD700, false);
        } else if (this.handler.getTypeKind() == ConverterScreenHandler.TYPE_CHARGE_CONTROLLER) {
            int bank = (int) Math.round(this.handler.getTargetVoltage());
            context.drawText(this.textRenderer, "Battery Bank:", left + 16, top + 114, 0xFF8B949E, false);
            context.drawText(this.textRenderer, String.format("%dV Nominal Bank", bank), left + 100, top + 114, 0xFFFFD700, false);
        }

        // Trip Status Banner
        if (this.handler.isTripped()) {
            context.fill(left + 10, top + 185, left + this.backgroundWidth - 10, top + 198, 0x44FF3333);
            context.drawCenteredTextWithShadow(this.textRenderer, "⚠ PROTECTION TRIP ACTIVE", left + this.backgroundWidth / 2, top + 187, 0xFFFF3333);
        }
    }

    private void renderPanelBox(DrawContext context, int x, int y, int width, int height, String header, int headerColor) {
        context.fill(x, y, x + width, y + height, 0xFF191D22);
        context.drawStrokedRectangle(x, y, width, height, 0xFF282E37);
        context.drawText(this.textRenderer, header, x + 6, y + 4, headerColor, false);
        context.fill(x + 4, y + 14, x + width - 4, y + 15, 0xFF282E37);
    }

    private void renderStatusBadge(DrawContext context, int x, int y) {
        int badgeWidth = 68;
        int badgeHeight = 12;

        if (this.handler.isTripped()) {
            context.fill(x, y, x + badgeWidth, y + badgeHeight, 0xFFE74C3C);
            context.drawCenteredTextWithShadow(this.textRenderer, "TRIPPED", x + badgeWidth / 2, y + 2, 0xFFFFFFFF);
            return;
        }

        ElectricalState state = this.handler.getElectricalState();
        int color = switch (state) {
            case NOMINAL -> 0xFF2ECC71;
            case BROWNOUT -> 0xFFF1C40F;
            case SURGE -> 0xFFE67E22;
            case DESTROYED -> 0xFFE74C3C;
            case OFF -> 0xFF636E72;
        };

        context.fill(x, y, x + badgeWidth, y + badgeHeight, color);
        context.drawCenteredTextWithShadow(this.textRenderer, state.name(), x + badgeWidth / 2, y + 2, 0xFFFFFFFF);
    }
}

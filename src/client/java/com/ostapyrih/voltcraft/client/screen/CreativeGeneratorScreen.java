package com.ostapyrih.voltcraft.client.screen;

import com.ostapyrih.voltcraft.network.SetCreativeGeneratorPayload;
import com.ostapyrih.voltcraft.screen.handler.CreativeGeneratorScreenHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * Interactive industrial laboratory control dashboard for the Creative Power Generator.
 */
public class CreativeGeneratorScreen extends HandledScreen<CreativeGeneratorScreenHandler> {

    private TextFieldWidget voltageField;
    private ButtonWidget powerToggleButton;

    public CreativeGeneratorScreen(CreativeGeneratorScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 300;
        this.backgroundHeight = 210;
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - this.backgroundWidth) / 2;
        int top = (this.height - this.backgroundHeight) / 2;

        // Direct Set Text Input Field + Set Button (Row 1 right side)
        this.voltageField = new TextFieldWidget(this.textRenderer, left + 196, top + 107, 50, 16, Text.literal("Voltage"));
        this.voltageField.setMaxLength(6);
        this.voltageField.setText(String.format("%.1f", this.handler.getVoltage()));
        this.addDrawableChild(this.voltageField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Set"), b -> applyDirectVoltage())
            .dimensions(left + 250, top + 107, 36, 16).build());

        // Voltage Step Adjustment Buttons (Row 2)
        this.addDrawableChild(ButtonWidget.builder(Text.literal("-10V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_DEC_10V))
            .dimensions(left + 74, top + 126, 36, 16).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("-1V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_DEC_1V))
            .dimensions(left + 113, top + 126, 30, 16).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("+1V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_INC_1V))
            .dimensions(left + 146, top + 126, 30, 16).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("+10V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_INC_10V))
            .dimensions(left + 179, top + 126, 36, 16).build());

        // Presets Row
        this.addDrawableChild(ButtonWidget.builder(Text.literal("5V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_V_5V))
            .dimensions(left + 54, top + 145, 28, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("12V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_V_12V))
            .dimensions(left + 84, top + 145, 30, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("24V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_V_24V))
            .dimensions(left + 116, top + 145, 30, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("48V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_V_48V))
            .dimensions(left + 148, top + 145, 30, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("120V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_V_120V))
            .dimensions(left + 180, top + 145, 34, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("230V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_V_230V))
            .dimensions(left + 216, top + 145, 34, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("400V"), b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_V_400V))
            .dimensions(left + 252, top + 145, 34, 16).build());

        // Lower Controls Row: Power Toggle, Waveform, Current Limit, Reset Energy
        this.powerToggleButton = ButtonWidget.builder(
            Text.literal(this.handler.isEnabled() ? "POWER: ON" : "POWER: OFF"),
            b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_TOGGLE_POWER)
        ).dimensions(left + 10, top + 168, 76, 18).build();
        this.addDrawableChild(this.powerToggleButton);

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Waveform"),
            b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_CYCLE_FREQ)
        ).dimensions(left + 90, top + 168, 64, 18).build());

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Current Limit"),
            b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_CYCLE_CURRENT_LIMIT)
        ).dimensions(left + 158, top + 168, 70, 18).build());

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Reset"),
            b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_RESET_ENERGY)
        ).dimensions(left + 232, top + 168, 54, 18).build());

        // Secondary row: R_int
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Internal R"),
            b -> sendButtonClick(CreativeGeneratorScreenHandler.BUTTON_CYCLE_R_INT)
        ).dimensions(left + 100, top + 189, 100, 16).build());
    }

    private void sendButtonClick(int buttonId) {
        if (this.client != null && this.client.interactionManager != null) {
            this.client.interactionManager.clickButton(this.handler.syncId, buttonId);
        }
    }

    private void applyDirectVoltage() {
        if (this.voltageField != null) {
            try {
                double val = Double.parseDouble(this.voltageField.getText().trim());
                ClientPlayNetworking.send(new SetCreativeGeneratorPayload(this.handler.getPos(), val));
            } catch (NumberFormatException ignored) {
                this.voltageField.setText(String.format("%.1f", this.handler.getVoltage()));
            }
        }
    }

    @Override
    public void handledScreenTick() {
        super.handledScreenTick();
        if (this.voltageField != null && !this.voltageField.isFocused()) {
            this.voltageField.setText(String.format("%.1f", this.handler.getVoltage()));
        }
        if (this.powerToggleButton != null) {
            this.powerToggleButton.setMessage(Text.literal(this.handler.isEnabled() ? "POWER: ON" : "POWER: OFF"));
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (this.voltageField != null && this.voltageField.isFocused()) {
            if (input.key() == 257 || input.key() == 335) { // Enter
                applyDirectVoltage();
                return true;
            }
            return this.voltageField.keyPressed(input);
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {}

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
        int badgeWidth = 68;
        int badgeHeight = 12;
        int badgeX = left + this.backgroundWidth - 76;
        int badgeY = top + 4;
        boolean online = this.handler.isEnabled();
        context.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, online ? 0xFF2ECC71 : 0xFFE74C3C);
        context.drawCenteredTextWithShadow(this.textRenderer, online ? "ONLINE" : "OFFLINE", badgeX + badgeWidth / 2, badgeY + 2, 0xFFFFFFFF);

        // Column 1: EMF Setting
        renderPanelBox(context, left + 10, top + 24, 88, 76, "⚙ SETTING", 0xFF58A6FF);
        context.drawText(this.textRenderer, String.format("%.1f V", this.handler.getVoltage()), left + 16, top + 42, 0xFF58A6FF, false);
        context.drawText(this.textRenderer, this.handler.getFrequencyDisplay(), left + 16, top + 56, 0xFFFFA657, false);
        context.drawText(this.textRenderer, String.format("R: %.3f Ω", this.handler.getInternalResistance()), left + 16, top + 70, 0xFF8B949E, false);

        // Column 2: LIVE OUTPUT
        renderPanelBox(context, left + 104, top + 24, 92, 76, "⚡ OUTPUT", 0xFF7EE787);
        context.drawText(this.textRenderer, String.format("%.2f A", this.handler.getDeliveredCurrent()), left + 110, top + 42, 0xFFFFA657, false);
        context.drawText(this.textRenderer, String.format("%.1f W", this.handler.getDeliveredPower()), left + 110, top + 56, 0xFF7EE787, false);
        context.drawText(this.textRenderer, String.format("Limit: %.0f A", this.handler.getMaxCurrent()), left + 110, top + 70, 0xFF8B949E, false);

        // Column 3: TOTAL ENERGY
        renderPanelBox(context, left + 202, top + 24, 88, 76, "⏱ ENERGY", 0xFFFFD700);
        context.drawText(this.textRenderer, String.format("%.3f kWh", this.handler.getTotalEnergyKwh()), left + 208, top + 42, 0xFFFFD700, false);
        context.drawText(this.textRenderer, "Ideal Source", left + 208, top + 56, 0xFF56D364, false);
        context.drawText(this.textRenderer, "Loss: ~0 W", left + 208, top + 70, 0xFF8B949E, false);

        // Control Area Separator
        context.fill(left + 10, top + 103, left + this.backgroundWidth - 10, top + 104, 0xFF2C343E);
        context.drawText(this.textRenderer, String.format("EMF: %.1f V", this.handler.getVoltage()), left + 14, top + 111, 0xFFFFD700, false);
        context.drawText(this.textRenderer, "Tune:", left + 14, top + 130, 0xFF8B949E, false);
        context.drawText(this.textRenderer, "Presets:", left + 10, top + 149, 0xFF8B949E, false);
    }

    private void renderPanelBox(DrawContext context, int x, int y, int width, int height, String header, int headerColor) {
        context.fill(x, y, x + width, y + height, 0xFF191D22);
        context.drawStrokedRectangle(x, y, width, height, 0xFF282E37);
        context.drawText(this.textRenderer, header, x + 6, y + 4, headerColor, false);
        context.fill(x + 4, y + 14, x + width - 4, y + 15, 0xFF282E37);
    }
}

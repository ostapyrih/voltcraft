package com.ostapyrih.voltcraft.client.screen;

import com.ostapyrih.voltcraft.api.data.ElectricalState;
import com.ostapyrih.voltcraft.screen.handler.EuConverterScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * Industrial slate control HUD for the 230V AC to E Rotary Energy Bridge.
 */
public class EuConverterScreen extends HandledScreen<EuConverterScreenHandler> {

    private ButtonWidget resetTripButton;

    public EuConverterScreen(EuConverterScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 290;
        this.backgroundHeight = 160;
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - this.backgroundWidth) / 2;
        int top = (this.height - this.backgroundHeight) / 2;

        this.resetTripButton = ButtonWidget.builder(Text.literal("Reset Trip"), b -> {
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(this.handler.syncId, EuConverterScreenHandler.BUTTON_RESET_TRIP);
            }
        }).dimensions(left + 104, top + 130, 82, 18).build();
        this.addDrawableChild(this.resetTripButton);
    }

    @Override
    public void handledScreenTick() {
        super.handledScreenTick();
        if (this.resetTripButton != null) {
            this.resetTripButton.visible = this.handler.isTripped();
        }
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

        // Dark Industrial Enclosure
        context.fill(left, top, left + this.backgroundWidth, top + this.backgroundHeight, 0xFF14171A);
        context.drawStrokedRectangle(left, top, this.backgroundWidth, this.backgroundHeight, 0xFF38404A);

        // Header Title Bar
        context.fill(left + 2, top + 2, left + this.backgroundWidth - 2, top + 20, 0xFF1D2228);
        context.drawText(this.textRenderer, Text.literal("Rotary Energy Bridge (230V AC \u2192 E)"), left + 8, top + 6, 0xFFFFD700, true);

        // Status Badge
        int badgeWidth = 76;
        int badgeHeight = 12;
        int badgeX = left + this.backgroundWidth - 84;
        int badgeY = top + 4;

        boolean tripped = this.handler.isTripped();
        ElectricalState state = this.handler.getElectricalState();
        double freq = this.handler.getInputFrequency();
        double vin = this.handler.getInputVoltage();

        int badgeColor;
        String badgeText;

        if (tripped) {
            badgeColor = 0xFFE74C3C;
            badgeText = "TRIPPED";
        } else if (vin > 5.0 && freq < 40.0) {
            badgeColor = 0xFFE67E22;
            badgeText = "DC REJECT";
        } else if (state == ElectricalState.NOMINAL) {
            badgeColor = 0xFF2ECC71;
            badgeText = "CONVERTING";
        } else if (state == ElectricalState.BROWNOUT) {
            badgeColor = 0xFFF39C12;
            badgeText = "BROWNOUT";
        } else if (state == ElectricalState.SURGE) {
            badgeColor = 0xFFE74C3C;
            badgeText = "OVERVOLT";
        } else {
            badgeColor = 0xFF7F8C8D;
            badgeText = "STANDBY";
        }

        context.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, badgeColor);
        context.drawCenteredTextWithShadow(this.textRenderer, badgeText, badgeX + badgeWidth / 2, badgeY + 2, 0xFFFFFFFF);

        // Column 1: 230V AC Input
        renderPanelBox(context, left + 10, top + 24, 88, 76, "\u25b2 230V AC IN", 0xFF58A6FF);
        context.drawText(this.textRenderer, String.format("%.1f V", vin), left + 16, top + 42, 0xFF58A6FF, false);
        context.drawText(this.textRenderer, String.format("%.2f A", this.handler.getInputCurrent()), left + 16, top + 56, 0xFFFFA657, false);
        context.drawText(this.textRenderer, String.format("%.1f W", this.handler.getInputPower()), left + 16, top + 70, 0xFF7EE787, false);
        context.drawText(this.textRenderer, String.format("%.1f Hz AC", freq), left + 16, top + 84, 0xFF8B949E, false);

        // Column 2: Conversion Specs
        renderPanelBox(context, left + 102, top + 24, 86, 76, "\u2699 SPECS", 0xFFFFD700);
        context.drawText(this.textRenderer, "25W \u2192 1 E/t", left + 108, top + 42, 0xFFFFD700, false);
        context.drawText(this.textRenderer, "207V - 253V", left + 108, top + 56, 0xFF7EE787, false);
        context.drawText(this.textRenderer, "\u2265 40.0 Hz AC", left + 108, top + 70, 0xFFFFA657, false);
        context.drawText(this.textRenderer, String.format("%.1f \u00b0C", this.handler.getTemperature()), left + 108, top + 84, 0xFF8B949E, false);

        // Column 3: E Output
        renderPanelBox(context, left + 192, top + 24, 88, 76, "\u25bc E OUTPUT", 0xFF7EE787);
        context.drawText(this.textRenderer, String.format("%.1f E/t", this.handler.getEuOutputRate()), left + 198, top + 42, 0xFF7EE787, false);
        context.drawText(this.textRenderer, String.format("%.0f E/s", this.handler.getEuOutputRate() * 20.0), left + 198, top + 56, 0xFF58A6FF, false);
        context.drawText(this.textRenderer, String.format("%d E", this.handler.getStoredEu()), left + 198, top + 70, 0xFFFFD700, false);
        context.drawText(this.textRenderer, String.format("Tot: %d", this.handler.getTotalEuGenerated()), left + 198, top + 84, 0xFF8B949E, false);

        // Buffer Bar
        int barX = left + 12;
        int barY = top + 108;
        int barWidth = this.backgroundWidth - 24;
        int barHeight = 12;

        context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF0D1117);
        context.drawStrokedRectangle(barX, barY, barWidth, barHeight, 0xFF30363D);

        long stored = this.handler.getStoredEu();
        long cap = Math.max(1L, this.handler.getCapacityEu());
        int fillWidth = (int) Math.clamp((stored * (barWidth - 2)) / cap, 0, barWidth - 2);
        if (fillWidth > 0) {
            context.fill(barX + 1, barY + 1, barX + 1 + fillWidth, barY + barHeight - 1, 0xFF2ECC71);
        }

        String bufferLabel = String.format("E Buffer: %d / %d E", stored, cap);
        context.drawCenteredTextWithShadow(this.textRenderer, bufferLabel, barX + barWidth / 2, barY + 2, 0xFFFFFFFF);
    }

    private void renderPanelBox(DrawContext context, int x, int y, int width, int height, String header, int headerColor) {
        context.fill(x, y, x + width, y + height, 0xFF191D22);
        context.drawStrokedRectangle(x, y, width, height, 0xFF282E37);
        context.drawText(this.textRenderer, header, x + 6, y + 4, headerColor, false);
        context.fill(x + 4, y + 14, x + width - 4, y + 15, 0xFF282E37);
    }
}

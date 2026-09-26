package com.ostapyrih.voltcraft.client.screen;

import com.ostapyrih.voltcraft.network.SetCreativeLoadPayload;
import com.ostapyrih.voltcraft.screen.handler.CreativeLoadScreenHandler;
import com.ostapyrih.voltcraft.simulation.creative.CreativeLoadLogic.LoadMode;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * Interactive industrial laboratory control dashboard for the Creative Electrical Load.
 */
public class CreativeLoadScreen extends HandledScreen<CreativeLoadScreenHandler> {

    private TextFieldWidget targetField;
    private ButtonWidget loadToggleButton;
    private ButtonWidget[] presetButtons = new ButtonWidget[5];

    public CreativeLoadScreen(CreativeLoadScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 300;
        this.backgroundHeight = 210;
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - this.backgroundWidth) / 2;
        int top = (this.height - this.backgroundHeight) / 2;

        // Row 1: Mode Selection
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Resistance (Ω)"),
            b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_MODE_RESISTANCE))
            .dimensions(left + 10, top + 106, 90, 16).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Power (W)"),
            b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_MODE_POWER))
            .dimensions(left + 104, top + 106, 70, 16).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Current (A)"),
            b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_MODE_CURRENT))
            .dimensions(left + 178, top + 106, 70, 16).build());

        // Direct Text Input Field + Set Button
        this.targetField = new TextFieldWidget(this.textRenderer, left + 196, top + 126, 50, 16, Text.literal("Target"));
        this.targetField.setMaxLength(6);
        this.targetField.setText(String.format("%.1f", this.handler.getTargetValue()));
        this.addDrawableChild(this.targetField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Set"), b -> applyDirectTarget())
            .dimensions(left + 250, top + 126, 36, 16).build());

        // Step Adjustment Buttons
        this.addDrawableChild(ButtonWidget.builder(Text.literal("-10"), b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_DEC_LARGE))
            .dimensions(left + 54, top + 126, 32, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("-1"), b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_DEC_SMALL))
            .dimensions(left + 88, top + 126, 28, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("+1"), b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_INC_SMALL))
            .dimensions(left + 118, top + 126, 28, 16).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("+10"), b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_INC_LARGE))
            .dimensions(left + 148, top + 126, 32, 16).build());

        // Presets Row
        int[] presetIds = {
            CreativeLoadScreenHandler.BUTTON_PRESET_1,
            CreativeLoadScreenHandler.BUTTON_PRESET_2,
            CreativeLoadScreenHandler.BUTTON_PRESET_3,
            CreativeLoadScreenHandler.BUTTON_PRESET_4,
            CreativeLoadScreenHandler.BUTTON_PRESET_5
        };
        for (int i = 0; i < 5; i++) {
            final int id = presetIds[i];
            presetButtons[i] = ButtonWidget.builder(Text.literal(getPresetLabel(i)), b -> sendButtonClick(id))
                .dimensions(left + 54 + (i * 46), top + 146, 42, 16).build();
            this.addDrawableChild(presetButtons[i]);
        }

        // Lower Controls: Toggle Load & Reset Energy
        this.loadToggleButton = ButtonWidget.builder(
            Text.literal(this.handler.isEnabled() ? "LOAD: ON" : "LOAD: OFF"),
            b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_TOGGLE_LOAD)
        ).dimensions(left + 10, top + 172, 80, 20).build();
        this.addDrawableChild(this.loadToggleButton);

        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Reset Energy"),
            b -> sendButtonClick(CreativeLoadScreenHandler.BUTTON_RESET_ENERGY)
        ).dimensions(left + 200, top + 172, 86, 20).build());
    }

    private String getPresetLabel(int index) {
        LoadMode mode = this.handler.getMode();
        return switch (mode) {
            case CONSTANT_RESISTANCE -> switch (index) {
                case 0 -> "1Ω";
                case 1 -> "5Ω";
                case 2 -> "10Ω";
                case 3 -> "50Ω";
                default -> "100Ω";
            };
            case CONSTANT_POWER -> switch (index) {
                case 0 -> "100W";
                case 1 -> "500W";
                case 2 -> "1kW";
                case 3 -> "2.5k";
                default -> "5kW";
            };
            case CONSTANT_CURRENT -> switch (index) {
                case 0 -> "1A";
                case 1 -> "5A";
                case 2 -> "10A";
                case 3 -> "25A";
                default -> "50A";
            };
        };
    }

    private void updatePresetLabels() {
        for (int i = 0; i < 5; i++) {
            if (presetButtons[i] != null) {
                presetButtons[i].setMessage(Text.literal(getPresetLabel(i)));
            }
        }
    }

    private void sendButtonClick(int buttonId) {
        if (this.client != null && this.client.interactionManager != null) {
            this.client.interactionManager.clickButton(this.handler.syncId, buttonId);
        }
    }

    private void applyDirectTarget() {
        if (this.targetField != null) {
            try {
                double val = Double.parseDouble(this.targetField.getText().trim());
                ClientPlayNetworking.send(new SetCreativeLoadPayload(this.handler.getPos(), val));
            } catch (NumberFormatException ignored) {
                this.targetField.setText(String.format("%.1f", this.handler.getTargetValue()));
            }
        }
    }

    @Override
    public void handledScreenTick() {
        super.handledScreenTick();
        if (this.targetField != null && !this.targetField.isFocused()) {
            this.targetField.setText(String.format("%.1f", this.handler.getTargetValue()));
        }
        if (this.loadToggleButton != null) {
            this.loadToggleButton.setMessage(Text.literal(this.handler.isEnabled() ? "LOAD: ON" : "LOAD: OFF"));
        }
        updatePresetLabels();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (this.targetField != null && this.targetField.isFocused()) {
            if (input.key() == 257 || input.key() == 335) { // Enter
                applyDirectTarget();
                return true;
            }
            return this.targetField.keyPressed(input);
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
        context.drawCenteredTextWithShadow(this.textRenderer, online ? "ACTIVE" : "DISABLED", badgeX + badgeWidth / 2, badgeY + 2, 0xFFFFFFFF);

        // Column 1: SETPOINT
        renderPanelBox(context, left + 10, top + 24, 88, 76, "⚙ SETPOINT", 0xFF58A6FF);
        context.drawText(this.textRenderer, this.handler.getTargetDisplay(), left + 16, top + 42, 0xFF58A6FF, false);
        context.drawText(this.textRenderer, this.handler.getMode().name(), left + 16, top + 56, 0xFFFFA657, false);
        context.drawText(this.textRenderer, online ? "Drawing Power" : "Open Circuit", left + 16, top + 70, 0xFF8B949E, false);

        // Column 2: LIVE DRAW
        renderPanelBox(context, left + 104, top + 24, 92, 76, "⚡ LIVE DRAW", 0xFF7EE787);
        context.drawText(this.textRenderer, String.format("%.1f V", this.handler.getTerminalVoltage()), left + 110, top + 42, 0xFF58A6FF, false);
        context.drawText(this.textRenderer, String.format("%.2f A", this.handler.getDrawnCurrent()), left + 110, top + 56, 0xFFFFA657, false);
        context.drawText(this.textRenderer, String.format("%.1f W", this.handler.getDissipatedPower()), left + 110, top + 70, 0xFF7EE787, false);

        // Column 3: METRICS
        renderPanelBox(context, left + 202, top + 24, 88, 76, "⏱ METRICS", 0xFFFFD700);
        context.drawText(this.textRenderer, String.format("%.3f kWh", this.handler.getTotalEnergyKwh()), left + 208, top + 42, 0xFFFFD700, false);
        double req = this.handler.getEquivalentResistance();
        String rStr = req > 9999.0 ? "Req: ∞ Ω" : String.format("Req: %.2f Ω", req);
        context.drawText(this.textRenderer, rStr, left + 208, top + 56, 0xFF7EE787, false);
        context.drawText(this.textRenderer, "PF: 1.00", left + 208, top + 70, 0xFF8B949E, false);

        // Control Area Separator
        context.fill(left + 10, top + 103, left + this.backgroundWidth - 10, top + 104, 0xFF2C343E);
        context.drawText(this.textRenderer, "Tune:", left + 14, top + 130, 0xFF8B949E, false);
        context.drawText(this.textRenderer, "Presets:", left + 10, top + 150, 0xFF8B949E, false);
    }

    private void renderPanelBox(DrawContext context, int x, int y, int width, int height, String header, int headerColor) {
        context.fill(x, y, x + width, y + height, 0xFF191D22);
        context.drawStrokedRectangle(x, y, width, height, 0xFF282E37);
        context.drawText(this.textRenderer, header, x + 6, y + 4, headerColor, false);
        context.fill(x + 4, y + 14, x + width - 4, y + 15, 0xFF282E37);
    }
}

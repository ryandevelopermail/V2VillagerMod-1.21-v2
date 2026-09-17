package dev.sterner.guardvillagers.client.screen.developer;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

public final class DeveloperPanelScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 230;
    private final WorldSetupTab worldSetupTab = new WorldSetupTab();
    private DeveloperPanelTab activeTab = worldSetupTab;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;

    public DeveloperPanelScreen() {
        super(Text.translatable("screen.guardvillagers.developer_panel"));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(MAX_PANEL_WIDTH, width - 16);
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(12, (height - PANEL_HEIGHT) / 2);
        activeTab.init(this, panelLeft + 12, panelTop + 42, panelWidth - 24, PANEL_HEIGHT - 54);
    }

    public <T extends ClickableWidget> T addPanelWidget(T widget) {
        return addDrawableChild(widget);
    }

    public TextRenderer panelTextRenderer() {
        return textRenderer;
    }

    public void onSetupStatus(String message, int progressPercent, boolean finished, boolean success) {
        worldSetupTab.onStatus(message, progressPercent, finished, success);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + PANEL_HEIGHT, 0xD0101010);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 34, 0xE0252525);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, panelTop + 10, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, activeTab.title(), panelLeft + 14, panelTop + 27, 0xA0A0A0);
        activeTab.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

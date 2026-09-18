package dev.sterner.guardvillagers.client.screen.developer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

interface DeveloperPanelTab {
    Text title();

    void init(DeveloperPanelScreen screen, int left, int top, int width, int height);

    void render(DrawContext context, int mouseX, int mouseY, float delta);

    default boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return false;
    }
}

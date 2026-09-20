package dev.sterner.guardvillagers.mixin.client;

import dev.sterner.guardvillagers.client.professionalstorage.ProfessionalStorageSnapshotCache;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageRow;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GenericContainerScreen.class)
public abstract class GenericContainerScreenMixin extends HandledScreen<GenericContainerScreenHandler> {
    @Unique
    private static final int GUARDVILLAGERS_PANEL_WIDTH = 126;
    @Unique
    private static final int GUARDVILLAGERS_PANEL_GAP = 6;
    @Unique
    private int guardvillagers$vanillaBackgroundWidth;
    @Unique
    private boolean guardvillagers$expanded;

    protected GenericContainerScreenMixin(
            GenericContainerScreenHandler handler,
            PlayerInventory inventory,
            Text title
    ) {
        super(handler, inventory, title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void guardvillagers$acceptLateSnapshot(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        guardvillagers$refreshLayout();
    }

    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void guardvillagers$drawProfessionalPanel(
            DrawContext context,
            float delta,
            int mouseX,
            int mouseY,
            CallbackInfo ci
    ) {
        ProfessionalStorageSnapshotCache.find(handler.syncId).ifPresent(snapshot -> {
            int panelX = x + guardvillagers$vanillaBackgroundWidth + GUARDVILLAGERS_PANEL_GAP;
            int panelY = y;
            context.fill(panelX, panelY, panelX + GUARDVILLAGERS_PANEL_WIDTH, panelY + backgroundHeight, 0xFF8B8B8B);
            context.fill(panelX + 1, panelY + 1, panelX + GUARDVILLAGERS_PANEL_WIDTH - 1, panelY + backgroundHeight - 1, 0xFF202020);
            context.drawTextWithShadow(textRenderer, "Storage profile", panelX + 8, panelY + 8, 0xFFFFFFFF);

            int rowY = panelY + 27;
            for (ProfessionalStorageRow row : snapshot.rows()) {
                String line = row.label() + ": " + row.value();
                String fitted = textRenderer.trimToWidth(line, GUARDVILLAGERS_PANEL_WIDTH - 16);
                context.drawTextWithShadow(textRenderer, fitted, panelX + 8, rowY, guardvillagers$color(row.tone()));
                rowY += 18;
            }
        });
    }

    @Override
    public void removed() {
        ProfessionalStorageSnapshotCache.remove(handler.syncId);
        super.removed();
    }

    @Unique
    private void guardvillagers$refreshLayout() {
        if (guardvillagers$expanded || ProfessionalStorageSnapshotCache.find(handler.syncId).isEmpty()) {
            return;
        }
        guardvillagers$vanillaBackgroundWidth = backgroundWidth;
        backgroundWidth += GUARDVILLAGERS_PANEL_GAP + GUARDVILLAGERS_PANEL_WIDTH;
        x = (width - backgroundWidth) / 2;
        guardvillagers$expanded = true;
    }

    @Unique
    private static int guardvillagers$color(ProfessionalStorageRow.Tone tone) {
        return switch (tone) {
            case NORMAL -> 0xFFE0E0E0;
            case PAIRED -> 0xFF66D17A;
            case WARNING -> 0xFFFFD166;
            case BLOCKER -> 0xFFFF6B6B;
        };
    }
}

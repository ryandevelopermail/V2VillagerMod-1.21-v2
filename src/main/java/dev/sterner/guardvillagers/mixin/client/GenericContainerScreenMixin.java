package dev.sterner.guardvillagers.mixin.client;

import dev.sterner.guardvillagers.client.professionalstorage.ProfessionalStoragePanelLayout;
import dev.sterner.guardvillagers.client.professionalstorage.ProfessionalStorageScrollState;
import dev.sterner.guardvillagers.client.professionalstorage.ProfessionalStorageSnapshotCache;
import dev.sterner.guardvillagers.client.professionalstorage.ProfessionalStorageTextLayout;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageRow;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageSnapshot;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageTab;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(GenericContainerScreen.class)
public abstract class GenericContainerScreenMixin extends HandledScreen<GenericContainerScreenHandler> {
    @Unique private static final int GUARDVILLAGERS_PANEL_WIDTH = 126;
    @Unique private static final int GUARDVILLAGERS_PANEL_GAP = 6;
    @Unique private static final int GUARDVILLAGERS_PANEL_PADDING = 8;
    @Unique private static final int GUARDVILLAGERS_TAB_HEIGHT = 14;
    @Unique private static final int GUARDVILLAGERS_TAB_GAP = 2;
    @Unique private static final int GUARDVILLAGERS_SCROLLBAR_WIDTH = 4;
    @Unique private static final int GUARDVILLAGERS_SCROLL_STEP = 18;

    @Unique private int guardvillagers$vanillaBackgroundWidth;
    @Unique private boolean guardvillagers$expanded;
    @Unique private ProfessionalStorageScrollState guardvillagers$scrollState;
    @Unique private boolean guardvillagers$draggingScrollbar;
    @Unique private int guardvillagers$scrollbarGrabOffset;
    @Unique private @Nullable List<Text> guardvillagers$hoverTooltip;

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
        guardvillagers$hoverTooltip = null;
        ProfessionalStorageSnapshotCache.find(handler.syncId).ifPresent(snapshot ->
                guardvillagers$drawPanel(context, snapshot, mouseX, mouseY));
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void guardvillagers$drawProfessionalTooltip(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        if (guardvillagers$hoverTooltip != null && !guardvillagers$hoverTooltip.isEmpty()) {
            context.drawTooltip(textRenderer, guardvillagers$hoverTooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            ProfessionalStorageSnapshot snapshot = ProfessionalStorageSnapshotCache.find(handler.syncId).orElse(null);
            if (snapshot != null) {
                guardvillagers$refreshLayout();
                PanelGeometry geometry = guardvillagers$geometry(snapshot);
                for (TabButton tabButton : guardvillagers$tabButtons(snapshot, geometry)) {
                    if (tabButton.contains(mouseX, mouseY)) {
                        guardvillagers$state().select(tabButton.tabId());
                        guardvillagers$draggingScrollbar = false;
                        guardvillagers$clampOffsets(snapshot, geometry);
                        return true;
                    }
                }

                TabLayout selected = guardvillagers$selectedLayout(snapshot, geometry);
                if (selected.maximumScroll() > 0 && geometry.scrollbarContains(mouseX, mouseY)) {
                    int thumbHeight = ProfessionalStoragePanelLayout.scrollbarThumbHeight(
                            geometry.viewportHeight(), selected.contentHeight());
                    int thumbOffset = ProfessionalStoragePanelLayout.scrollbarThumbOffset(
                            guardvillagers$state().offset(selected.tab().id()),
                            selected.maximumScroll(),
                            geometry.viewportHeight(),
                            thumbHeight);
                    int thumbTop = geometry.viewportY() + thumbOffset;
                    guardvillagers$scrollbarGrabOffset = mouseY >= thumbTop && mouseY < thumbTop + thumbHeight
                            ? (int) mouseY - thumbTop
                            : thumbHeight / 2;
                    guardvillagers$draggingScrollbar = true;
                    guardvillagers$setScrollFromThumb(
                            selected,
                            geometry,
                            (int) mouseY - guardvillagers$scrollbarGrabOffset);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        ProfessionalStorageSnapshot snapshot = ProfessionalStorageSnapshotCache.find(handler.syncId).orElse(null);
        if (snapshot != null) {
            guardvillagers$refreshLayout();
            PanelGeometry geometry = guardvillagers$geometry(snapshot);
            if (geometry.viewportContains(mouseX, mouseY)) {
                TabLayout selected = guardvillagers$selectedLayout(snapshot, geometry);
                int current = guardvillagers$state().offset(selected.tab().id());
                int delta = (int) Math.round(verticalAmount * GUARDVILLAGERS_SCROLL_STEP);
                guardvillagers$state().setOffset(
                        selected.tab().id(), current - delta, selected.maximumScroll());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double deltaX,
            double deltaY
    ) {
        if (button == 0 && guardvillagers$draggingScrollbar) {
            ProfessionalStorageSnapshot snapshot = ProfessionalStorageSnapshotCache.find(handler.syncId).orElse(null);
            if (snapshot != null) {
                PanelGeometry geometry = guardvillagers$geometry(snapshot);
                TabLayout selected = guardvillagers$selectedLayout(snapshot, geometry);
                guardvillagers$setScrollFromThumb(
                        selected,
                        geometry,
                        (int) mouseY - guardvillagers$scrollbarGrabOffset);
                return true;
            }
            guardvillagers$draggingScrollbar = false;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && guardvillagers$draggingScrollbar) {
            guardvillagers$draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        ProfessionalStorageSnapshotCache.remove(handler.syncId);
        if (guardvillagers$scrollState != null) {
            guardvillagers$scrollState.clear();
        }
        guardvillagers$draggingScrollbar = false;
        guardvillagers$hoverTooltip = null;
        super.removed();
    }

    @Unique
    private void guardvillagers$drawPanel(
            DrawContext context,
            ProfessionalStorageSnapshot snapshot,
            int mouseX,
            int mouseY
    ) {
        PanelGeometry geometry = guardvillagers$geometry(snapshot);
        context.fill(
                geometry.panelX(), geometry.panelY(),
                geometry.panelX() + GUARDVILLAGERS_PANEL_WIDTH,
                geometry.panelY() + backgroundHeight,
                0xFF8B8B8B);
        context.fill(
                geometry.panelX() + 1, geometry.panelY() + 1,
                geometry.panelX() + GUARDVILLAGERS_PANEL_WIDTH - 1,
                geometry.panelY() + backgroundHeight - 1,
                0xFF202020);
        context.drawTextWithShadow(
                textRenderer,
                "Storage profile",
                geometry.panelX() + GUARDVILLAGERS_PANEL_PADDING,
                geometry.panelY() + 8,
                0xFFFFFFFF);

        for (TabButton button : guardvillagers$tabButtons(snapshot, geometry)) {
            boolean selected = button.tabId().equals(guardvillagers$state().selectedTabId());
            context.fill(
                    button.x(), button.y(),
                    button.x() + button.width(), button.y() + button.height(),
                    selected ? 0xFFB0B0B0 : 0xFF5A5A5A);
            context.fill(
                    button.x() + 1, button.y() + 1,
                    button.x() + button.width() - 1, button.y() + button.height() - 1,
                    selected ? 0xFF4A4A4A : 0xFF2C2C2C);
            String fittedTitle = guardvillagers$fitSingleLine(button.title(), button.width() - 6);
            int textX = button.x() + Math.max(3, (button.width() - textRenderer.getWidth(fittedTitle)) / 2);
            context.drawTextWithShadow(textRenderer, fittedTitle, textX, button.y() + 3, 0xFFFFFFFF);
            if (!fittedTitle.equals(button.title()) && button.contains(mouseX, mouseY)) {
                guardvillagers$hoverTooltip = List.of(Text.literal(button.title()));
            }
        }

        TabLayout selected = guardvillagers$selectedLayout(snapshot, geometry);
        int scrollOffset = guardvillagers$state().offset(selected.tab().id());
        int rowY = geometry.viewportY() - scrollOffset;
        context.enableScissor(
                geometry.contentX(),
                geometry.viewportY(),
                geometry.contentRight(selected.maximumScroll() > 0),
                geometry.viewportBottom());
        for (RenderedRow rendered : selected.rows()) {
            int rowTop = rowY;
            int textY = rowTop;
            for (String line : rendered.label().lines()) {
                context.drawTextWithShadow(textRenderer, line, geometry.contentX(), textY, 0xFFA8A8A8);
                textY += ProfessionalStoragePanelLayout.LINE_HEIGHT;
            }
            textY += ProfessionalStoragePanelLayout.LABEL_VALUE_GAP;
            for (String line : rendered.value().lines()) {
                context.drawTextWithShadow(
                        textRenderer,
                        line,
                        geometry.contentX(),
                        textY,
                        guardvillagers$color(rendered.row().tone()));
                textY += ProfessionalStoragePanelLayout.LINE_HEIGHT;
            }
            rowY += rendered.height();

            if ((rendered.label().ellipsized() || rendered.value().ellipsized())
                    && mouseX >= geometry.contentX()
                    && mouseX < geometry.contentRight(selected.maximumScroll() > 0)
                    && mouseY >= Math.max(rowTop, geometry.viewportY())
                    && mouseY < Math.min(rowY, geometry.viewportBottom())) {
                List<Text> tooltip = new ArrayList<>(2);
                if (rendered.label().ellipsized()) {
                    tooltip.add(Text.literal(rendered.row().label()));
                }
                if (rendered.value().ellipsized()) {
                    tooltip.add(Text.literal(rendered.row().value()));
                }
                guardvillagers$hoverTooltip = tooltip;
            }
        }
        context.disableScissor();

        if (selected.maximumScroll() > 0) {
            int thumbHeight = ProfessionalStoragePanelLayout.scrollbarThumbHeight(
                    geometry.viewportHeight(), selected.contentHeight());
            int thumbOffset = ProfessionalStoragePanelLayout.scrollbarThumbOffset(
                    scrollOffset,
                    selected.maximumScroll(),
                    geometry.viewportHeight(),
                    thumbHeight);
            context.fill(
                    geometry.scrollbarX(), geometry.viewportY(),
                    geometry.scrollbarX() + GUARDVILLAGERS_SCROLLBAR_WIDTH,
                    geometry.viewportBottom(),
                    0xFF101010);
            context.fill(
                    geometry.scrollbarX(), geometry.viewportY() + thumbOffset,
                    geometry.scrollbarX() + GUARDVILLAGERS_SCROLLBAR_WIDTH,
                    geometry.viewportY() + thumbOffset + thumbHeight,
                    0xFFB0B0B0);
        }
    }

    @Unique
    private void guardvillagers$refreshLayout() {
        ProfessionalStorageSnapshot snapshot = ProfessionalStorageSnapshotCache.find(handler.syncId).orElse(null);
        if (snapshot == null) {
            return;
        }
        if (!guardvillagers$expanded) {
            guardvillagers$vanillaBackgroundWidth = backgroundWidth;
            backgroundWidth += GUARDVILLAGERS_PANEL_GAP + GUARDVILLAGERS_PANEL_WIDTH;
            guardvillagers$expanded = true;
        }
        x = ProfessionalStoragePanelLayout.clampContainerX(width, backgroundWidth);
        guardvillagers$state().synchronizeTabs(snapshot.tabs().stream().map(ProfessionalStorageTab::id).toList());
        guardvillagers$clampOffsets(snapshot, guardvillagers$geometry(snapshot));
    }

    @Unique
    private void guardvillagers$clampOffsets(ProfessionalStorageSnapshot snapshot, PanelGeometry geometry) {
        Map<String, Integer> maxima = new HashMap<>();
        for (ProfessionalStorageTab tab : snapshot.tabs()) {
            maxima.put(tab.id(), guardvillagers$layoutTab(tab, geometry).maximumScroll());
        }
        guardvillagers$state().clampOffsets(maxima);
    }

    @Unique
    private TabLayout guardvillagers$selectedLayout(
            ProfessionalStorageSnapshot snapshot,
            PanelGeometry geometry
    ) {
        ProfessionalStorageTab selected = snapshot.tabs().stream()
                .filter(tab -> tab.id().equals(guardvillagers$state().selectedTabId()))
                .findFirst()
                .orElse(snapshot.tabs().getFirst());
        return guardvillagers$layoutTab(selected, geometry);
    }

    @Unique
    private TabLayout guardvillagers$layoutTab(ProfessionalStorageTab tab, PanelGeometry geometry) {
        List<RenderedRow> rows = guardvillagers$layoutRows(tab, geometry.contentWidth(false));
        int contentHeight = rows.stream().mapToInt(RenderedRow::height).sum();
        int maximumScroll = ProfessionalStoragePanelLayout.maxScroll(contentHeight, geometry.viewportHeight());
        if (maximumScroll > 0) {
            rows = guardvillagers$layoutRows(tab, geometry.contentWidth(true));
            contentHeight = rows.stream().mapToInt(RenderedRow::height).sum();
            maximumScroll = ProfessionalStoragePanelLayout.maxScroll(contentHeight, geometry.viewportHeight());
        }
        return new TabLayout(tab, rows, contentHeight, maximumScroll);
    }

    @Unique
    private List<RenderedRow> guardvillagers$layoutRows(ProfessionalStorageTab tab, int width) {
        List<RenderedRow> renderedRows = new ArrayList<>(tab.rows().size());
        for (ProfessionalStorageRow row : tab.rows()) {
            ProfessionalStorageTextLayout.WrappedText label = ProfessionalStorageTextLayout.wrap(
                    row.label(), width, textRenderer::getWidth);
            ProfessionalStorageTextLayout.WrappedText value = ProfessionalStorageTextLayout.wrap(
                    row.value(), width, textRenderer::getWidth);
            renderedRows.add(new RenderedRow(
                    row,
                    label,
                    value,
                    ProfessionalStoragePanelLayout.rowHeight(label.lines().size(), value.lines().size())));
        }
        return List.copyOf(renderedRows);
    }

    @Unique
    private PanelGeometry guardvillagers$geometry(ProfessionalStorageSnapshot snapshot) {
        int panelX = x + guardvillagers$vanillaBackgroundWidth + GUARDVILLAGERS_PANEL_GAP;
        int tabRowCount = (snapshot.tabs().size() + 1) / 2;
        int tabY = y + 22;
        int viewportY = tabY + tabRowCount * (GUARDVILLAGERS_TAB_HEIGHT + GUARDVILLAGERS_TAB_GAP) + 3;
        int viewportBottom = Math.max(viewportY, y + backgroundHeight - GUARDVILLAGERS_PANEL_PADDING);
        return new PanelGeometry(
                panelX,
                y,
                tabY,
                panelX + GUARDVILLAGERS_PANEL_PADDING,
                viewportY,
                viewportBottom,
                panelX + GUARDVILLAGERS_PANEL_WIDTH - GUARDVILLAGERS_PANEL_PADDING - GUARDVILLAGERS_SCROLLBAR_WIDTH);
    }

    @Unique
    private List<TabButton> guardvillagers$tabButtons(
            ProfessionalStorageSnapshot snapshot,
            PanelGeometry geometry
    ) {
        int columns = snapshot.tabs().size() == 1 ? 1 : 2;
        int availableWidth = GUARDVILLAGERS_PANEL_WIDTH - 2 * GUARDVILLAGERS_PANEL_PADDING;
        int buttonWidth = (availableWidth - (columns - 1) * GUARDVILLAGERS_TAB_GAP) / columns;
        List<TabButton> buttons = new ArrayList<>(snapshot.tabs().size());
        for (int index = 0; index < snapshot.tabs().size(); index++) {
            int column = index % columns;
            int row = index / columns;
            ProfessionalStorageTab tab = snapshot.tabs().get(index);
            buttons.add(new TabButton(
                    tab.id(),
                    tab.title(),
                    geometry.contentX() + column * (buttonWidth + GUARDVILLAGERS_TAB_GAP),
                    geometry.tabY() + row * (GUARDVILLAGERS_TAB_HEIGHT + GUARDVILLAGERS_TAB_GAP),
                    buttonWidth,
                    GUARDVILLAGERS_TAB_HEIGHT));
        }
        return buttons;
    }

    @Unique
    private void guardvillagers$setScrollFromThumb(
            TabLayout selected,
            PanelGeometry geometry,
            int thumbTop
    ) {
        int thumbHeight = ProfessionalStoragePanelLayout.scrollbarThumbHeight(
                geometry.viewportHeight(), selected.contentHeight());
        int scrollOffset = ProfessionalStoragePanelLayout.scrollOffsetForThumb(
                thumbTop - geometry.viewportY(),
                selected.maximumScroll(),
                geometry.viewportHeight(),
                thumbHeight);
        guardvillagers$state().setOffset(selected.tab().id(), scrollOffset, selected.maximumScroll());
    }

    @Unique
    private ProfessionalStorageScrollState guardvillagers$state() {
        if (guardvillagers$scrollState == null) {
            guardvillagers$scrollState = new ProfessionalStorageScrollState();
        }
        return guardvillagers$scrollState;
    }

    @Unique
    private String guardvillagers$fitSingleLine(String text, int maximumWidth) {
        if (textRenderer.getWidth(text) <= maximumWidth) {
            return text;
        }
        String ellipsis = "…";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (textRenderer.getWidth(text.substring(0, middle) + ellipsis) <= maximumWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low) + ellipsis;
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

    @Unique
    private record PanelGeometry(
            int panelX,
            int panelY,
            int tabY,
            int contentX,
            int viewportY,
            int viewportBottom,
            int scrollbarX
    ) {
        int viewportHeight() {
            return viewportBottom - viewportY;
        }

        int contentRight(boolean scrollbarVisible) {
            return scrollbarVisible
                    ? scrollbarX - 4
                    : panelX + GUARDVILLAGERS_PANEL_WIDTH - GUARDVILLAGERS_PANEL_PADDING;
        }

        int contentWidth(boolean scrollbarVisible) {
            return Math.max(1, contentRight(scrollbarVisible) - contentX);
        }

        boolean viewportContains(double mouseX, double mouseY) {
            return mouseX >= contentX
                    && mouseX < panelX + GUARDVILLAGERS_PANEL_WIDTH - GUARDVILLAGERS_PANEL_PADDING
                    && mouseY >= viewportY
                    && mouseY < viewportBottom;
        }

        boolean scrollbarContains(double mouseX, double mouseY) {
            return mouseX >= scrollbarX
                    && mouseX < scrollbarX + GUARDVILLAGERS_SCROLLBAR_WIDTH
                    && mouseY >= viewportY
                    && mouseY < viewportBottom;
        }
    }

    @Unique
    private record TabButton(String tabId, String title, int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    @Unique
    private record RenderedRow(
            ProfessionalStorageRow row,
            ProfessionalStorageTextLayout.WrappedText label,
            ProfessionalStorageTextLayout.WrappedText value,
            int height
    ) {
    }

    @Unique
    private record TabLayout(
            ProfessionalStorageTab tab,
            List<RenderedRow> rows,
            int contentHeight,
            int maximumScroll
    ) {
    }
}

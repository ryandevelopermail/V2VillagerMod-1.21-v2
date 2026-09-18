package dev.sterner.guardvillagers.client.screen.developer;

import dev.sterner.guardvillagers.common.developer.DeveloperProfession;
import dev.sterner.guardvillagers.common.developer.DeveloperProfessionSelection;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupRequest;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupType;
import dev.sterner.guardvillagers.common.developer.LumberjackInventoryPreset;
import dev.sterner.guardvillagers.common.network.DeveloperSetupRequestPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

final class WorldSetupTab implements DeveloperPanelTab {
    private static final int V1_ROW_HEIGHT = 25;
    private static final int V1_LIST_OFFSET_Y = 53;

    private final EnumSet<DeveloperProfession> selectedV1Professions = EnumSet.noneOf(DeveloperProfession.class);
    private final Map<DeveloperProfession, String> v1Quantities = new EnumMap<>(DeveloperProfession.class);
    private final List<ProfessionRow> professionRows = new ArrayList<>();
    private DeveloperSetupType setupType = DeveloperSetupType.PLAIN_VILLAGER;
    private LumberjackInventoryPreset inventoryPreset = LumberjackInventoryPreset.EMPTY_NATURAL;
    private boolean generateTrees;
    private boolean createFurnaceSetup;
    private boolean createShepherdSupply;
    private int left;
    private int top;
    private int width;
    private int height;
    private int v1VisibleRows;
    private int v1ScrollOffset;
    private TextRenderer textRenderer;
    private ButtonWidget setupTypeButton;
    private ButtonWidget professionButton;
    private ButtonWidget chestButton;
    private ButtonWidget craftingTableButton;
    private ButtonWidget treeButton;
    private ButtonWidget furnaceButton;
    private ButtonWidget shepherdSupplyButton;
    private ButtonWidget inventoryPresetButton;
    private ButtonWidget selectAllButton;
    private ButtonWidget clearAllButton;
    private ButtonWidget scrollUpButton;
    private ButtonWidget scrollDownButton;
    private ButtonWidget createButton;
    private TextFieldWidget treeCountField;
    private String status = "Ready.";
    private int progress;
    private int statusColor = 0xA0A0A0;

    WorldSetupTab() {
        for (DeveloperProfession profession : DeveloperProfession.v1Professions()) {
            v1Quantities.put(profession, Integer.toString(DeveloperSetupRequest.DEFAULT_PROFESSION_QUANTITY));
        }
    }

    @Override
    public Text title() {
        return Text.translatable("screen.guardvillagers.developer_panel.world_setup");
    }

    @Override
    public void init(DeveloperPanelScreen screen, int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.textRenderer = screen.panelTextRenderer();
        this.v1VisibleRows = Math.max(2, (height - 110) / V1_ROW_HEIGHT);
        int columnGap = 14;
        int columnWidth = (width - columnGap) / 2;
        int labelWidth = Math.min(78, columnWidth / 2);
        int leftControlX = left + labelWidth;
        int rightColumnX = left + columnWidth + columnGap;
        int rightControlX = rightColumnX + labelWidth;
        int controlWidth = columnWidth - labelWidth;

        setupTypeButton = screen.addPanelWidget(ButtonWidget.builder(setupTypeText(), button -> {
            setupType = setupType.next();
            button.setMessage(setupTypeText());
            refreshModeControls();
        }).dimensions(leftControlX, top, controlWidth, 20).build());

        professionButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("Lumberjack"), button -> {
        }).dimensions(leftControlX, top + 25, controlWidth, 20).build());
        professionButton.active = false;

        chestButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("Not used"), button -> {
        }).dimensions(leftControlX, top + 50, controlWidth, 20).build());
        chestButton.active = false;

        craftingTableButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("Crafting Table: Required"), button -> {
        }).dimensions(leftControlX, top + 75, controlWidth, 20).build());
        craftingTableButton.active = false;

        treeButton = screen.addPanelWidget(ButtonWidget.builder(treeText(), button -> {
            generateTrees = !generateTrees;
            button.setMessage(treeText());
            treeCountField.setEditable(generateTrees);
            treeCountField.active = generateTrees;
        }).dimensions(rightControlX, top, controlWidth - 48, 20).build());

        treeCountField = new TextFieldWidget(textRenderer, rightControlX + controlWidth - 43, top, 43, 20,
                Text.translatable("screen.guardvillagers.developer_panel.tree_count"));
        treeCountField.setMaxLength(2);
        treeCountField.setText(Integer.toString(DeveloperSetupRequest.DEFAULT_TREE_COUNT));
        treeCountField.setTextPredicate(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        screen.addPanelWidget(treeCountField);

        furnaceButton = screen.addPanelWidget(ButtonWidget.builder(furnaceText(), button -> {
            createFurnaceSetup = !createFurnaceSetup;
            button.setMessage(furnaceText());
        }).dimensions(rightControlX, top + 25, controlWidth, 20).build());

        shepherdSupplyButton = screen.addPanelWidget(ButtonWidget.builder(shepherdSupplyText(), button -> {
            createShepherdSupply = !createShepherdSupply;
            button.setMessage(shepherdSupplyText());
        }).dimensions(rightControlX, top + 50, controlWidth, 20).build());

        inventoryPresetButton = screen.addPanelWidget(ButtonWidget.builder(inventoryPresetText(), button -> {
            inventoryPreset = inventoryPreset.next();
            button.setMessage(inventoryPresetText());
        }).dimensions(rightControlX, top + 75, controlWidth, 20).build());

        selectAllButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("Select All"), button -> {
            selectedV1Professions.addAll(DeveloperProfession.v1Professions());
            refreshProfessionRows();
        }).dimensions(left, top + 28, 90, 20).build());
        clearAllButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("Clear All"), button -> {
            selectedV1Professions.clear();
            refreshProfessionRows();
        }).dimensions(left + 95, top + 28, 90, 20).build());
        scrollUpButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("▲"), button -> scrollV1(-1))
                .dimensions(left + width - 55, top + 28, 25, 20).build());
        scrollDownButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("▼"), button -> scrollV1(1))
                .dimensions(left + width - 25, top + 28, 25, 20).build());

        professionRows.clear();
        for (DeveloperProfession profession : DeveloperProfession.v1Professions()) {
            ButtonWidget selectedButton = screen.addPanelWidget(ButtonWidget.builder(selectionText(profession), button -> {
                if (!selectedV1Professions.add(profession)) {
                    selectedV1Professions.remove(profession);
                }
                refreshProfessionRows();
            }).dimensions(left + 145, top, 90, 20).build());
            TextFieldWidget quantityField = new TextFieldWidget(
                    textRenderer,
                    left + 275,
                    top,
                    38,
                    20,
                    Text.literal(profession.displayName() + " quantity"));
            quantityField.setMaxLength(2);
            quantityField.setText(v1Quantities.get(profession));
            quantityField.setTextPredicate(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
            quantityField.setChangedListener(value -> v1Quantities.put(profession, value));
            screen.addPanelWidget(quantityField);
            professionRows.add(new ProfessionRow(profession, selectedButton, quantityField));
        }

        createButton = screen.addPanelWidget(ButtonWidget.builder(
                Text.translatable("screen.guardvillagers.developer_panel.create"),
                button -> sendRequest()
        ).dimensions(left + (width - 180) / 2, nonV1CreateY(), 180, 20).build());
        refreshModeControls();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawTextWithShadow(textRenderer, Text.literal("Setup Type"), left, top + 6, 0xFFFFFF);
        if (setupType == DeveloperSetupType.V1_PROFESSION) {
            renderV1ProfessionList(context);
            context.drawTextWrapped(textRenderer, Text.literal(statusText()), left, v1StatusY(), width, statusColor);
            return;
        }

        context.drawTextWithShadow(textRenderer, Text.literal("Profession"), left, top + 31, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Paired Chest"), left, top + 56, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Job Block"), left, top + 81, 0xFFFFFF);
        int rightColumnX = left + (width - 14) / 2 + 14;
        context.drawTextWithShadow(textRenderer, Text.literal("Mature Trees"), rightColumnX, top + 6, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Furnace"), rightColumnX, top + 31, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Fence Supply"), rightColumnX, top + 56, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Inventory"), rightColumnX, top + 81, 0xFFFFFF);
        context.drawTextWrapped(textRenderer, Text.literal(statusText()), left, top + 140, width, statusColor);
    }

    private void renderV1ProfessionList(DrawContext context) {
        int first = v1ScrollOffset;
        int last = Math.min(professionRows.size(), first + v1VisibleRows);
        for (int index = first; index < last; index++) {
            ProfessionRow row = professionRows.get(index);
            int rowY = top + V1_LIST_OFFSET_Y + (index - first) * V1_ROW_HEIGHT;
            context.drawTextWithShadow(textRenderer, Text.literal(row.profession().displayName()), left + 4, rowY + 6, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, Text.literal("Qty"), left + 245, rowY + 6, 0xA0A0A0);
        }
        context.drawTextWithShadow(
                textRenderer,
                Text.literal((first + 1) + "-" + last + " / " + professionRows.size()),
                left + width - 122,
                top + 34,
                0xA0A0A0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (setupType != DeveloperSetupType.V1_PROFESSION
                || mouseX < left
                || mouseX > left + width
                || mouseY < top + V1_LIST_OFFSET_Y
                || mouseY > v1CreateY()) {
            return false;
        }
        if (verticalAmount > 0.0D) {
            scrollV1(-1);
        } else if (verticalAmount < 0.0D) {
            scrollV1(1);
        }
        return verticalAmount != 0.0D;
    }

    void onStatus(String message, int progressPercent, boolean finished, boolean success) {
        status = message;
        progress = progressPercent;
        statusColor = finished ? (success ? 0x55FF55 : 0xFF5555) : 0xFFFF55;
        if (createButton != null) {
            createButton.active = finished;
        }
    }

    private void sendRequest() {
        int treeCount = DeveloperSetupRequest.DEFAULT_TREE_COUNT;
        if (generateTrees) {
            try {
                treeCount = Integer.parseInt(treeCountField.getText());
            } catch (NumberFormatException exception) {
                showError("Enter a valid tree count.");
                return;
            }
        }

        List<DeveloperProfessionSelection> professions = new ArrayList<>();
        if (setupType == DeveloperSetupType.V1_PROFESSION) {
            for (DeveloperProfession profession : DeveloperProfession.v1Professions()) {
                if (!selectedV1Professions.contains(profession)) {
                    continue;
                }
                String quantityText = v1Quantities.get(profession);
                try {
                    professions.add(new DeveloperProfessionSelection(profession, Integer.parseInt(quantityText)));
                } catch (NumberFormatException exception) {
                    showError("Enter a valid quantity for " + profession.displayName() + ".");
                    return;
                }
            }
        } else if (setupType == DeveloperSetupType.V2_PROFESSION) {
            professions.add(new DeveloperProfessionSelection(DeveloperProfession.LUMBERJACK, 1));
        }

        boolean createJobBlock = setupType != DeveloperSetupType.PLAIN_VILLAGER;
        boolean includeChest = setupType == DeveloperSetupType.V2_PROFESSION;
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                setupType,
                professions,
                includeChest,
                createJobBlock,
                createFurnaceSetup,
                createShepherdSupply,
                inventoryPreset,
                generateTrees,
                treeCount
        );
        String validationError = request.validationError().orElse(null);
        if (validationError != null) {
            showError(validationError);
            return;
        }
        ClientPlayNetworking.send(new DeveloperSetupRequestPacket(request));
        status = "Sending setup request...";
        progress = 0;
        statusColor = 0xFFFF55;
        createButton.active = false;
    }

    private void showError(String message) {
        status = message;
        statusColor = 0xFF5555;
    }

    private void refreshModeControls() {
        boolean v1 = setupType == DeveloperSetupType.V1_PROFESSION;
        boolean v2 = setupType == DeveloperSetupType.V2_PROFESSION;
        professionButton.visible = !v1;
        professionButton.setMessage(Text.literal(v2 ? "Lumberjack" : "Not used"));
        chestButton.visible = !v1;
        chestButton.setMessage(Text.literal(v2 ? "Paired Chest: Required" : "Not used"));
        craftingTableButton.visible = !v1;
        craftingTableButton.setMessage(Text.literal(v2 ? "Crafting Table: Required" : "Not used"));

        if (!v2) {
            generateTrees = false;
            createFurnaceSetup = false;
            createShepherdSupply = false;
            inventoryPreset = LumberjackInventoryPreset.EMPTY_NATURAL;
        }
        treeButton.visible = !v1;
        treeButton.active = v2;
        treeButton.setMessage(treeText());
        treeCountField.visible = !v1;
        treeCountField.active = v2 && generateTrees;
        treeCountField.setEditable(v2 && generateTrees);
        furnaceButton.visible = !v1;
        furnaceButton.active = v2;
        furnaceButton.setMessage(furnaceText());
        shepherdSupplyButton.visible = !v1;
        shepherdSupplyButton.active = v2;
        shepherdSupplyButton.setMessage(shepherdSupplyText());
        inventoryPresetButton.visible = !v1;
        inventoryPresetButton.active = v2;
        inventoryPresetButton.setMessage(inventoryPresetText());

        selectAllButton.visible = v1;
        clearAllButton.visible = v1;
        scrollUpButton.visible = v1;
        scrollDownButton.visible = v1;
        createButton.setY(v1 ? v1CreateY() : nonV1CreateY());
        refreshProfessionRows();
    }

    private void refreshProfessionRows() {
        boolean v1 = setupType == DeveloperSetupType.V1_PROFESSION;
        int maxOffset = maxV1ScrollOffset();
        v1ScrollOffset = Math.max(0, Math.min(v1ScrollOffset, maxOffset));
        for (int index = 0; index < professionRows.size(); index++) {
            ProfessionRow row = professionRows.get(index);
            boolean visible = v1 && index >= v1ScrollOffset && index < v1ScrollOffset + v1VisibleRows;
            row.selectedButton().visible = visible;
            row.quantityField().visible = visible;
            row.selectedButton().setMessage(selectionText(row.profession()));
            row.quantityField().active = selectedV1Professions.contains(row.profession());
            if (visible) {
                int rowY = top + V1_LIST_OFFSET_Y + (index - v1ScrollOffset) * V1_ROW_HEIGHT;
                row.selectedButton().setY(rowY);
                row.quantityField().setY(rowY);
            }
        }
        if (scrollUpButton != null) {
            scrollUpButton.active = v1ScrollOffset > 0;
            scrollDownButton.active = v1ScrollOffset < maxOffset;
        }
    }

    private void scrollV1(int amount) {
        v1ScrollOffset = Math.max(0, Math.min(maxV1ScrollOffset(), v1ScrollOffset + amount));
        refreshProfessionRows();
    }

    private int maxV1ScrollOffset() {
        return Math.max(0, professionRows.size() - v1VisibleRows);
    }

    private int nonV1CreateY() {
        return top + 110;
    }

    private int v1CreateY() {
        return top + height - 52;
    }

    private int v1StatusY() {
        return top + height - 27;
    }

    private String statusText() {
        return status + (progress > 0 && progress < 100 ? " [" + progress + "%]" : "");
    }

    private Text setupTypeText() {
        return Text.literal(setupType.displayName());
    }

    private Text selectionText(DeveloperProfession profession) {
        return Text.literal(selectedV1Professions.contains(profession) ? "Selected" : "Select");
    }

    private Text treeText() {
        return Text.literal(generateTrees ? "Generate: Yes" : "Generate: No");
    }

    private Text furnaceText() {
        return Text.literal(createFurnaceSetup ? "Create: Yes" : "Create: No");
    }

    private Text shepherdSupplyText() {
        return Text.literal(createShepherdSupply ? "Load: Yes" : "Load: No");
    }

    private Text inventoryPresetText() {
        return Text.literal(inventoryPreset.displayName());
    }

    private record ProfessionRow(
            DeveloperProfession profession,
            ButtonWidget selectedButton,
            TextFieldWidget quantityField
    ) {
    }
}

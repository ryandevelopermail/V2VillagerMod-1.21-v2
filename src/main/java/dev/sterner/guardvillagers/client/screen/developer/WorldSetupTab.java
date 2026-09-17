package dev.sterner.guardvillagers.client.screen.developer;

import dev.sterner.guardvillagers.common.developer.DeveloperProfession;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupRequest;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupType;
import dev.sterner.guardvillagers.common.network.DeveloperSetupRequestPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

final class WorldSetupTab implements DeveloperPanelTab {
    private DeveloperSetupType setupType = DeveloperSetupType.PLAIN_VILLAGER;
    private DeveloperProfession profession = DeveloperProfession.LUMBERJACK;
    private boolean createPairedChest = true;
    private boolean generateTrees;
    private int left;
    private int top;
    private int width;
    private TextRenderer textRenderer;
    private ButtonWidget setupTypeButton;
    private ButtonWidget professionButton;
    private ButtonWidget chestButton;
    private ButtonWidget craftingTableButton;
    private ButtonWidget treeButton;
    private ButtonWidget createButton;
    private TextFieldWidget treeCountField;
    private String status = "Ready.";
    private int progress;
    private int statusColor = 0xA0A0A0;

    @Override
    public Text title() {
        return Text.translatable("screen.guardvillagers.developer_panel.world_setup");
    }

    @Override
    public void init(DeveloperPanelScreen screen, int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.textRenderer = screen.panelTextRenderer();
        int labelWidth = 92;
        int controlX = left + labelWidth;
        int controlWidth = width - labelWidth;

        setupTypeButton = screen.addPanelWidget(ButtonWidget.builder(setupTypeText(), button -> {
            setupType = setupType.next();
            button.setMessage(setupTypeText());
            refreshModeControls();
        }).dimensions(controlX, top, controlWidth, 20).build());

        professionButton = screen.addPanelWidget(ButtonWidget.builder(professionText(), button -> {
            profession = profession.next();
            button.setMessage(professionText());
        }).dimensions(controlX, top + 25, controlWidth, 20).build());

        chestButton = screen.addPanelWidget(ButtonWidget.builder(chestText(), button -> {
            createPairedChest = !createPairedChest;
            button.setMessage(chestText());
        }).dimensions(controlX, top + 50, controlWidth, 20).build());

        craftingTableButton = screen.addPanelWidget(ButtonWidget.builder(Text.literal("Crafting Table: Required"), button -> {
        }).dimensions(controlX, top + 75, controlWidth, 20).build());
        craftingTableButton.active = false;

        treeButton = screen.addPanelWidget(ButtonWidget.builder(treeText(), button -> {
            generateTrees = !generateTrees;
            button.setMessage(treeText());
            treeCountField.setEditable(generateTrees);
            treeCountField.active = generateTrees;
        }).dimensions(controlX, top + 100, controlWidth - 48, 20).build());

        treeCountField = new TextFieldWidget(textRenderer, controlX + controlWidth - 43, top + 100, 43, 20,
                Text.translatable("screen.guardvillagers.developer_panel.tree_count"));
        treeCountField.setMaxLength(2);
        treeCountField.setText(Integer.toString(DeveloperSetupRequest.DEFAULT_TREE_COUNT));
        treeCountField.setTextPredicate(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        treeCountField.setEditable(generateTrees);
        treeCountField.active = generateTrees;
        screen.addPanelWidget(treeCountField);

        createButton = screen.addPanelWidget(ButtonWidget.builder(
                Text.translatable("screen.guardvillagers.developer_panel.create"),
                button -> sendRequest()
        ).dimensions(controlX, top + 130, controlWidth, 20).build());
        refreshModeControls();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawTextWithShadow(textRenderer, Text.literal("Setup Type"), left, top + 6, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Profession"), left, top + 31, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Paired Chest"), left, top + 56, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Job Block"), left, top + 81, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Mature Trees"), left, top + 106, 0xFFFFFF);
        String progressText = progress > 0 && progress < 100 ? " [" + progress + "%]" : "";
        context.drawTextWithShadow(textRenderer, Text.literal(status + progressText), left, top + 157, statusColor);
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
                status = "Enter a valid tree count.";
                statusColor = 0xFF5555;
                return;
            }
        }
        boolean createTable = setupType != DeveloperSetupType.PLAIN_VILLAGER;
        boolean includeChest = setupType == DeveloperSetupType.V2_PROFESSION && createPairedChest;
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                setupType,
                profession,
                includeChest,
                createTable,
                generateTrees,
                treeCount
        );
        String validationError = request.validationError().orElse(null);
        if (validationError != null) {
            status = validationError;
            statusColor = 0xFF5555;
            return;
        }
        ClientPlayNetworking.send(new DeveloperSetupRequestPacket(request));
        status = "Sending setup request...";
        progress = 0;
        statusColor = 0xFFFF55;
        createButton.active = false;
    }

    private void refreshModeControls() {
        boolean v2 = setupType == DeveloperSetupType.V2_PROFESSION;
        boolean professionSetup = setupType != DeveloperSetupType.PLAIN_VILLAGER;
        chestButton.active = v2;
        chestButton.setMessage(v2 ? chestText() : Text.literal("Not used"));
        craftingTableButton.setMessage(Text.literal(professionSetup ? "Crafting Table: Required" : "Not used"));
    }

    private Text setupTypeText() {
        return Text.literal(setupType.displayName());
    }

    private Text professionText() {
        return Text.literal(profession.displayName());
    }

    private Text chestText() {
        return Text.literal(createPairedChest ? "Create: Yes" : "Create: No");
    }

    private Text treeText() {
        return Text.literal(generateTrees ? "Generate: Yes" : "Generate: No");
    }
}

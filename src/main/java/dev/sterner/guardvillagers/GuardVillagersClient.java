package dev.sterner.guardvillagers;

import dev.sterner.guardvillagers.client.model.GuardArmorModel;
import dev.sterner.guardvillagers.client.model.GuardSteveModel;
import dev.sterner.guardvillagers.client.model.GuardVillagerModel;
import dev.sterner.guardvillagers.client.renderer.GuardRenderer;
import dev.sterner.guardvillagers.client.screen.GuardVillagerScreen;
import dev.sterner.guardvillagers.client.screen.developer.DeveloperPanelScreen;
import dev.sterner.guardvillagers.common.network.DeveloperSetupStatusPacket;
import dev.sterner.guardvillagers.common.network.OpenDeveloperPanelPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

import static dev.sterner.guardvillagers.GuardVillagers.*;

public class GuardVillagersClient implements ClientModInitializer {

    public static EntityModelLayer GUARD = new EntityModelLayer(GuardVillagers.id( "guard"), "main");
    public static EntityModelLayer GUARD_STEVE = new EntityModelLayer(GuardVillagers.id( "guard_steve"), "main");
    public static EntityModelLayer GUARD_ARMOR_OUTER = new EntityModelLayer(GuardVillagers.id( "guard_armor_outer"), "main");
    public static EntityModelLayer GUARD_ARMOR_INNER = new EntityModelLayer(GuardVillagers.id( "guard_armor_inner"), "main");


    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OpenDeveloperPanelPacket.ID, (payload, context) ->
                context.client().execute(() -> context.client().setScreen(new DeveloperPanelScreen())));
        ClientPlayNetworking.registerGlobalReceiver(DeveloperSetupStatusPacket.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().currentScreen instanceof DeveloperPanelScreen screen) {
                        screen.onSetupStatus(payload.message(), payload.progressPercent(), payload.finished(), payload.success());
                    }
                }));
        HandledScreens.register(GUARD_SCREEN_HANDLER, GuardVillagerScreen::new);
        EntityModelLayerRegistry.registerModelLayer(GUARD, GuardVillagerModel::createBodyLayer);
        EntityModelLayerRegistry.registerModelLayer(GUARD_STEVE, GuardSteveModel::createMesh);
        EntityModelLayerRegistry.registerModelLayer(GUARD_ARMOR_OUTER, GuardArmorModel::createOuterArmorLayer);
        EntityModelLayerRegistry.registerModelLayer(GUARD_ARMOR_INNER, GuardArmorModel::createInnerArmorLayer);
        EntityRendererRegistry.register(GUARD_VILLAGER, GuardRenderer::new);
        EntityRendererRegistry.register(AXE_GUARD_VILLAGER, GuardRenderer::new);
        EntityRendererRegistry.register(BUTCHER_GUARD_VILLAGER, GuardRenderer::new);
        EntityRendererRegistry.register(MASON_GUARD_VILLAGER, GuardRenderer::new);
        EntityRendererRegistry.register(FISHERMAN_GUARD_VILLAGER, GuardRenderer::new);
        EntityRendererRegistry.register(LUMBERJACK_GUARD_VILLAGER, GuardRenderer::new);


    }
}

package dev.sterner.guardvillagers.common.developer;

import dev.sterner.guardvillagers.common.network.OpenDeveloperPanelPacket;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;

public final class DeveloperCommands {
    private DeveloperCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("v2dev")
                        .requires(source -> source.hasPermissionLevel(DeveloperSetupManager.REQUIRED_PERMISSION_LEVEL))
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            ServerPlayNetworking.send(player, new OpenDeveloperPanelPacket());
                            return 1;
                        })));
    }
}

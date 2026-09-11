package ru.mqclass.ipcopy.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.example.spacemoderation.feature.chat.commands.DotCommandSuggestions", remap = false)
public abstract class SpaceModerationSuggestionsMixin {

    @Inject(
        method = "registerDotCommands(Lcom/mojang/brigadier/CommandDispatcher;)V",
        at = @At("TAIL"),
        remap = false,
        require = 0
    )
    private static void ipcopy$registerSuggestions(
        CommandDispatcher<FabricClientCommandSource> dispatcher,
        CallbackInfo callback
    ) {
        dispatcher.register(ClientCommandManager.literal(".ipcopy")
            .executes(context -> 1)
            .then(ClientCommandManager.literal("gui").executes(context -> 1))
            .then(ClientCommandManager.literal("test").executes(context -> 1))
            .then(ClientCommandManager.literal("toggle").executes(context -> 1))
            .then(ClientCommandManager.literal("history").executes(context -> 1))
            .then(ClientCommandManager.literal("clear").executes(context -> 1))
            .then(ClientCommandManager.literal("reload").executes(context -> 1))
        );
        dispatcher.register(ClientCommandManager.literal(".apf")
            .then(ClientCommandManager.argument("ник", StringArgumentType.word()).executes(context -> 1))
        );
    }
}

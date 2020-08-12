package org.dynmap.fabric_1_16_1.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.MessageType;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.dynmap.fabric_1_16_1.context.OnGameMessageContext;
import org.dynmap.fabric_1_16_1.event.PlayerEvents;
import org.dynmap.fabric_1_16_1.event.ServerChatEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
    @Inject(method = "broadcastChatMessage", at = @At("HEAD"))
    public void broadcastChatMessage(Text message, MessageType type, UUID senderUuid, CallbackInfo info) {
        ServerPlayerEntity player = OnGameMessageContext.getContext();
        if (player != null) {
            TranslatableText translatableText = (TranslatableText) message;
            String string = (String) translatableText.getArgs()[1];
            ServerChatEvents.EVENT.invoker().onChatMessage(player, string);
        }
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    public void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, CallbackInfo info) {
        PlayerEvents.PLAYER_LOGGED_IN.invoker().onPlayerLoggedIn(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    public void remove(ServerPlayerEntity player, CallbackInfo info) {
        PlayerEvents.PLAYER_LOGGED_OUT.invoker().onPlayerLoggedOut(player);
    }

    @Inject(method = "respawnPlayer", at = @At("TAIL"))
    public void respawnPlayer(ServerPlayerEntity player, boolean alive, CallbackInfoReturnable<ServerPlayerEntity> info) {
        PlayerEvents.PLAYER_RESPAWN.invoker().onPlayerRespawn(player);
    }
}

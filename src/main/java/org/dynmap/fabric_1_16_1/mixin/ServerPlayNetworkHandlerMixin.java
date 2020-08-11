package org.dynmap.fabric_1_16_1.mixin;

import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.dynmap.fabric_1_16_1.context.OnGameMessageContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin implements ServerPlayNetworkHandlerAccessor {
    @Inject(method = "onGameMessage", at = @At("HEAD"), cancellable = true)
    public void onGameMessageBegin(ChatMessageC2SPacket packet, CallbackInfo info) {
        OnGameMessageContext.begin(getPlayer());
    }

    @Inject(method = "onGameMessage", at = @At("RETURN"), cancellable = true)
    public void onGameMessageEnd(ChatMessageC2SPacket packet, CallbackInfo info) {
        OnGameMessageContext.end();
    }
}

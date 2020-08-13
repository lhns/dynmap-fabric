package org.dynmap.fabric_1_16_2.mixin;

import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.dynmap.fabric_1_16_2.context.OnGameMessageContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    public void onGameMessageBegin(ChatMessageC2SPacket packet, CallbackInfo info) {
        OnGameMessageContext.begin(this.player);
    }

    @Inject(method = "onGameMessage", at = @At("RETURN"))
    public void onGameMessageEnd(ChatMessageC2SPacket packet, CallbackInfo info) {
        OnGameMessageContext.end();
    }
}

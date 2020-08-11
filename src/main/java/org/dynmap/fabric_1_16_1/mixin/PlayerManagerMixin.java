package org.dynmap.fabric_1_16_1.mixin;

import net.minecraft.network.MessageType;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.dynmap.fabric_1_16_1.context.OnGameMessageContext;
import org.dynmap.fabric_1_16_1.event.ServerChatEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    public void broadcastChatMessage(Text message, MessageType type, UUID senderUuid, CallbackInfo info) {
        ServerPlayerEntity player = OnGameMessageContext.getContext();
        if (player != null) {
            TranslatableText translatableText = (TranslatableText) message;
            String string = (String) translatableText.getArgs()[1];
            ServerChatEvents.EVENT.invoker().onChatMessage(player, string);
        }
    }
}

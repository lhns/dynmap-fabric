package org.dynmap.fabric_1_16_1.event;

import net.minecraft.server.network.ServerPlayerEntity;

public class ServerChatEvent {
    private final ServerPlayerEntity player;
    private final String message;

    public ServerChatEvent(ServerPlayerEntity player, String message) {
        this.player = player;
        this.message = message;
    }

    public ServerPlayerEntity getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }
}
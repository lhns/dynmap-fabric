package org.dynmap.fabric_1_16_1.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.network.ServerPlayerEntity;

public class ServerChatEvents {
    private ServerChatEvents() {
    }

    // TODO call from ServerPlayNetworkHandler.onGameMessage
    public static Event<ServerChatCallback> EVENT = EventFactory.createArrayBacked(ServerChatCallback.class,
            (listeners) -> (event) -> {
                for (ServerChatCallback callback : listeners) {
                    callback.onChatMessage(event);
                }
            }
    );

    @FunctionalInterface
    public interface ServerChatCallback {
        void onChatMessage(ServerChatEvent event);
    }

    public static class ServerChatEvent {
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
}
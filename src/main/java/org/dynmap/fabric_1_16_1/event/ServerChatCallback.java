package org.dynmap.fabric_1_16_1.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

public interface ServerChatCallback {
    // TODO call from ServerPlayNetworkHandler.onGameMessage
    Event<ServerChatCallback> EVENT = EventFactory.createArrayBacked(ServerChatCallback.class,
            (listeners) -> (event) -> {
                for (ServerChatCallback callback : listeners) {
                    callback.onChatMessage(event);
                }
            }
    );

    void onChatMessage(ServerChatEvent event);
}

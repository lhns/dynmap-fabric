package org.dynmap.fabric_1_16_1.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.network.ServerPlayerEntity;

public class PlayerEvents {
    private PlayerEvents() {
    }

    // TODO: implement all

    public static Event<PlayerLoggedIn> PLAYER_LOGGED_IN = EventFactory.createArrayBacked(PlayerLoggedIn.class,
            (listeners) -> (event) -> {
                for (PlayerLoggedIn callback : listeners) {
                    callback.onPlayerLoggedIn(event);
                }
            }
    );

    public static Event<PlayerLoggedOut> PLAYER_LOGGED_OUT = EventFactory.createArrayBacked(PlayerLoggedOut.class,
            (listeners) -> (event) -> {
                for (PlayerLoggedOut callback : listeners) {
                    callback.onPlayerLoggedOut(event);
                }
            }
    );

    public static Event<PlayerChangedDimension> PLAYER_CHANGED_DIMENSION = EventFactory.createArrayBacked(PlayerChangedDimension.class,
            (listeners) -> (event) -> {
                for (PlayerChangedDimension callback : listeners) {
                    callback.onPlayerChangedDimension(event);
                }
            }
    );

    public static Event<PlayerRespawn> PLAYER_RESPAWN = EventFactory.createArrayBacked(PlayerRespawn.class,
            (listeners) -> (event) -> {
                for (PlayerRespawn callback : listeners) {
                    callback.onPlayerRespawn(event);
                }
            }
    );

    @FunctionalInterface
    public interface PlayerLoggedIn {
        void onPlayerLoggedIn(PlayerLoggedInEvent event);
    }

    @FunctionalInterface
    public interface PlayerLoggedOut {
        void onPlayerLoggedOut(PlayerLoggedOutEvent event);
    }

    @FunctionalInterface
    public interface PlayerChangedDimension {
        void onPlayerChangedDimension(PlayerChangedDimensionEvent event);
    }

    @FunctionalInterface
    public interface PlayerRespawn {
        void onPlayerRespawn(PlayerRespawnEvent event);
    }

    public static class PlayerLoggedInEvent {
        private final ServerPlayerEntity player;

        public PlayerLoggedInEvent(ServerPlayerEntity player) {
            this.player = player;
        }

        public ServerPlayerEntity getPlayer() {
            return player;
        }
    }

    public static class PlayerLoggedOutEvent {
        private final ServerPlayerEntity player;

        public PlayerLoggedOutEvent(ServerPlayerEntity player) {
            this.player = player;
        }

        public ServerPlayerEntity getPlayer() {
            return player;
        }
    }

    public static class PlayerChangedDimensionEvent {
        private final ServerPlayerEntity player;

        public PlayerChangedDimensionEvent(ServerPlayerEntity player) {
            this.player = player;
        }

        public ServerPlayerEntity getPlayer() {
            return player;
        }
    }

    public static class PlayerRespawnEvent {
        private final ServerPlayerEntity player;

        public PlayerRespawnEvent(ServerPlayerEntity player) {
            this.player = player;
        }

        public ServerPlayerEntity getPlayer() {
            return player;
        }
    }
}

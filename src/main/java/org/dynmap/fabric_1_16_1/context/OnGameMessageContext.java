package org.dynmap.fabric_1_16_1.context;

import net.minecraft.server.network.ServerPlayerEntity;

import javax.annotation.Nullable;

public class OnGameMessageContext {
    private OnGameMessageContext() {
    }

    private static final ThreadLocal<ServerPlayerEntity> localPlayer = new ThreadLocal<>();

    public static void begin(ServerPlayerEntity player) {
        localPlayer.set(player);
    }

    public static void end() {
        localPlayer.remove();
    }

    @Nullable
    public static ServerPlayerEntity getContext() {
        return localPlayer.get();
    }
}

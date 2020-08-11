package org.dynmap.fabric_1_16_1;

import com.sun.nio.zipfs.ZipFileSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.server.ServerStartCallback;
import net.fabricmc.fabric.api.event.server.ServerStopCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.Log;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DynmapMod implements ModInitializer, DedicatedServerModInitializer, ClientModInitializer {// The instance of your mod that Forge uses.
    public static DynmapMod instance;

    // Says where the client and server 'proxy' code is loaded.
    public static Proxy proxy;

    public static DynmapPlugin plugin;
    public static File jarfile;
    public static String ver;
    public static boolean useforcedchunks;

    public class APICallback extends DynmapCommonAPIListener {
        @Override
        public void apiListenerAdded() {
            if (plugin == null) {
                plugin = proxy.startServer(server);
            }
        }

        @Override
        public void apiEnabled(DynmapCommonAPI api) {
        }
    }

    @Override
    public void onInitialize() {
        instance = this;

        ModContainer container = FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class)
                .stream().filter(e -> this == e.getEntrypoint()).findAny().get().getProvider();

        Path path = container.getRootPath();
        if (path.getFileSystem() instanceof ZipFileSystem) {
            path = Paths.get(path.getFileSystem().toString());
        }

        jarfile = path.toFile();
        ver = container.getMetadata().getVersion().getFriendlyString();

        Log.setLogger(new DynmapPlugin.OurLog());
        org.dynmap.modsupport.ModSupportImpl.init();

        ServerStartCallback.EVENT.register(server -> {
            onServerStarting(server);
            onServerStarted(server);
        });
        ServerStopCallback.EVENT.register(this::serverStopping);
    }

    @Override
    public void onInitializeClient() {
        proxy = new ClientProxy();
    }

    @Override
    public void onInitializeServer() {
        proxy = new Proxy();
    }

    private MinecraftServer server;

    public void onServerStarting(MinecraftServer server) {
        this.server = server;
        if (plugin == null)
            plugin = proxy.startServer(server);
        plugin.onStarting(server.getCommandManager().getDispatcher());
    }

    public void onServerStarted(MinecraftServer server) {
        DynmapCommonAPIListener.register(new APICallback());
        plugin.serverStarted();
    }

    public void serverStopping(MinecraftServer server) {
        proxy.stopServer(plugin);
        plugin = null;
    }
}

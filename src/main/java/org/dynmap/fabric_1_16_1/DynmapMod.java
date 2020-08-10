package org.dynmap.fabric_1_16_1;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.Log;

import java.io.File;

public class DynmapMod implements ModInitializer {// The instance of your mod that Forge uses.
    public static DynmapMod instance;

    // Says where the client and server 'proxy' code is loaded.
    public static Proxy proxy = DistExecutor.runForDist(() -> ClientProxy::new, () -> Proxy::new);

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

        jarfile = null; //ModList.get().getModFileById("dynmap").getFile().getFilePath().toFile();
        ver = "0.0"; //ModList.get().getModContainerById("dynmap").get().getModInfo().getVersion().toString();

        Log.setLogger(new DynmapPlugin.OurLog());
        org.dynmap.modsupport.ModSupportImpl.init();
    }

    private MinecraftServer server;

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        server = event.getServer();
        if (plugin == null)
            plugin = proxy.startServer(server);
        plugin.onStarting(server.getCommandManager().getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        DynmapCommonAPIListener.register(new APICallback());
        plugin.serverStarted();
    }

    @SubscribeEvent
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.stopServer(plugin);
        plugin = null;
    }
}

package org.dynmap.fabric_1_16_1;

import com.google.common.collect.Iterables;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.nio.zipfs.ZipFileSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.Material;
import net.minecraft.command.CommandException;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.MessageType;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.BannedIpList;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.UserCache;
import net.minecraft.util.Util;
import net.minecraft.util.collection.IdList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dynmap.*;
import org.dynmap.common.*;
import org.dynmap.fabric_1_16_1.event.BlockEvents;
import org.dynmap.fabric_1_16_1.event.ChunkDataEvents;
import org.dynmap.fabric_1_16_1.event.PlayerEvents;
import org.dynmap.fabric_1_16_1.event.ServerChatEvents;
import org.dynmap.fabric_1_16_1.mixin.BiomeEffectsAccessor;
import org.dynmap.fabric_1_16_1.mixin.ThreadedAnvilChunkStorageAccessor;
import org.dynmap.fabric_1_16_1.permissions.FilePermissions;
import org.dynmap.fabric_1_16_1.permissions.OpPermissions;
import org.dynmap.fabric_1_16_1.permissions.PermissionProvider;
import org.dynmap.permissions.PermissionsHandler;
import org.dynmap.renderer.DynmapBlockState;
import org.dynmap.utils.DynmapLogger;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.VisibilityLimit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DynmapPlugin {
    private DynmapCore core;
    private PermissionProvider permissions;
    private boolean core_enabled;
    public SnapshotCache sscache;
    public PlayerList playerList;
    private MapManager mapManager;
    private net.minecraft.server.MinecraftServer server;
    public static DynmapPlugin plugin;
    private ChatHandler chathandler;
    private HashMap<String, Integer> sortWeights = new HashMap<String, Integer>();
    // Drop world load ticket after 30 seconds
    private long worldIdleTimeoutNS = 30 * 1000000000L;
    private HashMap<String, ForgeWorld> worlds = new HashMap<String, ForgeWorld>();
    private WorldAccess last_world;
    private ForgeWorld last_fworld;
    private Map<String, ForgePlayer> players = new HashMap<String, ForgePlayer>();
    //TODO private ForgeMetrics metrics;
    private HashSet<String> modsused = new HashSet<String>();
    private ForgeServer fserver = new ForgeServer();
    private boolean tickregistered = false;
    // TPS calculator
    private double tps;
    private long lasttick;
    private long avgticklen;
    // Per tick limit, in nsec
    private long perTickLimit = (50000000); // 50 ms
    private boolean useSaveFolder = true;

    private static final int SIGNPOST_ID = 63;
    private static final int WALLSIGN_ID = 68;

    private static final String[] TRIGGER_DEFAULTS = { "blockupdate", "chunkpopulate", "chunkgenerate" };

    private static final Pattern patternControlCode = Pattern.compile("(?i)\\u00A7[0-9A-FK-OR]");

    public static class BlockUpdateRec {
        WorldAccess w;
        String wid;
        int x, y, z;
    }
    ConcurrentLinkedQueue<BlockUpdateRec> blockupdatequeue = new ConcurrentLinkedQueue<BlockUpdateRec>();

    public static DynmapBlockState[] stateByID;

    private Map<String, LongOpenHashSet> knownloadedchunks = new HashMap<String, LongOpenHashSet>();
    private boolean didInitialKnownChunks = false;
    private void addKnownChunk(ForgeWorld fw, ChunkPos pos) {
        LongOpenHashSet cset = knownloadedchunks.get(fw.getName());
        if (cset == null) {
            cset = new LongOpenHashSet();
            knownloadedchunks.put(fw.getName(), cset);
        }
        cset.add(pos.toLong());
    }
    private void removeKnownChunk(ForgeWorld fw, ChunkPos pos) {
        LongOpenHashSet cset = knownloadedchunks.get(fw.getName());
        if (cset != null) {
            cset.remove(pos.toLong());
        }
    }
    private boolean checkIfKnownChunk(ForgeWorld fw, ChunkPos pos) {
        LongOpenHashSet cset = knownloadedchunks.get(fw.getName());
        if (cset != null) {
            return cset.contains(pos.toLong());
        }
        return false;
    }

    /**
     * Initialize block states (org.dynmap.blockstate.DynmapBlockState)
     */
    public void initializeBlockStates() {
        stateByID = new DynmapBlockState[512*32];	// Simple map - scale as needed
        Arrays.fill(stateByID, DynmapBlockState.AIR); // Default to air

        IdList<BlockState> bsids = Block.STATE_IDS;

        DynmapBlockState basebs = null;
        Block baseb = null;
        int baseidx = 0;

        Iterator<BlockState> iter = bsids.iterator();
        while (iter.hasNext()) {
            BlockState bs = iter.next();
            int idx = bsids.getId(bs);
            if (idx >= stateByID.length) {
                int plen = stateByID.length;
                stateByID = Arrays.copyOf(stateByID, idx+1);
                Arrays.fill(stateByID, plen, stateByID.length, DynmapBlockState.AIR);
            }
            Block b = bs.getBlock();
            // If this is new block vs last, it's the base block state
            if (b != baseb) {
                basebs = null;
                baseidx = idx;
                baseb = b;
            }

            Identifier ui = Registry.BLOCK.getId(b);
            if (ui == null) {
                continue;
            }
            String bn = ui.getNamespace() + ":" + ui.getPath();
            // Only do defined names, and not "air"
            if (!bn.equals(DynmapBlockState.AIR_BLOCK)) {
                Material mat = bs.getMaterial();
                String statename = "";
                for(net.minecraft.state.property.Property<?> p : bs.getProperties()) {
                    if (statename.length() > 0) {
                        statename += ",";
                    }
                    statename += p.getName() + "=" + bs.get(p).toString();
                }
                //Log.info("bn=" + bn + ", statenme=" + statename + ",idx=" + idx + ",baseidx=" + baseidx);
                DynmapBlockState dbs = new DynmapBlockState(basebs, idx - baseidx, bn, statename, mat.toString(), idx);
                stateByID[idx] = dbs;
                if (basebs == null) { basebs = dbs; }
                if (mat.isSolid()) {
                    dbs.setSolid();
                }
                if (mat == Material.AIR) {
                    dbs.setAir();
                }
                if (mat == Material.WOOD) {
                    dbs.setLog();
                }
                if (mat == Material.LEAVES) {
                    dbs.setLeaves();
                }
                if ((!bs.getFluidState().isEmpty()) && !(bs.getBlock() instanceof FluidBlock)) {
                    dbs.setWaterlogged();
                }
            }
        }
        for (int gidx = 0; gidx < DynmapBlockState.getGlobalIndexMax(); gidx++) {
            DynmapBlockState bs = DynmapBlockState.getStateByGlobalIndex(gidx);
            //Log.info(gidx + ":" + bs.toString() + ", gidx=" + bs.globalStateIndex + ", sidx=" + bs.stateIndex);
        }
    }

    public static final Item getItemByID(int id) {
        return Item.byRawId(id);
    }

    private static Biome[] biomelist = null;

    public static final Biome[] getBiomeList() {
        if (biomelist == null) {
            biomelist = new Biome[256];
            Iterator<Biome> iter = Registry.BIOME.iterator();
            while (iter.hasNext()) {
                Biome b = iter.next();
                int bidx = Registry.BIOME.getRawId(b);
                if (bidx >= biomelist.length) {
                    biomelist = Arrays.copyOf(biomelist, bidx + biomelist.length);
                }
                biomelist[bidx] = b;
            }
        }
        return biomelist;
    }
    public static final ClientConnection getNetworkManager(ServerPlayNetworkHandler nh) {
        return nh.connection;
    }

    private ForgePlayer getOrAddPlayer(PlayerEntity p) {
        String name = p.getName().getString();
        ForgePlayer fp = players.get(name);
        if(fp != null) {
            fp.player = p;
        }
        else {
            fp = new ForgePlayer(p);
            players.put(name, fp);
        }
        return fp;
    }

    private static class TaskRecord implements Comparable<Object>
    {
        private long ticktorun;
        private long id;
        private FutureTask<?> future;
        @Override
        public int compareTo(Object o)
        {
            TaskRecord tr = (TaskRecord)o;

            if (this.ticktorun < tr.ticktorun)
            {
                return -1;
            }
            else if (this.ticktorun > tr.ticktorun)
            {
                return 1;
            }
            else if (this.id < tr.id)
            {
                return -1;
            }
            else if (this.id > tr.id)
            {
                return 1;
            }
            else
            {
                return 0;
            }
        }
    }

    private class ChatMessage {
        String message;
        PlayerEntity sender;
    }
    private ConcurrentLinkedQueue<ChatMessage> msgqueue = new ConcurrentLinkedQueue<ChatMessage>();

    public class ChatHandler {
        public void handleChat(ServerChatEvents.ServerChatEvent event) {
            String msg = event.getMessage();
            if (!msg.startsWith("/")) {
                ChatMessage cm = new ChatMessage();
                cm.message = msg;
                cm.sender = event.getPlayer();
                msgqueue.add(cm);
            }
        }
    }

    public static class OurLog implements DynmapLogger {
        Logger log;
        public static final String DM = "[Dynmap] ";

        OurLog() {
            log = LogManager.getLogger("Dynmap");
        }

        @Override
        public void info(String s) {
            log.info(DM + s);
        }

        @Override
        public void severe(Throwable t) {
            log.fatal(t);
        }

        @Override
        public void severe(String s) {
            log.fatal(DM + s);
        }

        @Override
        public void severe(String s, Throwable t) {
            log.fatal(DM + s, t);
        }

        @Override
        public void verboseinfo(String s) {
            log.info(DM + s);
        }

        @Override
        public void warning(String s) {
            log.warn(DM + s);
        }

        @Override
        public void warning(String s, Throwable t) {
            log.warn(DM + s, t);
        }
    }
    public DynmapPlugin(MinecraftServer srv)
    {
        plugin = this;
        this.server = srv;
    }

    public boolean isOp(String player) {
        String[] ops = server.getPlayerManager().getOpList().getNames();
        for (String op : ops) {
            if (op.equalsIgnoreCase(player)) {
                return true;
            }
        }
        return (server.isSinglePlayer() && player.equalsIgnoreCase(server.getUserName()));
    }

    private boolean hasPerm(PlayerEntity psender, String permission) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if((psender != null) && ph.hasPermission(psender.getName().getString(), permission)) {
            return true;
        }
        return permissions.has(psender, permission);
    }

    private boolean hasPermNode(PlayerEntity psender, String permission) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if((psender != null) && ph.hasPermissionNode(psender.getName().getString(), permission)) {
            return true;
        }
        return permissions.hasPermissionNode(psender, permission);
    }

    private Set<String> hasOfflinePermissions(String player, Set<String> perms) {
        Set<String> rslt = null;
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if(ph != null) {
            rslt = ph.hasOfflinePermissions(player, perms);
        }
        Set<String> rslt2 = hasOfflinePermissions(player, perms);
        if((rslt != null) && (rslt2 != null)) {
            Set<String> newrslt = new HashSet<String>(rslt);
            newrslt.addAll(rslt2);
            rslt = newrslt;
        }
        else if(rslt2 != null) {
            rslt = rslt2;
        }
        return rslt;
    }
    private boolean hasOfflinePermission(String player, String perm) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if(ph != null) {
            if(ph.hasOfflinePermission(player, perm)) {
                return true;
            }
        }
        return permissions.hasOfflinePermission(player, perm);
    }

    /**
     * Server access abstraction class
     */
    public class ForgeServer extends DynmapServerInterface
    {
        /* Server thread scheduler */
        private final Object schedlock = new Object();
        private long cur_tick;
        private long next_id;
        private long cur_tick_starttime;
        private PriorityQueue<TaskRecord> runqueue = new PriorityQueue<TaskRecord>();

        public ForgeServer() {
        }

        private GameProfile getProfileByName(String player) {
            UserCache cache = server.getUserCache();
            return cache.findByName(player);
        }

        @Override
        public int getBlockIDAt(String wname, int x, int y, int z) {
            return -1;
        }

        @Override
        public int isSignAt(String wname, int x, int y, int z) {
            return -1;
        }

        @Override
        public void scheduleServerTask(Runnable run, long delay)
        {
            TaskRecord tr = new TaskRecord();
            tr.future = new FutureTask<Object>(run, null);

            /* Add task record to queue */
            synchronized (schedlock)
            {
                tr.id = next_id++;
                tr.ticktorun = cur_tick + delay;
                runqueue.add(tr);
            }
        }
        @Override
        public DynmapPlayer[] getOnlinePlayers()
        {
            if(server.getPlayerManager() == null)
                return new DynmapPlayer[0];
            List<ServerPlayerEntity> playlist = server.getPlayerManager().getPlayerList();
            int pcnt = playlist.size();
            DynmapPlayer[] dplay = new DynmapPlayer[pcnt];

            for (int i = 0; i < pcnt; i++)
            {
                PlayerEntity p = playlist.get(i);
                dplay[i] = getOrAddPlayer(p);
            }

            return dplay;
        }
        @Override
        public void reload()
        {
            plugin.onDisable();
            plugin.onEnable();
            plugin.onStart();
        }
        @Override
        public DynmapPlayer getPlayer(String name)
        {
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();

            for (Object o : players)
            {
                PlayerEntity p = (PlayerEntity)o;

                if (p.getName().getString().equalsIgnoreCase(name))
                {
                    return getOrAddPlayer(p);
                }
            }

            return null;
        }
        @Override
        public Set<String> getIPBans()
        {
            BannedIpList bl = server.getPlayerManager().getIpBanList();
            Set<String> ips = new HashSet<String>();

            for (String s : bl.getNames()) {
                ips.add(s);
            }

            return ips;
        }
        @Override
        public <T> Future<T> callSyncMethod(Callable<T> task) {
            return callSyncMethod(task, 0);
        }
        public <T> Future<T> callSyncMethod(Callable<T> task, long delay)
        {
            TaskRecord tr = new TaskRecord();
            FutureTask<T> ft = new FutureTask<T>(task);
            tr.future = ft;

            /* Add task record to queue */
            synchronized (schedlock)
            {
                tr.id = next_id++;
                tr.ticktorun = cur_tick + delay;
                runqueue.add(tr);
            }

            return ft;
        }
        @Override
        public String getServerName()
        {
            String sn;
            if (server.isSinglePlayer())
                sn = "Integrated";
            else
                sn = server.getServerIp();
            if(sn == null) sn = "Unknown Server";
            return sn;
        }
        @Override
        public boolean isPlayerBanned(String pid)
        {
            BannedPlayerList bl = server.getPlayerManager().getUserBanList();
            return bl.contains(getProfileByName(pid));
        }

        @Override
        public String stripChatColor(String s)
        {
            return patternControlCode.matcher(s).replaceAll("");
        }
        private Set<DynmapListenerManager.EventType> registered = new HashSet<DynmapListenerManager.EventType>();
        @Override
        public boolean requestEventNotification(DynmapListenerManager.EventType type)
        {
            if (registered.contains(type))
            {
                return true;
            }

            switch (type)
            {
                case WORLD_LOAD:
                case WORLD_UNLOAD:
                    /* Already called for normal world activation/deactivation */
                    break;

                case WORLD_SPAWN_CHANGE:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onSpawnChange(SpawnChangeEvent evt) {
                            DynmapWorld w = new BukkitWorld(evt.getWorld());
                            core.listenerManager.processWorldEvent(EventType.WORLD_SPAWN_CHANGE, w);
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                case PLAYER_JOIN:
                case PLAYER_QUIT:
                    /* Already handled */
                    break;

                case PLAYER_BED_LEAVE:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onPlayerBedLeave(PlayerBedLeaveEvent evt) {
                            DynmapPlayer p = new BukkitPlayer(evt.getPlayer());
                            core.listenerManager.processPlayerEvent(EventType.PLAYER_BED_LEAVE, p);
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                case PLAYER_CHAT:
                    if (chathandler == null) {
                        chathandler = new ChatHandler();
                        ServerChatEvents.EVENT.register(event -> chathandler.handleChat(event));
                    }
                    break;

                case BLOCK_BREAK:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onBlockBreak(BlockBreakEvent evt) {
                            if(evt.isCancelled()) return;
                            Block b = evt.getBlock();
                            if(b == null) return;
                            Location l = b.getLocation();
                            core.listenerManager.processBlockEvent(EventType.BLOCK_BREAK, b.getType().getId(),
                                    BukkitWorld.normalizeWorldName(l.getWorld().getName()), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                case SIGN_CHANGE:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onSignChange(SignChangeEvent evt) {
                            if(evt.isCancelled()) return;
                            Block b = evt.getBlock();
                            Location l = b.getLocation();
                            String[] lines = evt.getLines();
                            DynmapPlayer dp = null;
                            Player p = evt.getPlayer();
                            if(p != null) dp = new BukkitPlayer(p);
                            core.listenerManager.processSignChangeEvent(EventType.SIGN_CHANGE, b.getType().getId(),
                                    BukkitWorld.normalizeWorldName(l.getWorld().getName()), l.getBlockX(), l.getBlockY(), l.getBlockZ(), lines, dp);
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                default:
                    Log.severe("Unhandled event type: " + type);
                    return false;
            }

            registered.add(type);
            return true;
        }
        @Override
        public boolean sendWebChatEvent(String source, String name, String msg)
        {
            return DynmapCommonAPIListener.fireWebChatEvent(source, name, msg);
        }
        @Override
        public void broadcastMessage(String msg)
        {
            Text component = new LiteralText(msg);
            server.getPlayerManager().broadcastChatMessage(component, MessageType.SYSTEM, Util.NIL_UUID);
            Log.info(stripChatColor(msg));
        }
        @Override
        public String[] getBiomeIDs()
        {
            BiomeMap[] b = BiomeMap.values();
            String[] bname = new String[b.length];

            for (int i = 0; i < bname.length; i++)
            {
                bname[i] = b[i].toString();
            }

            return bname;
        }
        @Override
        public double getCacheHitRate()
        {
            if(sscache != null)
                return sscache.getHitRate();
            return 0.0;
        }
        @Override
        public void resetCacheStats()
        {
            if(sscache != null)
                sscache.resetStats();
        }
        @Override
        public DynmapWorld getWorldByName(String wname)
        {
            return DynmapPlugin.this.getWorldByName(wname);
        }
        @Override
        public DynmapPlayer getOfflinePlayer(String name)
        {
            /*
            OfflinePlayer op = getServer().getOfflinePlayer(name);
            if(op != null) {
                return new BukkitPlayer(op);
            }
            */
            return null;
        }
        @Override
        public Set<String> checkPlayerPermissions(String player, Set<String> perms)
        {
            PlayerManager scm = server.getPlayerManager();
            if (scm == null) return Collections.emptySet();
            BannedPlayerList bl = scm.getUserBanList();
            if (bl == null) return Collections.emptySet();
            if(bl.contains(getProfileByName(player))) {
                return Collections.emptySet();
            }
            Set<String> rslt = hasOfflinePermissions(player, perms);
            if (rslt == null) {
                rslt = new HashSet<String>();
                if(plugin.isOp(player)) {
                    rslt.addAll(perms);
                }
            }
            return rslt;
        }
        @Override
        public boolean checkPlayerPermission(String player, String perm)
        {
            PlayerManager scm = server.getPlayerManager();
            if (scm == null) return false;
            BannedPlayerList bl = scm.getUserBanList();
            if (bl == null) return false;
            if(bl.contains(getProfileByName(player))) {
                return false;
            }
            return hasOfflinePermission(player, perm);
        }
        /**
         * Render processor helper - used by code running on render threads to request chunk snapshot cache from server/sync thread
         */
        @Override
        public MapChunkCache createMapChunkCache(DynmapWorld w, List<DynmapChunk> chunks,
                                                 boolean blockdata, boolean highesty, boolean biome, boolean rawbiome)
        {
            ForgeMapChunkCache c = (ForgeMapChunkCache) w.getChunkCache(chunks);
            if(c == null) {
                return null;
            }
            if (w.visibility_limits != null)
            {
                for (VisibilityLimit limit: w.visibility_limits)
                {
                    c.setVisibleRange(limit);
                }

                c.setHiddenFillStyle(w.hiddenchunkstyle);
            }

            if (w.hidden_limits != null)
            {
                for (VisibilityLimit limit: w.hidden_limits)
                {
                    c.setHiddenRange(limit);
                }

                c.setHiddenFillStyle(w.hiddenchunkstyle);
            }

            if (!c.setChunkDataTypes(blockdata, biome, highesty, rawbiome))
            {
                Log.severe("CraftBukkit build does not support biome APIs");
            }

            if (chunks.size() == 0)     /* No chunks to get? */
            {
                c.loadChunks(0);
                return c;
            }

            //Now handle any chunks in server thread that are already loaded (on server thread)
            final ForgeMapChunkCache cc = c;
            Future<Boolean> f = this.callSyncMethod(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    // Update busy state on world
                    ForgeWorld fw = (ForgeWorld)cc.getWorld();
                    //TODO
                    //setBusy(fw.getWorld());
                    cc.getLoadedChunks();
                    return true;
                }
            }, 0);
            try {
                f.get();
            }
            catch (CancellationException cx) {
                return null;
            }
            catch (ExecutionException xx) {
                Log.severe("Exception while loading chunks", xx.getCause());
                return null;
            }
            catch (Exception ix) {
                Log.severe(ix);
                return null;
            }
            if(!w.isLoaded()) {
                return null;
            }
            // Now, do rest of chunk reading from calling thread
            c.readChunks(chunks.size());

            return c;
        }
        @Override
        public int getMaxPlayers()
        {
            return server.getMaxPlayerCount();
        }
        @Override
        public int getCurrentPlayers()
        {
            return server.getPlayerManager().getCurrentPlayerCount();
        }

        public void tickEvent(MinecraftServer server)  {
            cur_tick_starttime = System.nanoTime();
            long elapsed = cur_tick_starttime - lasttick;
            lasttick = cur_tick_starttime;
            avgticklen = ((avgticklen * 99) / 100) + (elapsed / 100);
            tps = (double)1E9 / (double)avgticklen;
            // Tick core
            if (core != null) {
                core.serverTick(tps);
            }

            boolean done = false;
            TaskRecord tr = null;

            while(!blockupdatequeue.isEmpty()) {
                BlockUpdateRec r = blockupdatequeue.remove();
                BlockState bs = r.w.getBlockState(new BlockPos(r.x, r.y, r.z));
                int idx = Block.STATE_IDS.getId(bs);
                if(!org.dynmap.hdmap.HDBlockModels.isChangeIgnoredBlock(stateByID[idx])) {
                    if(onblockchange_with_id)
                        mapManager.touch(r.wid, r.x, r.y, r.z, "blockchange[" + idx + "]");
                    else
                        mapManager.touch(r.wid, r.x, r.y, r.z, "blockchange");
                }
            }

            long now;

            synchronized(schedlock) {
                cur_tick++;
                now = System.nanoTime();
                tr = runqueue.peek();
                /* Nothing due to run */
                if((tr == null) || (tr.ticktorun > cur_tick) || ((now - cur_tick_starttime) > perTickLimit)) {
                    done = true;
                }
                else {
                    tr = runqueue.poll();
                }
            }
            while (!done) {
                tr.future.run();

                synchronized(schedlock) {
                    tr = runqueue.peek();
                    now = System.nanoTime();
                    /* Nothing due to run */
                    if((tr == null) || (tr.ticktorun > cur_tick) || ((now - cur_tick_starttime) > perTickLimit)) {
                        done = true;
                    }
                    else {
                        tr = runqueue.poll();
                    }
                }
            }
            while(!msgqueue.isEmpty()) {
                ChatMessage cm = msgqueue.poll();
                DynmapPlayer dp = null;
                if(cm.sender != null)
                    dp = getOrAddPlayer(cm.sender);
                else
                    dp = new ForgePlayer(null);

                core.listenerManager.processChatEvent(DynmapListenerManager.EventType.PLAYER_CHAT, dp, cm.message);
            }
            // Check for generated chunks
            if((cur_tick % 20) == 0) {
            }
        }

        private <T> Predicate<T> distinctByKeyAndNonNull(Function<? super T, ?> keyExtractor) {
            Set<Object> seen = ConcurrentHashMap.newKeySet();
            return t -> t != null && seen.add(keyExtractor.apply(t));
        }

        private Stream<ModContainer> getModContainers() {
            FabricLoader loader = FabricLoader.getInstance();
            return Stream.of(
                    loader.getEntrypointContainers("main", ModInitializer.class),
                    loader.getEntrypointContainers("server", DedicatedServerModInitializer.class),
                    loader.getEntrypointContainers("client", ClientModInitializer.class)
            )
                    .flatMap(Collection::stream)
                    .map(EntrypointContainer::getProvider)
                    .filter(distinctByKeyAndNonNull(container -> container.getMetadata().getId()));
        }

        private Optional<ModContainer> getModContainerById(String id) {
            return getModContainers()
                    .filter(container -> container.getMetadata().getId().equals(id))
                    .findFirst();
        }

        @Override
        public boolean isModLoaded(String name) {
            FabricLoader loader = FabricLoader.getInstance();
             boolean loaded = getModContainerById(name).isPresent();
            if (loaded) {
                modsused.add(name);
            }
            return loaded;
        }
        @Override
        public String getModVersion(String name) {
            Optional<ModContainer> mod = getModContainerById(name);    // Try case sensitive lookup
            return mod.map(modContainer -> modContainer.getMetadata().getVersion().getFriendlyString()).orElse(null);
        }
        @Override
        public double getServerTPS() {
            return tps;
        }

        @Override
        public String getServerIP() {
            if (server.isSinglePlayer())
                return "0.0.0.0";
            else
                return server.getServerIp();
        }
        @Override
        public File getModContainerFile(String name) {
            Optional<ModContainer> container = getModContainerById(name);    // Try case sensitive lookup
            if (container.isPresent()) {
                Path path = container.get().getRootPath();
                if (path.getFileSystem() instanceof ZipFileSystem) {
                    path = Paths.get(path.getFileSystem().toString());
                }
                return path.toFile();
            }
            return null;
        }
        @Override
        public List<String> getModList() {
            return getModContainers().map(container -> container.getMetadata().getId()).collect(Collectors.toList());
        }

        @Override
        public Map<Integer, String> getBlockIDMap() {
            Map<Integer, String> map = new HashMap<Integer, String>();
            return map;
        }

        @Override
        public InputStream openResource(String modid, String rname) {
            if (modid == null) modid = "minecraft";

            if ("minecraft".equals(modid)) {
                return MinecraftServer.class.getClassLoader().getResourceAsStream(rname);
            } else {
                if (rname.startsWith("/") || rname.startsWith("\\")) {
                    rname = rname.substring(1);
                }

                final String finalModid = modid;
                final String finalRname = rname;
                return getModContainerById(modid).map(container -> {
                    try {
                        return Files.newInputStream(container.getPath(finalRname));
                    } catch (IOException e) {
                        Log.severe("Failed to load resource of mod :" + finalModid, e);
                        return null;
                    }
                }).orElse(null);
            }
        }
        /**
         * Get block unique ID map (module:blockid)
         */
        @Override
        public Map<String, Integer> getBlockUniqueIDMap() {
            HashMap<String, Integer> map = new HashMap<String, Integer>();
            return map;
        }
        /**
         * Get item unique ID map (module:itemid)
         */
        @Override
        public Map<String, Integer> getItemUniqueIDMap() {
            HashMap<String, Integer> map = new HashMap<String, Integer>();
            return map;
        }

    }
    private static final Gson gson = new GsonBuilder().create();

    public class TexturesPayload {
        public long timestamp;
        public String profileId;
        public String profileName;
        public boolean isPublic;
        public Map<String, ProfileTexture> textures;

    }
    public class ProfileTexture {
        public String url;
    }

    /**
     * Player access abstraction class
     */
    public class ForgePlayer extends ForgeCommandSender implements DynmapPlayer
    {
        private PlayerEntity player;
        private final String skinurl;
        private final UUID uuid;


        public ForgePlayer(PlayerEntity p)
        {
            player = p;
            String url = null;
            if (player != null) {
                uuid = player.getUuid();
                GameProfile prof = player.getGameProfile();
                if (prof != null) {
                    Property textureProperty = Iterables.getFirst(prof.getProperties().get("textures"), null);

                    if (textureProperty != null) {
                        TexturesPayload result = null;
                        try {
                            String json = new String(Base64.decodeBase64(textureProperty.getValue()), Charsets.UTF_8);
                            result = gson.fromJson(json, TexturesPayload.class);
                        } catch (JsonParseException e) {
                        }
                        if ((result != null) && (result.textures != null) && (result.textures.containsKey("SKIN"))) {
                            url = result.textures.get("SKIN").url;
                        }
                    }
                }
            }
            else {
                uuid = null;
            }
            skinurl = url;
        }
        @Override
        public boolean isConnected()
        {
            return true;
        }
        @Override
        public String getName()
        {
            if(player != null) {
                String n = player.getName().getString();;
                return n;
            }
            else
                return "[Server]";
        }
        @Override
        public String getDisplayName()
        {
            if(player != null) {
                String n = player.getDisplayName().getString();
                return n;
            }
            else
                return "[Server]";
        }
        @Override
        public boolean isOnline()
        {
            return true;
        }
        @Override
        public DynmapLocation getLocation()
        {
            if (player == null) {
                return null;
            }
            Vec3d v = player.getPos();
            return toLoc(player.world, v.x, v.y, v.z);
        }
        @Override
        public String getWorld()
        {
            if (player == null)
            {
                return null;
            }

            if (player.world != null)
            {
                return DynmapPlugin.this.getWorld(player.world).getName();
            }

            return null;
        }
        @Override
        public InetSocketAddress getAddress()
        {
            if((player != null) && (player instanceof ServerPlayerEntity)) {
                ServerPlayNetworkHandler nsh = ((ServerPlayerEntity)player).networkHandler;
                if((nsh != null) && (getNetworkManager(nsh) != null)) {
                    SocketAddress sa = getNetworkManager(nsh).getAddress();
                    if(sa instanceof InetSocketAddress) {
                        return (InetSocketAddress)sa;
                    }
                }
            }
            return null;
        }
        @Override
        public boolean isSneaking()
        {
            if (player != null)
            {
                return player.isSneaking();
            }

            return false;
        }
        @Override
        public double getHealth()
        {
            if (player != null)
            {
                double h = player.getHealth();
                if(h > 20) h = 20;
                return h;  // Scale to 20 range
            }
            else
            {
                return 0;
            }
        }
        @Override
        public int getArmorPoints()
        {
            if (player != null)
            {
                return player.getArmor();
            }
            else
            {
                return 0;
            }
        }
        @Override
        public DynmapLocation getBedSpawnLocation()
        {
            return null;
        }
        @Override
        public long getLastLoginTime()
        {
            return 0;
        }
        @Override
        public long getFirstLoginTime()
        {
            return 0;
        }
        @Override
        public boolean hasPrivilege(String privid)
        {
            if(player != null)
                return hasPerm(player, privid);
            return false;
        }
        @Override
        public boolean isOp()
        {
            return DynmapPlugin.this.isOp(player.getName().getString());
        }
        @Override
        public void sendMessage(String msg)
        {
            Text ichatcomponent = new LiteralText(msg);
            server.getPlayerManager().broadcastChatMessage(ichatcomponent, MessageType.CHAT, player.getUuid());
        }
        @Override
        public boolean isInvisible() {
            if(player != null) {
                return player.isInvisible();
            }
            return false;
        }
        @Override
        public int getSortWeight() {
            Integer wt = sortWeights.get(getName());
            if (wt != null)
                return wt;
            return 0;
        }
        @Override
        public void setSortWeight(int wt) {
            if (wt == 0) {
                sortWeights.remove(getName());
            }
            else {
                sortWeights.put(getName(), wt);
            }
        }
        @Override
        public boolean hasPermissionNode(String node) {
            if(player != null)
                return hasPermNode(player, node);
            return false;
        }
        @Override
        public String getSkinURL() {
            return skinurl;
        }
        @Override
        public UUID getUUID() {
            return uuid;
        }
        /**
         * Send title and subtitle text (called from server thread)
         */
        @Override
        public void sendTitleText(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
            if (player instanceof ServerPlayerEntity) {
                ServerPlayerEntity mp = (ServerPlayerEntity) player;
                TitleS2CPacket times = new TitleS2CPacket(fadeInTicks, stayTicks, fadeOutTicks);
                mp.networkHandler.sendPacket(times);
                if (title != null) {
                    TitleS2CPacket titlepkt = new TitleS2CPacket(TitleS2CPacket.Action.TITLE, new LiteralText(title));
                    mp.networkHandler.sendPacket(titlepkt);
                }

                if (subtitle != null) {
                    TitleS2CPacket subtitlepkt = new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE, new LiteralText(subtitle));
                    mp.networkHandler.sendPacket(subtitlepkt);
                }
            }
        }
    }
    /* Handler for generic console command sender */
    public class ForgeCommandSender implements DynmapCommandSender
    {
        private ServerCommandSource sender;

        protected ForgeCommandSender() {
            sender = null;
        }

        public ForgeCommandSender(ServerCommandSource send)
        {
            sender = send;
        }

        @Override
        public boolean hasPrivilege(String privid)
        {
            return true;
        }

        @Override
        public void sendMessage(String msg)
        {
            if(sender != null) {
                Text ichatcomponent = new LiteralText(msg);
                sender.sendFeedback(ichatcomponent, false);
            }
        }

        @Override
        public boolean isConnected()
        {
            return false;
        }
        @Override
        public boolean isOp()
        {
            return true;
        }
        @Override
        public boolean hasPermissionNode(String node) {
            return true;
        }
    }

    public void loadExtraBiomes(String mcver) {
        int cnt = 0;
        BiomeMap.loadWellKnownByVersion(mcver);

        Biome[] list = getBiomeList();

        for(int i = 0; i < list.length; i++) {
            Biome bb = list[i];
            if(bb != null) {
                String id = Registry.BIOME.getId(bb).getPath();
                float tmp = bb.getTemperature(), hum = bb.getRainfall();
                int watermult = ((BiomeEffectsAccessor)bb.getEffects()).getWaterColor();
                Log.verboseinfo("biome[" + i + "]: hum=" + hum + ", tmp=" + tmp + ", mult=" + Integer.toHexString(watermult));

                BiomeMap bmap = BiomeMap.byBiomeID(i);
                if (bmap.isDefault()) {
                    bmap = new BiomeMap(i, id, tmp, hum);
                    Log.verboseinfo("Add custom biome [" + bmap.toString() + "] (" + i + ")");
                    cnt++;
                }
                else {
                    bmap.setTemperature(tmp);
                    bmap.setRainfall(hum);
                }
                if (watermult != -1) {
                    bmap.setWaterColorMultiplier(watermult);
                    Log.verboseinfo("Set watercolormult for " + bmap.toString() + " (" + i + ") to " + Integer.toHexString(watermult));
                }
            }
        }
        if(cnt > 0)
            Log.info("Added " + cnt + " custom biome mappings");
    }

    private String[] getBiomeNames() {
        Biome[] list = getBiomeList();
        String[] lst = new String[list.length];
        for(int i = 0; i < list.length; i++) {
            Biome bb = list[i];
            if (bb != null) {
                lst[i] = Registry.BIOME.getId(bb).getPath();
            }
        }
        return lst;
    }

    public void onEnable()
    {
        /* Get MC version */
        String mcver = server.getVersion();

        /* Load extra biomes */
        loadExtraBiomes(mcver);
        /* Set up player login/quit event handler */
        registerPlayerLoginListener();

        /* Initialize permissions handler */
        permissions = FilePermissions.create();
        if(permissions == null) {
            permissions = new OpPermissions(new String[] { "webchat", "marker.icons", "marker.list", "webregister", "stats", "hide.self", "show.self" });
        }
        /* Get and initialize data folder */
        File dataDirectory = new File("dynmap");

        if (!dataDirectory.exists())
        {
            dataDirectory.mkdirs();
        }

        /* Instantiate core */
        if (core == null)
        {
            core = new DynmapCore();
        }

        /* Inject dependencies */
        core.setPluginJarFile(DynmapMod.jarfile);
        core.setPluginVersion(DynmapMod.ver);
        core.setMinecraftVersion(mcver);
        core.setDataFolder(dataDirectory);
        core.setServer(fserver);
        ForgeMapChunkCache.init();
        core.setTriggerDefault(TRIGGER_DEFAULTS);
        core.setBiomeNames(getBiomeNames());

        if(!core.initConfiguration(null))
        {
            return;
        }
        // Extract default permission example, if needed
        File filepermexample = new File(core.getDataFolder(), "permissions.yml.example");
        core.createDefaultFileFromResource("/permissions.yml.example", filepermexample);

        DynmapCommonAPIListener.apiInitialized(core);
    }

    private static int test(ServerCommandSource source) throws CommandSyntaxException
    {
        System.out.println(source.toString());
        return 1;
    }

    private DynmapCommand dynmapCmd;
    private DmapCommand dmapCmd;
    private DmarkerCommand dmarkerCmd;
    private DynmapExpCommand dynmapexpCmd;

    public void onStarting(CommandDispatcher<ServerCommandSource> cd) {
        /* Register command hander */
        dynmapCmd = new DynmapCommand(this);
        dmapCmd = new DmapCommand(this);
        dmarkerCmd = new DmarkerCommand(this);
        dynmapexpCmd = new DynmapExpCommand(this);
        dynmapCmd.register(cd);
        dmapCmd.register(cd);
        dmarkerCmd.register(cd);
        dynmapexpCmd.register(cd);

        Log.info("Register commands");
    }

    public void onStart() {
        initializeBlockStates();
        /* Enable core */
        if (!core.enableCore(null))
        {
            return;
        }
        core_enabled = true;
        VersionCheck.runCheck(core);
        // Get per tick time limit
        perTickLimit = core.getMaxTickUseMS() * 1000000;
        // Prep TPS
        lasttick = System.nanoTime();
        tps = 20.0;

        /* Register tick handler */
        if(!tickregistered) {
            ServerTickEvents.END_SERVER_TICK.register(server -> fserver.tickEvent(server));
            tickregistered = true;
        }

        playerList = core.playerList;
        sscache = new SnapshotCache(core.getSnapShotCacheSize(), core.useSoftRefInSnapShotCache());
        /* Get map manager from core */
        mapManager = core.getMapManager();

        /* Load saved world definitions */
        loadWorlds();

        /* Initialized the currently loaded worlds */
        if(server.getWorlds() != null) {
            for (ServerWorld world : server.getWorlds()) {
                ForgeWorld w = this.getWorld(world);
                /*NOTYET - need rest of forge
                if(DimensionManager.getWorld(world.provider.getDimensionId()) == null) { // If not loaded
                    w.setWorldUnloaded();
                }
                */
            }
        }
        for(ForgeWorld w : worlds.values()) {
            if (core.processWorldLoad(w)) {   /* Have core process load first - fire event listeners if good load after */
                if(w.isLoaded()) {
                    core.listenerManager.processWorldEvent(DynmapListenerManager.EventType.WORLD_LOAD, w);
                }
            }
        }
        core.updateConfigHashcode();

        /* Register our update trigger events */
        registerEvents();
        Log.info("Register events");

        //DynmapCommonAPIListener.apiInitialized(core);

        Log.info("Enabled");
    }

    public void onDisable()
    {
        DynmapCommonAPIListener.apiTerminated();

        //if (metrics != null) {
        //	metrics.stop();
        //	metrics = null;
        //}
        /* Save worlds */
        saveWorlds();

        /* Purge tick queue */
        fserver.runqueue.clear();

        /* Disable core */
        core.disableCore();
        core_enabled = false;

        if (sscache != null)
        {
            sscache.cleanup();
            sscache = null;
        }

        Log.info("Disabled");
    }

    void onCommand(ServerCommandSource sender, String cmd, String[] args)
    {
        DynmapCommandSender dsender;
        PlayerEntity psender;
        try {
            psender = sender.getPlayer();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException x) {
            psender = null;
        }

        if (psender != null)
        {
            dsender = new ForgePlayer(psender);
        }
        else
        {
            dsender = new ForgeCommandSender(sender);
        }

        core.processCommand(dsender, cmd, cmd, args);
    }

    private DynmapLocation toLoc(World worldObj, double x, double y, double z)
    {
        return new DynmapLocation(DynmapPlugin.this.getWorld(worldObj).getName(), x, y, z);
    }

    public class PlayerTracker {
        public void onPlayerLogin(PlayerEvents.PlayerLoggedInEvent event) {
            if (!core_enabled) return;
            final DynmapPlayer dp = getOrAddPlayer(event.getPlayer());
            /* This event can be called from off server thread, so push processing there */
            core.getServer().scheduleServerTask(new Runnable() {
                public void run() {
                    core.listenerManager.processPlayerEvent(DynmapListenerManager.EventType.PLAYER_JOIN, dp);
                }
            }, 2);
        }

        public void onPlayerLogout(PlayerEvents.PlayerLoggedOutEvent event) {
            if (!core_enabled) return;
            final DynmapPlayer dp = getOrAddPlayer(event.getPlayer());
            final String name = event.getPlayer().getName().getString();
            /* This event can be called from off server thread, so push processing there */
            core.getServer().scheduleServerTask(new Runnable() {
                public void run() {
                    core.listenerManager.processPlayerEvent(DynmapListenerManager.EventType.PLAYER_QUIT, dp);
                    players.remove(name);
                }
            }, 0);
        }

        public void onPlayerChangedDimension(PlayerEvents.PlayerChangedDimensionEvent event) {
            if (!core_enabled) return;
            getOrAddPlayer(event.getPlayer());    // Freshen player object reference
        }

        public void onPlayerRespawn(PlayerEvents.PlayerRespawnEvent event) {
            if (!core_enabled) return;
            getOrAddPlayer(event.getPlayer());    // Freshen player object reference
        }
    }
    private PlayerTracker playerTracker = null;

    private void registerPlayerLoginListener()
    {
        if (playerTracker == null) {
            playerTracker = new PlayerTracker();
            PlayerEvents.PLAYER_LOGGED_IN.register(event -> playerTracker.onPlayerLogin(event));
            PlayerEvents.PLAYER_LOGGED_OUT.register(event -> playerTracker.onPlayerLogout(event));
            PlayerEvents.PLAYER_CHANGED_DIMENSION.register(event -> playerTracker.onPlayerChangedDimension(event));
            PlayerEvents.PLAYER_RESPAWN.register(event -> playerTracker.onPlayerRespawn(event));
        }
    }

    public class WorldTracker {
        public void handleWorldLoad(MinecraftServer server, ServerWorld world) {
            if(!core_enabled) return;

            final ForgeWorld fw = getWorld(world);
            // This event can be called from off server thread, so push processing there
            core.getServer().scheduleServerTask(new Runnable() {
                public void run() {
                    if(core.processWorldLoad(fw))    // Have core process load first - fire event listeners if good load after
                        core.listenerManager.processWorldEvent(DynmapListenerManager.EventType.WORLD_LOAD, fw);
                }
            }, 0);
        }

        public void handleWorldUnload(MinecraftServer server, ServerWorld world) {
            if(!core_enabled) return;

            final ForgeWorld fw = getWorld(world);
            if(fw != null) {
                // This event can be called from off server thread, so push processing there
                core.getServer().scheduleServerTask(new Runnable() {
                    public void run() {
                        core.listenerManager.processWorldEvent(DynmapListenerManager.EventType.WORLD_UNLOAD, fw);
                        core.processWorldUnload(fw);
                    }
                }, 0);
                // Set world unloaded (needs to be immediate, since it may be invalid after event)
                fw.setWorldUnloaded();
                // Clean up tracker
                //WorldUpdateTracker wut = updateTrackers.remove(fw.getName());
                //if(wut != null) wut.world = null;
            }
        }

        public void handleChunkLoad(ServerWorld world, WorldChunk chunk) {
            if(!onchunkgenerate) return;

            if ((chunk != null) && (chunk.getStatus() == ChunkStatus.FULL)) {
                ForgeWorld fw = getWorld(world, false);
                if (fw != null) {
                    addKnownChunk(fw, chunk.getPos());
                }
            }
        }

        public void handleChunkUnload(ServerWorld world, WorldChunk chunk) {
            if(!onchunkgenerate) return;

            if ((chunk != null) && (chunk.getStatus() == ChunkStatus.FULL)) {
                ForgeWorld fw = getWorld(world, false);
                ChunkPos cp = chunk.getPos();
                if (fw != null) {
                    if (!checkIfKnownChunk(fw, cp)) {
                        int ymax = 0;
                        ChunkSection[] sections = chunk.getSectionArray();
                        for(int i = 0; i < sections.length; i++) {
                            if((sections[i] != null) && (!sections[i].isEmpty())) {
                                ymax = 16*(i+1);
                            }
                        }
                        int x = cp.x << 4;
                        int z = cp.z << 4;
                        // If not empty AND not initial scan
                        if (ymax > 0) {
                            Log.info("New generated chunk detected at " + cp + " for " + fw.getName());
                            mapManager.touchVolume(fw.getName(), x, 0, z, x + 15, ymax, z + 16, "chunkgenerate");
                        }
                    }
                    removeKnownChunk(fw, cp);
                }
            }
        }

        public void handleChunkDataSave(ServerWorld world, WorldChunk chunk) {
            if (!onchunkgenerate) return;

            if ((chunk != null) && (chunk.getStatus() == ChunkStatus.FULL)) {
                ForgeWorld fw = getWorld(world, false);
                ChunkPos cp = chunk.getPos();
                if (fw != null) {
                    if (!checkIfKnownChunk(fw, cp)) {
                        int ymax = 0;
                        ChunkSection[] sections = chunk.getSectionArray();
                        for (int i = 0; i < sections.length; i++) {
                            if ((sections[i] != null) && (!sections[i].isEmpty())) {
                                ymax = 16 * (i + 1);
                            }
                        }
                        int x = cp.x << 4;
                        int z = cp.z << 4;
                        // If not empty AND not initial scan
                        if (ymax > 0) {
                            mapManager.touchVolume(fw.getName(), x, 0, z, x + 15, ymax, z + 16, "chunkgenerate");
                        }
                        addKnownChunk(fw, cp);
                    }
                }
            }
        }

        public void handleBlockEvent(BlockEvents.BlockEvent event) {
            if (!core_enabled) return;
            if (!onblockchange) return;
            BlockUpdateRec r = new BlockUpdateRec();
            r.w = event.getWorld();
            ForgeWorld fw = getWorld(r.w, false);
            if (fw == null) return;
            r.wid = fw.getName();
            BlockPos p = event.getPos();
            r.x = p.getX();
            r.y = p.getY();
            r.z = p.getZ();
            blockupdatequeue.add(r);
        }
    }
    private WorldTracker worldTracker = null;
    private boolean onblockchange = false;
    private boolean onchunkpopulate = false;
    private boolean onchunkgenerate = false;
    private boolean onblockchange_with_id = false;

    private void registerEvents()
    {
        // To trigger rendering.
        onblockchange = core.isTrigger("blockupdate");
        onchunkpopulate = core.isTrigger("chunkpopulate");
        onchunkgenerate = core.isTrigger("chunkgenerate");
        onblockchange_with_id = core.isTrigger("blockupdate-with-id");
        if(onblockchange_with_id)
            onblockchange = true;
        if ((worldTracker == null) && (onblockchange || onchunkpopulate || onchunkgenerate)) {
            worldTracker = new WorldTracker();
            ServerWorldEvents.LOAD.register((server, world) -> worldTracker.handleWorldLoad(server, world));
            ServerWorldEvents.UNLOAD.register((server, world) -> worldTracker.handleWorldUnload(server, world));
            ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> worldTracker.handleChunkLoad(world, chunk));
            ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> worldTracker.handleChunkUnload(world, chunk));
            ChunkDataEvents.SAVE.register((world, chunk) -> worldTracker.handleChunkDataSave(world, chunk));
            BlockEvents.EVENT.register(event -> worldTracker.handleBlockEvent(event));
        }
        // Prime the known full chunks
        if (onchunkgenerate && (server.getWorlds() != null)) {
            for (ServerWorld world : server.getWorlds()) {
                ForgeWorld fw = getWorld(world);
                if (fw == null) continue;
                Long2ObjectLinkedOpenHashMap<ChunkHolder> chunks = ((ThreadedAnvilChunkStorageAccessor)world.getChunkManager().threadedAnvilChunkStorage).getChunkHolders();
                for (Map.Entry<Long, ChunkHolder> k : chunks.long2ObjectEntrySet()) {
                    long key = k.getKey();
                    ChunkHolder ch = k.getValue();
                    Chunk c = null;
                    try {
                        c = ch.getFuture().getNow(null);
                    } catch (Exception x) { }
                    if (c == null) continue;
                    ChunkStatus cs = c.getStatus();
                    ChunkPos pos = ch.getPos();
                    if (cs == ChunkStatus.FULL) {	// Cooked?
                        // Add it as known
                        addKnownChunk(fw, pos);
                    }
                }
            }
        }
    }

    private ForgeWorld getWorldByName(String name) {
        return worlds.get(name);
    }

    private ForgeWorld getWorld(WorldAccess w) {
        return getWorld(w, true);
    }

    private ForgeWorld getWorld(WorldAccess w, boolean add_if_not_found) {
        if(last_world == w) {
            return last_fworld;
        }
        String wname = ForgeWorld.getWorldName(w);

        for(ForgeWorld fw : worlds.values()) {
            if(fw.getRawName().equals(wname)) {
                last_world = w;
                last_fworld = fw;
                if(!fw.isLoaded()) {
                    fw.setWorldLoaded(w);
                }
                return fw;
            }
        }
        ForgeWorld fw = null;
        if(add_if_not_found) {
            /* Add to list if not found */
            fw = new ForgeWorld(w);
            worlds.put(fw.getName(), fw);
        }
        last_world = w;
        last_fworld = fw;
        return fw;
    }

    private void saveWorlds() {
        File f = new File(core.getDataFolder(), "forgeworlds.yml");
        ConfigurationNode cn = new ConfigurationNode(f);
        ArrayList<HashMap<String,Object>> lst = new ArrayList<HashMap<String,Object>>();
        for(DynmapWorld fw : core.mapManager.getWorlds()) {
            HashMap<String, Object> vals = new HashMap<String, Object>();
            vals.put("name", fw.getRawName());
            vals.put("height",  fw.worldheight);
            vals.put("sealevel", fw.sealevel);
            vals.put("nether",  fw.isNether());
            vals.put("the_end",  ((ForgeWorld)fw).isTheEnd());
            vals.put("title", fw.getTitle());
            lst.add(vals);
        }
        cn.put("worlds", lst);
        cn.put("useSaveFolderAsName", useSaveFolder);
        cn.put("maxWorldHeight", ForgeWorld.getMaxWorldHeight());

        cn.save();
    }
    private void loadWorlds() {
        File f = new File(core.getDataFolder(), "forgeworlds.yml");
        if(f.canRead() == false) {
            useSaveFolder = true;
            return;
        }
        ConfigurationNode cn = new ConfigurationNode(f);
        cn.load();
        // If defined, use maxWorldHeight
        ForgeWorld.setMaxWorldHeight(cn.getInteger("maxWorldHeight", 256));

        // If setting defined, use it
        if (cn.containsKey("useSaveFolderAsName")) {
            useSaveFolder = cn.getBoolean("useSaveFolderAsName", useSaveFolder);
        }
        List<Map<String,Object>> lst = cn.getMapList("worlds");
        if(lst == null) {
            Log.warning("Discarding bad forgeworlds.yml");
            return;
        }

        for(Map<String,Object> world : lst) {
            try {
                String name = (String)world.get("name");
                int height = (Integer)world.get("height");
                int sealevel = (Integer)world.get("sealevel");
                boolean nether = (Boolean)world.get("nether");
                boolean theend = (Boolean)world.get("the_end");
                String title = (String)world.get("title");
                if(name != null) {
                    ForgeWorld fw = new ForgeWorld(name, height, sealevel, nether, theend, title);
                    fw.setWorldUnloaded();
                    core.processWorldLoad(fw);
                    worlds.put(fw.getName(), fw);
                }
            } catch (Exception x) {
                Log.warning("Unable to load saved worlds from forgeworlds.yml");
                return;
            }
        }
    }
    public void serverStarted() {
        this.onStart();
        if (core != null) {
            core.serverStarted();
        }
    }
    public MinecraftServer getMCServer() {
        return server;
    }
}

class DynmapCommandHandler
{
    private String cmd;
    private DynmapPlugin plugin;

    public DynmapCommandHandler(String cmd, DynmapPlugin p)
    {
        this.cmd = cmd;
        this.plugin = p;
    }

    public void register(CommandDispatcher<ServerCommandSource> cd) {
        cd.register(CommandManager.literal(cmd).
                then(RequiredArgumentBuilder.<ServerCommandSource, String> argument("args", StringArgumentType.greedyString()).
                        executes((ctx) -> this.execute(plugin.getMCServer(), ctx.getSource(), ctx.getInput()))).
                executes((ctx) -> this.execute(plugin.getMCServer(), ctx.getSource(), ctx.getInput())));
    }

    //    @Override
    public int execute(MinecraftServer server, ServerCommandSource sender,
                       String cmdline) throws CommandException {
        String[] args = cmdline.split("\\s+");
        plugin.onCommand(sender, cmd, Arrays.copyOfRange(args, 1, args.length));
        return 1;
    }

    //    @Override
    public String getUsage(ServerCommandSource arg0) {
        return "Run /" + cmd + " help for details on using command";
    }
}

class DynmapCommand extends DynmapCommandHandler {
    DynmapCommand(DynmapPlugin p) {
        super("dynmap", p);
    }
}
class DmapCommand extends DynmapCommandHandler {
    DmapCommand(DynmapPlugin p) {
        super("dmap", p);
    }
}
class DmarkerCommand extends DynmapCommandHandler {
    DmarkerCommand(DynmapPlugin p) {
        super("dmarker", p);
    }
}
class DynmapExpCommand extends DynmapCommandHandler {
    DynmapExpCommand(DynmapPlugin p) {
        super("dynmapexp", p);
    }
}
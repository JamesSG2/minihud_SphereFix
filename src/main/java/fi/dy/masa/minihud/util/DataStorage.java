package fi.dy.masa.minihud.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonObject;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.structure.Structure;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.network.ClientPlayHandler;
import fi.dy.masa.malilib.network.IPluginClientPlayHandler;
import fi.dy.masa.malilib.util.*;
import fi.dy.masa.minihud.MiniHUD;
import fi.dy.masa.minihud.Reference;
import fi.dy.masa.minihud.config.RendererToggle;
import fi.dy.masa.minihud.data.HudDataManager;
import fi.dy.masa.minihud.data.MobCapDataHandler;
import fi.dy.masa.minihud.mixin.IMixinMinecraftServer;
import fi.dy.masa.minihud.network.ServuxStructuresHandler;
import fi.dy.masa.minihud.network.ServuxStructuresPacket;
import fi.dy.masa.minihud.renderer.*;
import fi.dy.masa.minihud.renderer.shapes.ShapeManager;
import fi.dy.masa.minihud.renderer.worker.ChunkTask;
import fi.dy.masa.minihud.renderer.worker.ThreadWorker;

public class DataStorage
{
    private static final ThreadFactory THREAD_FACTORY = (new ThreadFactoryBuilder()).setNameFormat("MiniHUD Worker Thread %d").setDaemon(true).build();
    private static final Pattern PATTERN_CARPET_TPS = Pattern.compile("TPS: (?<tps>[0-9]+[\\.,][0-9]) MSPT: (?<mspt>[0-9]+[\\.,][0-9])");

    private static final DataStorage INSTANCE = new DataStorage();
    private final MobCapDataHandler mobCapData = new MobCapDataHandler();
    private final static ServuxStructuresHandler<ServuxStructuresPacket.Payload> HANDLER = ServuxStructuresHandler.getInstance();
    private boolean carpetServer = false;
    private boolean servuxServer = false;
    private boolean hasInValidServux = false;
    private boolean hasIntegratedServer = false;
    private int simulationDistance = -1;
    private int structureDataTimeout = 30 * 20;
    private boolean serverTPSValid;
    private boolean hasSyncedTime;
    private String servuxVersion;
    private int servuxTimeout;
    private boolean hasStructureDataFromServer;
    private boolean structureRendererNeedsUpdate;
    private boolean structuresNeedUpdating;
    private boolean shouldRegisterStructureChannel;
    private long lastServerTick;
    private long lastServerTimeUpdate;
    private BlockPos lastStructureUpdatePos;
    private double serverTPS;
    private double serverMSPT;
    private Vec3d distanceReferencePoint = Vec3d.ZERO;
    private final int[] blockBreakCounter = new int[100];
    private final ArrayListMultimap<StructureType, StructureData> structures = ArrayListMultimap.create();
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private IntegratedServer integratedServer;
    private DynamicRegistryManager registryManager = DynamicRegistryManager.EMPTY;
    private final PriorityBlockingQueue<ChunkTask> taskQueue = Queues.newPriorityBlockingQueue();
    private final Thread workerThread;
    private final ThreadWorker worker;

    private DataStorage()
    {
        this.worker = new ThreadWorker();
        this.workerThread = THREAD_FACTORY.newThread(this.worker);
        this.workerThread.start();
    }

    public static DataStorage getInstance()
    {
        return INSTANCE;
    }

    public void onGameInit()
    {
        ClientPlayHandler.getInstance().registerClientPlayHandler(HANDLER);
        HANDLER.registerPlayPayload(ServuxStructuresPacket.Payload.ID, ServuxStructuresPacket.Payload.CODEC, IPluginClientPlayHandler.BOTH_CLIENT);
    }

    public Identifier getNetworkChannel() { return ServuxStructuresHandler.CHANNEL_ID; }

    public IPluginClientPlayHandler<ServuxStructuresPacket.Payload> getNetworkHandler() { return HANDLER; }

    public MobCapDataHandler getMobCapData()
    {
        return this.mobCapData;
    }

    public void reset(boolean isLogout)
    {
        if (isLogout)
        {
            MiniHUD.printDebug("DataStorage#reset() - log-out");
            /*
            this.worker.stopThread();

            try
            {
                this.workerThread.interrupt();
                this.workerThread.join();
            }
            catch (InterruptedException e)
            {
                MiniHUD.logger.warn("Interrupted whilst waiting for worker thread to die", e);
            }
            */
            HANDLER.reset(this.getNetworkChannel());
            HANDLER.resetFailures(this.getNetworkChannel());

            this.servuxServer = false;
            this.hasInValidServux = false;
            this.structureDataTimeout = 30 * 20;
            this.registryManager = DynamicRegistryManager.EMPTY;
            this.carpetServer = false;
            this.setHasIntegratedServer(false, null);
        }
        else
        {
            MiniHUD.printDebug("DataStorage#reset() - dimension change or log-in");
        }

        this.mobCapData.clear();
        this.serverTPSValid = false;
        this.hasSyncedTime = false;
        this.structuresNeedUpdating = true;
        this.hasStructureDataFromServer = false;
        this.structureRendererNeedsUpdate = true;

        this.lastStructureUpdatePos = null;
        this.structures.clear();
        this.clearTasks();
        this.servuxTimeout = -1;

        ShapeManager.INSTANCE.clear();
        OverlayRendererBeaconRange.INSTANCE.clear();
        OverlayRendererConduitRange.INSTANCE.clear();
        OverlayRendererBiomeBorders.INSTANCE.clear();
        OverlayRendererLightLevel.reset();
    }

    public void clearTasks()
    {
        this.taskQueue.clear();
    }

    public ChunkTask getNextTask() throws InterruptedException
    {
        return this.taskQueue.take();
    }

    public void addTask(Runnable task, ChunkPos pos, Vec3i playerPos)
    {
        if (this.taskQueue.size() < 64000)
        {
            this.taskQueue.offer(new ChunkTask(task, pos, playerPos));
        }
    }

    public void setIsServuxServer()
    {
        this.servuxServer = true;
        if (this.hasInValidServux)
        {
            this.hasInValidServux = false;
        }
    }

    public void setServuxVersion(String ver)
    {
        if (ver != null && ver.isEmpty() == false)
        {
            this.servuxVersion = ver;
        }
        else
        {
            this.servuxVersion = "unknown";
        }
    }

    public String getServuxVersion()
    {
        if (this.hasServuxServer())
        {
            return this.servuxVersion;
        }

        return "not_connected";
    }

    public boolean hasIntegratedServer() { return this.hasIntegratedServer; }

    public void setHasIntegratedServer(boolean toggle, @Nullable IntegratedServer server)
    {
        this.hasIntegratedServer = toggle;
        this.integratedServer = server;
    }

    public IntegratedServer getIntegratedServer()
    {
        return this.integratedServer;
    }

    public boolean isSinglePlayer()
    {
        if (this.mc != null)
        {
            return this.mc.isInSingleplayer();
        }

        return false;
    }

    public void onWorldPre()
    {
        if (this.hasIntegratedServer == false)
        {
            HANDLER.registerPlayReceiver(ServuxStructuresPacket.Payload.ID, HANDLER::receivePlayPayload);
        }
    }

    public void onWorldJoin()
    {
        MiniHUD.printDebug("DataStorage#onWorldJoin()");
        OverlayRendererBeaconRange.INSTANCE.setNeedsUpdate();
        OverlayRendererConduitRange.INSTANCE.setNeedsUpdate();
        OverlayRendererSpawnChunks.setNeedsUpdate();

        if (this.hasIntegratedServer == false)
        {
            // We don't always receive the initial metadata packet,
            // so we must send either a register or unregister packet to be sure.
            if (RendererToggle.OVERLAY_STRUCTURE_MAIN_TOGGLE.getBooleanValue())
            {
                this.registerStructureChannel();
                this.structuresNeedUpdating = true;
            }
            else
            {
                this.unregisterStructureChannel();
            }
        }
    }

    /**
     * Store's the world registry manager for Dynamic Lookup for various data
     * Set this at WorldLoadPost
     * @param manager ()
     */
    public void setWorldRegistryManager(DynamicRegistryManager manager)
    {
        if (manager != null && manager != DynamicRegistryManager.EMPTY)
        {
            this.registryManager = manager;
        }
        else
        {
            this.registryManager = DynamicRegistryManager.EMPTY;
        }
    }

    public DynamicRegistryManager getWorldRegistryManager()
    {
        if (this.registryManager != DynamicRegistryManager.EMPTY)
        {
            return this.registryManager;
        }
        else
        {
            return DynamicRegistryManager.EMPTY;
        }
    }

    public void setSimulationDistance(int distance)
    {
        if (distance >= 0)
        {
            if (this.simulationDistance != distance)
            {
                OverlayRendererSpawnChunks.setNeedsUpdate();
            }
            this.simulationDistance = distance;
            //MiniHUD.printDebug("DataStorage#setSimulationDistance(): set to: [{}]", distance);
        }
        else
        {
            this.simulationDistance = -1;
        }
    }

    public boolean isSimulationDistanceKnown()
    {
        return this.simulationDistance >= 0;
    }

    public int getSimulationDistance()
    {
        if (this.simulationDistance > 0)
        {
            return this.simulationDistance;
        }

        return 10;
    }

    public boolean hasTPSData()
    {
        return this.serverTPSValid;
    }

    public boolean hasCarpetServer()
    {
        return this.carpetServer;
    }

    public boolean hasServuxServer() { return this.servuxServer; }

    public double getServerTPS()
    {
        return this.serverTPS;
    }

    public double getServerMSPT()
    {
        return this.serverMSPT;
    }

    public boolean structureRendererNeedsUpdate()
    {
        return this.structureRendererNeedsUpdate;
    }

    public void setStructuresNeedUpdating()
    {
        this.structuresNeedUpdating = true;
    }

    public void setStructureRendererNeedsUpdate()
    {
        this.structureRendererNeedsUpdate = true;
    }

    public Vec3d getDistanceReferencePoint()
    {
        return this.distanceReferencePoint;
    }

    public void setDistanceReferencePoint(Vec3d pos)
    {
        this.distanceReferencePoint = pos;
        String str = String.format("x: %.2f, y: %.2f, z: %.2f", pos.x, pos.y, pos.z);
        InfoUtils.printActionbarMessage("minihud.message.distance_reference_point_set", str);
    }

    public void markChunkForHeightmapCheck(int chunkX, int chunkZ)
    {
        Entity entity = MinecraftClient.getInstance().getCameraEntity();

        // Only update the renderers when blocks change near the camera
        if (entity != null)
        {
            Vec3d pos = entity.getPos();

            if (Math.abs(pos.x - (chunkX << 4) - 8) <= 48D || Math.abs(pos.z - (chunkZ << 4) - 8) <= 48D)
            {
                OverlayRendererSpawnableColumnHeights.markChunkChanged(chunkX, chunkZ);
                OverlayRendererLightLevel.setNeedsUpdate();
            }
        }
    }

    public void onClientTickPre(MinecraftClient mc)
    {
        if (mc.world != null)
        {
            int tick = (int) (mc.world.getTime() % this.blockBreakCounter.length);
            this.blockBreakCounter[tick] = 0;
        }
    }

    public void onPlayerBlockBreak(MinecraftClient mc)
    {
        if (mc.world != null)
        {
            int tick = (int) (mc.world.getTime() % this.blockBreakCounter.length);
            ++this.blockBreakCounter[tick];
        }
    }

    public double getBlockBreakingSpeed()
    {
        return MiscUtils.intAverage(this.blockBreakCounter) * 20;
    }

    public boolean onSendChatMessage(String message)
    {
        String[] parts = message.split(" ");

        if (parts.length > 0 && (parts[0].equals("minihud-seed") || parts[0].equals("/minihud-seed")))
        {
            if (parts.length == 2)
            {
                try
                {
                    HudDataManager.getInstance().setWorldSeed(Long.parseLong(parts[1]));
                    InfoUtils.printActionbarMessage("minihud.message.seed_set", HudDataManager.getInstance().worldSeed());
                }
                catch (NumberFormatException e)
                {
                    InfoUtils.printActionbarMessage("minihud.message.error.invalid_seed");
                }
            }
            else if (parts.length == 1)
            {
                if (HudDataManager.getInstance().hasStoredWorldSeed())
                {
                    InfoUtils.printActionbarMessage("minihud.message.seed_is", HudDataManager.getInstance().worldSeed());
                }
                else
                {
                    InfoUtils.printActionbarMessage("minihud.message.no_seed");
                }
            }

            return true;
        }
        else if (parts.length > 0 && (parts[0].equals("minihud-spawnchunkradius") || parts[0].equals("/minihud-spawnchunkradius")))
        {
            if (parts.length == 2)
            {
                try
                {
                    int radius = Integer.parseInt(parts[1]);

                    if (radius >= 0 && radius <= 32)
                    {
                        HudDataManager.getInstance().setSpawnChunkRadius(radius, true);
                    }
                    else
                    {
                        InfoUtils.printActionbarMessage("minihud.message.error.invalid_spawn_chunk_radius");
                    }
                }
                catch (NumberFormatException e)
                {
                    InfoUtils.printActionbarMessage("minihud.message.error.invalid_spawn_chunk_radius");
                }
            }
            else if (parts.length == 1)
            {
                if (HudDataManager.getInstance().isSpawnChunkRadiusKnown())
                {
                    int radius = HudDataManager.getInstance().getSpawnChunkRadius();
                    String strRadius = radius > 0 ? GuiBase.TXT_GREEN + String.format("%d", radius) + GuiBase.TXT_RST : GuiBase.TXT_RED + String.format("%d", radius) + GuiBase.TXT_RST;
                    InfoUtils.printActionbarMessage(StringUtils.translate("minihud.message.spawn_chunk_radius_is", strRadius));
                }
                else
                {
                    InfoUtils.printActionbarMessage("minihud.message.no_spawn_chunk_radius");
                }
            }

            return true;
        }

        return false;
    }

    public void onChatMessage(Text message)
    {
        if (message instanceof MutableText mutableText &&
            mutableText.getContent() instanceof TranslatableTextContent text)
        {
            // The vanilla "/seed" command
            if ("commands.seed.success".equals(text.getKey()) && text.getArgs().length == 1)
            {
                try
                {
                    //String str = message.getString();
                    //int i1 = str.indexOf("[");
                    //int i2 = str.indexOf("]");
                    MutableText m = (MutableText) text.getArgs()[0];
                    TranslatableTextContent t = (TranslatableTextContent) m.getContent();
                    PlainTextContent.Literal l = (PlainTextContent.Literal) ((MutableText) t.getArgs()[0]).getContent();
                    String str = l.string();

                    //if (i1 != -1 && i2 != -1)
                    {
                        //this.setWorldSeed(Long.parseLong(str.substring(i1 + 1, i2)));
                        HudDataManager.getInstance().setWorldSeed(Long.parseLong(str));
                        MiniHUD.logger.info("Received world seed from the vanilla /seed command: {}", HudDataManager.getInstance().worldSeed());
                        InfoUtils.printActionbarMessage("minihud.message.seed_set", HudDataManager.getInstance().worldSeed());
                    }
                }
                catch (Exception e)
                {
                    MiniHUD.logger.warn("Failed to read the world seed from '{}'", text.getArgs()[0]);
                }
            }
            // The "/jed seed" command
            else if ("jed.commands.seed.success".equals(text.getKey()))
            {
                try
                {
                    HudDataManager.getInstance().setWorldSeed(Long.parseLong(text.getArgs()[1].toString()));
                    MiniHUD.logger.info("Received world seed from the JED '/jed seed' command: {}", HudDataManager.getInstance().worldSeed());
                    InfoUtils.printActionbarMessage("minihud.message.seed_set", HudDataManager.getInstance().worldSeed());
                }
                catch (Exception e)
                {
                    MiniHUD.logger.warn("Failed to read the world seed from '{}'", text.getArgs()[1], e);
                }
            }
            else if ("commands.setworldspawn.success".equals(text.getKey()) && text.getArgs().length == 4)
            {
                try
                {
                    Object[] o = text.getArgs();
                    int x = Integer.parseInt(o[0].toString());
                    int y = Integer.parseInt(o[1].toString());
                    int z = Integer.parseInt(o[2].toString());

                    BlockPos newSpawn = new BlockPos(x, y, z);
                    HudDataManager.getInstance().setWorldSpawn(newSpawn);

                    String spawnStr = String.format("x: %d, y: %d, z: %d", newSpawn.getX(), newSpawn.getY(), newSpawn.getZ());
                    MiniHUD.logger.info("Received world spawn from the vanilla /setworldspawn command: {}", spawnStr);
                    InfoUtils.printActionbarMessage("minihud.message.spawn_set", spawnStr);
                }
                catch (Exception e)
                {
                    MiniHUD.logger.warn("Failed to read the world spawn point from '{}'", text.getArgs(), e);
                }
            }
            else if (("commands.gamerule.set".equals(text.getKey()) || "commands.gamerule.query".equals(text.getKey())) && text.getArgs().length == 2)
            {
                try
                {
                    Object[] o = text.getArgs();
                    String rule = o[0].toString();

                    if (rule.equals("spawnChunkRadius"))
                    {
                        int value = Integer.parseInt(o[1].toString());

                        if (HudDataManager.getInstance().getSpawnChunkRadius() != value)
                        {
                            MiniHUD.logger.info("Received spawn chunk radius from the vanilla /gamerule command: {}", HudDataManager.getInstance().getSpawnChunkRadius());
                            HudDataManager.getInstance().setSpawnChunkRadius(value, true);
                        }
                        else
                        {
                            int radius = HudDataManager.getInstance().getSpawnChunkRadius();
                            String strRadius = radius > 0 ? GuiBase.TXT_GREEN + String.format("%d", radius) + GuiBase.TXT_RST : GuiBase.TXT_RED + String.format("%d", radius) + GuiBase.TXT_RST;
                            InfoUtils.printActionbarMessage(StringUtils.translate("minihud.message.spawn_chunk_radius_is", strRadius));
                        }
                    }
                }
                catch (Exception e)
                {
                    MiniHUD.logger.warn("Failed to read the spawn chunk radius from '{}'", text.getArgs(), e);
                }
            }
        }
    }

    public void onServerTimeUpdate(long totalWorldTime)
    {
        // Carpet server sends the TPS and MSPT values via the player list footer data,
        // and for single player the data is grabbed directly from the integrated server.
        if (this.carpetServer == false && this.mc.isInSingleplayer() == false)
        {
            long currentTime = System.nanoTime();

            if (this.hasSyncedTime)
            {
                long elapsedTicks = totalWorldTime - this.lastServerTick;

                if (elapsedTicks > 0)
                {
                    this.serverMSPT = ((double) (currentTime - this.lastServerTimeUpdate) / (double) elapsedTicks) / 1000000D;
                    this.serverTPS = this.serverMSPT <= 50 ? 20D : (1000D / this.serverMSPT);
                    this.serverTPSValid = true;
                }
            }

            this.lastServerTick = totalWorldTime;
            this.lastServerTimeUpdate = currentTime;
            this.hasSyncedTime = true;
        }
    }

    public void updateIntegratedServerTPS()
    {
        if (this.mc != null && this.mc.player != null && this.mc.getServer() != null)
        {
            this.serverMSPT = MiscUtils.longAverage(this.mc.getServer().getTickTimes()) / 1000000D;
            this.serverTPS = this.serverMSPT <= 50 ? 20D : (1000D / this.serverMSPT);
            this.serverTPSValid = true;
        }
    }

    /**
     * Gets a copy of the structure data map, and clears the dirty flag
     */
    public ArrayListMultimap<StructureType, StructureData> getCopyOfStructureData()
    {
        ArrayListMultimap<StructureType, StructureData> copy = ArrayListMultimap.create();

        if (RendererToggle.OVERLAY_STRUCTURE_MAIN_TOGGLE.getBooleanValue() == false)
        {
            return copy;
        }

        synchronized (this.structures)
        {
            for (StructureType type : StructureType.VALUES)
            {
                Collection<StructureData> values = this.structures.get(type);

                if (values.isEmpty() == false)
                {
                    copy.putAll(type, values);
                }
            }

            this.structureRendererNeedsUpdate = false;
        }

        return copy;
    }

    /**
     * Get all structures withinRange of the player (Helps reduce overhead)
     * @param pos (Player Position)
     * @param maxRange (maxChunkRange)
     * @return (The list)
     */
    public ArrayListMultimap<StructureType, StructureData> getCopyOfStructureDataWithinRange(BlockPos pos, int maxRange)
    {
        ArrayListMultimap<StructureType, StructureData> copy = ArrayListMultimap.create();

        if (RendererToggle.OVERLAY_STRUCTURE_MAIN_TOGGLE.getBooleanValue() == false)
        {
            return copy;
        }

        synchronized (this.structures)
        {
            for (StructureType type : StructureType.VALUES)
            {
                Collection<StructureData> values = this.structures.get(type);
                Collection<StructureData> valuesCopy = new ArrayList<>();

                if (values.isEmpty() == false)
                {
                    for (StructureData structure : values)
                    {
                        if (MiscUtils.isStructureWithinRange(structure.getBoundingBox(), pos, maxRange))
                        {
                            valuesCopy.add(structure);
                        }
                    }

                    copy.putAll(type, valuesCopy);
                }
            }

            this.structureRendererNeedsUpdate = false;
        }

        return copy;
    }

    public void updateStructureData()
    {
        if (this.mc != null && this.mc.world != null && this.mc.player != null)
        {
            long currentTime = this.mc.world.getTime();

            if ((currentTime % 20) == 0)
            {
                if (this.mc.isIntegratedServerRunning())
                {
                    if (RendererToggle.OVERLAY_STRUCTURE_MAIN_TOGGLE.getBooleanValue())
                    {
                        BlockPos playerPos = PositionUtils.getEntityBlockPos(this.mc.player);

                        if (this.structuresNeedUpdating(playerPos, 32))
                        {
                            this.updateStructureDataFromIntegratedServer(playerPos);
                        }
                    }
                }
                else if (this.hasStructureDataFromServer)
                {
                    this.removeExpiredStructures(currentTime, this.structureDataTimeout);
                }
                else if (this.shouldRegisterStructureChannel && this.mc.getNetworkHandler() != null)
                {
                    if (RendererToggle.OVERLAY_STRUCTURE_MAIN_TOGGLE.getBooleanValue())
                    {
                        this.registerStructureChannel();
                        this.structuresNeedUpdating = true;
                    }

                    this.shouldRegisterStructureChannel = false;
                }
            }
        }
    }

    public void registerStructureChannel()
    {
        this.shouldRegisterStructureChannel = true;

        if (this.servuxServer == false && this.hasIntegratedServer == false && this.hasInValidServux == false)
        {
            if (HANDLER.isPlayRegistered(this.getNetworkChannel()))
            {
                MiniHUD.printDebug("DataStorage#registerStructureChannel(): sending STRUCTURES_REGISTER to Servux");

                NbtCompound nbt = new NbtCompound();
                nbt.putString("version", Reference.MOD_STRING);

                HANDLER.encodeStructuresPacket(new ServuxStructuresPacket(ServuxStructuresPacket.Type.PACKET_C2S_STRUCTURES_REGISTER, nbt));
            }
        }
        else
        {
            this.shouldRegisterStructureChannel = false;
        }
        // QuickCarpet doesn't exist for 1.20.5+,
        // Will re-add if they update it
    }

    public boolean receiveServuxStrucutresMetadata(NbtCompound data)
    {
        if (this.servuxServer == false && this.hasIntegratedServer == false &&
            this.shouldRegisterStructureChannel)
        {
            MiniHUD.printDebug("DataStorage#receiveServuxStrucutresMetadata(): received METADATA from Servux");

            if (data.getInt("version") != ServuxStructuresPacket.PROTOCOL_VERSION)
            {
                MiniHUD.logger.warn("structureChannel: Mis-matched protocol version!");
            }
            this.servuxTimeout = data.getInt("timeout");
            this.setServuxVersion(data.getString("servux"));
            if (data.contains("spawnPosX", Constants.NBT.TAG_INT))
            {
                HudDataManager.getInstance().setWorldSpawn(new BlockPos(data.getInt("spawnPosX"), data.getInt("spawnPosY"), data.getInt("spawnPosZ")));
            }
            if (data.contains("spawnChunkRadius", Constants.NBT.TAG_INT))
            {
                HudDataManager.getInstance().setSpawnChunkRadius(data.getInt("spawnChunkRadius"), true);
            }
            if (data.contains("worldSeed", Constants.NBT.TAG_LONG))
            {
                HudDataManager.getInstance().setWorldSeed(data.getLong("worldSeed"));
            }
            this.setIsServuxServer();

            if (RendererToggle.OVERLAY_STRUCTURE_MAIN_TOGGLE.getBooleanValue())
            {
                this.registerStructureChannel();
                return true;
            }
            else
            {
                this.unregisterStructureChannel();
            }
        }

        return false;
    }

    public void unregisterStructureChannel()
    {
        if (this.servuxServer || RendererToggle.OVERLAY_STRUCTURE_MAIN_TOGGLE.getBooleanValue() == false)
        {
            this.servuxServer = false;
            if (this.hasInValidServux == false)
            {
                MiniHUD.printDebug("DataStorage#unregisterStructureChannel(): for {}", this.servuxVersion != null ? this.servuxVersion : "<unknown>");

                HANDLER.encodeStructuresPacket(new ServuxStructuresPacket(ServuxStructuresPacket.Type.PACKET_C2S_STRUCTURES_UNREGISTER, new NbtCompound()));
                HANDLER.reset(HANDLER.getPayloadChannel());
            }
        }
        this.shouldRegisterStructureChannel = false;
    }

    public void onPacketFailure()
    {
        // Define how to handle multiple sendPayload failures
        this.shouldRegisterStructureChannel = false;
        this.servuxServer = false;
        this.hasInValidServux = true;
    }

    private boolean structuresNeedUpdating(BlockPos playerPos, int hysteresis)
    {
        return this.structuresNeedUpdating || this.lastStructureUpdatePos == null ||
                Math.abs(playerPos.getX() - this.lastStructureUpdatePos.getX()) >= hysteresis ||
                Math.abs(playerPos.getY() - this.lastStructureUpdatePos.getY()) >= hysteresis ||
                Math.abs(playerPos.getZ() - this.lastStructureUpdatePos.getZ()) >= hysteresis;
    }

    public int getStrucutreCount()
    {
        return this.structures.size();
    }

    private void updateStructureDataFromIntegratedServer(final BlockPos playerPos)
    {
        final RegistryKey<World> worldId = this.mc.player.getEntityWorld().getRegistryKey();
        final ServerWorld world = this.mc.getServer().getWorld(worldId);

        if (world != null)
        {
            MinecraftServer server = this.mc.getServer();
            final int maxChunkRange = this.mc.options.getClampedViewDistance();

            //server.executeTask(new ServerTask(server.getTicks(), () ->
            ((IMixinMinecraftServer) server).minihud_send(new ServerTask(server.getTicks(), () ->
            {
                synchronized (this.structures)
                {
                    this.addStructureDataFromGenerator(world, playerPos, maxChunkRange);
                }
            }));
        }
        else
        {
            synchronized (this.structures)
            {
                this.structures.clear();
            }
        }

        this.lastStructureUpdatePos = playerPos;
        this.structuresNeedUpdating = false;
    }

    public void addOrUpdateStructuresFromServer(NbtList structures, boolean isServux)
    {
        if (isServux == false)
        {
            MiniHUD.printDebug("DataStorage#addOrUpdateStructuresFromServer(): Ignoring structure data when isServux is false");
            //this.unregisterStructureChannel();
            return;
        }

        if (structures.getHeldType() == Constants.NBT.TAG_COMPOUND)
        {
            this.structureDataTimeout = this.servuxTimeout + 300;

            long currentTime = this.mc.world.getTime();
            final int count = structures.size();
            final int oldCount = this.structures.size();

            this.removeExpiredStructures(currentTime, this.structureDataTimeout);

            for (int i = 0; i < count; ++i)
            {
                NbtCompound tag = structures.getCompound(i);
                StructureData data = StructureData.fromStructureStartTag(tag, currentTime);

                if (data != null)
                {
                    // Remove the old entry and replace it with the new entry with the current refresh time
                    if (this.structures.containsEntry(data.getStructureType(), data))
                    {
                        this.structures.remove(data.getStructureType(), data);
                    }

                    this.structures.put(data.getStructureType(), data);
                }
            }

            MiniHUD.printDebug("addOrUpdateStructuresFromServer: received {} structures // total size {} -> {}", count, oldCount, this.structures.size());

            this.structureRendererNeedsUpdate = true;
            this.hasStructureDataFromServer = true;
        }
    }

    private void removeExpiredStructures(long currentTime, int timeout)
    {
        int countBefore = this.structures.values().size();

        this.structures.values().removeIf(data -> currentTime > (data.getRefreshTime() + (long) timeout));

        int countAfter = this.structures.values().size();

        if (countBefore != countAfter)
        {
            MiniHUD.printDebug("removeExpiredStructures: from server: {} -> {} structures", countBefore, countAfter);
        }
    }

    private void addStructureDataFromGenerator(ServerWorld world, BlockPos playerPos, int maxChunkRange)
    {
        int lastCount = this.structures.size();

        this.structures.clear();

        int minCX = (playerPos.getX() >> 4) - maxChunkRange;
        int minCZ = (playerPos.getZ() >> 4) - maxChunkRange;
        int maxCX = (playerPos.getX() >> 4) + maxChunkRange;
        int maxCZ = (playerPos.getZ() >> 4) + maxChunkRange;

        for (int cz = minCZ; cz <= maxCZ; ++cz)
        {
            for (int cx = minCX; cx <= maxCX; ++cx)
            {
                // Don't load the chunk
                Chunk chunk;
                try
                {
                     chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                }
                catch (Exception ignored)
                {
                    continue;
                }

                if (chunk == null)
                {
                    continue;
                }

                for (Map.Entry<Structure, StructureStart> entry : chunk.getStructureStarts().entrySet())
                {
                    Structure structure = entry.getKey();
                    StructureStart start = entry.getValue();
                    Identifier id = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE).getId(structure);
                    StructureType type = StructureType.fromStructureId(id != null ? id.toString() : "?");

                    if (type.isEnabled() &&
                        start.hasChildren() &&
                        MiscUtils.isStructureWithinRange(start.getBoundingBox(), playerPos, maxChunkRange << 4))
                    {
                        this.structures.put(type, StructureData.fromStructureStart(type, start));
                    }
                }
            }
        }

        MiniHUD.printDebug("addStructureDataFromGenerator: updated from the integrated server: {} -> {} structures", lastCount, this.structures.size());
        this.structureRendererNeedsUpdate = true;
    }

    public void handleCarpetServerTPSData(Text textComponent)
    {
        if (textComponent.getString().isEmpty() == false)
        {
            String text = Formatting.strip(textComponent.getString());
            String[] lines = text.split("\n");

            for (String line : lines)
            {
                Matcher matcher = PATTERN_CARPET_TPS.matcher(line);

                if (matcher.matches())
                {
                    try
                    {
                        this.serverTPS = Double.parseDouble(matcher.group("tps"));
                        this.serverMSPT = Double.parseDouble(matcher.group("mspt"));
                        this.serverTPSValid = true;
                        this.carpetServer = true;
                        return;
                    }
                    catch (NumberFormatException ignore) {}
                }
            }
        }
    }

    public JsonObject toJson()
    {
        JsonObject obj = new JsonObject();

        obj.add("distance_pos", JsonUtils.vec3dToJson(this.distanceReferencePoint));

        return obj;
    }

    public void fromJson(JsonObject obj)
    {
        Vec3d pos = JsonUtils.vec3dFromJson(obj, "distance_pos");
        this.distanceReferencePoint = Objects.requireNonNullElse(pos, Vec3d.ZERO);

        // Backwards compat
        if (JsonUtils.hasLong(obj, "seed"))
        {
            HudDataManager.getInstance().setWorldSeed(JsonUtils.getLong(obj, "seed"));
        }
        if (JsonUtils.hasInteger(obj, "spawn_chunk_radius"))
        {
            HudDataManager.getInstance().setSpawnChunkRadius(JsonUtils.getIntegerOrDefault(obj, "spawn_chunk_radius", 2), false);
        }
    }
}

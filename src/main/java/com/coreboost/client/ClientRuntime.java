package com.coreboost.client;

import com.coreboost.command.CoreBoostCommand;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBed;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientRuntime {
    public static volatile boolean culling = true, particles = true, hud = true, fastMath = true;
    public static volatile int particleCap = 256, cullDistance = 48;
    private static final int MAX_ENTITY_JOBS = 24, MAX_TILE_JOBS = 12;
    private static final ThreadPoolExecutor WORKERS = makeWorkers();
    private static final AtomicBoolean SCAN_ACTIVE = new AtomicBoolean();
    private static final ConcurrentHashMap<Integer, Decision> ENTITY_RESULTS = new ConcurrentHashMap<Integer, Decision>();
    private static final ConcurrentHashMap<Long, Decision> TILE_RESULTS = new ConcurrentHashMap<Long, Decision>();
    private static final AtomicLong HIDDEN = new AtomicLong(), SUPPRESSED = new AtomicLong(), CHAT_PARSED = new AtomicLong();
    private static final AtomicBoolean CHAT_BUSY = new AtomicBoolean();
    private static volatile int CHAT_LAST_WORDS;
    private static final Map<FontRenderer, LinkedHashMap<String, Integer>> WIDTHS =
        new WeakHashMap<FontRenderer, LinkedHashMap<String, Integer>>();
    private static Configuration config;
    private static volatile long tick, worldGeneration;
    private static World lastWorld;
    private static int entityCursor, tileCursor;
    private static int particleCount;

    private ClientRuntime() { }

    private static ThreadPoolExecutor makeWorkers() {
        final int count = Math.max(1, Runtime.getRuntime().availableProcessors());
        final AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "CoreBoost-Worker-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(), factory);
        pool.prestartAllCoreThreads();
        return pool;
    }

    public static synchronized void start(File file) {
        FastMath.initializeAsync(WORKERS);
        config = new Configuration(file);
        config.load();
        culling = config.get("optimizations", "multiThreadedCulling", true,
            "Snapshot and asynchronously evaluate visibility.").getBoolean(true);
        particles = config.get("optimizations", "particleLimiter", true,
            "Limit client particle additions each tick.").getBoolean(true);
        hud = config.get("optimizations", "hudWidthCache", true,
            "Cache repeated font string width calculations.").getBoolean(true);
        fastMath = config.get("optimizations", "fastMath", true,
            "Use a trig lookup table for culling camera vectors.").getBoolean(true);
        Property cap = config.get("optimizations", "particleCapPerTick", 256,
            "Maximum accepted particle additions each tick.");
        cap.setMinValue(32);
        cap.setMaxValue(2048);
        particleCap = cap.getInt(256);
        Property range = config.get("optimizations", "cullingDistance", 48,
            "Maximum distance for visibility snapshots.");
        range.setMinValue(16);
        range.setMaxValue(96);
        cullDistance = range.getInt(48);
        config.save();
        MinecraftForge.EVENT_BUS.register(new ClientRuntime());
        ClientCommandHandler.instance.registerCommand(new CoreBoostCommand());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            particleCount = 0;
        } else if (event.phase == TickEvent.Phase.END) {
            visibilityTick();
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.message == null || !CHAT_BUSY.compareAndSet(false, true)) return;
        final String copy = event.message.getFormattedText();
        try {
            WORKERS.execute(new Runnable() {
                public void run() {
                    try {
                        int words = 0;
                        boolean inWord = false;
                        for (int i = 0; i < copy.length(); i++) {
                            char c = copy.charAt(i);
                            if (c == '\u00a7' && i + 1 < copy.length()) { i++; continue; }
                            if (Character.isWhitespace(c)) inWord = false;
                            else if (!inWord) { words++; inWord = true; }
                        }
                        CHAT_LAST_WORDS = words;
                        CHAT_PARSED.incrementAndGet();
                    } finally {
                        CHAT_BUSY.set(false);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            CHAT_BUSY.set(false);
        }
    }

    public static boolean allowParticle(EntityFX effect) {
        if (!particles || effect == null) return true;
        if (particleCount < particleCap) { particleCount++; return true; }
        SUPPRESSED.incrementAndGet();
        return false;
    }

    public static boolean shouldCullEntity(int id) { return culling && recent(ENTITY_RESULTS.get(id)); }
    public static boolean shouldCullTile(long pos) { return culling && recent(TILE_RESULTS.get(pos)); }

    private static boolean recent(Decision result) {
        if (result == null || !result.hidden) return false;
        long age = tick - result.tick;
        return result.generation == worldGeneration && age >= 0L && age <= 2L;
    }

    private void visibilityTick() {
        tick++;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityPlayer player = mc.thePlayer;
        if (world == null || player == null) {
            if (lastWorld != null) { ENTITY_RESULTS.clear(); TILE_RESULTS.clear(); worldGeneration++; }
            lastWorld = null;
            return;
        }
        if (lastWorld != world) {
            ENTITY_RESULTS.clear();
            TILE_RESULTS.clear();
            worldGeneration++;
            lastWorld = world;
        }
        if (!culling || !SCAN_ACTIVE.compareAndSet(false, true)) return;
        if (tick % 100L == 0L) { ENTITY_RESULTS.clear(); TILE_RESULTS.clear(); }

        List<Job> jobs = new ArrayList<Job>(MAX_ENTITY_JOBS + MAX_TILE_JOBS);
        double ex = player.posX, ey = player.posY + player.getEyeHeight(), ez = player.posZ;
        float yaw = player.rotationYaw * ((float)Math.PI / 180.0F);
        float pitch = player.rotationPitch * ((float)Math.PI / 180.0F);
        float sy = fastMath ? FastMath.sin(yaw) : (float)Math.sin(yaw);
        float cy = fastMath ? FastMath.cos(yaw) : (float)Math.cos(yaw);
        float sp = fastMath ? FastMath.sin(pitch) : (float)Math.sin(pitch);
        float cp = fastMath ? FastMath.cos(pitch) : (float)Math.cos(pitch);
        double lx = -sy * cp, ly = -sp, lz = cy * cp;
        double limitSq = (double)cullDistance * cullDistance;

        List<Entity> entities = world.loadedEntityList;
        int entitySize = entities.size();
        int entityUsed = 0;
        if (entitySize > 0) {
            int start = Math.floorMod(entityCursor, entitySize);
            int visited = 0;
            for (; visited < entitySize && entityUsed < MAX_ENTITY_JOBS; visited++) {
                Entity e = entities.get((start + visited) % entitySize);
                if (e == null || e == player || !(e instanceof EntityPlayer || e instanceof EntityItem || e instanceof EntityArmorStand)) continue;
                Job job = snapshot(world, false, e.getEntityId(), 0L, ex, ey, ez, lx, ly, lz,
                    e.posX, e.posY + e.height * 0.5D, e.posZ, limitSq);
                if (job != null) { jobs.add(job); entityUsed++; }
            }
            entityCursor = (start + visited) % entitySize;
        }

        List<TileEntity> tiles = world.loadedTileEntityList;
        int tileSize = tiles.size();
        int tileUsed = 0;
        if (tileSize > 0) {
            int start = Math.floorMod(tileCursor, tileSize);
            int visited = 0;
            for (; visited < tileSize && tileUsed < MAX_TILE_JOBS; visited++) {
                TileEntity te = tiles.get((start + visited) % tileSize);
                if (!(te instanceof TileEntityChest) && !(te instanceof TileEntityBed)) continue;
                BlockPos p = te.getPos();
                if (p == null) continue;
                Job job = snapshot(world, true, 0, p.toLong(), ex, ey, ez, lx, ly, lz,
                    p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, limitSq);
                if (job != null) { jobs.add(job); tileUsed++; }
            }
            tileCursor = (start + visited) % tileSize;
        }

        if (jobs.isEmpty()) { SCAN_ACTIVE.set(false); return; }

        final AtomicInteger remaining = new AtomicInteger(jobs.size());
        for (Job job : jobs) {
            final Job immutable = job;
            try {
                WORKERS.execute(new Runnable() {
                    public void run() {
                        try {
                            boolean hide = immutable.behind() || immutable.blocked();
                            Decision result = new Decision(immutable.tick, immutable.generation, hide);
                            if (immutable.tile) TILE_RESULTS.put(immutable.tileKey, result);
                            else ENTITY_RESULTS.put(immutable.entityId, result);
                            if (hide) HIDDEN.incrementAndGet();
                        } catch (Throwable ignored) {
                            // Snapshot math is fail-open.
                        } finally {
                            if (remaining.decrementAndGet() == 0) SCAN_ACTIVE.set(false);
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                if (remaining.decrementAndGet() == 0) SCAN_ACTIVE.set(false);
            }
        }
    }

    private static Job snapshot(World world, boolean tile, int id, long key,
                                double ex, double ey, double ez, double lx, double ly, double lz,
                                double tx, double ty, double tz, double limitSq) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 16.0D || d2 > limitSq) return null;
        int steps = Math.max(2, Math.min(128, (int)Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) * 2.0D)));
        double[] boxes = new double[steps * 6];
        int count = 0, lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps; i++) {
            double t = i / (double)steps;
            int x = floor(ex + dx * t), y = floor(ey + dy * t), z = floor(ez + dz * t);
            if (x == lastX && y == lastY && z == lastZ) continue;
            lastX = x; lastY = y; lastZ = z;
            pos.set(x, y, z);
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (!block.isOpaqueCube()) continue;
            AxisAlignedBB box = block.getCollisionBoundingBox(world, pos, state);
            if (box == null) continue;
            int o = count++ * 6;
            boxes[o] = box.minX; boxes[o + 1] = box.minY; boxes[o + 2] = box.minZ;
            boxes[o + 3] = box.maxX; boxes[o + 4] = box.maxY; boxes[o + 5] = box.maxZ;
        }
        return new Job(tile, id, key, tick, worldGeneration, ex, ey, ez, tx, ty, tz, lx, ly, lz, boxes, count);
    }

    private static int floor(double d) { int i = (int)d; return d < i ? i - 1 : i; }

    private static final class Decision {
        final long tick, generation;
        final boolean hidden;

        Decision(long tick, long generation, boolean hidden) {
            this.tick = tick;
            this.generation = generation;
            this.hidden = hidden;
        }
    }

    private static final class Job {
        final boolean tile;
        final int entityId;
        final long tileKey, tick, generation;
        final double ex, ey, ez, tx, ty, tz, lx, ly, lz;
        final double[] boxes;
        final int count;

        Job(boolean tile, int id, long key, long tick, long generation, double ex, double ey, double ez,
            double tx, double ty, double tz, double lx, double ly, double lz, double[] boxes, int count) {
            this.tile = tile;
            entityId = id;
            tileKey = key;
            this.tick = tick;
            this.generation = generation;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.lx = lx;
            this.ly = ly;
            this.lz = lz;
            this.boxes = boxes;
            this.count = count;
        }

        boolean behind() {
            double dx = tx - ex, dy = ty - ey, dz = tz - ez;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return len > 0.0001D && (dx * lx + dy * ly + dz * lz) / len < -0.12D;
        }

        boolean blocked() {
            double dx = tx - ex, dy = ty - ey, dz = tz - ez;
            for (int i = 0; i < count; i++) {
                int o = i * 6;
                if (intersects(ex, ey, ez, dx, dy, dz, boxes[o], boxes[o + 1], boxes[o + 2],
                    boxes[o + 3], boxes[o + 4], boxes[o + 5])) return true;
            }
            return false;
        }

        private static boolean intersects(double ox, double oy, double oz, double dx, double dy, double dz,
                                          double ax, double ay, double az, double bx, double by, double bz) {
            double x1, x2, y1, y2, z1, z2;
            if (Math.abs(dx) < 1e-9) {
                if (ox < ax || ox > bx) return false;
                x1 = -Double.MAX_VALUE;
                x2 = Double.MAX_VALUE;
            } else {
                x1 = (ax - ox) / dx;
                x2 = (bx - ox) / dx;
                if (x1 > x2) { double s = x1; x1 = x2; x2 = s; }
            }
            if (Math.abs(dy) < 1e-9) {
                if (oy < ay || oy > by) return false;
                y1 = -Double.MAX_VALUE;
                y2 = Double.MAX_VALUE;
            } else {
                y1 = (ay - oy) / dy;
                y2 = (by - oy) / dy;
                if (y1 > y2) { double s = y1; y1 = y2; y2 = s; }
            }
            if (Math.abs(dz) < 1e-9) {
                if (oz < az || oz > bz) return false;
                z1 = -Double.MAX_VALUE;
                z2 = Double.MAX_VALUE;
            } else {
                z1 = (az - oz) / dz;
                z2 = (bz - oz) / dz;
                if (z1 > z2) { double s = z1; z1 = z2; z2 = s; }
            }
            double low = Math.max(0.025D, Math.max(x1, Math.max(y1, z1)));
            double high = Math.min(0.975D, Math.min(x2, Math.min(y2, z2)));
            return high >= low;
        }
    }

    public static synchronized Integer cachedWidth(FontRenderer font, String text) {
        LinkedHashMap<String, Integer> map = WIDTHS.get(font);
        return map == null ? null : map.get(text);
    }

    public static synchronized void cacheWidth(FontRenderer font, String text, int value) {
        LinkedHashMap<String, Integer> map = WIDTHS.get(font);
        if (map == null) {
            map = new LinkedHashMap<String, Integer>(96, 0.75F, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) {
                    return size() > 512;
                }
            };
            WIDTHS.put(font, map);
        }
        map.put(text, value);
    }

    public static synchronized void clearWidths(FontRenderer font) { WIDTHS.remove(font); }

    public static synchronized int widthCacheSize() {
        int result = 0;
        for (Map<String, Integer> map : WIDTHS.values()) result += map.size();
        return result;
    }

    public static String[] status() {
        return new String[] {
            "Workers: " + WORKERS.getPoolSize() + " total, " + WORKERS.getActiveCount() + " active, " + WORKERS.getQueue().size() + " queued",
            "Culling: " + (culling ? "on" : "off") + " | hidden decisions: " + HIDDEN.get(),
            "Particles: " + (particles ? "on" : "off") + " | cap/tick: " + particleCap + " | suppressed: " + SUPPRESSED.get(),
            "HUD cache: " + (hud ? "on" : "off") + " | strings: " + widthCacheSize(),
            "Fast math: " + (fastMath ? "on" : "off") + " | async chat parses: " + CHAT_PARSED.get() + " (last words: " + CHAT_LAST_WORDS + ")"
        };
    }

    public static synchronized void setOption(String option, boolean value) {
        if ("culling".equals(option)) culling = value;
        else if ("particles".equals(option)) particles = value;
        else if ("hud".equals(option)) { hud = value; if (!value) WIDTHS.clear(); }
        else if ("fastmath".equals(option)) fastMath = value;
        if (config != null) {
            String key = "culling".equals(option) ? "multiThreadedCulling"
                : "particles".equals(option) ? "particleLimiter"
                : "hud".equals(option) ? "hudWidthCache" : "fastMath";
            config.get("optimizations", key, true).set(value);
            config.save();
        }
    }
}

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
import org.spongepowered.asm.mixin.Mixins;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientRuntime {
    private static final Minecraft MC = Minecraft.getMinecraft();
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "CoreBoost-worker-" + THREAD_NUMBER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static final AtomicReference<CullingSnapshot> CULLING =
            new AtomicReference<CullingSnapshot>(CullingSnapshot.EMPTY);
    private static final AtomicInteger PARTICLES_THIS_TICK = new AtomicInteger();
    private static final AtomicLong CHAT_PARSED = new AtomicLong();
    private static volatile String CHAT_LAST_WORDS = "none";
    private static volatile long lastCullingTick;
    private static volatile int allowedParticles = 160;

    private static final Map<FontRenderer, Map<String, Integer>> WIDTH_CACHE =
            Collections.synchronizedMap(new java.util.WeakHashMap<FontRenderer, Map<String, Integer>>());

    public static volatile boolean culling = true;
    public static volatile boolean particles = true;
    public static volatile boolean hud = true;
    public static volatile boolean fastMath = true;

    private ClientRuntime() {
    }

    public static void start(File configFile) {
        Configuration config = new Configuration(configFile);
        config.load();

        culling = config.get("optimizations", "culling", true).getBoolean(true);
        particles = config.get("optimizations", "particles", true).getBoolean(true);
        hud = config.get("optimizations", "hudCache", true).getBoolean(true);
        fastMath = config.get("optimizations", "fastMath", true).getBoolean(true);
        allowedParticles = Math.max(20, config.get("optimizations", "particlesPerTick", 160).getInt(160));

        if (config.hasChanged()) {
            config.save();
        }

        Mixins.addConfiguration("mixins.coreboost.json");
        MinecraftForge.EVENT_BUS.register(new ClientRuntime());
        ClientCommandHandler.instance.registerCommand(new CoreBoostCommand());

        FastMath.initializeAsync(WORKERS);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (MC.theWorld == null || MC.thePlayer == null) {
            CULLING.set(CullingSnapshot.EMPTY);
            PARTICLES_THIS_TICK.set(0);
            return;
        }

        PARTICLES_THIS_TICK.set(0);
        long tick = MC.theWorld.getTotalWorldTime();
        if (culling && tick - lastCullingTick >= 2L) {
            lastCullingTick = tick;
            submitCullingSnapshot();
        }
    }

    private static void submitCullingSnapshot() {
        final net.minecraft.entity.Entity viewEntity = MC.getRenderViewEntity();
        final World world = MC.theWorld;

        if (viewEntity == null || world == null) {
            CULLING.set(CullingSnapshot.EMPTY);
            return;
        }

        final double x = viewEntity.posX;
        final double y = viewEntity.posY + viewEntity.getEyeHeight();
        final double z = viewEntity.posZ;
        final float yaw = viewEntity.rotationYaw;
        final float pitch = viewEntity.rotationPitch;
        final float reach = 64.0F;

        final List<EntitySample> entities = new ArrayList<EntitySample>();
        for (Entity entity : world.loadedEntityList) {
            if (entity == null || entity == viewEntity || entity.isDead) {
                continue;
            }
            if (entity instanceof EntityPlayer || entity instanceof EntityItem || entity instanceof EntityArmorStand) {
                entities.add(new EntitySample(entity.getEntityId(), entity.posX, entity.posY + entity.height * 0.5,
                        entity.posZ, entity.width, entity.height));
            }
        }

        final List<TileSample> tiles = new ArrayList<TileSample>();
        for (TileEntity tile : world.loadedTileEntityList) {
            if (tile == null || tile.isInvalid()) {
                continue;
            }
            if (tile instanceof TileEntityChest) {
                BlockPos pos = tile.getPos();
                if (pos != null) {
                    tiles.add(new TileSample(pos.toLong(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
                }
            }
        }

        WORKERS.execute(new Runnable() {
            @Override
            public void run() {
                Map<Integer, Boolean> entityResults = new HashMap<Integer, Boolean>(entities.size());
                for (EntitySample sample : entities) {
                    entityResults.put(sample.id, shouldHide(x, y, z, yaw, pitch, reach,
                            sample.x, sample.y, sample.z, sample.width, sample.height));
                }

                Map<Long, Boolean> tileResults = new HashMap<Long, Boolean>(tiles.size());
                for (TileSample sample : tiles) {
                    tileResults.put(sample.key, shouldHide(x, y, z, yaw, pitch, reach,
                            sample.x, sample.y, sample.z, 1.0, 1.0));
                }

                CULLING.set(new CullingSnapshot(entityResults, tileResults));
            }
        });
    }

    private static boolean shouldHide(double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
                                      float reach, double targetX, double targetY, double targetZ,
                                      double width, double height) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq > reach * reach || distanceSq < 0.0001) {
            return distanceSq > reach * reach;
        }

        double distance = Math.sqrt(distanceSq);
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double lookX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double lookY = -Math.sin(pitchRadians);
        double lookZ = Math.cos(yawRadians) * Math.cos(pitchRadians);
        double dot = (dx * lookX + dy * lookY + dz * lookZ) / distance;

        double radius = Math.max(width, height) * 0.5;
        double angularPadding = Math.min(0.35, radius / distance);
        if (dot < 0.25 - angularPadding) {
            return true;
        }

        // Deliberately avoid reading world blocks here: Minecraft world access stays on the client thread.
        return false;
    }

    public static boolean shouldCullEntity(int entityId) {
        if (!culling) {
            return false;
        }
        Boolean hidden = CULLING.get().entities.get(entityId);
        return hidden != null && hidden.booleanValue();
    }

    public static boolean shouldCullTile(long tileKey) {
        if (!culling) {
            return false;
        }
        Boolean hidden = CULLING.get().tiles.get(tileKey);
        return hidden != null && hidden.booleanValue();
    }

    public static boolean allowParticle(EntityFX effect) {
        if (!particles || effect == null) {
            return true;
        }
        return PARTICLES_THIS_TICK.incrementAndGet() <= allowedParticles;
    }

    public static Integer cachedWidth(FontRenderer renderer, String text) {
        if (!hud || renderer == null || text == null) {
            return null;
        }

        synchronized (WIDTH_CACHE) {
            Map<String, Integer> widths = WIDTH_CACHE.get(renderer);
            return widths == null ? null : widths.get(text);
        }
    }

    public static void cacheWidth(FontRenderer renderer, String text, int width) {
        if (!hud || renderer == null || text == null || text.length() > 256) {
            return;
        }

        synchronized (WIDTH_CACHE) {
            Map<String, Integer> widths = WIDTH_CACHE.get(renderer);
            if (widths == null) {
                widths = new HashMap<String, Integer>();
                WIDTH_CACHE.put(renderer, widths);
            }
            if (widths.size() > 2048) {
                widths.clear();
            }
            widths.put(text, Integer.valueOf(width));
        }
    }

    public static void clearWidths(FontRenderer renderer) {
        if (renderer == null) {
            return;
        }
        synchronized (WIDTH_CACHE) {
            WIDTH_CACHE.remove(renderer);
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) {
            return;
        }

        final String text = event.message.getUnformattedText();
        WORKERS.execute(new Runnable() {
            @Override
            public void run() {
                int words = 0;
                boolean inWord = false;
                for (int i = 0; i < text.length(); i++) {
                    boolean whitespace = Character.isWhitespace(text.charAt(i));
                    if (!whitespace && !inWord) {
                        words++;
                    }
                    inWord = !whitespace;
                }
                CHAT_LAST_WORDS = Integer.toString(words);
                CHAT_PARSED.incrementAndGet();
            }
        });
    }

    public static List<String> status() {
        List<String> lines = new ArrayList<String>();
        lines.add("§aCoreBoost active");
        lines.add("§7Workers: " + THREAD_NUMBER.get() + "/" + Math.max(1, Runtime.getRuntime().availableProcessors()));
        lines.add("§7Culling: " + (culling ? "on" : "off") + " | particles: " + (particles ? "on" : "off"));
        lines.add("§7HUD cache: " + (hud ? "on" : "off"));
        lines.add("§7Fast math: " + (fastMath ? "on" : "off") + " | async chat parses: "
                + CHAT_PARSED.get() + " (last words: " + CHAT_LAST_WORDS + ")");
        return lines;
    }

    public static boolean toggle(String key) {
        boolean value;
        if ("culling".equals(key)) {
            culling = !culling;
            value = culling;
        } else if ("particles".equals(key)) {
            particles = !particles;
            value = particles;
        } else if ("hud".equals(key)) {
            hud = !hud;
            value = hud;
        } else if ("fastmath".equals(key) || "fastMath".equals(key)) {
            fastMath = !fastMath;
            value = fastMath;
        } else {
            return false;
        }
        return value;
    }

    private static final class EntitySample {
        private final int id;
        private final double x;
        private final double y;
        private final double z;
        private final double width;
        private final double height;

        private EntitySample(int id, double x, double y, double z, double width, double height) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
        }
    }

    private static final class TileSample {
        private final long key;
        private final double x;
        private final double y;
        private final double z;

        private TileSample(long key, double x, double y, double z) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class CullingSnapshot {
        private static final CullingSnapshot EMPTY =
                new CullingSnapshot(Collections.<Integer, Boolean>emptyMap(),
                        Collections.<Long, Boolean>emptyMap());

        private final Map<Integer, Boolean> entities;
        private final Map<Long, Boolean> tiles;

        private CullingSnapshot(Map<Integer, Boolean> entities, Map<Long, Boolean> tiles) {
            this.entities = entities;
            this.tiles = tiles;
        }
    }
}

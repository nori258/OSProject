package com.spawnerdetector.modules;

import com.spawnerdetector.SpawnerDetectorAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldEvents;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds mob spawners on servers that hide blocks below Y=0 from the client.
 * <ul>
 *     <li>Packets (confirmed): spawner block entities in chunk data and block entity update packets.</li>
 *     <li>Activity (confirmed): the block event and spawn particles a spawner broadcasts each time it spawns mobs.
 *     The server only runs a spawner while a player is within 16 blocks of it.</li>
 *     <li>Light (possible): single light sources below Y=0 (torches, lanterns, furnaces) in the light data the
 *     server sends, which give away lit bases even when their blocks are hidden.</li>
 *     <li>Sound (possible): spawner mob sounds coming from a spot with no visible mob of that type.</li>
 * </ul>
 * Trial spawners and cave spider spawners are never reported, and the possible layers stay quiet around them.
 * <p>
 * Packet events arrive on the network thread, so all world access, state changes and chat output
 * are handed to the client thread.
 */
public class SpawnerDetector extends Module {
    private static final Logger LOG = LoggerFactory.getLogger("SpawnerDetector");

    /** A possible spawner this close to a known spawner is treated as that spawner. */
    private static final double SAME_SPAWNER_RADIUS = 24;
    /** The possible layers ignore anything this close to a trial spawner or a cave spider spawner. */
    private static final double IGNORE_RADIUS = 32;
    /** Spawned mobs appear within 4 blocks of the spawner; look a little further for the ones that already moved. */
    private static final double SPAWNED_MOB_RADIUS = 6;
    /** Ticks to wait after a spawner activity packet so the spawned mobs reach the client before we look at them. */
    private static final int ACTIVITY_DELAY = 10;
    /** Torches are 14, lanterns and glowstone 15, furnaces 13. Candles and soul lanterns stay below this. */
    private static final int MIN_LIGHT_SOURCE_LEVEL = 13;
    private static final int BEAM_HEIGHT = 256;

    private static final SystemToast.Type CONFIRMED_TOAST = new SystemToast.Type(6000L);
    private static final SystemToast.Type POSSIBLE_TOAST = new SystemToast.Type(5000L);

    private record SpawnerSound(String mob, Set<EntityType<?>> sources) {}

    private record SpawnerData(BlockPos pos, BlockEntityType<?> type, @Nullable NbtCompound nbt) {}

    private record LightSource(int sectionIndex, int x, int y, int z, int level) {}

    // Cave spiders play the spider's sounds (there is no cave spider ambient sound), so spider sounds are opt-in.
    private static final Identifier SPIDER_AMBIENT = SoundEvents.ENTITY_SPIDER_AMBIENT.id();

    private static final Map<Identifier, SpawnerSound> SPAWNER_SOUNDS = Map.of(
        SoundEvents.ENTITY_ZOMBIE_AMBIENT.id(), new SpawnerSound("Zombie", Set.of(EntityType.ZOMBIE)),
        SoundEvents.ENTITY_SKELETON_AMBIENT.id(), new SpawnerSound("Skeleton", Set.of(EntityType.SKELETON)),
        SPIDER_AMBIENT, new SpawnerSound("Spider", Set.of(EntityType.SPIDER, EntityType.CAVE_SPIDER)),
        SoundEvents.ENTITY_BLAZE_AMBIENT.id(), new SpawnerSound("Blaze", Set.of(EntityType.BLAZE)),
        SoundEvents.ENTITY_SILVERFISH_AMBIENT.id(), new SpawnerSound("Silverfish", Set.of(EntityType.SILVERFISH)),
        SoundEvents.ENTITY_SILVERFISH_STEP.id(), new SpawnerSound("Silverfish", Set.of(EntityType.SILVERFISH))
    );

    private final Setting<Boolean> packetDetection;
    private final Setting<Boolean> activityDetection;
    private final Setting<Boolean> lightDetection;
    private final Setting<Boolean> soundDetection;
    private final Setting<Integer> soundRadius;
    private final Setting<Boolean> spiderSounds;
    private final Setting<Boolean> notifications;
    private final Setting<Boolean> debug;
    private final Setting<Boolean> spawnerBeam;
    private final Setting<Integer> beamWidth;
    private final Setting<SettingColor> beamColor;
    private final Setting<SettingColor> possibleBeamColor;

    private final Set<BlockPos> confirmedSpawners = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> possibleSpawners = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> ignoredSpawners = ConcurrentHashMap.newKeySet();
    /** Spawners that just reported activity, with the ticks left before we classify them. */
    private final Map<BlockPos, Integer> pendingActivity = new HashMap<>();

    private final Color beamSideColor = new Color();
    private ClientWorld trackedWorld;

    public SpawnerDetector() {
        super(SpawnerDetectorAddon.CATEGORY, "spawner-detector", "Detects mob spawners from packets, spawner activity, light and mob sounds.");

        // Stay subscribed while joining: the first chunk packets arrive before Meteor's GameJoinedEvent.
        runInMainMenu = true;

        SettingGroup sgGeneral = settings.getDefaultGroup();
        SettingGroup sgRender = settings.createGroup("Render");

        packetDetection = sgGeneral.add(new BoolSetting.Builder()
            .name("packet-detection")
            .description("Confirms spawners from chunk data and block entity update packets. Does nothing if the server strips hidden spawners from chunks.")
            .defaultValue(true)
            .build()
        );

        activityDetection = sgGeneral.add(new BoolSetting.Builder()
            .name("activity-detection")
            .description("Confirms spawners from the packets they send when they spawn mobs. Works on hidden spawners, but only while a player is within 16 blocks of one and you are within 64.")
            .defaultValue(true)
            .build()
        );

        lightDetection = sgGeneral.add(new BoolSetting.Builder()
            .name("light-detection")
            .description("Flags single light sources below Y=0 (torches, lanterns, furnaces) from the light data the server sends. Finds lit bases, not spawners directly.")
            .defaultValue(true)
            .build()
        );

        soundDetection = sgGeneral.add(new BoolSetting.Builder()
            .name("sound-detection")
            .description("Flags spawner mob sounds with no visible mob nearby as possible spawners.")
            .defaultValue(true)
            .build()
        );

        soundRadius = sgGeneral.add(new IntSetting.Builder()
            .name("sound-radius")
            .description("Maximum distance from you, in blocks, for sound detection.")
            .defaultValue(20)
            .range(5, 50)
            .sliderRange(5, 50)
            .visible(soundDetection::get)
            .build()
        );

        spiderSounds = sgGeneral.add(new BoolSetting.Builder()
            .name("spider-sounds")
            .description("Also use spider sounds. Cave spiders make the same sound, so this can flag mineshaft cave spider spawners.")
            .defaultValue(false)
            .visible(soundDetection::get)
            .build()
        );

        notifications = sgGeneral.add(new BoolSetting.Builder()
            .name("notifications")
            .description("Shows a toast when a spawner is found.")
            .defaultValue(true)
            .build()
        );

        debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug")
            .description("Logs every block entity, spawner activity, light and sound packet to the game log.")
            .defaultValue(false)
            .build()
        );

        spawnerBeam = sgRender.add(new BoolSetting.Builder()
            .name("spawner-beam")
            .description("Renders a beam through blocks at every detected spawner.")
            .defaultValue(true)
            .build()
        );

        beamWidth = sgRender.add(new IntSetting.Builder()
            .name("beam-width")
            .description("Beam width in blocks.")
            .defaultValue(3)
            .range(1, 5)
            .sliderRange(1, 5)
            .visible(spawnerBeam::get)
            .build()
        );

        beamColor = sgRender.add(new ColorSetting.Builder()
            .name("beam-color")
            .description("Beam color for confirmed spawners.")
            .defaultValue(new SettingColor(255, 50, 50, 180))
            .visible(spawnerBeam::get)
            .build()
        );

        possibleBeamColor = sgRender.add(new ColorSetting.Builder()
            .name("possible-beam-color")
            .description("Beam color for possible spawners.")
            .defaultValue(new SettingColor(255, 165, 0, 120))
            .visible(spawnerBeam::get)
            .build()
        );
    }

    @Override
    public void onActivate() {
        syncWorld();
        if (mc.world == null) return;

        // Spawners in chunks that loaded before the module was enabled, as long as the client can see them.
        for (BlockEntity blockEntity : Utils.blockEntities()) {
            BlockEntityType<?> type = blockEntity.getType();
            if (type != BlockEntityType.MOB_SPAWNER && type != BlockEntityType.TRIAL_SPAWNER) continue;

            NbtCompound nbt = blockEntity.toInitialChunkDataNbt(mc.world.getRegistryManager());
            handleSpawner(new SpawnerData(blockEntity.getPos().toImmutable(), type, nbt));
        }
    }

    @Override
    public void onDeactivate() {
        clearState();
        trackedWorld = null;
    }

    // Block entity packets

    @EventHandler
    private void onBlockEntityPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof BlockEntityUpdateS2CPacket) && !(event.packet instanceof ChunkDataS2CPacket)) return;

        List<SpawnerData> spawners = new ArrayList<>();

        if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            collectSpawner(packet.getPos(), packet.getBlockEntityType(), packet.getNbt(), spawners);
        } else if (event.packet instanceof ChunkDataS2CPacket packet) {
            packet.getChunkData()
                .getBlockEntities(packet.getChunkX(), packet.getChunkZ())
                .accept((pos, type, nbt) -> collectSpawner(pos, type, nbt, spawners));
        }

        if (!spawners.isEmpty()) runOnClientThread(() -> spawners.forEach(this::handleSpawner));
    }

    private void collectSpawner(BlockPos pos, BlockEntityType<?> type, @Nullable NbtCompound nbt, List<SpawnerData> spawners) {
        if (debug.get()) LOG.info("[debug] Block entity {} at {}", Registries.BLOCK_ENTITY_TYPE.getId(type), pos.toShortString());

        // Chunk data reuses one mutable position for every block entity.
        if (type == BlockEntityType.MOB_SPAWNER || type == BlockEntityType.TRIAL_SPAWNER) {
            spawners.add(new SpawnerData(pos.toImmutable(), type, nbt));
        }
    }

    private void handleSpawner(SpawnerData spawner) {
        BlockPos pos = spawner.pos();

        if (spawner.type() == BlockEntityType.TRIAL_SPAWNER) {
            ignoreSpawner(pos);
            return;
        }

        EntityType<?> mob = spawnerMob(spawner.nbt());
        if (mob == EntityType.CAVE_SPIDER) {
            ignoreSpawner(pos);
            return;
        }

        if (packetDetection.get()) confirmSpawner(pos, mob != null ? mob.getName().getString() : "Unknown", "");
    }

    private static @Nullable EntityType<?> spawnerMob(@Nullable NbtCompound nbt) {
        if (nbt == null) return null;

        String id = nbt.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getString("id", "");
        return id.isEmpty() ? null : EntityType.get(id).orElse(null);
    }

    // Spawner activity: sent to everyone within 64 blocks whenever a spawner spawns mobs, even if its block is hidden

    @EventHandler
    private void onActivityPacket(PacketEvent.Receive event) {
        if (!activityDetection.get()) return;

        BlockPos pos;
        if (event.packet instanceof BlockEventS2CPacket packet && packet.getBlock() == Blocks.SPAWNER) {
            pos = packet.getPos().toImmutable();
        } else if (event.packet instanceof WorldEventS2CPacket packet && packet.getEventId() == WorldEvents.SPAWNER_SPAWNS_MOB) {
            pos = packet.getPos().toImmutable();
        } else {
            return;
        }

        if (debug.get()) LOG.info("[debug] Spawner activity ({}) at {}", event.packet.getClass().getSimpleName(), pos.toShortString());
        runOnClientThread(() -> {
            if (!confirmedSpawners.contains(pos) && !ignoredSpawners.contains(pos)) pendingActivity.putIfAbsent(pos, ACTIVITY_DELAY);
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (pendingActivity.isEmpty() || !Utils.canUpdate()) return;
        syncWorld();

        List<BlockPos> ready = new ArrayList<>();
        pendingActivity.replaceAll((pos, ticks) -> ticks - 1);
        pendingActivity.forEach((pos, ticks) -> {
            if (ticks <= 0) ready.add(pos);
        });

        for (BlockPos pos : ready) {
            pendingActivity.remove(pos);
            classifyActiveSpawner(pos);
        }
    }

    /** Names an active spawner after the mobs that just appeared around it, if the server lets us see them. */
    private void classifyActiveSpawner(BlockPos pos) {
        if (confirmedSpawners.contains(pos) || ignoredSpawners.contains(pos)) return;

        Box around = new Box(pos).expand(SPAWNED_MOB_RADIUS);
        LivingEntity mob = mc.world.getEntitiesByClass(LivingEntity.class, around, entity -> !(entity instanceof PlayerEntity))
            .stream()
            .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(Vec3d.ofCenter(pos))))
            .orElse(null);

        if (mob != null && mob.getType() == EntityType.CAVE_SPIDER) {
            ignoreSpawner(pos);
            return;
        }

        String mobName = mob != null ? mob.getType().getName().getString() : "Active";
        confirmSpawner(pos, mobName, " (spawning)");
    }

    // Light: single bright light sources below Y=0

    @EventHandler
    private void onLightPacket(PacketEvent.Receive event) {
        if (!lightDetection.get()) return;

        int chunkX;
        int chunkZ;
        LightData data;
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            chunkX = packet.getChunkX();
            chunkZ = packet.getChunkZ();
            data = packet.getLightData();
        } else if (event.packet instanceof LightUpdateS2CPacket packet) {
            chunkX = packet.getChunkX();
            chunkZ = packet.getChunkZ();
            data = packet.getData();
        } else {
            return;
        }

        List<LightSource> sources = findLightSources(data);
        if (debug.get()) LOG.info("[debug] Light for chunk {}, {}: block light sections {}, single light sources {}", chunkX, chunkZ, data.getInitedBlock(), sources.size());
        if (sources.isEmpty()) return;

        runOnClientThread(() -> {
            int bottomY = mc.world.getBottomY();

            for (LightSource source : sources) {
                // Light data starts one section below the world.
                int sectionY = (source.sectionIndex() - 1) * 16 + bottomY;
                int y = sectionY + source.y();
                if (sectionY < bottomY || y >= 0) continue;

                BlockPos pos = new BlockPos(chunkX * 16 + source.x(), y, chunkZ * 16 + source.z());
                reportPossible(pos, "Light source (level %d) below Y=0".formatted(source.level()));
            }
        });
    }

    /**
     * Finds blocks whose block light is at least {@link #MIN_LIGHT_SOURCE_LEVEL} and brighter than every neighbour:
     * a single light source such as a torch or lantern. Lava lakes are skipped because their blocks light each other.
     */
    private static List<LightSource> findLightSources(LightData data) {
        BitSet sent = data.getInitedBlock();
        List<byte[]> nibbles = data.getBlockNibbles();

        Map<Integer, byte[]> sections = new HashMap<>();
        int nibble = 0;
        for (int index = sent.nextSetBit(0); index >= 0 && nibble < nibbles.size(); index = sent.nextSetBit(index + 1)) {
            sections.put(index, nibbles.get(nibble++));
        }

        List<LightSource> sources = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : sections.entrySet()) {
            int index = entry.getKey();
            byte[] light = entry.getValue();
            if (maxLevel(light) < MIN_LIGHT_SOURCE_LEVEL) continue;

            byte[] below = sections.get(index - 1);
            byte[] above = sections.get(index + 1);

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int level = level(light, x, y, z);
                        if (level < MIN_LIGHT_SOURCE_LEVEL) continue;

                        if (x > 0 && level(light, x - 1, y, z) >= level) continue;
                        if (x < 15 && level(light, x + 1, y, z) >= level) continue;
                        if (z > 0 && level(light, x, y, z - 1) >= level) continue;
                        if (z < 15 && level(light, x, y, z + 1) >= level) continue;
                        if (y > 0 && level(light, x, y - 1, z) >= level) continue;
                        if (y < 15 && level(light, x, y + 1, z) >= level) continue;
                        // Sections above and below that were not sent have no block light.
                        if (y == 0 && below != null && level(below, x, 15, z) >= level) continue;
                        if (y == 15 && above != null && level(above, x, 0, z) >= level) continue;

                        sources.add(new LightSource(index, x, y, z, level));
                    }
                }
            }
        }

        return sources;
    }

    private static int level(byte[] light, int x, int y, int z) {
        int index = y << 8 | z << 4 | x;
        return light[index >> 1] >> ((index & 1) << 2) & 15;
    }

    private static int maxLevel(byte[] light) {
        int max = 0;
        for (byte b : light) {
            max = Math.max(max, Math.max(b & 15, b >> 4 & 15));
        }
        return max;
    }

    // Mob sounds

    @EventHandler
    private void onSoundPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlaySoundS2CPacket packet) || !soundDetection.get()) return;

        Identifier id = packet.getSound().value().id();
        Vec3d soundPos = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
        if (debug.get()) LOG.info("[debug] Sound {} at {}", id, soundPos);

        SpawnerSound sound = SPAWNER_SOUNDS.get(id);
        if (sound == null || (id.equals(SPIDER_AMBIENT) && !spiderSounds.get())) return;

        runOnClientThread(() -> {
            if (mc.player.getEntityPos().distanceTo(soundPos) > soundRadius.get()) return;

            Box searchBox = Box.of(soundPos, 16, 16, 16);
            if (!mc.world.getEntitiesByClass(LivingEntity.class, searchBox, entity -> sound.sources().contains(entity.getType())).isEmpty()) return;

            reportPossible(BlockPos.ofFloored(soundPos), sound.mob() + " sound with no visible mob");
        });
    }

    // Broken spawners

    @EventHandler
    private void onBlockUpdatePacket(PacketEvent.Receive event) {
        List<BlockPos> airPositions = new ArrayList<>();

        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            if (packet.getState().isAir()) airPositions.add(packet.getPos().toImmutable());
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates((pos, state) -> {
                if (state.isAir()) airPositions.add(pos.toImmutable());
            });
        } else {
            return;
        }

        if (airPositions.isEmpty()) return;

        // Only air counts: hiding a block replaces it with stone or deepslate, breaking it leaves air.
        runOnClientThread(() -> {
            for (BlockPos pos : airPositions) {
                if (confirmedSpawners.remove(pos)) {
                    ChatUtils.infoPrefix("Spawner", "Spawner at (highlight)%s(default) was broken", pos.toShortString());
                    LOG.info("Spawner at {} was broken", pos.toShortString());
                }
                ignoredSpawners.remove(pos);
                pendingActivity.remove(pos);
            }
        });
    }

    // Shared

    private void confirmSpawner(BlockPos pos, String mobName, String detail) {
        // Active spawners resend their data on every spawn cycle, so only announce new ones.
        if (!confirmedSpawners.add(pos)) return;
        possibleSpawners.removeIf(possible -> possible.isWithinDistance(pos, SAME_SPAWNER_RADIUS));

        String coords = pos.toShortString();
        ChatUtils.infoPrefix("Spawner", "(highlight)%s(default) spawner at (highlight)%s(default)%s", mobName, coords, detail);
        LOG.info("Confirmed {} spawner at {}{}", mobName, coords, detail);
        if (notifications.get()) showToast(CONFIRMED_TOAST, "Spawner Found", mobName + " @ " + coords);
    }

    private void ignoreSpawner(BlockPos pos) {
        confirmedSpawners.remove(pos);
        pendingActivity.remove(pos);
        if (ignoredSpawners.add(pos)) possibleSpawners.removeIf(possible -> possible.isWithinDistance(pos, IGNORE_RADIUS));
    }

    private void reportPossible(BlockPos pos, String reason) {
        if (isNear(confirmedSpawners, pos, SAME_SPAWNER_RADIUS)) return;
        if (isNear(possibleSpawners, pos, SAME_SPAWNER_RADIUS)) return;
        if (isNear(ignoredSpawners, pos, IGNORE_RADIUS)) return;

        possibleSpawners.add(pos);

        String coords = pos.toShortString();
        ChatUtils.warningPrefix("Spawner?", "%s @ (highlight)%s", reason, coords);
        LOG.info("Possible spawner at {}: {}", coords, reason);
        if (notifications.get()) showToast(POSSIBLE_TOAST, "Possible Spawner", coords);
    }

    private static boolean isNear(Set<BlockPos> spawners, BlockPos pos, double radius) {
        for (BlockPos spawner : spawners) {
            if (spawner.isWithinDistance(pos, radius)) return true;
        }
        return false;
    }

    private void showToast(SystemToast.Type type, String title, String text) {
        // show() updates the visible toast of this type instead of queueing another one.
        SystemToast.show(mc.getToastManager(), type, Text.literal(title), Text.literal(text));
    }

    private void runOnClientThread(Runnable task) {
        mc.execute(() -> {
            // Drop tasks that were queued just before the module was turned off or the player left.
            if (!isActive() || !Utils.canUpdate()) return;

            syncWorld();
            task.run();
        });
    }

    /** Spawner positions belong to one dimension, so forget them whenever the client world changes. */
    private void syncWorld() {
        if (mc.world == trackedWorld) return;

        trackedWorld = mc.world;
        clearState();
    }

    private void clearState() {
        confirmedSpawners.clear();
        possibleSpawners.clear();
        ignoredSpawners.clear();
        pendingActivity.clear();
    }

    // Render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!spawnerBeam.get()) return;
        syncWorld();

        for (BlockPos pos : confirmedSpawners) {
            renderBeam(event, pos, beamColor.get());
            event.renderer.box(pos, beamColor.get(), beamColor.get(), ShapeMode.Lines, 0);
        }

        for (BlockPos pos : possibleSpawners) {
            renderBeam(event, pos, possibleBeamColor.get());
        }
    }

    private void renderBeam(Render3DEvent event, BlockPos pos, Color color) {
        double half = beamWidth.get() / 2.0;
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;

        // Faint sides so a wide beam does not hide everything behind it, solid edges so it stays easy to spot.
        beamSideColor.set(color).a(color.a / 3);
        event.renderer.box(x - half, pos.getY(), z - half, x + half, pos.getY() + BEAM_HEIGHT, z + half, beamSideColor, color, ShapeMode.Both, 0);
    }
}

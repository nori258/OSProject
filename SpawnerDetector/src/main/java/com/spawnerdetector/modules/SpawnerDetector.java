package com.spawnerdetector.modules;

import com.spawnerdetector.SpawnerDetectorAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds mob spawners on servers that hide blocks below Y=0 from the client.
 * <ul>
 *     <li>Layer 1 (confirmed): spawner block entities in chunk data and block entity update packets.</li>
 *     <li>Layer 2 (possible): light update packets that leave a whole section below Y=0 without block light.</li>
 *     <li>Layer 3 (possible): spawner mob sounds coming from a spot with no visible mob of that type.</li>
 * </ul>
 * Trial spawners and cave spider spawners are never reported, and layers 2 and 3 stay quiet around them.
 * <p>
 * Packet events arrive on the network thread, so all world access, state changes and chat output
 * are handed to the client thread.
 */
public class SpawnerDetector extends Module {
    private static final Logger LOG = LoggerFactory.getLogger("SpawnerDetector");

    /** A possible spawner this close to a known spawner is treated as that spawner. Covers neighbouring light sections. */
    private static final double SAME_SPAWNER_RADIUS = 24;
    /** Layers 2 and 3 ignore anything this close to a trial spawner or a cave spider spawner. */
    private static final double IGNORE_RADIUS = 32;
    private static final int BEAM_HEIGHT = 256;

    private static final SystemToast.Type CONFIRMED_TOAST = new SystemToast.Type(6000L);
    private static final SystemToast.Type POSSIBLE_TOAST = new SystemToast.Type(5000L);

    private record SpawnerSound(String mob, Set<EntityType<?>> sources) {}

    private record SpawnerData(BlockPos pos, BlockEntityType<?> type, @Nullable NbtCompound nbt) {}

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
    private final Setting<Boolean> lightDetection;
    private final Setting<Boolean> soundDetection;
    private final Setting<Integer> soundRadius;
    private final Setting<Boolean> spiderSounds;
    private final Setting<Boolean> notifications;
    private final Setting<Boolean> debug;
    private final Setting<Boolean> spawnerBeam;
    private final Setting<SettingColor> beamColor;
    private final Setting<SettingColor> possibleBeamColor;

    private final Set<BlockPos> confirmedSpawners = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> possibleSpawners = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> ignoredSpawners = ConcurrentHashMap.newKeySet();

    private ClientWorld trackedWorld;

    public SpawnerDetector() {
        super(SpawnerDetectorAddon.CATEGORY, "spawner-detector", "Detects mob spawners from packets, light updates and mob sounds.");

        // Stay subscribed while joining: the first chunk packets arrive before Meteor's GameJoinedEvent.
        runInMainMenu = true;

        SettingGroup sgGeneral = settings.getDefaultGroup();
        SettingGroup sgRender = settings.createGroup("Render");

        packetDetection = sgGeneral.add(new BoolSetting.Builder()
            .name("packet-detection")
            .description("Confirms spawners from chunk data and block entity update packets.")
            .defaultValue(true)
            .build()
        );

        lightDetection = sgGeneral.add(new BoolSetting.Builder()
            .name("light-detection")
            .description("Flags light updates that leave a whole section below Y=0 without block light as possible spawners.")
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
            .description("Logs every block entity, light update and sound packet to the game log.")
            .defaultValue(false)
            .build()
        );

        spawnerBeam = sgRender.add(new BoolSetting.Builder()
            .name("spawner-beam")
            .description("Renders a beam through blocks at every detected spawner.")
            .defaultValue(true)
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
        confirmedSpawners.clear();
        possibleSpawners.clear();
        ignoredSpawners.clear();
        trackedWorld = null;
    }

    // Layer 1: block entity packets

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
            confirmedSpawners.remove(pos);
            ignoreSpawner(pos);
            return;
        }

        // Active spawners resend their block entity on every spawn cycle, so only announce new ones.
        if (!packetDetection.get() || !confirmedSpawners.add(pos)) return;
        possibleSpawners.removeIf(possible -> possible.isWithinDistance(pos, SAME_SPAWNER_RADIUS));

        String mobName = mob != null ? mob.getName().getString() : "Unknown";
        String coords = pos.toShortString();
        ChatUtils.infoPrefix("Spawner", "(highlight)%s(default) spawner at (highlight)%s", mobName, coords);
        LOG.info("Confirmed {} spawner at {}", mobName, coords);
        if (notifications.get()) showToast(CONFIRMED_TOAST, "Spawner Found", mobName + " @ " + coords);
    }

    private void ignoreSpawner(BlockPos pos) {
        if (ignoredSpawners.add(pos)) possibleSpawners.removeIf(possible -> possible.isWithinDistance(pos, IGNORE_RADIUS));
    }

    private static @Nullable EntityType<?> spawnerMob(@Nullable NbtCompound nbt) {
        if (nbt == null) return null;

        String id = nbt.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getString("id", "");
        return id.isEmpty() ? null : EntityType.get(id).orElse(null);
    }

    // Layer 2: light updates

    @EventHandler
    private void onLightPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof LightUpdateS2CPacket packet) || !lightDetection.get()) return;

        int chunkX = packet.getChunkX();
        int chunkZ = packet.getChunkZ();
        LightData data = packet.getData();
        BitSet sent = data.getInitedBlock();
        BitSet empty = data.getUninitedBlock();
        List<byte[]> nibbles = data.getBlockNibbles();

        // Sections sent as empty have no block light at all; sent sections can still be all zero.
        BitSet dark = (BitSet) empty.clone();
        int nibble = 0;
        for (int index = sent.nextSetBit(0); index >= 0 && nibble < nibbles.size(); index = sent.nextSetBit(index + 1)) {
            if (isAllZero(nibbles.get(nibble++))) dark.set(index);
        }

        if (debug.get()) LOG.info("[debug] Light update for chunk {}, {}: block light sections sent {}, empty {}, dark {}", chunkX, chunkZ, sent, empty, dark);
        if (dark.isEmpty()) return;

        runOnClientThread(() -> {
            int bottomY = mc.world.getBottomY();

            for (int index = dark.nextSetBit(0); index >= 0; index = dark.nextSetBit(index + 1)) {
                // Light data starts one section below the world.
                int sectionY = (index - 1) * 16 + bottomY;
                if (sectionY < bottomY || sectionY >= 0) continue;

                if (debug.get()) LOG.info("[debug] Light anomaly at chunk {} {} section {}", chunkX, chunkZ, sectionY >> 4);

                BlockPos candidate = new BlockPos(chunkX * 16 + 8, sectionY + 8, chunkZ * 16 + 8);
                reportPossible(candidate, "Light anomaly in chunk %d, %d (Y %d to %d)".formatted(chunkX, chunkZ, sectionY, sectionY + 15));
            }
        });
    }

    private static boolean isAllZero(byte[] nibbles) {
        for (byte b : nibbles) {
            if (b != 0) return false;
        }
        return true;
    }

    // Layer 3: mob sounds

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

    // Shared

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
        confirmedSpawners.clear();
        possibleSpawners.clear();
        ignoredSpawners.clear();
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
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        event.renderer.line(x, pos.getY(), z, x, pos.getY() + BEAM_HEIGHT, z, color);
    }
}

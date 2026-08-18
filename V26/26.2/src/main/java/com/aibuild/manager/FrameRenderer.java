package com.aibuild.manager;

import com.aibuild.Aibuild;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders two kinds of live particle wireframes:
 * 1. Selection frame (configurable color) — the two-point area the player selected.
 * 2. Preview frame (separate configurable color) — where a schematic will be placed.
 *
 * Both are rendered simultaneously via a repeating scheduler task, so the player
 * can see their selection and the schematic placement target at the same time.
 *
 * Configured via config.yml under "frame" and "preview" sections.
 */
public class FrameRenderer {

    private final Aibuild plugin;

    /** Per-player selection edge points (world-space coordinates). */
    private final Map<UUID, List<Location>> frames = new HashMap<>();

    /** Per-player preview (schematic placement) edge points. */
    private final Map<UUID, List<Location>> previewFrames = new HashMap<>();

    private static final int DEFAULT_DENSITY = 2;
    private static final int MIN_DENSITY = 1;
    private static final int DEFAULT_INTERVAL = 10;
    private static final float DEFAULT_SIZE = 1.0f;

    /** Repeating task that emits the particles for every active frame. */
    private BukkitTask task;

    public FrameRenderer(Aibuild plugin) {
        this.plugin = plugin;

        // Clean up when the player leaves or changes worlds.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                clearAllFrames(e.getPlayer());
            }

            @org.bukkit.event.EventHandler
            public void onWorldSwitch(org.bukkit.event.player.PlayerChangedWorldEvent e) {
                clearAllFrames(e.getPlayer());
            }
        }, plugin);
    }

    // =================================================================
    //  Configuration
    // =================================================================

    /** @return true if the selection wireframe is enabled in config. */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("frame.enabled", true);
    }

    /** @return true if the preview wireframe is enabled in config. */
    public boolean isPreviewEnabled() {
        return plugin.getConfig().getBoolean("preview.enabled", true);
    }

    // =================================================================
    //  Selection frame (red / configured color)
    // =================================================================

    /**
     * Render or refresh the selection wireframe for the given two-point selection.
     */
    public void showFrame(Player player, BlockVector3 pos1, BlockVector3 pos2) {
        if (!isEnabled()) {
            clearFrame(player);
            return;
        }

        int minX = Math.min(pos1.x(), pos2.x());
        int minY = Math.min(pos1.y(), pos2.y());
        int minZ = Math.min(pos1.z(), pos2.z());
        int maxX = Math.max(pos1.x(), pos2.x());
        int maxY = Math.max(pos1.y(), pos2.y());
        int maxZ = Math.max(pos1.z(), pos2.z());

        int density = Math.max(MIN_DENSITY, plugin.getConfig().getInt("frame.density", DEFAULT_DENSITY));

        double x0 = minX, y0 = minY, z0 = minZ;
        double x1 = maxX + 1.0, y1 = maxY + 1.0, z1 = maxZ + 1.0;

        List<Location> points = new ArrayList<>();
        World world = player.getWorld();

        buildCuboidEdges(points, world, x0, y0, z0, x1, y1, z1, density);

        frames.put(player.getUniqueId(), points);
        ensureTaskRunning();
    }

    /** Clear the selection wireframe for a player. */
    public void clearFrame(Player player) {
        frames.remove(player.getUniqueId());
        stopTaskIfIdle();
    }

    // =================================================================
    //  Preview frame (green / separate color)
    // =================================================================

    /**
     * Show a preview wireframe indicating where a schematic will be placed.
     *
     * @param player   the viewing player
     * @param worldMin world-space min corner of the schematic placement
     * @param worldMax world-space max corner of the schematic placement
     */
    public void showPreviewFrame(Player player, BlockVector3 worldMin, BlockVector3 worldMax) {
        if (!isPreviewEnabled()) {
            clearPreviewFrame(player);
            return;
        }

        int density = Math.max(MIN_DENSITY, plugin.getConfig().getInt("frame.density", DEFAULT_DENSITY));

        double x0 = worldMin.x();
        double y0 = worldMin.y();
        double z0 = worldMin.z();
        double x1 = worldMax.x() + 1.0;
        double y1 = worldMax.y() + 1.0;
        double z1 = worldMax.z() + 1.0;

        List<Location> points = new ArrayList<>();
        World world = player.getWorld();

        buildCuboidEdges(points, world, x0, y0, z0, x1, y1, z1, density);

        previewFrames.put(player.getUniqueId(), points);
        ensureTaskRunning();
    }

    /** Clear the preview wireframe for a player. */
    public void clearPreviewFrame(Player player) {
        previewFrames.remove(player.getUniqueId());
        stopTaskIfIdle();
    }

    /** Clear both selection and preview frames for a player. */
    public void clearAllFrames(Player player) {
        frames.remove(player.getUniqueId());
        previewFrames.remove(player.getUniqueId());
        stopTaskIfIdle();
    }

    /** Clear all wireframes server-wide (used on plugin disable). */
    public void clearAll() {
        frames.clear();
        previewFrames.clear();
        stopTaskIfIdle();
    }

    // =================================================================
    //  Particle rendering
    // =================================================================

    /** Start the repeating render task if it isn't already running. */
    private void ensureTaskRunning() {
        if (task != null) return;
        int interval = Math.max(1, plugin.getConfig().getInt("frame.interval", DEFAULT_INTERVAL));
        task = new BukkitRunnable() {
            @Override
            public void run() {
                render();
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    /** Stop the render task once no player has any active frame. */
    private void stopTaskIfIdle() {
        if (task != null && frames.isEmpty() && previewFrames.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    /** Emit particles for all active frames (selection + preview). */
    private void render() {
        if (frames.isEmpty() && previewFrames.isEmpty()) {
            stopTaskIfIdle();
            return;
        }

        Particle.DustOptions selectionDust = readSelectionDustOptions();
        Particle.DustOptions previewDust = readPreviewDustOptions();

        // Render selection frames
        for (Map.Entry<UUID, List<Location>> entry : frames.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            for (Location loc : entry.getValue()) {
                if (loc.getWorld() == null || !loc.getWorld().equals(player.getWorld())) continue;
                player.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, selectionDust);
            }
        }

        // Render preview frames
        for (Map.Entry<UUID, List<Location>> entry : previewFrames.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            for (Location loc : entry.getValue()) {
                if (loc.getWorld() == null || !loc.getWorld().equals(player.getWorld())) continue;
                player.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, previewDust);
            }
        }
    }

    /** Build DUST options for the selection frame. */
    private Particle.DustOptions readSelectionDustOptions() {
        List<Integer> rgb = plugin.getConfig().getIntegerList("frame.color");
        int r = 255, g = 40, b = 40; // default: redstone red
        if (rgb != null && rgb.size() >= 3) {
            r = clamp(rgb.get(0));
            g = clamp(rgb.get(1));
            b = clamp(rgb.get(2));
        }
        double size = plugin.getConfig().getDouble("frame.size", DEFAULT_SIZE);
        return new Particle.DustOptions(Color.fromRGB(r, g, b), (float) Math.max(0.1, size));
    }

    /** Build DUST options for the preview frame (different color). */
    private Particle.DustOptions readPreviewDustOptions() {
        List<Integer> rgb = plugin.getConfig().getIntegerList("preview.color");
        int r = 40, g = 220, b = 80; // default: green
        if (rgb != null && rgb.size() >= 3) {
            r = clamp(rgb.get(0));
            g = clamp(rgb.get(1));
            b = clamp(rgb.get(2));
        }
        double size = plugin.getConfig().getDouble("preview.size", DEFAULT_SIZE);
        return new Particle.DustOptions(Color.fromRGB(r, g, b), (float) Math.max(0.1, size));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // =================================================================
    //  Geometry helpers
    // =================================================================

    /**
     * Build the 12 edges of a cuboid from (x0,y0,z0) to (x1,y1,z1) into the
     * given point list, with the specified point density.
     */
    private void buildCuboidEdges(List<Location> out, World world,
                                  double x0, double y0, double z0,
                                  double x1, double y1, double z1,
                                  int density) {
        // 4 edges along X
        addEdge(out, world, x0, y0, z0, x1, y0, z0, density);
        addEdge(out, world, x0, y1, z0, x1, y1, z0, density);
        addEdge(out, world, x0, y0, z1, x1, y0, z1, density);
        addEdge(out, world, x0, y1, z1, x1, y1, z1, density);
        // 4 edges along Y
        addEdge(out, world, x0, y0, z0, x0, y1, z0, density);
        addEdge(out, world, x1, y0, z0, x1, y1, z0, density);
        addEdge(out, world, x0, y0, z1, x0, y1, z1, density);
        addEdge(out, world, x1, y0, z1, x1, y1, z1, density);
        // 4 edges along Z
        addEdge(out, world, x0, y0, z0, x0, y0, z1, density);
        addEdge(out, world, x1, y0, z0, x1, y0, z1, density);
        addEdge(out, world, x0, y1, z0, x0, y1, z1, density);
        addEdge(out, world, x1, y1, z0, x1, y1, z1, density);
    }

    /**
     * Add evenly spaced points along a single axis-aligned edge.
     */
    private void addEdge(List<Location> out, World world,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         int density) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0) {
            out.add(new Location(world, x1, y1, z1));
            return;
        }

        double step = Math.max(MIN_DENSITY, density);
        int count = (int) Math.floor(length / step);
        for (int i = 0; i <= count; i++) {
            double t = (i * step) / length;
            out.add(new Location(world, x1 + dx * t, y1 + dy * t, z1 + dz * t));
        }
        // Always include the far endpoint so the corner is closed.
        out.add(new Location(world, x2, y2, z2));
    }
}

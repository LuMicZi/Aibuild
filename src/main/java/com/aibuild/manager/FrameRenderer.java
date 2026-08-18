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
 * Renders the two-point selection as a live particle wireframe: DUST particles
 * are re-spawned along the 12 edges of the cuboid on a repeating scheduler task,
 * so the player actually sees the outline in-game (WorldEdit-CUI-like effect).
 *
 * Each player has their own edge point cloud and their own visibility. The frame
 * is refreshed when the selection changes and cleared when the player switches
 * worlds, leaves, or after a successful build (see SchematicManager).
 *
 * Configured via config.yml under the "frame" section:
 *   frame.enabled  -> whether the wireframe is rendered at all
 *   frame.density  -> spacing (in blocks) between points on each edge
 *   frame.interval -> ticks between particle refreshes (lower = smoother)
 *   frame.color    -> RGB list [r, g, b] for the DUST particle color
 *   frame.size     -> DUST particle scale
 */
public class FrameRenderer {

    private final Aibuild plugin;
    /** Per-player list of edge points to render (world-space coordinates). */
    private final Map<UUID, List<Location>> frames = new HashMap<>();

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
                clearFrame(e.getPlayer());
            }

            @org.bukkit.event.EventHandler
            public void onWorldSwitch(org.bukkit.event.player.PlayerChangedWorldEvent e) {
                clearFrame(e.getPlayer());
            }
        }, plugin);
    }

    /** @return true if the wireframe is enabled in config. */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("frame.enabled", true);
    }

    /**
     * Render or refresh the wireframe for the given selection.
     * Does nothing (and clears any existing frame) if disabled in config.
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

        // Render outline over the full block cuboid: from the min block's lower
        // corner to the max block's upper corner (+1 so the last block is enclosed).
        double x0 = minX, y0 = minY, z0 = minZ;
        double x1 = maxX + 1.0, y1 = maxY + 1.0, z1 = maxZ + 1.0;

        List<Location> points = new ArrayList<>();
        World world = player.getWorld();

        // 12 edges of the cuboid.
        // 4 edges along X
        addEdge(points, world, x0, y0, z0, x1, y0, z0, density);
        addEdge(points, world, x0, y1, z0, x1, y1, z0, density);
        addEdge(points, world, x0, y0, z1, x1, y0, z1, density);
        addEdge(points, world, x0, y1, z1, x1, y1, z1, density);
        // 4 edges along Y
        addEdge(points, world, x0, y0, z0, x0, y1, z0, density);
        addEdge(points, world, x1, y0, z0, x1, y1, z0, density);
        addEdge(points, world, x0, y0, z1, x0, y1, z1, density);
        addEdge(points, world, x1, y0, z1, x1, y1, z1, density);
        // 4 edges along Z
        addEdge(points, world, x0, y0, z0, x0, y0, z1, density);
        addEdge(points, world, x1, y0, z0, x1, y0, z1, density);
        addEdge(points, world, x0, y1, z0, x0, y1, z1, density);
        addEdge(points, world, x1, y1, z0, x1, y1, z1, density);

        frames.put(player.getUniqueId(), points);
        ensureTaskRunning();
    }

    /** Clear the wireframe for a player. Safe to call when no frame is shown. */
    public void clearFrame(Player player) {
        frames.remove(player.getUniqueId());
        stopTaskIfIdle();
    }

    /** Clear all wireframes server-wide (used on plugin disable). */
    public void clearAll() {
        frames.clear();
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

    /** Stop the render task once no player has an active frame. */
    private void stopTaskIfIdle() {
        if (task != null && frames.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    /** Emit the DUST particles for every active frame, to its owning player only. */
    private void render() {
        if (frames.isEmpty()) {
            stopTaskIfIdle();
            return;
        }

        Particle.DustOptions dust = readDustOptions();

        for (Map.Entry<UUID, List<Location>> entry : frames.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            for (Location loc : entry.getValue()) {
                // Only render points in the player's current world.
                if (loc.getWorld() == null || !loc.getWorld().equals(player.getWorld())) continue;
                // Send the particle to this player only, so it doesn't spam others.
                player.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, dust);
            }
        }
    }

    /** Build the DUST options (color + size) from config, falling back to red. */
    private Particle.DustOptions readDustOptions() {
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

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // =================================================================
    //  Geometry helpers
    // =================================================================

    /**
     * Add evenly spaced points along a single axis-aligned edge from
     * (x1,y1,z1) to (x2,y2,z2). Spacing between points is {@code density} blocks;
     * both endpoints are always included.
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

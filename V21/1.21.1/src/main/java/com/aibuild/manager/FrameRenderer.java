package com.aibuild.manager;

import com.aibuild.Aibuild;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders the two-point selection as a wireframe (dashed points along each
 * edge + corner markers) using invisible marker ArmorStands.
 *
 * Each player has their own set of entities. The wireframe is cleared
 * when the selection changes, the player switches worlds, leaves, or after
 * a successful build (see SchematicManager).
 *
 * The entire feature is toggled via config.yml:
 *   frame.enabled  -> whether wireframes are rendered at all
 *   frame.density  -> spacing (in blocks) between points on each edge
 */
public class FrameRenderer {

    private final Aibuild plugin;
    private final Map<UUID, List<Entity>> markers = new HashMap<>();

    private static final int DEFAULT_DENSITY = 2;
    private static final int MIN_DENSITY = 1;

    public FrameRenderer(Aibuild plugin) {
        this.plugin = plugin;

        // Clean up when the plugin disables.
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
     * Does nothing if the frame is disabled in config.
     */
    public void showFrame(Player player, BlockVector3 pos1, BlockVector3 pos2) {
        if (!isEnabled()) {
            clearFrame(player);
            return;
        }

        World world = player.getWorld();
        int minX = Math.min(pos1.x(), pos2.x());
        int minY = Math.min(pos1.y(), pos2.y());
        int minZ = Math.min(pos1.z(), pos2.z());
        int maxX = Math.max(pos1.x(), pos2.x());
        int maxY = Math.max(pos1.y(), pos2.y());
        int maxZ = Math.max(pos1.z(), pos2.z());

        int density = Math.max(MIN_DENSITY, plugin.getConfig().getInt("frame.density", DEFAULT_DENSITY));

        List<Entity> list = markers.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        // Wipe previous markers for this player first.
        removeEntities(list);
        list.clear();

        // Corner dots (8 corners)
        for (int x = minX; x <= maxX; x += (maxX - minX == 0 ? 1 : (maxX - minX))) {
            for (int y = minY; y <= maxY; y += (maxY - minY == 0 ? 1 : (maxY - minY))) {
                for (int z = minZ; z <= maxZ; z += (maxZ - minZ == 0 ? 1 : (maxZ - minZ))) {
                    list.add(spawnMarker(world, x + 0.5, y + 0.5, z + 0.5));
                }
            }
        }

        // Edges (12 edges of a cuboid)
        // Axis-aligned helper -> place dots between the two ends of an edge.
        addEdgeDots(list, world, minX, minY, minZ, maxX, minY, minZ, density); // bottom X-
        addEdgeDots(list, world, minX, minY, maxZ, maxX, minY, maxZ, density); // bottom X+
        addEdgeDots(list, world, minX, minY, minZ, minX, minY, maxZ, density); // bottom Z-
        addEdgeDots(list, world, maxX, minY, minZ, maxX, minY, maxZ, density); // bottom Z+
        addEdgeDots(list, world, minX, maxY, minZ, maxX, maxY, minZ, density); // top X-
        addEdgeDots(list, world, minX, maxY, maxZ, maxX, maxY, maxZ, density); // top X+
        addEdgeDots(list, world, minX, maxY, minZ, minX, maxY, maxZ, density); // top Z-
        addEdgeDots(list, world, maxX, maxY, minZ, maxX, maxY, maxZ, density); // top Z+
        addEdgeDots(list, world, minX, minY, minZ, minX, maxY, minZ, density); // -X side Y-
        addEdgeDots(list, world, maxX, minY, minZ, maxX, maxY, minZ, density); // +X side Y-
        addEdgeDots(list, world, minX, minY, maxZ, minX, maxY, maxZ, density); // -X side Y+
        addEdgeDots(list, world, maxX, minY, maxZ, maxX, maxY, maxZ, density); // +X side Y+
    }

    /**
     * Clear the wireframe for a player, removing all spawned markers.
     * Safe to call when no frame is currently shown.
     */
    public void clearFrame(Player player) {
        List<Entity> list = markers.remove(player.getUniqueId());
        if (list != null) {
            removeEntities(list);
        }
    }

    /** Remove all wireframes server-wide (used on plugin disable). */
    public void clearAll() {
        for (List<Entity> list : markers.values()) {
            removeEntities(list);
        }
        markers.clear();
    }

    // =================================================================
    //  Helpers
    // =================================================================

    private ArmorStand spawnMarker(World world, double x, double y, double z) {
        ArmorStand stand = (ArmorStand) world.spawnEntity(new Location(world, x, y, z), EntityType.ARMOR_STAND);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setVisible(false);
        stand.setCustomNameVisible(false);
        stand.setPersistent(false);
        return stand;
    }

    private void addEdgeDots(List<Entity> list, World world,
                             int x1, int y1, int z1, int x2, int y2, int z2,
                             int density) {
        // Determine the primary axis (the one that changes)
        int dx = x2 - x1;
        int dy = y2 - y1;
        int dz = z2 - z1;

        // Build a parametric list of positions starting from (x1,y1,z1), ending at (x2,y2,z2).
        // density controls spacing between dots.
        int curX = x1;
        int curY = y1;
        int curZ = z1;
        list.add(spawnMarker(world, curX + 0.5, curY + 0.5, curZ + 0.5));

        // Simple stepping: increment along the axis that changes.
        if (dx != 0) {
            int step = Integer.signum(dx) * Math.max(1, density);
            int target = x2;
            while (Math.abs(curX - target) > 0) {
                curX += step;
                if ((step > 0 && curX > target) || (step < 0 && curX < target)) curX = target;
                list.add(spawnMarker(world, curX + 0.5, curY + 0.5, curZ + 0.5));
            }
        } else if (dy != 0) {
            int step = Integer.signum(dy) * Math.max(1, density);
            int target = y2;
            while (Math.abs(curY - target) > 0) {
                curY += step;
                if ((step > 0 && curY > target) || (step < 0 && curY < target)) curY = target;
                list.add(spawnMarker(world, curX + 0.5, curY + 0.5, curZ + 0.5));
            }
        } else if (dz != 0) {
            int step = Integer.signum(dz) * Math.max(1, density);
            int target = z2;
            while (Math.abs(curZ - target) > 0) {
                curZ += step;
                if ((step > 0 && curZ > target) || (step < 0 && curZ < target)) curZ = target;
                list.add(spawnMarker(world, curX + 0.5, curY + 0.5, curZ + 0.5));
            }
        }
    }

    private void removeEntities(List<Entity> list) {
        for (Entity e : list) {
            if (e != null && !e.isDead()) {
                e.remove();
            }
        }
    }
}

package com.aibuild.manager;

import com.aibuild.Aibuild;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.entity.Player;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SchematicManager {

    private final Aibuild plugin;

    /**
     * Stores each player's last build region so it can be "undone" later.
     * The stored bounds are the actual world coordinates of the placed schematic.
     * Map key: player UUID.
     */
    private final Map<UUID, BuildRecord> lastBuilds = new HashMap<>();

    private static final class BuildRecord {
        final String worldName;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        BuildRecord(String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        int countBlocks() {
            return (Math.abs(maxX - minX) + 1) * (Math.abs(maxY - minY) + 1) * (Math.abs(maxZ - minZ) + 1);
        }
    }

    public SchematicManager(Aibuild plugin) {
        this.plugin = plugin;
    }

    public List<String> getSchematicNames() {
        List<String> names = new ArrayList<>();
        File folder = plugin.getSchematicFolder();
        if (!folder.exists()) return names;
        File[] files = folder.listFiles((dir, n) -> n.endsWith(".schem") || n.endsWith(".schematic"));
        if (files == null) return names;
        for (File f : files) {
            names.add(f.getName().replace(".schem", "").replace(".schematic", ""));
        }
        return names;
    }

    public boolean hasSchematic(String name) {
        File folder = plugin.getSchematicFolder();
        if (!folder.exists()) return false;
        return new File(folder, name + ".schem").exists() || new File(folder, name + ".schematic").exists();
    }

    private File findSchematicFile(String name) {
        File folder = plugin.getSchematicFolder();
        File f1 = new File(folder, name + ".schem");
        if (f1.exists()) return f1;
        File f2 = new File(folder, name + ".schematic");
        if (f2.exists()) return f2;
        return null;
    }

    private Clipboard loadClipboard(String name) throws IOException {
        File file = findSchematicFile(name);
        if (file == null) throw new IOException("file not found");
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) throw new IOException("unsupported format");
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            return reader.read();
        }
    }

    /**
     * Paste the schematic for the player.
     *
     * If the player has a complete two-point selection (pos1 & pos2) in the
     * current world, the schematic's bottom-corner (min point) is aligned to
     * the selection's min corner. Otherwise it falls back to the player's
     * feet (bottom-center aligned), preserving the original behavior.
     *
     * On success, stores the world bounds so they can be "undone" later.
     */
    public boolean pasteSchematic(Player player, String schematicName) {
        if (!hasSchematic(schematicName)) {
            player.sendMessage(plugin.msg("schematic-not-found", "name", schematicName));
            return false;
        }

        Clipboard clipboard;
        try {
            clipboard = loadClipboard(schematicName);
        } catch (Exception e) {
            player.sendMessage(plugin.msg("building-failed", "error", plugin.msgRaw("building-failed-load")));
            plugin.getLogger().warning("Failed to load '" + schematicName + "': " + e.getMessage());
            return false;
        }

        BlockVector3 min = clipboard.getMinimumPoint();
        BlockVector3 max = clipboard.getMaximumPoint();
        BlockVector3 origin = clipboard.getOrigin();

        int width = max.x() - min.x() + 1;
        int height = max.y() - min.y() + 1;
        int length = max.z() - min.z() + 1;

        BlockVector3 targetOrigin;
        if (plugin.getSelectionManager().hasCompleteSelection(player)) {
            // Align the schematic's min-corner to the selection's min corner.
            BlockVector3 regionMin = plugin.getSelectionManager().getRegionMin(player);
            targetOrigin = regionMin.add(origin).subtract(min);
            player.sendMessage(plugin.msg("building-mode-selection"));
        } else {
            // Fallback: align schematic bottom-center on the player's feet.
            BlockVector3 localCenter = BlockVector3.at(min.x() + width / 2, min.y(), min.z() + length / 2);
            BlockVector3 playerPos = BukkitAdapter.asBlockVector(player.getLocation());
            targetOrigin = playerPos.subtract(localCenter).add(origin);
            player.sendMessage(plugin.msg("building-mode-feet"));
        }

        // Compute world coordinates for info messages and undo record
        int worldMinX = Math.min(min.x(), max.x()) + (targetOrigin.x() - origin.x());
        int worldMaxX = Math.max(min.x(), max.x()) + (targetOrigin.x() - origin.x());
        int worldMinY = Math.min(min.y(), max.y()) + (targetOrigin.y() - origin.y());
        int worldMaxY = Math.max(min.y(), max.y()) + (targetOrigin.y() - origin.y());
        int worldMinZ = Math.min(min.z(), max.z()) + (targetOrigin.z() - origin.z());
        int worldMaxZ = Math.max(min.z(), max.z()) + (targetOrigin.z() - origin.z());

        // Send localized messages
        player.sendMessage(plugin.msg("building-started", "name", schematicName));
        player.sendMessage(plugin.msg("building-size",
                "w", String.valueOf(width),
                "h", String.valueOf(height),
                "l", String.valueOf(length)));
        player.sendMessage(plugin.msg("building-bounds",
                "mx", String.valueOf(worldMinX),
                "my", String.valueOf(worldMinY),
                "mz", String.valueOf(worldMinZ),
                "Mx", String.valueOf(worldMaxX),
                "My", String.valueOf(worldMaxY),
                "Mz", String.valueOf(worldMaxZ)));

        // Execute paste via WorldEdit (fastMode = no history -> no watchdog slowdown)
        World bukkitWorld = player.getLocation().getWorld();
        com.sk89q.worldedit.world.World weWorld = new BukkitWorld(bukkitWorld);

        try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            editSession.setFastMode(true);

            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(targetOrigin)
                    .ignoreAirBlocks(false)
                    .build();

            Operations.complete(operation);

            // On success — record the build region so undo can clear it
            lastBuilds.put(player.getUniqueId(), new BuildRecord(
                    bukkitWorld.getName(),
                    worldMinX, worldMinY, worldMinZ,
                    worldMaxX, worldMaxY, worldMaxZ));

            // Clear the wireframe after a successful build (user requested behavior).
            plugin.getFrameRenderer().clearFrame(player);

            player.sendMessage(plugin.msg("building-completed"));
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Paste failed: " + e.getMessage());
            player.sendMessage(plugin.msg("building-failed", "error", e.getMessage()));
            return false;
        }
    }

    /**
     * Undo a player's last build — fills the recorded region with air.
     * Returns true on success, false if there is nothing to undo or error.
     */
    public boolean undoSchematicAtPlayer(Player player) {
        BuildRecord record = lastBuilds.get(player.getUniqueId());
        if (record == null) {
            player.sendMessage(plugin.msg("undo-no-record"));
            return false;
        }

        // Make sure the world where the build happened is still loaded
        World bukkitWorld = plugin.getServer().getWorld(record.worldName);
        if (bukkitWorld == null) {
            player.sendMessage(plugin.msg("undo-failed", "error", "world '" + record.worldName + "' not loaded"));
            return false;
        }

        com.sk89q.worldedit.world.World weWorld = new BukkitWorld(bukkitWorld);

        try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            editSession.setFastMode(true);

            // Set every block in the recorded region to air
            BlockVector3 weMin = BlockVector3.at(record.minX, record.minY, record.minZ);
            BlockVector3 weMax = BlockVector3.at(record.maxX, record.maxY, record.maxZ);

            int total = 0;
            for (int x = weMin.x(); x <= weMax.x(); x++) {
                for (int y = weMin.y(); y <= weMax.y(); y++) {
                    for (int z = weMin.z(); z <= weMax.z(); z++) {
                        editSession.setBlock(
                                BlockVector3.at(x, y, z),
                                com.sk89q.worldedit.world.block.BlockTypes.AIR.getDefaultState());
                        total++;
                    }
                }
            }

            // Clear the record — only one undo per build
            lastBuilds.remove(player.getUniqueId());

            plugin.getLogger().info("Player " + player.getName() + " undid a build — " + total + " blocks cleared in '" + record.worldName + "'.");
            player.sendMessage(plugin.msg("undo-success"));
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Undo failed: " + e.getMessage());
            player.sendMessage(plugin.msg("undo-failed", "error", e.getMessage()));
            return false;
        }
    }

    /** @return the total number of blocks that would be removed by undo (informational) */
    public int getUndoBlockCount(Player player) {
        BuildRecord r = lastBuilds.get(player.getUniqueId());
        return r == null ? 0 : r.countBlocks();
    }

    public int[] getSchematicSize(String name) {
        try {
            Clipboard clipboard = loadClipboard(name);
            BlockVector3 min = clipboard.getMinimumPoint();
            BlockVector3 max = clipboard.getMaximumPoint();
            return new int[]{
                    max.x() - min.x() + 1,
                    max.y() - min.y() + 1,
                    max.z() - min.z() + 1
            };
        } catch (Exception e) {
            return new int[]{0, 0, 0};
        }
    }
}

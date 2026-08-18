package com.aibuild.manager;

import com.aibuild.Aibuild;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
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
     * Stores each player's last build so it can be "undone" later.
     * Instead of only recording the bounds (and blanking them to air on undo),
     * we keep a snapshot Clipboard of the ORIGINAL blocks that occupied the
     * region before the paste. Undo re-pastes that snapshot, restoring terrain,
     * water, and any pre-existing structures exactly as they were.
     * Map key: player UUID.
     */
    private final Map<UUID, BuildRecord> lastBuilds = new HashMap<>();

    private static final class BuildRecord {
        final String worldName;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        /** Original blocks of the region, captured just before the paste. */
        final Clipboard snapshot;
        /** World position the snapshot's min-corner should be pasted back to. */
        final BlockVector3 pasteBack;
        BuildRecord(String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                    Clipboard snapshot, BlockVector3 pasteBack) {
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.snapshot = snapshot;
            this.pasteBack = pasteBack;
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

            // --- Snapshot the ORIGINAL blocks of the target region BEFORE pasting,
            //     so undo can restore the terrain exactly instead of blanking to air.
            BlockVector3 regionMinWorld = BlockVector3.at(worldMinX, worldMinY, worldMinZ);
            BlockVector3 regionMaxWorld = BlockVector3.at(worldMaxX, worldMaxY, worldMaxZ);
            Clipboard snapshot = captureRegion(weWorld, regionMinWorld, regionMaxWorld);

            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(targetOrigin)
                    .ignoreAirBlocks(false)
                    .build();

            Operations.complete(operation);

            // On success — record the original snapshot so undo can restore it.
            lastBuilds.put(player.getUniqueId(), new BuildRecord(
                    bukkitWorld.getName(),
                    worldMinX, worldMinY, worldMinZ,
                    worldMaxX, worldMaxY, worldMaxZ,
                    snapshot, regionMinWorld));

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
     * Undo a player's last build — restores the region to the ORIGINAL blocks
     * that were captured before the paste (terrain, water, existing structures).
     * Returns true on success, false if there is nothing to undo or on error.
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

        if (record.snapshot == null) {
            player.sendMessage(plugin.msg("undo-failed", "error", "no snapshot available"));
            return false;
        }

        com.sk89q.worldedit.world.World weWorld = new BukkitWorld(bukkitWorld);

        try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            editSession.setFastMode(true);

            // Paste the original snapshot back, including air, so the region is
            // restored exactly (a block that was air before becomes air again).
            Operation operation = new ClipboardHolder(record.snapshot)
                    .createPaste(editSession)
                    .to(record.pasteBack)
                    .ignoreAirBlocks(false)
                    .build();

            Operations.complete(operation);

            // Clear the record — only one undo per build
            lastBuilds.remove(player.getUniqueId());

            plugin.getLogger().info("Player " + player.getName() + " undid a build — restored "
                    + record.countBlocks() + " blocks in '" + record.worldName + "'.");
            player.sendMessage(plugin.msg("undo-success"));
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Undo failed: " + e.getMessage());
            player.sendMessage(plugin.msg("undo-failed", "error", e.getMessage()));
            return false;
        }
    }

    // =================================================================
    //  Region snapshot helper
    // =================================================================

    /**
     * Copy every block in the given world region into an in-memory Clipboard.
     * The clipboard's origin is set to the region's min corner so it can later
     * be pasted back to that same world position.
     */
    private Clipboard captureRegion(com.sk89q.worldedit.world.World weWorld,
                                    BlockVector3 min, BlockVector3 max) throws Exception {
        Region region = new CuboidRegion(weWorld, min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(min);

        try (EditSession source = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {
            source.setFastMode(true);
            ForwardExtentCopy copy = new ForwardExtentCopy(source, region, clipboard, min);
            copy.setCopyingEntities(false);
            copy.setCopyingBiomes(false);
            Operations.complete(copy);
        }
        return clipboard;
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

    // =================================================================
    //  Preview system — show where a schematic will be placed before building
    // =================================================================

    /**
     * Stores a player's pending preview so they can confirm or cancel.
     */
    private static final class PreviewInfo {
        final String schematicName;
        final Clipboard clipboard;
        final BlockVector3 targetOrigin;
        final BlockVector3 worldMin;
        final BlockVector3 worldMax;

        PreviewInfo(String schematicName, Clipboard clipboard,
                    BlockVector3 targetOrigin,
                    BlockVector3 worldMin, BlockVector3 worldMax) {
            this.schematicName = schematicName;
            this.clipboard = clipboard;
            this.targetOrigin = targetOrigin;
            this.worldMin = worldMin;
            this.worldMax = worldMax;
        }
    }

    private final Map<UUID, PreviewInfo> pendingPreviews = new HashMap<>();

    /**
     * Compute where a schematic would be placed for the given player,
     * based on their current selection (if any) or their feet position.
     *
     * @return a BlockVector3 array of [worldMin, worldMax], or null on error
     */
    public BlockVector3[] computeTargetBounds(Player player, String schematicName) {
        if (!hasSchematic(schematicName)) return null;

        Clipboard clipboard;
        try {
            clipboard = loadClipboard(schematicName);
        } catch (Exception e) {
            return null;
        }

        BlockVector3 min = clipboard.getMinimumPoint();
        BlockVector3 max = clipboard.getMaximumPoint();
        BlockVector3 origin = clipboard.getOrigin();

        int width = max.x() - min.x() + 1;
        int height = max.y() - min.y() + 1;
        int length = max.z() - min.z() + 1;

        BlockVector3 targetOrigin;
        if (plugin.getSelectionManager().hasCompleteSelection(player)) {
            BlockVector3 regionMin = plugin.getSelectionManager().getRegionMin(player);
            targetOrigin = regionMin.add(origin).subtract(min);
        } else {
            BlockVector3 localCenter = BlockVector3.at(min.x() + width / 2, min.y(), min.z() + length / 2);
            BlockVector3 playerPos = BukkitAdapter.asBlockVector(player.getLocation());
            targetOrigin = playerPos.subtract(localCenter).add(origin);
        }

        int worldMinX = Math.min(min.x(), max.x()) + (targetOrigin.x() - origin.x());
        int worldMaxX = Math.max(min.x(), max.x()) + (targetOrigin.x() - origin.x());
        int worldMinY = Math.min(min.y(), max.y()) + (targetOrigin.y() - origin.y());
        int worldMaxY = Math.max(min.y(), max.y()) + (targetOrigin.y() - origin.y());
        int worldMinZ = Math.min(min.z(), max.z()) + (targetOrigin.z() - origin.z());
        int worldMaxZ = Math.max(min.z(), max.z()) + (targetOrigin.z() - origin.z());

        return new BlockVector3[]{
                BlockVector3.at(worldMinX, worldMinY, worldMinZ),
                BlockVector3.at(worldMaxX, worldMaxY, worldMaxZ)
        };
    }

    /**
     * Show a preview wireframe for where the schematic would be placed.
     * Stores the preview info so it can be confirmed or cancelled later.
     *
     * @return true if preview was shown, false on error
     */
    public boolean showPreview(Player player, String schematicName) {
        if (!hasSchematic(schematicName)) {
            player.sendMessage(plugin.msg("schematic-not-found", "name", schematicName));
            return false;
        }

        Clipboard clipboard;
        try {
            clipboard = loadClipboard(schematicName);
        } catch (Exception e) {
            player.sendMessage(plugin.msg("building-failed", "error", plugin.msgRaw("building-failed-load")));
            return false;
        }

        BlockVector3 min = clipboard.getMinimumPoint();
        BlockVector3 max = clipboard.getMaximumPoint();
        BlockVector3 origin = clipboard.getOrigin();

        int width = max.x() - min.x() + 1;
        int height = max.y() - min.y() + 1;
        int length = max.z() - min.z() + 1;

        BlockVector3 targetOrigin;
        String modeMsg;
        if (plugin.getSelectionManager().hasCompleteSelection(player)) {
            BlockVector3 regionMin = plugin.getSelectionManager().getRegionMin(player);
            targetOrigin = regionMin.add(origin).subtract(min);
            modeMsg = plugin.msgRaw("building-mode-selection");
        } else {
            BlockVector3 localCenter = BlockVector3.at(min.x() + width / 2, min.y(), min.z() + length / 2);
            BlockVector3 playerPos = BukkitAdapter.asBlockVector(player.getLocation());
            targetOrigin = playerPos.subtract(localCenter).add(origin);
            modeMsg = plugin.msgRaw("building-mode-feet");
        }

        int worldMinX = Math.min(min.x(), max.x()) + (targetOrigin.x() - origin.x());
        int worldMaxX = Math.max(min.x(), max.x()) + (targetOrigin.x() - origin.x());
        int worldMinY = Math.min(min.y(), max.y()) + (targetOrigin.y() - origin.y());
        int worldMaxY = Math.max(min.y(), max.y()) + (targetOrigin.y() - origin.y());
        int worldMinZ = Math.min(min.z(), max.z()) + (targetOrigin.z() - origin.z());
        int worldMaxZ = Math.max(min.z(), max.z()) + (targetOrigin.z() - origin.z());

        BlockVector3 worldMin = BlockVector3.at(worldMinX, worldMinY, worldMinZ);
        BlockVector3 worldMax = BlockVector3.at(worldMaxX, worldMaxY, worldMaxZ);

        // Store pending preview info
        pendingPreviews.put(player.getUniqueId(), new PreviewInfo(
                schematicName, clipboard, targetOrigin, worldMin, worldMax));

        // Show preview wireframe
        plugin.getFrameRenderer().showPreviewFrame(player, worldMin, worldMax);

        // Send info messages
        player.sendMessage(plugin.msg("preview-showing", "name", schematicName));
        player.sendMessage(modeMsg);
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
        player.sendMessage(plugin.msg("preview-confirm-hint"));

        return true;
    }

    /**
     * Confirm the pending preview and actually build the schematic.
     *
     * @return true if build succeeded, false if nothing to confirm or error
     */
    public boolean confirmBuild(Player player) {
        PreviewInfo info = pendingPreviews.get(player.getUniqueId());
        if (info == null) {
            player.sendMessage(plugin.msg("preview-no-preview"));
            return false;
        }

        // Execute paste directly using stored clipboard and target
        World bukkitWorld = player.getLocation().getWorld();
        com.sk89q.worldedit.world.World weWorld = new BukkitWorld(bukkitWorld);

        try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            editSession.setFastMode(true);

            // Snapshot original blocks before pasting
            BlockVector3 regionMinWorld = info.worldMin;
            BlockVector3 regionMaxWorld = info.worldMax;
            Clipboard snapshot = captureRegion(weWorld, regionMinWorld, regionMaxWorld);

            Operation operation = new ClipboardHolder(info.clipboard)
                    .createPaste(editSession)
                    .to(info.targetOrigin)
                    .ignoreAirBlocks(false)
                    .build();

            Operations.complete(operation);

            // Record build for undo
            lastBuilds.put(player.getUniqueId(), new BuildRecord(
                    bukkitWorld.getName(),
                    info.worldMin.x(), info.worldMin.y(), info.worldMin.z(),
                    info.worldMax.x(), info.worldMax.y(), info.worldMax.z(),
                    snapshot, regionMinWorld));

            // Clear both frames
            plugin.getFrameRenderer().clearAllFrames(player);

            // Clear pending preview
            pendingPreviews.remove(player.getUniqueId());

            player.sendMessage(plugin.msg("building-completed"));
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Paste failed: " + e.getMessage());
            player.sendMessage(plugin.msg("building-failed", "error", e.getMessage()));
            return false;
        }
    }

    /** Cancel the pending preview (clear preview frame and stored info). */
    public void cancelPreview(Player player) {
        pendingPreviews.remove(player.getUniqueId());
        plugin.getFrameRenderer().clearPreviewFrame(player);
    }

    /** @return the schematic name being previewed, or null if none */
    public String getPreviewName(Player player) {
        PreviewInfo info = pendingPreviews.get(player.getUniqueId());
        return info == null ? null : info.schematicName;
    }

    /** @return true if the player has a pending preview */
    public boolean hasPreview(Player player) {
        return pendingPreviews.containsKey(player.getUniqueId());
    }
}

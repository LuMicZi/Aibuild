package com.aibuild.manager;

import com.aibuild.Aibuild;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player two-point region selection, driven by an in-game "selection tool"
 * (a blaze rod marked with a PersistentDataContainer tag).
 *
 * Left-click  -> set pos1
 * Right-click -> set pos2
 *
 * Both points must be in the same world; switching worlds starts a fresh selection.
 */
public class SelectionManager implements Listener {

    private final Aibuild plugin;
    private final NamespacedKey toolKey;

    private final Map<UUID, Selection> selections = new HashMap<>();

    private static final class Selection {
        String worldName;
        BlockVector3 pos1;
        BlockVector3 pos2;
    }

    public SelectionManager(Aibuild plugin) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "selection-tool");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // =================================================================
    //  Tool item
    // =================================================================

    public ItemStack createTool() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + plugin.msgRaw("tool-name"));
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.msgRaw("tool-lore-1"));
            lore.add(ChatColor.GRAY + plugin.msgRaw("tool-lore-2"));
            lore.add("");
            lore.add(ChatColor.AQUA + plugin.msgRaw("tool-lore-hint"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isTool(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    /** Give the selection tool to the player, dropping it on the ground if the inventory is full. */
    public void giveTool(Player player) {
        ItemStack tool = createTool();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(tool);
        if (!leftover.isEmpty()) {
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
    }

    // =================================================================
    //  Selection state
    // =================================================================

    public void setPos1(Player player, Block block) {
        Selection sel = selections.get(player.getUniqueId());
        String worldName = block.getWorld().getName();
        if (sel == null || sel.worldName == null || !sel.worldName.equals(worldName)) {
            sel = new Selection();
            sel.worldName = worldName;
            selections.put(player.getUniqueId(), sel);
        }
        sel.pos1 = BlockVector3.at(block.getX(), block.getY(), block.getZ());
        player.sendMessage(plugin.msg("sel-pos1-set",
                "x", String.valueOf(block.getX()),
                "y", String.valueOf(block.getY()),
                "z", String.valueOf(block.getZ()),
                "world", worldName));
        // Cancel any pending preview since selection changed
        plugin.getSchematicManager().cancelPreview(player);
        if (sel.pos2 != null) {
            sendRegionSize(player, sel);
            plugin.getFrameRenderer().showFrame(player, sel.pos1, sel.pos2);
        } else {
            plugin.getFrameRenderer().clearFrame(player);
        }
    }

    public void setPos2(Player player, Block block) {
        Selection sel = selections.get(player.getUniqueId());
        String worldName = block.getWorld().getName();
        if (sel == null || sel.worldName == null) {
            sel = new Selection();
            sel.worldName = worldName;
            selections.put(player.getUniqueId(), sel);
        } else if (!sel.worldName.equals(worldName)) {
            player.sendMessage(plugin.msg("sel-world-mismatch"));
            return;
        }
        sel.pos2 = BlockVector3.at(block.getX(), block.getY(), block.getZ());
        player.sendMessage(plugin.msg("sel-pos2-set",
                "x", String.valueOf(block.getX()),
                "y", String.valueOf(block.getY()),
                "z", String.valueOf(block.getZ()),
                "world", worldName));
        // Cancel any pending preview since selection changed
        plugin.getSchematicManager().cancelPreview(player);
        if (sel.pos1 != null) {
            sendRegionSize(player, sel);
            plugin.getFrameRenderer().showFrame(player, sel.pos1, sel.pos2);
        } else {
            plugin.getFrameRenderer().clearFrame(player);
        }
    }

    private void sendRegionSize(Player player, Selection sel) {
        int w = Math.abs(sel.pos1.x() - sel.pos2.x()) + 1;
        int h = Math.abs(sel.pos1.y() - sel.pos2.y()) + 1;
        int l = Math.abs(sel.pos1.z() - sel.pos2.z()) + 1;
        player.sendMessage(plugin.msg("sel-region-size",
                "w", String.valueOf(w),
                "h", String.valueOf(h),
                "l", String.valueOf(l)));
    }

    /** @return the player's selection, or null if none. pos1/pos2 may be individually null. */
    public Selection getSelection(Player player) {
        return selections.get(player.getUniqueId());
    }

    /** @return true if the player has both points set AND in their current world. */
    public boolean hasCompleteSelection(Player player) {
        Selection sel = selections.get(player.getUniqueId());
        if (sel == null || sel.pos1 == null || sel.pos2 == null || sel.worldName == null) return false;
        return sel.worldName.equals(player.getWorld().getName());
    }

    /**
     * Region min corner (component-wise min of pos1/pos2) in world coordinates.
     * Caller must ensure {@link #hasCompleteSelection(Player)} is true.
     */
    public BlockVector3 getRegionMin(Player player) {
        Selection sel = selections.get(player.getUniqueId());
        return BlockVector3.at(
                Math.min(sel.pos1.x(), sel.pos2.x()),
                Math.min(sel.pos1.y(), sel.pos2.y()),
                Math.min(sel.pos1.z(), sel.pos2.z()));
    }

    public void clearSelection(Player player) {
        selections.remove(player.getUniqueId());
        plugin.getSchematicManager().cancelPreview(player);
        plugin.getFrameRenderer().clearFrame(player);
    }

    // =================================================================
    //  Tool interaction
    // =================================================================

    @EventHandler
    public void onToolUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isTool(item)) return;

        if (!player.hasPermission("aibuild.tool")) {
            player.sendMessage(plugin.msg("tool-no-perm"));
            event.setCancelled(true);
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            setPos1(player, clicked);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            setPos2(player, clicked);
        }
    }
}

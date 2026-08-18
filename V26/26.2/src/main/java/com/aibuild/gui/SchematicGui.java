package com.aibuild.gui;

import com.aibuild.Aibuild;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Schematic browser GUI — with preview-before-build flow.
 *
 * Layout (6 rows × 9 = 54 slots):
 *   Row 1 (0-8):   Top decorative border (orange glass), slot 4 = banner
 *   Rows 2-5 (9-44): Content grid — 36 schematic slots per page
 *   Row 6 (45-53): Control panel (cyan glass) with prev/undo/confirm/info/next
 *
 * Flow:
 *   1. Click a schematic → shows a green particle preview wireframe at the
 *      target placement position (selection corner or player feet).
 *   2. Click the same schematic again → confirms and builds immediately.
 *   3. Click a different schematic → switches preview to the new one.
 *   4. Click the confirm button → confirms and builds the current preview.
 *   5. Navigate / undo → cancels the current preview.
 */
public class SchematicGui implements Listener {

    private static final int PAGE_SIZE = 36;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 44;

    private static final String GUI_TOKEN = "[Aibuild]";

    private static final int SLOT_BANNER = 4;
    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_UNDO = 47;
    private static final int SLOT_CONFIRM = 49;  // Confirm build (was info slot)
    private static final int SLOT_INFO = 51;     // Info moved right
    private static final int SLOT_NEXT_PAGE = 53;

    private static final Material TOP_BORDER = Material.ORANGE_STAINED_GLASS_PANE;
    private static final Material CONTENT_FILL = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
    private static final Material BOTTOM_BORDER = Material.CYAN_STAINED_GLASS_PANE;

    private final Aibuild plugin;

    public SchematicGui(Aibuild plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openGui(Player player) {
        openGui(player, 1);
    }

    public void openGui(Player player, int page) {
        List<String> schematics = plugin.getSchematicManager().getSchematicNames();
        Collections.sort(schematics);

        int totalPages = Math.max(1, (int) Math.ceil((double) schematics.size() / PAGE_SIZE));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        String pageTitle = plugin.msgRaw("gui-title",
                "page", String.valueOf(page),
                "total", String.valueOf(totalPages))
                + " " + GUI_TOKEN;

        Inventory inv = Bukkit.createInventory(null, 54, pageTitle);

        fillTopBorder(inv);
        fillContentArea(inv, schematics, page, player);
        fillBottomBorder(inv, player, schematics.size(), page, totalPages);

        player.openInventory(inv);
    }

    // =================================================================
    //  Layout construction
    // =================================================================

    private void fillTopBorder(Inventory inv) {
        for (int i = 0; i < 9; i++) {
            if (i == SLOT_BANNER) {
                inv.setItem(i, createBannerItem());
            } else {
                inv.setItem(i, createPane(TOP_BORDER, " "));
            }
        }
    }

    private void fillContentArea(Inventory inv, List<String> schematics, int page, Player player) {
        String previewName = plugin.getSchematicManager().getPreviewName(player);

        int startIndex = (page - 1) * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, schematics.size());
        for (int i = startIndex; i < endIndex; i++) {
            String name = schematics.get(i);
            boolean isPreview = name.equals(previewName);
            inv.setItem(CONTENT_START + (i - startIndex), createSchematicItem(name, isPreview));
        }
        for (int slot = CONTENT_START; slot <= CONTENT_END; slot++) {
            if (inv.getItem(slot) == null || inv.getItem(slot).getType() == Material.AIR) {
                inv.setItem(slot, createPane(CONTENT_FILL, " "));
            }
        }
    }

    private void fillBottomBorder(Inventory inv, Player player, int count, int page, int totalPages) {
        for (int slot = 45; slot <= 53; slot++) {
            inv.setItem(slot, createPane(BOTTOM_BORDER, " "));
        }
        if (page > 1) {
            inv.setItem(SLOT_PREV_PAGE, createNavButton(Material.ARROW, false,
                    plugin.msgRaw("gui-prev-page", "page", String.valueOf(page - 1), "total", "")));
        }
        inv.setItem(SLOT_UNDO, createUndoItem(player));

        // Confirm button — only visible when there's an active preview
        boolean hasPreview = plugin.getSchematicManager().hasPreview(player);
        if (hasPreview) {
            inv.setItem(SLOT_CONFIRM, createConfirmItem(player));
        }

        inv.setItem(SLOT_INFO, createInfoItem(count, page, totalPages));

        if (page < totalPages) {
            inv.setItem(SLOT_NEXT_PAGE, createNavButton(Material.ARROW, true,
                    plugin.msgRaw("gui-next-page", "page", String.valueOf(page + 1), "total", "")));
        }
    }

    // =================================================================
    //  Item factories
    // =================================================================

    private ItemStack createPane(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createBannerItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "AiBuild");
            List<String> lore = new ArrayList<>();
            lore.add(separator());
            lore.add(ChatColor.YELLOW + plugin.msgRaw("gui-info-lore-author"));
            lore.add(separator());
            meta.setLore(lore);
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSchematicItem(String name, boolean isPreview) {
        // Use a different material when this schematic is being previewed
        Material mat = isPreview ? Material.LIME_STAINED_GLASS_PANE : Material.ENCHANTED_BOOK;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + name);
            List<String> lore = new ArrayList<>();

            int[] size = plugin.getSchematicManager().getSchematicSize(name);
            if (size[0] > 0 || size[1] > 0 || size[2] > 0) {
                lore.add(ChatColor.AQUA + plugin.msgRaw("gui-item-lore-size",
                        "w", String.valueOf(size[0]),
                        "h", String.valueOf(size[1]),
                        "l", String.valueOf(size[2])));
            }
            lore.add(separator());

            if (isPreview) {
                lore.add(ChatColor.GREEN + "✓ " + plugin.msgRaw("gui-item-lore-previewing"));
                lore.add(ChatColor.YELLOW + plugin.msgRaw("gui-item-lore-click-confirm"));
            } else {
                lore.add(ChatColor.GREEN + plugin.msgRaw("gui-item-lore-confirm"));
                lore.add(ChatColor.GRAY + plugin.msgRaw("gui-item-lore-click-preview"));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createNavButton(Material mat, boolean next, String label) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String arrow = next ? "» " : "« ";
            meta.setDisplayName(ChatColor.YELLOW + arrow + ChatColor.stripColor(label));
            List<String> lore = new ArrayList<>();
            lore.add(separator());
            lore.add(ChatColor.GRAY + plugin.msgRaw("gui-item-lore-click"));
            meta.setLore(lore);
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createUndoItem(Player player) {
        int blocks = plugin.getSchematicManager().getUndoBlockCount(player);
        boolean hasRecord = blocks > 0;

        Material mat = hasRecord ? Material.RED_TERRACOTTA : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (hasRecord) {
                meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "✕ " + plugin.msgRaw("gui-undo-title"));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.msgRaw("gui-undo-lore1"));
                lore.add(ChatColor.GRAY + plugin.msgRaw("gui-undo-lore2"));
                lore.add(separator());
                lore.add(ChatColor.RED + "Blocks: " + blocks);
                meta.setLore(lore);
                meta.addEnchant(Enchantment.LURE, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } else {
                meta.setDisplayName(ChatColor.DARK_GRAY + plugin.msgRaw("gui-undo-title"));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.DARK_GRAY + plugin.msgRaw("undo-no-record"));
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createConfirmItem(Player player) {
        String previewName = plugin.getSchematicManager().getPreviewName(player);
        ItemStack item = new ItemStack(Material.GREEN_TERRACOTTA);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "✓ " + plugin.msgRaw("gui-confirm-title"));
            List<String> lore = new ArrayList<>();
            if (previewName != null) {
                lore.add(ChatColor.YELLOW + "Template: " + previewName);
            }
            lore.add(separator());
            lore.add(ChatColor.GRAY + plugin.msgRaw("gui-confirm-lore1"));
            lore.add(ChatColor.GRAY + plugin.msgRaw("gui-confirm-lore2"));
            lore.add(separator());
            lore.add(ChatColor.GREEN + plugin.msgRaw("gui-item-lore-click-confirm"));
            meta.setLore(lore);
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoItem(int count, int page, int totalPages) {
        ItemStack item = new ItemStack(Material.BOOKSHELF);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "ⓘ " + plugin.msgRaw("gui-info-title"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.msgRaw("gui-info-lore-total", "count", String.valueOf(count)));
            lore.add(ChatColor.GRAY + plugin.msgRaw("gui-info-lore-page",
                    "page", String.valueOf(page),
                    "total", String.valueOf(totalPages)));
            lore.add(separator());
            lore.add(ChatColor.GRAY + plugin.msgRaw("gui-info-lore-author"));
            lore.add(separator());
            lore.add(ChatColor.GREEN + plugin.msgRaw("gui-info-lore-hint"));
            lore.add(ChatColor.YELLOW + plugin.msgRaw("gui-info-lore-hint2"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Decorative separator line for lore. */
    private String separator() {
        return ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "───────────────────";
    }

    // =================================================================
    //  Click handling
    // =================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        String title = event.getView().getTitle();
        if (!title.contains(GUI_TOKEN)) return;

        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        Material type = clicked.getType();
        ItemMeta meta = clicked.getItemMeta();
        int slot = event.getSlot();

        // Border / filler panes — ignore
        if (isPane(type)) return;

        // Banner (top center) — ignore
        if (type == Material.NETHER_STAR) return;

        // --- Confirm button (green terracotta at SLOT_CONFIRM) ---
        if (type == Material.GREEN_TERRACOTTA && slot == SLOT_CONFIRM) {
            player.closeInventory();
            if (!player.hasPermission("aibuild.build")) {
                player.sendMessage(plugin.msg("gui-build-no-perm"));
                return;
            }
            plugin.getSchematicManager().confirmBuild(player);
            return;
        }

        // --- Undo button ---
        if (type == Material.RED_TERRACOTTA && slot == SLOT_UNDO) {
            plugin.getSchematicManager().cancelPreview(player);
            player.closeInventory();
            if (!player.hasPermission("aibuild.undo")) {
                player.sendMessage(plugin.msg("undo-no-perm"));
                return;
            }
            plugin.getSchematicManager().undoSchematicAtPlayer(player);
            return;
        }

        // --- Navigation arrow (cancels preview, changes page) ---
        if (type == Material.ARROW && meta != null && meta.getDisplayName() != null) {
            plugin.getSchematicManager().cancelPreview(player);
            String name = ChatColor.stripColor(meta.getDisplayName());
            StringBuilder sb = new StringBuilder();
            for (char c : name.toCharArray()) {
                if (Character.isDigit(c)) sb.append(c);
            }
            if (sb.length() > 0) {
                try {
                    int page = Integer.parseInt(sb.toString());
                    openGui(player, page);
                } catch (NumberFormatException ignored) {}
            }
            return;
        }

        // --- Info bookshelf — show hint, don't cancel preview ---
        if (type == Material.BOOKSHELF) return;

        // --- Schematic item ---
        if (meta != null && meta.getDisplayName() != null) {
            String displayName = ChatColor.stripColor(meta.getDisplayName()).trim();
            if (displayName.isEmpty()) return;

            // Green stained glass pane = currently previewed schematic → confirm build
            if (type == Material.LIME_STAINED_GLASS_PANE) {
                player.closeInventory();
                if (!player.hasPermission("aibuild.build")) {
                    player.sendMessage(plugin.msg("gui-build-no-perm"));
                    return;
                }
                plugin.getSchematicManager().confirmBuild(player);
                return;
            }

            // Enchanted book = new schematic to preview
            if (type == Material.ENCHANTED_BOOK) {
                if (!player.hasPermission("aibuild.build")) {
                    player.sendMessage(plugin.msg("gui-build-no-perm"));
                    return;
                }
                // Show preview (green particle wireframe), keep GUI open
                plugin.getSchematicManager().showPreview(player, displayName);
                // Re-open GUI to update the item states (previewed item → green pane)
                openGui(player, getCurrentPage(title));
                return;
            }
        }
    }

    /** Extract the current page number from the GUI title. */
    private int getCurrentPage(String title) {
        StringBuilder sb = new StringBuilder();
        for (char c : title.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
        }
        if (sb.length() > 0) {
            try {
                return Integer.parseInt(sb.toString());
            } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    private boolean isPane(Material type) {
        return type.name().endsWith("_STAINED_GLASS_PANE") || type == Material.GLASS_PANE;
    }
}

package com.aibuild;

import com.aibuild.command.AibuildCommand;
import com.aibuild.gui.SchematicGui;
import com.aibuild.manager.FrameRenderer;
import com.aibuild.manager.SchematicManager;
import com.aibuild.manager.SelectionManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Aibuild extends JavaPlugin {

    private static Aibuild instance;

    private SchematicManager schematicManager;
    private SchematicGui schematicGui;
    private SelectionManager selectionManager;
    private FrameRenderer frameRenderer;

    /** Current language code (lowercase). Values: "en" or "zh_cn" */
    private String language = "en";

    /** In-memory map of translation keys -> localized strings */
    private Map<String, String> messages = new HashMap<>();

    /** Directory where language files live on disk, e.g. plugins/Aibuild/languages/ */
    private File languageDir;

    @Override
    public void onEnable() {
        instance = this;

        // --- Step 1: Ensure plugin data folder exists ---
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
            getLogger().info("Created plugin data folder: " + dataFolder.getPath());
        }

        // --- Step 2: Ensure config.yml exists ---
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            getLogger().info("config.yml not found, saving default...");
            try {
                saveDefaultConfig();
            } catch (Exception ignored) {}
            if (!configFile.exists()) {
                // Fallback: write a minimal config if the JAR resource was unavailable
                try {
                    FileConfiguration cfg = getConfig();
                    cfg.set("language", "en");
                    cfg.set("schematic-folder", "schematics");
                    saveConfig();
                    getLogger().info("Wrote minimal config.yml (no default found in JAR)");
                } catch (Exception e) {
                    getLogger().warning("Failed to write minimal config: " + e.getMessage());
                }
            }
        } else {
            getLogger().info("Using existing config.yml");
        }

        // --- Step 2b: Ensure any keys added in newer versions are present ---
        // (preserves user values for keys that already exist; only adds missing ones)
        ensureConfigDefaults();

        // --- Step 3: Prepare language directory ---
        languageDir = new File(dataFolder, "languages");
        if (!languageDir.exists()) {
            languageDir.mkdirs();
            getLogger().info("Created language directory: " + languageDir.getPath());
        }
        // Copy default language files from JAR to disk (only if not present)
        saveLanguageFileIfMissing("messages_en.yml");
        saveLanguageFileIfMissing("messages_zh_CN.yml");

        // --- Step 4: Load language + messages from disk ---
        reloadLanguageInternal();

        // --- Step 5: Create schematics folder ---
        File schemFolder = getSchematicFolder();
        if (!schemFolder.exists()) {
            schemFolder.mkdirs();
            getLogger().info("Created schematics folder: " + schemFolder.getPath());
        } else {
            getLogger().info("Schematics folder: " + schemFolder.getPath());
        }

        // --- Step 6: Initialize modules ---
        schematicManager = new SchematicManager(this);
        schematicGui = new SchematicGui(this);
        selectionManager = new SelectionManager(this);
        frameRenderer = new FrameRenderer(this);

        // --- Step 7: Register commands ---
        try {
            Objects.requireNonNull(getCommand("aibuild"), "command 'aibuild' missing in plugin.yml")
                    .setExecutor(new AibuildCommand(this));
            getLogger().info("Command '/aibuild' registered");
        } catch (Exception e) {
            getLogger().severe("Failed to register command: " + e.getMessage());
        }

        // --- Step 8: Startup summary ---
        int count = schematicManager.getSchematicNames().size();
        getLogger().info("========================================");
        getLogger().info("Aibuild v" + getDescription().getVersion() + " is ENABLED");
        getLogger().info("Author : LuMickZi");
        getLogger().info("Language: " + language + "  (edit plugins/Aibuild/languages/)");
        getLogger().info("Templates loaded: " + count);
        getLogger().info("Commands: /aibuild help   (alias: /ab)");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        if (frameRenderer != null) {
            frameRenderer.clearAll();
        }
        getLogger().info("Aibuild disabled");
    }

    // =================================================================
    //  Public API (commands call these)
    // =================================================================

    /** Reload config.yml and language from disk. */
    public void reloadPlugin() {
        reloadConfig();
        ensureConfigDefaults();
        reloadLanguageInternal();
        getLogger().info("Reloaded. Current language: " + language);
    }

    /**
     * Switch language at runtime. Persists the choice to config.yml so it survives restart.
     *
     * @param lang "en" or "zh_cn" (case-insensitive).
     * @return true on success, false if the value is invalid.
     */
    public boolean setLanguage(String lang) {
        if (lang == null) return false;
        String normalized = lang.trim().toLowerCase();
        if (!normalized.equals("en") && !normalized.equals("zh_cn")) return false;

        FileConfiguration cfg = getConfig();
        cfg.set("language", normalized);
        try { saveConfig(); } catch (Exception ignored) {}

        language = normalized;
        loadMessagesInternal();
        return true;
    }

    public String getLanguage() { return language; }

    /** Get translated message WITH plugin prefix, replacing placeholders. */
    public String msg(String key, String... placeholders) {
        return getPrefix() + msgRaw(key, placeholders);
    }

    /** Get translated message WITHOUT prefix (for GUI lore / multi-line help). */
    public String msgRaw(String key, String... placeholders) {
        String value = messages.get(key);
        if (value == null) {
            // Missing key — show placeholder so translators can spot it
            value = "[" + key + "]";
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String ph = "%" + placeholders[i] + "%";
            String rep = placeholders[i + 1] == null ? "" : placeholders[i + 1];
            value = value.replace(ph, rep);
        }
        return value;
    }

    public String getPrefix() {
        String p = messages.get("prefix");
        return p == null ? "[Aibuild] " : p;
    }

    public File getSchematicFolder() {
        String folderName = getConfig().getString("schematic-folder", "schematics");
        File folder = new File(getDataFolder(), folderName);
        if (!folder.exists()) folder.mkdirs();
        return folder;
    }

    /**
     * Generic config migration: compare the bundled config.yml (inside the
     * jar) against the one on disk, and write back any missing keys with
     * their default values. User-set values are never overwritten.
     *
     * To add a new config key in the future, just edit
     * src/main/resources/config.yml — no Java changes needed here.
     */
    private void ensureConfigDefaults() {
        InputStream bundled = getResource("config.yml");
        if (bundled == null) {
            getLogger().warning("Bundled config.yml not found in jar; skipping config migration.");
            return;
        }

        YamlConfiguration defaults;
        try (Reader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            getLogger().warning("Failed to read bundled config.yml: " + e.getMessage());
            return;
        }

        FileConfiguration cfg = getConfig();
        boolean changed = false;

        for (String key : defaults.getKeys(true)) {
            // Skip section nodes — only copy leaf values.
            if (defaults.isConfigurationSection(key)) continue;
            if (!cfg.contains(key)) {
                Object value = defaults.get(key);
                cfg.set(key, value);
                changed = true;
                getLogger().info("Added missing config key: " + key + " = " + value);
            }
        }

        if (changed) {
            try {
                saveConfig();
                getLogger().info("config.yml updated with new default keys.");
            } catch (Exception e) {
                getLogger().warning("Failed to save merged config: " + e.getMessage());
            }
        }
    }

    // =================================================================
    //  Language file loading internals
    // =================================================================

    private void reloadLanguageInternal() {
        // Pick language from config.yml, defaulting to "en"
        try {
            FileConfiguration cfg = getConfig();
            String lang = cfg.getString("language", "en");
            if (lang == null) lang = "en";
            language = lang.trim().toLowerCase();
            if (!language.equals("en") && !language.equals("zh_cn")) {
                getLogger().warning("Unknown language '" + language + "' in config.yml, falling back to 'en'");
                language = "en";
            }
        } catch (Exception e) {
            getLogger().warning("Could not read language from config.yml, defaulting to 'en'");
            language = "en";
        }
        loadMessagesInternal();
    }

    /**
     * Load current language into memory. Strategy:
     * 1. Try plugins/Aibuild/languages/messages_<lang>.yml  (user-editable file on disk)
     * 2. If not found, fall back to the resource baked into the JAR
     * 3. If that also fails, fall back to messages_en.yml (the reference language)
     */
    private void loadMessagesInternal() {
        String fileName = "messages_" + language + ".yml";

        // 1. Try external file on disk first (user can edit)
        Map<String, String> loaded = loadMessagesFromDisk(new File(languageDir, fileName));
        if (loaded != null) {
            messages = loaded;
            getLogger().info("Loaded " + messages.size() + " message keys from languages/" + fileName);
            return;
        }

        // 2. Fall back to JAR resource
        loaded = loadMessagesFromResource(fileName);
        if (loaded != null) {
            messages = loaded;
            getLogger().info("Loaded " + messages.size() + " message keys from JAR: " + fileName);
            return;
        }

        // 3. Last resort: English from JAR
        if (!language.equals("en")) {
            loaded = loadMessagesFromResource("messages_en.yml");
            if (loaded != null) {
                messages = loaded;
                getLogger().info("Fell back to English (messages_en.yml from JAR)");
                return;
            }
        }

        getLogger().severe("Could not load ANY language file! Plugin messages will be raw keys.");
        messages = new HashMap<>();
    }

    /** Load a YAML file from disk using UTF-8. Returns null on failure. */
    private Map<String, String> loadMessagesFromDisk(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            YamlConfiguration yml = new YamlConfiguration();
            yml.load(reader);
            Map<String, String> result = new HashMap<>();
            for (String key : yml.getKeys(true)) {
                Object v = yml.get(key);
                if (v instanceof String) result.put(key, (String) v);
            }
            return result;
        } catch (Exception e) {
            getLogger().severe("Failed to parse " + file.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /** Load a YAML file from JAR resources using UTF-8. Returns null on failure. */
    private Map<String, String> loadMessagesFromResource(String resourceName) {
        try {
            InputStream in = getResource(resourceName);
            if (in == null) return null;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration yml = new YamlConfiguration();
                yml.load(reader);
                Map<String, String> result = new HashMap<>();
                for (String key : yml.getKeys(true)) {
                    Object v = yml.get(key);
                    if (v instanceof String) result.put(key, (String) v);
                }
                return result;
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load resource " + resourceName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * On first run, copy default language files from JAR to plugins/Aibuild/languages/.
     * Does nothing if the file already exists on disk (so user edits are preserved).
     */
    private void saveLanguageFileIfMissing(String fileName) {
        File target = new File(languageDir, fileName);
        if (target.exists()) {
            getLogger().info("Language file already exists: languages/" + fileName);
            return;
        }
        try (InputStream in = getResource(fileName)) {
            if (in == null) {
                getLogger().warning("Resource not found in JAR: " + fileName + " — will be skipped");
                return;
            }
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            getLogger().info("Saved default language file: languages/" + fileName);
        } catch (Exception e) {
            getLogger().severe("Failed to save " + fileName + " to disk: " + e.getMessage());
        }
    }

    // =================================================================
    //  Module accessors
    // =================================================================

    public static Aibuild getInstance() { return instance; }
    public SchematicManager getSchematicManager() { return schematicManager; }
    public SchematicGui getSchematicGui() { return schematicGui; }
    public SelectionManager getSelectionManager() { return selectionManager; }
    public FrameRenderer getFrameRenderer() { return frameRenderer; }
}

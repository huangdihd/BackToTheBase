package huangdihd.xinbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.TabExecutor;
import xin.bbtt.mcbot.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class BackToTheBase implements Plugin {
    private final Logger logger = LoggerFactory.getLogger(BackToTheBase.class.getSimpleName());
    public static BackToTheBase INSTANCE;
    public PlayerBaseConfig.BaseConfig baseConfig = new PlayerBaseConfig.BaseConfig();
    public static final String config_name = "base_config.json";
    public static final String COMMAND_NAME = "backtothebase";
    public static final String COMMAND_ALIAS = "bttb";
    public static final String GAME_COMMAND_PREFIX = "@" + COMMAND_NAME;
    public static final String GAME_COMMAND_ALIAS_PREFIX = "@" + COMMAND_ALIAS;
    private static final int MAX_ADMINS = 3;
    private static final long PENDING_ACTION_TIMEOUT_MS = 60_000L;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();

    public Map<String, PlayerBaseConfig> getPlayerConfigs() {
        return baseConfig.getPlayers();
    }

    @Override
    public void onLoad() {
        INSTANCE = this;
        BackToTheBaseLanguage.init(getClass().getClassLoader());

        File configFile = new File(config_name);
        if (!configFile.exists()) {
            try {
                if (!configFile.createNewFile()) {
                    getLogger().error("Failed to create config file");
                    System.exit(-1);
                }
                writeDefaultConfig(configFile);
            } catch (IOException e) {
                getLogger().error("Failed to create config file", e);
                System.exit(-1);
            }
        }
        if (configFile.isFile()) {
            try {
                loadConfig(configFile, false);
            } catch (IOException e) {
                getLogger().error("Failed to read config file", e);
                System.exit(-1);
            }
        }
    }

    @Override
    public void onUnload() {

    }

    @Override
    public void onEnable() {
        Bot.INSTANCE.getPluginManager().events().registerEvents(new OnPrivateChat(), this);
        Bot.INSTANCE.getPluginManager().registerCommand(
                new Command(COMMAND_NAME, new String[]{COMMAND_ALIAS}, "BackToTheBase management", COMMAND_NAME + " <command>"),
                new BackToTheBaseCommand(),
                this
        );
    }

    @Override
    public void onDisable() {

    }

    private void writeDefaultConfig(File configFile) throws IOException {
        PlayerBaseConfig.BaseConfig defaultConfig = new PlayerBaseConfig.BaseConfig();
        defaultConfig.setLanguage(BackToTheBaseLanguage.CHINESE);
        Map<String, PlayerBaseConfig> players = new LinkedHashMap<>();
        players.put("example_name", new PlayerBaseConfig(List.of(new ButtonLocation(
                "1",
                PlayerBaseConfig.DEFAULT_RETURN_X,
                PlayerBaseConfig.DEFAULT_RETURN_Y,
                PlayerBaseConfig.DEFAULT_RETURN_Z
        ))));
        defaultConfig.setPlayers(players);
        defaultConfig.setReturnConfig(new PlayerBaseConfig.ReturnConfig());
        writeConfig(configFile, defaultConfig);
    }

    public synchronized boolean reloadConfig() {
        File configFile = new File(config_name);
        if (!configFile.isFile()) {
            getLogger().error("Cannot reload {} because the file does not exist or is not a file. Keeping previous config.", config_name);
            return false;
        }

        try {
            if (loadConfig(configFile, true)) {
                return true;
            }
            getLogger().warn("Reload of {} failed validation. Keeping previous config.", config_name);
        } catch (IOException e) {
            getLogger().error("Failed to reload {}. Keeping previous config.", config_name, e);
        }
        return false;
    }

    private boolean loadConfig(File configFile, boolean runtimeReload) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            JsonElement parsed = gson.fromJson(reader, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                getLogger().error("Config root must be a JSON object. {}.", runtimeReload ? "Keeping previous config" : "No player configs loaded");
                return false;
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            getLogger().error("Config file is not valid JSON. {}.", runtimeReload ? "Keeping previous config" : "No player configs loaded", e);
            return false;
        }

        LoadResult result = root.has("players")
                ? parseReadmeConfig(root)
                : parseLegacyConfig(root);

        if (result == null) {
            getLogger().error("Load of {} failed validation. {}.", config_name, runtimeReload ? "Keeping previous config" : "Config file was not overwritten");
            return false;
        }

        if (result.invalid) {
            if (runtimeReload) {
                getLogger().error("Load of {} failed validation. Keeping previous config.", config_name);
                return false;
            }

            if (!hasValidPlayers(result.config)) {
                getLogger().error("Load of {} failed validation and no valid player configs were found.", config_name);
                return false;
            }

            getLogger().warn("Load of {} found invalid entries. Loading valid entries and skipping invalid ones.", config_name);
        }

        setLoadedConfig(result.config);
        if (result.changed && !result.invalid && !runtimeReload && hasValidPlayers(result.config)) {
            backupConfig(configFile);
            writeConfig(configFile, baseConfig);
            getLogger().info("Saved migrated/validated {}", config_name);
        }
        return true;
    }

    private LoadResult parseReadmeConfig(JsonObject root) {
        boolean changed = false;
        boolean invalid = false;
        ConfigValue<String> languageResult = parseLanguage(root);
        changed |= languageResult.changed;
        invalid |= languageResult.invalid;

        JsonElement playersElement = root.get("players");
        if (playersElement == null || !playersElement.isJsonObject()) {
            getLogger().error("Config field players must be an object.");
            return new LoadResult(new PlayerBaseConfig.BaseConfig(), true, true);
        }

        Map<String, PlayerBaseConfig> players = new LinkedHashMap<>();
        JsonObject playersRoot = playersElement.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : playersRoot.entrySet()) {
            String playerName = entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                getLogger().error("Config entry for {} must be an object.", playerName);
                changed = true;
                invalid = true;
                continue;
            }

            ConfigResult result = parsePlayerConfig(playerName, entry.getValue().getAsJsonObject());
            changed |= result.changed;
            invalid |= result.invalid;
            if (result.config != null) {
                players.put(playerName, result.config);
            }
        }

        ReturnConfigResult returnResult = parseGlobalReturnConfig(root);
        changed |= returnResult.changed;
        invalid |= returnResult.invalid;
        AdminConfigResult adminResult = parseAdminConfig(root);
        changed |= adminResult.changed;
        invalid |= adminResult.invalid;

        PlayerBaseConfig.BaseConfig config = new PlayerBaseConfig.BaseConfig();
        config.setLanguage(languageResult.value);
        config.setPlayers(players);
        config.setReturnConfig(returnResult.config);
        config.setAdmin(adminResult.config);
        return new LoadResult(config, changed, invalid);
    }

    private LoadResult parseLegacyConfig(JsonObject root) {
        boolean changed = false;
        boolean invalid = false;
        ConfigValue<String> languageResult = parseLanguage(root);
        changed |= languageResult.changed;
        invalid |= languageResult.invalid;
        Map<String, PlayerBaseConfig> players = new LinkedHashMap<>();
        LegacyReturnSelection legacyReturnSelection = new LegacyReturnSelection();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String playerName = entry.getKey();
            if ("language".equals(playerName)) {
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                getLogger().error("Config entry for {} must be an object.", playerName);
                invalid = true;
                continue;
            }

            JsonObject playerObj = entry.getValue().getAsJsonObject();
            if (hasCoordinate(playerObj)) {
                int x = getInt(playerObj, "x");
                int y = getInt(playerObj, "y");
                int z = getInt(playerObj, "z");

                getLogger().warn("Migrating old simple config format for {}", playerName);
                players.put(playerName, new PlayerBaseConfig(List.of(new ButtonLocation("1", x, y, z))));
                changed = true;

                ReturnConfigResult legacyReturnResult = parseLegacySimpleReturnConfig(
                        playerName,
                        playerObj,
                        new PlayerBaseConfig.ReturnLocation(x, y, z)
                );
                invalid |= legacyReturnResult.invalid;
                if (legacyReturnResult.config != null) {
                    legacyReturnSelection.acceptIntermediateConfig(playerName, legacyReturnResult.config);
                }

                continue;
            }

            ConfigResult result = parsePlayerConfig(playerName, playerObj);
            changed |= result.changed;
            invalid |= result.invalid;
            if (result.config != null) {
                players.put(playerName, result.config);
            }

            ReturnConfigResult legacyReturnResult = parseLegacyReturnConfig(playerName, playerObj);
            invalid |= legacyReturnResult.invalid;
            if (legacyReturnResult.config != null) {
                legacyReturnSelection.acceptIntermediateConfig(playerName, legacyReturnResult.config);
            }
        }

        PlayerBaseConfig.BaseConfig config = new PlayerBaseConfig.BaseConfig();
        config.setLanguage(languageResult.value);
        config.setPlayers(players);
        config.setReturnConfig(legacyReturnSelection.config);
        config.setAdmin(new PlayerBaseConfig.AdminConfig());
        return new LoadResult(config, changed, invalid);
    }

    private ConfigValue<String> parseLanguage(JsonObject root) {
        if (!root.has("language")) {
            getLogger().warn("Config field language is missing. Defaulting language to Chinese.");
            return new ConfigValue<>(BackToTheBaseLanguage.CHINESE, true, false);
        }
        JsonElement element = root.get("language");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            getLogger().warn("Config field language must be English or Chinese. Defaulting language to Chinese.");
            return new ConfigValue<>(BackToTheBaseLanguage.CHINESE, true, false);
        }
        String language = BackToTheBaseLanguage.normalize(element.getAsString());
        if (!BackToTheBaseLanguage.isValid(element.getAsString())) {
            getLogger().warn("Config field language must be English or Chinese. Defaulting language to Chinese.");
            return new ConfigValue<>(language, true, false);
        }
        return new ConfigValue<>(language, false, false);
    }

    private ConfigResult parsePlayerConfig(String playerName, JsonObject obj) {
        if (!obj.has("locations") || !obj.get("locations").isJsonArray()) {
            getLogger().error("Config entry for {} must contain a locations array. Skipping.", playerName);
            return new ConfigResult(null, true, true);
        }

        boolean changed = false;
        boolean invalid = false;
        JsonArray locationsJson = obj.getAsJsonArray("locations");
        List<ButtonLocation> locations = new ArrayList<>();
        Set<String> seenNumbers = new HashSet<>();
        Set<String> explicitNumbers = explicitLocationNumbers(locationsJson);
        Set<String> reservedNumbers = reservedLocationNumbers(locationsJson);
        for (int i = 0; i < locationsJson.size(); i++) {
            JsonElement locationElement = locationsJson.get(i);
            if (!locationElement.isJsonObject()) {
                getLogger().error("Invalid location under {}: location must be an object. Skipping.", playerName);
                changed = true;
                invalid = true;
                continue;
            }
            JsonObject locationObj = locationElement.getAsJsonObject();
            ConfigValue<String> numberResult = getLocationNumber(playerName, locationObj, seenNumbers, explicitNumbers, reservedNumbers);
            changed |= numberResult.changed;
            invalid |= numberResult.invalid;
            String number = numberResult.value;
            if (number == null) {
                changed = true;
                continue;
            }
            if (!isPositiveInteger(number)) {
                getLogger().error("Invalid location number {} under {}. Number must be a positive integer starting from 1. Skipping.", number, playerName);
                changed = true;
                invalid = true;
                continue;
            }
            if (seenNumbers.contains(number)) {
                getLogger().warn("Duplicate location number {} under {}. Keeping the first one.", number, playerName);
                changed = true;
                invalid = true;
                continue;
            }
            if (!hasCoordinate(locationObj)) {
                getLogger().error("Location {} under {} must contain x, y, and z. Skipping.", number, playerName);
                changed = true;
                invalid = true;
                continue;
            }

            seenNumbers.add(number);
            locations.add(new ButtonLocation(
                    number,
                    getInt(locationObj, "x"),
                    getInt(locationObj, "y"),
                    getInt(locationObj, "z")
            ));
        }

        if (locations.isEmpty() && invalid) {
            getLogger().error("Config entry for {} has no valid locations. Skipping.", playerName);
            return new ConfigResult(null, true, true);
        }

        if (obj.has("returnAfterUse") || obj.has("returnLocation")) {
            changed = true;
        }
        return new ConfigResult(new PlayerBaseConfig(locations), changed, invalid);
    }

    private ReturnConfigResult parseGlobalReturnConfig(JsonObject root) {
        if (!root.has("return")) {
            getLogger().warn("Config field return is missing. Defaulting return config.");
            return new ReturnConfigResult(new PlayerBaseConfig.ReturnConfig(), true, false);
        }

        JsonElement returnElement = root.get("return");
        if (!returnElement.isJsonObject()) {
            getLogger().error("Config field return must be an object.");
            return new ReturnConfigResult(new PlayerBaseConfig.ReturnConfig(), true, true);
        }

        JsonObject returnObj = returnElement.getAsJsonObject();
        ConfigValue<Boolean> enabledResult = getBoolean("return", returnObj, "enabled", false, false);
        ReturnLocationResult locationResult = parseReturnLocation("return.location", returnObj.get("location"), false);

        PlayerBaseConfig.ReturnConfig returnConfig = new PlayerBaseConfig.ReturnConfig();
        returnConfig.setEnabled(enabledResult.value);
        if (locationResult.location != null) {
            returnConfig.setLocation(locationResult.location);
        }

        boolean changed = enabledResult.changed || locationResult.changed;
        boolean invalid = enabledResult.invalid || locationResult.invalid;
        return new ReturnConfigResult(returnConfig, changed, invalid);
    }

    private AdminConfigResult parseAdminConfig(JsonObject root) {
        if (!root.has("admin")) {
            return new AdminConfigResult(new PlayerBaseConfig.AdminConfig(), true, false);
        }
        JsonElement adminElement = root.get("admin");
        if (!adminElement.isJsonObject()) {
            getLogger().error("Config field admin must be an object.");
            return new AdminConfigResult(new PlayerBaseConfig.AdminConfig(), true, true);
        }

        boolean changed = false;
        boolean invalid = false;
        JsonObject adminObj = adminElement.getAsJsonObject();
        ConfigValue<Boolean> enabledResult = getBoolean("admin", adminObj, "enabled", false, false);
        changed |= enabledResult.changed;
        invalid |= enabledResult.invalid;

        List<String> admins = new ArrayList<>();
        if (!adminObj.has("players")) {
            changed = true;
        } else if (!adminObj.get("players").isJsonArray()) {
            getLogger().error("admin.players must be an array.");
            invalid = true;
            changed = true;
        } else {
            for (JsonElement element : adminObj.getAsJsonArray("players")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    getLogger().warn("admin.players entries must be strings. Ignoring {}.", element);
                    changed = true;
                    continue;
                }
                String name = element.getAsString();
                if (name == null || name.isBlank() || admins.contains(name)) {
                    changed = true;
                    continue;
                }
                if (admins.size() >= MAX_ADMINS) {
                    getLogger().warn("admin.players supports at most {} players. Ignoring {}.", MAX_ADMINS, name);
                    changed = true;
                    continue;
                }
                admins.add(name);
            }
        }

        PlayerBaseConfig.AdminConfig adminConfig = new PlayerBaseConfig.AdminConfig();
        adminConfig.setEnabled(enabledResult.value);
        adminConfig.setPlayers(admins);
        return new AdminConfigResult(adminConfig, changed, invalid);
    }

    private ReturnConfigResult parseLegacySimpleReturnConfig(
            String playerName,
            JsonObject obj,
            PlayerBaseConfig.ReturnLocation fallbackLocation
    ) {
        ConfigValue<Boolean> returnAfterUseResult = getBoolean(playerName, obj, "returnAfterUse", false, false);
        ReturnLocationResult returnLocationResult = parseReturnLocation(
                "returnLocation for " + playerName,
                obj.get("returnLocation"),
                false
        );

        PlayerBaseConfig.ReturnConfig returnConfig = new PlayerBaseConfig.ReturnConfig();
        returnConfig.setEnabled(returnAfterUseResult.value);

        if (returnLocationResult.location != null) {
            returnConfig.setLocation(returnLocationResult.location);
        } else {
            returnConfig.setLocation(fallbackLocation);
        }

        boolean invalid = returnAfterUseResult.invalid || returnLocationResult.invalid;
        return new ReturnConfigResult(returnConfig, true, invalid);
    }

    private ReturnConfigResult parseLegacyReturnConfig(String playerName, JsonObject obj) {
        if (!obj.has("returnAfterUse") && !obj.has("returnLocation")) {
            return new ReturnConfigResult(null, false, false);
        }

        ConfigValue<Boolean> returnAfterUseResult = getBoolean(playerName, obj, "returnAfterUse", false, false);
        ReturnLocationResult returnLocationResult = parseReturnLocation("returnLocation for " + playerName, obj.get("returnLocation"), false);
        boolean invalid = returnAfterUseResult.invalid || returnLocationResult.invalid;

        if (returnLocationResult.location == null) {
            return new ReturnConfigResult(null, true, invalid);
        }

        PlayerBaseConfig.ReturnConfig returnConfig = new PlayerBaseConfig.ReturnConfig();
        returnConfig.setEnabled(returnAfterUseResult.value);
        returnConfig.setLocation(returnLocationResult.location);
        return new ReturnConfigResult(returnConfig, true, invalid);
    }

    private Set<String> reservedLocationNumbers(JsonArray locationsJson) {
        Set<String> reservedNumbers = new HashSet<>();
        for (JsonElement locationElement : locationsJson) {
            if (!locationElement.isJsonObject()) {
                continue;
            }
            JsonObject locationObj = locationElement.getAsJsonObject();
            String number = null;
            if (locationObj.has("number") && locationObj.get("number").isJsonPrimitive()) {
                number = locationObj.get("number").getAsString();
            } else if (!locationObj.has("number") && locationObj.has("priority") && locationObj.get("priority").isJsonPrimitive()) {
                number = locationObj.get("priority").getAsString();
            }
            if (isPositiveInteger(number)) {
                reservedNumbers.add(number);
            }
        }
        return reservedNumbers;
    }

    private Set<String> explicitLocationNumbers(JsonArray locationsJson) {
        Set<String> explicitNumbers = new HashSet<>();
        for (JsonElement locationElement : locationsJson) {
            if (!locationElement.isJsonObject()) {
                continue;
            }
            JsonObject locationObj = locationElement.getAsJsonObject();
            if (locationObj.has("number") && locationObj.get("number").isJsonPrimitive()) {
                String number = locationObj.get("number").getAsString();
                if (isPositiveInteger(number)) {
                    explicitNumbers.add(number);
                }
            }
        }
        return explicitNumbers;
    }

    private ConfigValue<String> getLocationNumber(
            String playerName,
            JsonObject locationObj,
            Set<String> seenNumbers,
            Set<String> explicitNumbers,
            Set<String> reservedNumbers
    ) {
        if (locationObj.has("number")) {
            if (!locationObj.get("number").isJsonPrimitive()) {
                getLogger().error("Location number under {} must be a positive integer string. Skipping.", playerName);
                return new ConfigValue<>(null, true, true);
            }
            return new ConfigValue<>(locationObj.get("number").getAsString(), false, false);
        }
        if (locationObj.has("priority")) {
            if (locationObj.get("priority").isJsonPrimitive()) {
                String priority = locationObj.get("priority").getAsString();
                if (isPositiveInteger(priority)) {
                    if (explicitNumbers.contains(priority)) {
                        String number = nextAvailableLocationNumber(seenNumbers, reservedNumbers);
                        getLogger().warn("Location under {} has priority {} reserved by an explicit number. Defaulting it to {}.", playerName, priority, number);
                        return new ConfigValue<>(number, true, false);
                    }
                    getLogger().warn("Location under {} is missing number. Using priority {} as number.", playerName, priority);
                    return new ConfigValue<>(priority, true, false);
                }
            }
            getLogger().error("Location priority under {} must be a positive integer. Skipping.", playerName);
            return new ConfigValue<>(null, true, true);
        }
        String number = nextAvailableLocationNumber(seenNumbers, reservedNumbers);
        getLogger().warn("Location under {} is missing number. Defaulting it to {}.", playerName, number);
        return new ConfigValue<>(number, true, false);
    }

    private String nextAvailableLocationNumber(Set<String> seenNumbers, Set<String> reservedNumbers) {
        int number = 1;
        while (seenNumbers.contains(String.valueOf(number)) || reservedNumbers.contains(String.valueOf(number))) {
            number++;
        }
        return String.valueOf(number);
    }

    private ReturnLocationResult parseReturnLocation(String label, JsonElement element, boolean required) {
        if (element == null) {
            if (required) {
                getLogger().error("{} is required.", label);
                return new ReturnLocationResult(null, true, true);
            }
            return new ReturnLocationResult(null, true, false);
        }
        if (!element.isJsonObject() || !hasCoordinate(element.getAsJsonObject())) {
            getLogger().error("{} must be an object with x, y, and z coordinates.", label);
            return new ReturnLocationResult(null, true, true);
        }
        JsonObject returnObj = element.getAsJsonObject();
        return new ReturnLocationResult(new PlayerBaseConfig.ReturnLocation(
                getInt(returnObj, "x"),
                getInt(returnObj, "y"),
                getInt(returnObj, "z")
        ), false, false);
    }

    private boolean hasCoordinate(JsonObject obj) {
        return getInt(obj, "x") != null && getInt(obj, "y") != null && getInt(obj, "z") != null;
    }

    private Integer getInt(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            return null;
        }
    }

    private ConfigValue<Boolean> getBoolean(String owner, JsonObject obj, String key, boolean defaultValue, boolean required) {
        if (!obj.has(key)) {
            if (required) {
                getLogger().error("{} for {} is required.", key, owner);
            }
            return new ConfigValue<>(defaultValue, true, required);
        }
        JsonElement element = obj.get(key);
        if (!element.isJsonPrimitive()) {
            getLogger().warn("{} for {} must be true or false. Defaulting to {}.", key, owner, defaultValue);
            return new ConfigValue<>(defaultValue, true, true);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isBoolean()) {
            getLogger().warn("{} for {} must be true or false. Defaulting to {}.", key, owner, defaultValue);
            return new ConfigValue<>(defaultValue, true, true);
        }
        return new ConfigValue<>(primitive.getAsBoolean(), false, false);
    }

    private boolean isPositiveInteger(String number) {
        return number != null && number.matches("[1-9][0-9]*");
    }

    private void setLoadedConfig(PlayerBaseConfig.BaseConfig config) {
        if (config == null) {
            config = new PlayerBaseConfig.BaseConfig();
        }
        baseConfig = config;
    }

    private boolean hasValidPlayers(PlayerBaseConfig.BaseConfig config) {
        return config != null && !config.getPlayers().isEmpty();
    }

    private void writeConfig(File configFile, PlayerBaseConfig.BaseConfig config) throws IOException {
        try (Writer writer = Files.newBufferedWriter(configFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(config, writer);
        }
    }

    private void backupConfig(File configFile) throws IOException {
        Files.copy(configFile.toPath(), new File(config_name + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized boolean saveCurrentConfig() {
        try {
            writeConfig(new File(config_name), baseConfig);
            return true;
        } catch (IOException e) {
            getLogger().error("Failed to save {}", config_name, e);
            return false;
        }
    }

    private List<String> saveFailedMessage() {
        return List.of(messages().saveFailed());
    }

    private BackToTheBaseLanguage messages() {
        return BackToTheBaseLanguage.of(baseConfig.getLanguage());
    }

    public synchronized List<String> handleManagementCommand(String sender, boolean console, String[] args) {
        String commandPrefix = console ? COMMAND_NAME : GAME_COMMAND_PREFIX;
        return handleManagementCommand(sender, console, commandPrefix, args);
    }

    public synchronized List<String> handleManagementCommand(String sender, boolean console, String commandPrefix, String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (args.length == 0) {
            return List.of(lang.unknownCommand());
        }
        String scope = console ? "console" : "game:" + sender;
        return switch (args[0].toLowerCase()) {
            case "stat" -> console ? consoleStat() : List.of(gameStat());
            case "confirm" -> confirmPending(scope);
            case "lang" -> handleLanguage(console, args);
            case "returnenable" -> setReturnEnabled(args);
            case "returnpoint" -> setReturnPoint(args);
            case "player" -> handlePlayer(scope, commandPrefix, args);
            case "loc" -> handleLoc(scope, commandPrefix, args);
            case "admin" -> console ? handleAdmin(args) : List.of(lang.consoleOnly());
            case "adminenable" -> console ? handleAdminEnable(args) : List.of(lang.consoleOnly());
            default -> List.of(lang.unknownCommand());
        };
    }

    private List<String> handleLanguage(boolean console, String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (!console) {
            return List.of(lang.consoleOnly());
        }
        if (args.length != 2 || !BackToTheBaseLanguage.isValid(args[1])) {
            return List.of(lang.usage("lang English|Chinese"));
        }
        baseConfig.setLanguage(BackToTheBaseLanguage.normalize(args[1]));
        if (!saveCurrentConfig()) {
            return saveFailedMessage();
        }
        return List.of(messages().languageChanged(baseConfig.getLanguage()));
    }

    private List<String> setReturnEnabled(String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (args.length != 2 || (!"true".equalsIgnoreCase(args[1]) && !"false".equalsIgnoreCase(args[1]))) {
            return List.of(lang.usage("returnenable true|false"));
        }
        baseConfig.getReturnConfig().setEnabled(Boolean.parseBoolean(args[1]));
        if (!saveCurrentConfig()) {
            return saveFailedMessage();
        }
        return List.of(messages().returnEnabled(Boolean.parseBoolean(args[1])));
    }

    private List<String> setReturnPoint(String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (args.length != 4) {
            return List.of(lang.usage("returnpoint <x> <y> <z>"));
        }
        Integer x = parseInt(args[1]);
        Integer y = parseInt(args[2]);
        Integer z = parseInt(args[3]);
        if (x == null || y == null || z == null) {
            return List.of(lang.coordinatesMustBeIntegers());
        }
        baseConfig.getReturnConfig().setLocation(new PlayerBaseConfig.ReturnLocation(x, y, z));
        if (!saveCurrentConfig()) {
            return saveFailedMessage();
        }
        return List.of(messages().returnPointSet(x, y, z));
    }

    private List<String> handlePlayer(String scope, String commandPrefix, String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (args.length < 2) {
            return List.of(lang.usage("player add|remove|list"));
        }
        if ("list".equalsIgnoreCase(args[1])) {
            return scope.startsWith("console") ? consolePlayerList() : List.of(gamePlayerList());
        }
        if (args.length != 3) {
            return List.of(lang.usage("player add|remove <playerName>"));
        }
        String playerName = args[2];
        if ("add".equalsIgnoreCase(args[1])) {
            if (getPlayerConfigs().containsKey(playerName)) {
                return List.of(lang.playerExists(playerName));
            }
            getPlayerConfigs().put(playerName, new PlayerBaseConfig(new ArrayList<>()));
            if (!saveCurrentConfig()) {
                return saveFailedMessage();
            }
            return List.of(messages().playerAdded(playerName));
        }
        if ("remove".equalsIgnoreCase(args[1])) {
            if (!getPlayerConfigs().containsKey(playerName)) {
                return List.of(lang.playerMissing(playerName));
            }
            pendingActions.put(scope, PendingAction.removePlayer(playerName));
            return List.of(lang.waitingRemovePlayer(playerName, confirmCommand(commandPrefix)));
        }
        return List.of(lang.unknownCommand());
    }

    private List<String> handleLoc(String scope, String commandPrefix, String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (args.length < 2) {
            return List.of(lang.usage("loc add|set|remove|list"));
        }
        if ("list".equalsIgnoreCase(args[1])) {
            if (args.length != 3) {
                return List.of(lang.usage("loc list <playerName>"));
            }
            return scope.startsWith("console") ? consoleLocList(args[2]) : List.of(gameLocList(args[2]));
        }
        if ("remove".equalsIgnoreCase(args[1])) {
            if (args.length != 4) {
                return List.of(lang.usage("loc remove <playerName> <number>"));
            }
            PlayerBaseConfig config = getPlayerConfigs().get(args[2]);
            if (config == null) {
                return List.of(lang.playerMissing(args[2]));
            }
            if (config.getLocation(args[3]) == null) {
                return List.of(lang.locationMissing(args[3]));
            }
            if (safeLocations(config).size() <= 1) {
                return List.of(lang.cannotRemoveLastLocation(args[2]));
            }
            pendingActions.put(scope, PendingAction.removeLoc(args[2], args[3]));
            return List.of(lang.waitingRemoveLocation(args[2], args[3], confirmCommand(commandPrefix)));
        }
        if (args.length != 7 || (!"add".equalsIgnoreCase(args[1]) && !"set".equalsIgnoreCase(args[1]))) {
            return List.of(lang.usage("loc add|set <playerName> <number> <x> <y> <z>"));
        }
        String playerName = args[2];
        String number = args[3];
        Integer x = parseInt(args[4]);
        Integer y = parseInt(args[5]);
        Integer z = parseInt(args[6]);
        if (!isPositiveInteger(number) || x == null || y == null || z == null) {
            return List.of(lang.numberAndCoordinatesMustBeIntegers());
        }
        PlayerBaseConfig config = getPlayerConfigs().get(playerName);
        boolean createdPlayer = false;
        if (config == null) {
            config = new PlayerBaseConfig(new ArrayList<>());
            getPlayerConfigs().put(playerName, config);
            createdPlayer = true;
        }
        if ("add".equalsIgnoreCase(args[1]) && config.getLocation(number) != null) {
            return List.of(lang.locationExists(number));
        }
        boolean existed = setLocation(config, new ButtonLocation(number, x, y, z));
        if (!saveCurrentConfig()) {
            return saveFailedMessage();
        }
        if ("add".equalsIgnoreCase(args[1])) {
            return List.of(messages().locationAddResult(createdPlayer, playerName, number, x, y, z));
        }
        if (createdPlayer) {
            return List.of(messages().locationSetCreatedPlayer(playerName, number, x, y, z));
        }
        return List.of(messages().locationSetResult(existed, playerName, number, x, y, z));
    }

    private List<String> handleAdmin(String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (args.length != 3 || (!"add".equalsIgnoreCase(args[1]) && !"remove".equalsIgnoreCase(args[1]))) {
            return List.of(lang.usage("admin add|remove <playerName>"));
        }

        List<String> admins = adminPlayers();
        String playerName = args[2];

        if ("add".equalsIgnoreCase(args[1])) {
            if (admins.contains(playerName)) {
                return List.of(lang.adminExists(playerName));
            }
            if (admins.size() >= MAX_ADMINS) {
                return List.of(lang.adminLimit(MAX_ADMINS, playerName));
            }

            admins.add(playerName);
            if (!saveCurrentConfig()) {
                return saveFailedMessage();
            }
            return List.of(messages().adminAdded(playerName));
        }

        if (!admins.contains(playerName)) {
            return List.of(lang.adminMissing(playerName));
        }

        admins.remove(playerName);
        if (!saveCurrentConfig()) {
            return saveFailedMessage();
        }
        return List.of(messages().adminRemoved(playerName));
    }

    private List<String> handleAdminEnable(String[] args) {
        BackToTheBaseLanguage lang = messages();
        if (args.length != 2 || (!"true".equalsIgnoreCase(args[1]) && !"false".equalsIgnoreCase(args[1]))) {
            return List.of(lang.usage("adminenable true|false"));
        }
        baseConfig.getAdmin().setEnabled(Boolean.parseBoolean(args[1]));
        if (!saveCurrentConfig()) {
            return saveFailedMessage();
        }
        return List.of(messages().adminEnabled(baseConfig.getAdmin().isEnabled()));
    }

    private List<String> confirmPending(String scope) {
        BackToTheBaseLanguage lang = messages();
        PendingAction action = pendingActions.remove(scope);
        if (action == null) {
            return List.of(lang.noPendingAction());
        }
        if (action.isExpired()) {
            return List.of(lang.pendingExpired());
        }

        if (action.type == PendingType.REMOVE_PLAYER) {
            if (!getPlayerConfigs().containsKey(action.playerName)) {
                return List.of(lang.playerMissing(action.playerName));
            }

            getPlayerConfigs().remove(action.playerName);
            if (!saveCurrentConfig()) {
                return saveFailedMessage();
            }
            return List.of(messages().confirmedRemovePlayer(action.playerName));
        }

        PlayerBaseConfig config = getPlayerConfigs().get(action.playerName);
        if (config == null) {
            return List.of(lang.playerMissing(action.playerName));
        }
        if (config.getLocation(action.number) == null) {
            return List.of(lang.locationMissing(action.number));
        }
        if (safeLocations(config).size() <= 1) {
            return List.of(lang.cannotRemoveLastLocation(action.playerName));
        }

        config.getLocations().removeIf(location -> location != null && action.number.equals(location.getNumber()));
        if (!saveCurrentConfig()) {
            return saveFailedMessage();
        }
        return List.of(messages().confirmedRemoveLocation(action.playerName, action.number));
    }

    private List<String> consoleStat() {
        BackToTheBaseLanguage lang = messages();
        List<String> lines = new ArrayList<>();
        PlayerBaseConfig.ReturnConfig ret = baseConfig.getReturnConfig();
        PlayerBaseConfig.ReturnLocation loc = ret.getLocation();
        lines.add(lang.statusHeader());
        lines.add(lang.returnFeature(ret.isEnabled()));
        lines.add(lang.returnLocation(loc.getX(), loc.getY(), loc.getZ()));
        lines.add(lang.adminFeature(baseConfig.getAdmin().isEnabled()));
        lines.add(lang.adminCount(adminPlayers().size(), MAX_ADMINS));
        lines.add(lang.playerCount(getPlayerConfigs().size()));
        lines.add(lang.locationCount(locationCount()));
        lines.add(lang.playerDataHeader());
        for (Map.Entry<String, PlayerBaseConfig> entry : getPlayerConfigs().entrySet()) {
            lines.add(lang.playerDataLine(entry.getKey(), safeLocations(entry.getValue()).size()));
        }
        lines.add(lang.divider());
        return lines;
    }

    private String gameStat() {
        BackToTheBaseLanguage lang = messages();
        PlayerBaseConfig.ReturnConfig ret = baseConfig.getReturnConfig();
        PlayerBaseConfig.ReturnLocation loc = ret.getLocation();
        return lang.gameStatus(
                ret.isEnabled(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                baseConfig.getAdmin().isEnabled(),
                adminPlayers().size(),
                MAX_ADMINS,
                getPlayerConfigs().size(),
                locationCount()
        );
    }

    private List<String> consolePlayerList() {
        BackToTheBaseLanguage lang = messages();
        List<String> lines = new ArrayList<>();
        lines.add(lang.playerListHeader());
        lines.add(lang.playerCount(getPlayerConfigs().size()));
        if (getPlayerConfigs().isEmpty()) {
            lines.add(lang.noPlayersConsole());
        } else {
            lines.add(lang.playerDataHeader());
            for (Map.Entry<String, PlayerBaseConfig> entry : getPlayerConfigs().entrySet()) {
                lines.add(lang.playerLocationData(entry.getKey(), locationNumbers(entry.getValue())));
            }
        }
        lines.add(lang.divider());
        return lines;
    }

    private String gamePlayerList() {
        BackToTheBaseLanguage lang = messages();
        if (getPlayerConfigs().isEmpty()) {
            return lang.noPlayers();
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, PlayerBaseConfig> entry : getPlayerConfigs().entrySet()) {
            parts.add(lang.gamePlayerEntry(entry.getKey(), locationNumbers(entry.getValue())));
        }
        return lang.gamePlayerList(String.join("; ", parts));
    }

    private List<String> consoleLocList(String playerName) {
        BackToTheBaseLanguage lang = messages();
        PlayerBaseConfig config = getPlayerConfigs().get(playerName);
        if (config == null) {
            return List.of(lang.playerMissing(playerName));
        }
        List<String> lines = new ArrayList<>();
        lines.add(lang.locationListHeader(playerName));
        lines.add(lang.locationCount(safeLocations(config).size()));
        for (ButtonLocation location : safeLocations(config)) {
            lines.add(lang.locationLine(location.getNumber(), location.getX(), location.getY(), location.getZ()));
        }
        lines.add(lang.divider());
        return lines;
    }

    private String gameLocList(String playerName) {
        BackToTheBaseLanguage lang = messages();
        PlayerBaseConfig config = getPlayerConfigs().get(playerName);
        if (config == null) {
            return lang.playerMissing(playerName);
        }
        List<String> parts = new ArrayList<>();
        for (ButtonLocation location : safeLocations(config)) {
            parts.add(location.getNumber() + "=" + location.getX() + " " + location.getY() + " " + location.getZ());
        }
        return lang.gameLocationList(playerName, String.join("; ", parts));
    }

    private boolean setLocation(PlayerBaseConfig config, ButtonLocation replacement) {
        if (config.getLocations() == null) {
            config.setLocations(new ArrayList<>());
        }
        for (int i = 0; i < config.getLocations().size(); i++) {
            ButtonLocation location = config.getLocations().get(i);
            if (location != null && replacement.getNumber().equals(location.getNumber())) {
                config.getLocations().set(i, replacement);
                return true;
            }
        }
        config.getLocations().add(replacement);
        return false;
    }

    private List<String> adminPlayers() {
        if (baseConfig.getAdmin().getPlayers() == null) {
            baseConfig.getAdmin().setPlayers(new ArrayList<>());
        }
        return baseConfig.getAdmin().getPlayers();
    }

    private List<ButtonLocation> safeLocations(PlayerBaseConfig config) {
        if (config == null || config.getLocations() == null) {
            return List.of();
        }
        List<ButtonLocation> locations = new ArrayList<>();
        for (ButtonLocation location : config.getLocations()) {
            if (location != null) {
                locations.add(location);
            }
        }
        return locations;
    }

    private String locationNumbers(PlayerBaseConfig config) {
        List<String> numbers = new ArrayList<>();
        for (ButtonLocation location : safeLocations(config)) {
            numbers.add(location.getNumber());
        }
        return String.join(",", numbers);
    }

    private int locationCount() {
        int count = 0;
        for (PlayerBaseConfig config : getPlayerConfigs().values()) {
            count += safeLocations(config).size();
        }
        return count;
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String confirmCommand(String commandPrefix) {
        if (commandPrefix == null || commandPrefix.isBlank()) {
            commandPrefix = COMMAND_NAME;
        }
        return commandPrefix + " confirm";
    }

    private String formatConsoleLogMessage(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("[BackToTheBase]:")) {
            return trimmed.substring("[BackToTheBase]:".length()).trim();
        }
        if (trimmed.startsWith("[BackToTheBase]")) {
            return trimmed.substring("[BackToTheBase]".length()).trim();
        }
        return trimmed;
    }

    private class BackToTheBaseCommand extends TabExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            if (args == null) {
                args = new String[0];
            }
            for (String line : handleManagementCommand("console", true, normalizeConsoleCommandLabel(label), args)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String message = formatConsoleLogMessage(line);
                if (!message.isBlank()) {
                    BackToTheBase.this.getLogger().info(message);
                }
            }
        }

        @Override
        public List<String> onTabComplete(Command command, String label, String[] args) {
            // Prevent tab completion from responding to unexpected command labels.
            if (!isPlainCommandLabel(label)) {
                return List.of();
            }
            if (args == null || args.length == 0) {
                return rootSuggestions("");
            }
            String root = args[0].toLowerCase();
            if (args.length == 1) {
                return rootSuggestions(args[0]);
            }
            return switch (root) {
                case "returnenable" -> args.length == 2 ? filter(List.of("true", "false"), args[1]) : List.of();
                case "adminenable" -> args.length == 2 ? filter(List.of("true", "false"), args[1]) : List.of();
                case "lang" -> args.length == 2 ? filter(List.of(BackToTheBaseLanguage.ENGLISH, BackToTheBaseLanguage.CHINESE), args[1]) : List.of();
                case "player" -> completePlayer(args);
                case "loc" -> completeLoc(args);
                case "admin" -> completeAdmin(args);
                default -> List.of();
            };
        }

        private boolean isPlainCommandLabel(String label) {
            return COMMAND_NAME.equalsIgnoreCase(label)
                    || COMMAND_ALIAS.equalsIgnoreCase(label)
                    || ("BackToTheBase:" + COMMAND_NAME).equalsIgnoreCase(label)
                    || ("BackToTheBase:" + COMMAND_ALIAS).equalsIgnoreCase(label);
        }

        private List<String> rootSuggestions(String prefix) {
            return filter(List.of("stat", "confirm", "lang", "returnenable", "returnpoint", "player", "loc", "admin", "adminenable"), prefix);
        }

        private List<String> completePlayer(String[] args) {
            if (args.length == 2) {
                return filter(List.of("add", "remove", "list"), args[1]);
            }
            if (args.length == 3 && "remove".equalsIgnoreCase(args[1])) {
                return filter(new ArrayList<>(getPlayerConfigs().keySet()), args[2]);
            }
            return List.of();
        }

        private List<String> completeLoc(String[] args) {
            if (args.length == 2) {
                return filter(List.of("add", "set", "remove", "list"), args[1]);
            }
            String action = args[1].toLowerCase();
            if (args.length == 3 && List.of("add", "set", "remove", "list").contains(action)) {
                return filter(new ArrayList<>(getPlayerConfigs().keySet()), args[2]);
            }
            if (args.length == 4 && List.of("set", "remove").contains(action)) {
                return filter(locationNumberList(args[2]), args[3]);
            }
            return List.of();
        }

        private List<String> completeAdmin(String[] args) {
            if (args.length == 2) {
                return filter(List.of("add", "remove"), args[1]);
            }
            if (args.length == 3 && "remove".equalsIgnoreCase(args[1])) {
                return filter(new ArrayList<>(adminPlayers()), args[2]);
            }
            return List.of();
        }

        private List<String> locationNumberList(String playerName) {
            List<String> numbers = new ArrayList<>();
            PlayerBaseConfig config = getPlayerConfigs().get(playerName);
            for (ButtonLocation location : safeLocations(config)) {
                numbers.add(location.getNumber());
            }
            return numbers;
        }

        private List<String> filter(List<String> values, String prefix) {
            String safePrefix = prefix == null ? "" : prefix.toLowerCase();
            List<String> matches = new ArrayList<>();
            for (String value : values) {
                if (value != null && value.toLowerCase().startsWith(safePrefix)) {
                    matches.add(value);
                }
            }
            return matches;
        }
    }

    private String normalizeConsoleCommandLabel(String label) {
        if (COMMAND_ALIAS.equalsIgnoreCase(label) || ("BackToTheBase:" + COMMAND_ALIAS).equalsIgnoreCase(label)) {
            return COMMAND_ALIAS;
        }
        return COMMAND_NAME;
    }

    private enum PendingType {
        REMOVE_PLAYER,
        REMOVE_LOC
    }

    private static class PendingAction {
        private final PendingType type;
        private final String playerName;
        private final String number;
        private final long createdAt;

        private PendingAction(PendingType type, String playerName, String number) {
            this.type = type;
            this.playerName = playerName;
            this.number = number;
            this.createdAt = System.currentTimeMillis();
        }

        private static PendingAction removePlayer(String playerName) {
            return new PendingAction(PendingType.REMOVE_PLAYER, playerName, null);
        }

        private static PendingAction removeLoc(String playerName, String number) {
            return new PendingAction(PendingType.REMOVE_LOC, playerName, number);
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - createdAt > PENDING_ACTION_TIMEOUT_MS;
        }
    }

    private static class LoadResult {
        private final PlayerBaseConfig.BaseConfig config;
        private final boolean changed;
        private final boolean invalid;

        private LoadResult(PlayerBaseConfig.BaseConfig config, boolean changed, boolean invalid) {
            this.config = config;
            this.changed = changed;
            this.invalid = invalid;
        }
    }

    private static class ConfigResult {
        private final PlayerBaseConfig config;
        private final boolean changed;
        private final boolean invalid;

        private ConfigResult(PlayerBaseConfig config, boolean changed, boolean invalid) {
            this.config = config;
            this.changed = changed;
            this.invalid = invalid;
        }
    }

    private static class ConfigValue<T> {
        private final T value;
        private final boolean changed;
        private final boolean invalid;

        private ConfigValue(T value, boolean changed, boolean invalid) {
            this.value = value;
            this.changed = changed;
            this.invalid = invalid;
        }
    }

    private static class ReturnLocationResult {
        private final PlayerBaseConfig.ReturnLocation location;
        private final boolean changed;
        private final boolean invalid;

        private ReturnLocationResult(PlayerBaseConfig.ReturnLocation location, boolean changed, boolean invalid) {
            this.location = location;
            this.changed = changed;
            this.invalid = invalid;
        }
    }

    private static class ReturnConfigResult {
        private final PlayerBaseConfig.ReturnConfig config;
        private final boolean changed;
        private final boolean invalid;

        private ReturnConfigResult(PlayerBaseConfig.ReturnConfig config, boolean changed, boolean invalid) {
            this.config = config;
            this.changed = changed;
            this.invalid = invalid;
        }
    }

    private static class AdminConfigResult {
        private final PlayerBaseConfig.AdminConfig config;
        private final boolean changed;
        private final boolean invalid;

        private AdminConfigResult(PlayerBaseConfig.AdminConfig config, boolean changed, boolean invalid) {
            this.config = config;
            this.changed = changed;
            this.invalid = invalid;
        }
    }

    private class LegacyReturnSelection {
        private final PlayerBaseConfig.ReturnConfig config = new PlayerBaseConfig.ReturnConfig();
        private boolean selected;

        private void acceptIntermediateConfig(String playerName, PlayerBaseConfig.ReturnConfig candidate) {
            if (!selected) {
                config.setEnabled(candidate.isEnabled());
                config.setLocation(candidate.getLocation());
                selected = true;
                return;
            }
            if (!sameReturnConfig(config, candidate)) {
                getLogger().warn(
                        "Multiple legacy player return configs were found. README format only supports one global return setting; ignoring return config for {}.",
                        playerName
                );
            }
        }

        private boolean sameReturnConfig(PlayerBaseConfig.ReturnConfig first, PlayerBaseConfig.ReturnConfig second) {
            PlayerBaseConfig.ReturnLocation firstLocation = first.getLocation();
            PlayerBaseConfig.ReturnLocation secondLocation = second.getLocation();
            return first.isEnabled() == second.isEnabled()
                    && firstLocation.getX() == secondLocation.getX()
                    && firstLocation.getY() == secondLocation.getY()
                    && firstLocation.getZ() == secondLocation.getZ();
        }
    }
}

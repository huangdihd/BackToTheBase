package huangdihd.xinbot;

import xin.bbtt.mcbot.LangManager;

public class BackToTheBaseLanguage {
    public static final String ENGLISH = "English";
    public static final String CHINESE = "Chinese";

    private static String loadedLanguageCode = "";
    private static ClassLoader pluginClassLoader = BackToTheBaseLanguage.class.getClassLoader();
    private final String language;

    private BackToTheBaseLanguage(String language) {
        this.language = normalize(language);
        loadLanguage(this.language);
    }

    public static BackToTheBaseLanguage of(String language) {
        return new BackToTheBaseLanguage(language);
    }

    public static boolean isValid(String language) {
        return language != null && (ENGLISH.equalsIgnoreCase(language) || CHINESE.equalsIgnoreCase(language));
    }

    public static String normalize(String language) {
        if (isValid(language) && ENGLISH.equalsIgnoreCase(language)) {
            return ENGLISH;
        }
        return CHINESE;
    }

    public static String toLangCode(String language) {
        return ENGLISH.equals(normalize(language)) ? "en_us" : "zh_cn";
    }

    public static synchronized void init(ClassLoader classLoader) {
        pluginClassLoader = classLoader;
        LangManager.initLang(classLoader);
        loadedLanguageCode = "";
    }

    private static synchronized void loadLanguage(String language) {
        String languageCode = toLangCode(language);
        if (languageCode.equals(loadedLanguageCode)) {
            return;
        }
        LangManager.loadFromClassLoader(pluginClassLoader, "en_us");
        if (!"en_us".equals(languageCode)) {
            LangManager.loadFromClassLoader(pluginClassLoader, languageCode);
        }
        loadedLanguageCode = languageCode;
    }

    public String unknownCommand() {
        return msg("backtothebase.unknown_command");
    }

    public String saveFailed() {
        return msg("backtothebase.save_failed");
    }

    public String consoleOnly() {
        return msg("backtothebase.console_only");
    }

    public String usage(String usage) {
        return msg("backtothebase.usage", usage);
    }

    public String languageChanged(String language) {
        return msg("backtothebase.language.changed", language);
    }

    public String returnEnabled(boolean enabled) {
        return msg(enabled ? "backtothebase.return.enabled" : "backtothebase.return.disabled");
    }

    public String coordinatesMustBeIntegers() {
        return msg("backtothebase.coordinates.integer");
    }

    public String numberAndCoordinatesMustBeIntegers() {
        return msg("backtothebase.number_coordinates.integer");
    }

    public String returnPointSet(int x, int y, int z) {
        return msg("backtothebase.return_point.set", x, y, z);
    }

    public String playerExists(String playerName) {
        return msg("backtothebase.player.exists", playerName);
    }

    public String playerMissing(String playerName) {
        return msg("backtothebase.player.missing", playerName);
    }

    public String playerAdded(String playerName) {
        return msg("backtothebase.player.added", playerName);
    }

    public String waitingRemovePlayer(String playerName, String confirmCommand) {
        return msg("backtothebase.player.remove.waiting", playerName, confirmCommand);
    }

    public String locationMissing(String number) {
        return msg("backtothebase.location.missing", number);
    }

    public String locationNotConfigured(String number) {
        return msg("backtothebase.location.not_configured", number);
    }

    public String cannotRemoveLastLocation(String playerName) {
        return msg("backtothebase.location.last", playerName);
    }

    public String waitingRemoveLocation(String playerName, String number, String confirmCommand) {
        return msg("backtothebase.location.remove.waiting", playerName, number, confirmCommand);
    }

    public String locationExists(String number) {
        return msg("backtothebase.location.exists", number);
    }

    public String locationAddResult(boolean createdPlayer, String playerName, String number, int x, int y, int z) {
        return msg(createdPlayer ? "backtothebase.location.add.created_player" : "backtothebase.location.added",
                playerName, number, x, y, z);
    }

    public String locationSetCreatedPlayer(String playerName, String number, int x, int y, int z) {
        return msg("backtothebase.location.set.created_player", playerName, number, x, y, z);
    }

    public String locationSetResult(boolean existed, String playerName, String number, int x, int y, int z) {
        return msg(existed ? "backtothebase.location.updated" : "backtothebase.location.created",
                playerName, number, x, y, z);
    }

    public String adminExists(String playerName) {
        return msg("backtothebase.admin.exists", playerName);
    }

    public String adminMissing(String playerName) {
        return msg("backtothebase.admin.missing", playerName);
    }

    public String adminLimit(int maxAdmins, String playerName) {
        return msg("backtothebase.admin.limit", maxAdmins, playerName);
    }

    public String adminAdded(String playerName) {
        return msg("backtothebase.admin.added", playerName);
    }

    public String adminRemoved(String playerName) {
        return msg("backtothebase.admin.removed", playerName);
    }

    public String adminEnabled(boolean enabled) {
        return msg(enabled ? "backtothebase.admin.enabled" : "backtothebase.admin.disabled");
    }

    public String noPendingAction() {
        return msg("backtothebase.pending.none");
    }

    public String pendingExpired() {
        return msg("backtothebase.pending.expired");
    }

    public String confirmedRemovePlayer(String playerName) {
        return msg("backtothebase.player.removed.confirmed", playerName);
    }

    public String confirmedRemoveLocation(String playerName, String number) {
        return msg("backtothebase.location.removed.confirmed", playerName, number);
    }

    public String statusHeader() {
        return msg("backtothebase.status.header");
    }

    public String returnFeature(boolean enabled) {
        return msg(enabled ? "backtothebase.status.return.running" : "backtothebase.status.return.stopped");
    }

    public String returnLocation(int x, int y, int z) {
        return msg("backtothebase.status.return_location", x, y, z);
    }

    public String adminFeature(boolean enabled) {
        return msg(enabled ? "backtothebase.status.admin.running" : "backtothebase.status.admin.stopped");
    }

    public String adminCount(int count, int maxAdmins) {
        return msg("backtothebase.status.admin_count", count, maxAdmins);
    }

    public String playerCount(int count) {
        return msg("backtothebase.status.player_count", count);
    }

    public String locationCount(int count) {
        return msg("backtothebase.status.location_count", count);
    }

    public String playerDataHeader() {
        return msg("backtothebase.status.player_data");
    }

    public String playerDataLine(String playerName, int locationCount) {
        return msg("backtothebase.status.player_data.line", playerName, locationCount);
    }

    public String divider() {
        return msg("backtothebase.divider");
    }

    public String gameStatus(boolean returnEnabled, int x, int y, int z, boolean adminEnabled, int admins, int maxAdmins, int players, int locations) {
        return msg("backtothebase.status.game",
                enabledText(returnEnabled), x, y, z, enabledText(adminEnabled), admins, maxAdmins, players, locations);
    }

    public String playerListHeader() {
        return msg("backtothebase.player_list.header");
    }

    public String noPlayers() {
        return msg("backtothebase.players.none");
    }

    public String noPlayersConsole() {
        return msg("backtothebase.players.none.console");
    }

    public String playerLocationData(String playerName, String numbers) {
        return msg("backtothebase.player.location_data", playerName, numbers);
    }

    public String gamePlayerList(String players) {
        return msg("backtothebase.player_list.game", players);
    }

    public String gamePlayerEntry(String playerName, String numbers) {
        return msg("backtothebase.player_list.entry", playerName, numbers);
    }

    public String locationListHeader(String playerName) {
        return msg("backtothebase.location_list.header", playerName);
    }

    public String locationLine(String number, int x, int y, int z) {
        return msg("backtothebase.location_list.line", number, x, y, z);
    }

    public String gameLocationList(String playerName, String locations) {
        return msg("backtothebase.location_list.game", playerName, locations);
    }

    public String enabledText(boolean enabled) {
        return msg(enabled ? "backtothebase.enabled" : "backtothebase.disabled");
    }

    public String movementSyncDisabled() {
        return msg("backtothebase.movementsync.disabled");
    }

    public String movementSyncInvalid() {
        return msg("backtothebase.movementsync.invalid");
    }

    public String actionAlreadyRunning() {
        return msg("backtothebase.action.running");
    }

    public String backStarted(String number) {
        return msg("backtothebase.back.started", number);
    }

    private String msg(String key, Object... args) {
        loadLanguage(language);
        return LangManager.get(key, args);
    }
}

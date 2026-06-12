package huangdihd.xinbot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoadTest {
    private static final Path CONFIG_PATH = Path.of(BackToTheBase.config_name);
    private static final Path BACKUP_PATH = Path.of(BackToTheBase.config_name + ".bak");

    private BackToTheBase plugin;

    @BeforeEach
    void setUp() throws IOException {
        deleteConfigFiles();
        plugin = new BackToTheBase();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteConfigFiles();
    }

    private void deleteConfigFiles() throws IOException {
        Files.deleteIfExists(CONFIG_PATH);
        Files.deleteIfExists(BACKUP_PATH);
    }

    private void writeConfig(String json) throws IOException {
        Files.writeString(CONFIG_PATH, json);
    }

    @Test
    void readmeFormatLoadsPlayersReturnAndAdmin() throws IOException {
        writeConfig("""
                {
                  "players": {
                    "Steve": {
                      "locations": [
                        {"number": "1", "x": 100, "y": 64, "z": 200},
                        {"number": "2", "x": 120, "y": 65, "z": 210}
                      ]
                    }
                  },
                  "return": {
                    "enabled": true,
                    "location": {"x": 1, "y": 60, "z": -1}
                  },
                  "admin": {
                    "enabled": true,
                    "players": ["A", "B"]
                  }
                }
                """);

        assertTrue(plugin.reloadConfig());

        PlayerBaseConfig steve = plugin.getPlayerConfigs().get("Steve");
        assertNotNull(steve);
        assertEquals(2, steve.getLocations().size());
        assertEquals(100, steve.getLocation("1").getX());
        assertEquals(210, steve.getLocation("2").getZ());

        assertTrue(plugin.getBaseConfig().getReturnConfig().isEnabled());
        assertEquals(1, plugin.getBaseConfig().getReturnConfig().getLocation().getX());
        assertEquals(-1, plugin.getBaseConfig().getReturnConfig().getLocation().getZ());

        assertTrue(plugin.getBaseConfig().getAdmin().isEnabled());
        assertEquals(List.of("A", "B"), plugin.getBaseConfig().getAdmin().getPlayers());
    }

    @Test
    void legacySimpleFormatMigratesOnReload() throws IOException {
        writeConfig("""
                {
                  "Steve": {
                    "x": 100, "y": 64, "z": 200,
                    "returnAfterUse": true,
                    "returnLocation": {"x": 0, "y": 60, "z": 0}
                  }
                }
                """);

        assertTrue(plugin.reloadConfig());

        PlayerBaseConfig steve = plugin.getPlayerConfigs().get("Steve");
        assertNotNull(steve);
        ButtonLocation location = steve.getLocation("1");
        assertNotNull(location);
        assertEquals(100, location.getX());
        assertEquals(64, location.getY());
        assertEquals(200, location.getZ());

        assertTrue(plugin.getBaseConfig().getReturnConfig().isEnabled());
        assertEquals(60, plugin.getBaseConfig().getReturnConfig().getLocation().getY());
    }

    @Test
    void invalidJsonKeepsPreviousConfig() throws IOException {
        writeConfig("""
                {"players": {"Steve": {"locations": [{"number": "1", "x": 1, "y": 2, "z": 3}]}}}
                """);
        assertTrue(plugin.reloadConfig());
        assertTrue(plugin.getPlayerConfigs().containsKey("Steve"));

        writeConfig("not json at all {{{");
        assertFalse(plugin.reloadConfig());
        assertTrue(plugin.getPlayerConfigs().containsKey("Steve"));
    }

    @Test
    void missingFileFailsReloadAndKeepsPreviousConfig() throws IOException {
        writeConfig("""
                {"players": {"Steve": {"locations": [{"number": "1", "x": 1, "y": 2, "z": 3}]}}}
                """);
        assertTrue(plugin.reloadConfig());

        deleteConfigFiles();
        assertFalse(plugin.reloadConfig());
        assertTrue(plugin.getPlayerConfigs().containsKey("Steve"));
    }

    @Test
    void nonStringAdminEntriesAreSkipped() throws IOException {
        writeConfig("""
                {
                  "players": {
                    "Steve": {"locations": [{"number": "1", "x": 1, "y": 2, "z": 3}]}
                  },
                  "return": {"enabled": false, "location": {"x": 0, "y": 60, "z": 0}},
                  "admin": {"enabled": true, "players": ["A", 123, true, "B"]}
                }
                """);

        assertTrue(plugin.reloadConfig());
        assertEquals(List.of("A", "B"), plugin.getBaseConfig().getAdmin().getPlayers());
    }

    @Test
    void adminListIsCappedAtThreePlayers() throws IOException {
        writeConfig("""
                {
                  "players": {
                    "Steve": {"locations": [{"number": "1", "x": 1, "y": 2, "z": 3}]}
                  },
                  "return": {"enabled": false, "location": {"x": 0, "y": 60, "z": 0}},
                  "admin": {"enabled": true, "players": ["A", "B", "C", "D"]}
                }
                """);

        assertTrue(plugin.reloadConfig());
        assertEquals(List.of("A", "B", "C"), plugin.getBaseConfig().getAdmin().getPlayers());
    }

    @Test
    void duplicateLocationNumberIsRejectedOnReload() throws IOException {
        writeConfig("""
                {"players": {"Steve": {"locations": [{"number": "1", "x": 1, "y": 2, "z": 3}]}}}
                """);
        assertTrue(plugin.reloadConfig());

        writeConfig("""
                {
                  "players": {
                    "Steve": {
                      "locations": [
                        {"number": "1", "x": 1, "y": 2, "z": 3},
                        {"number": "1", "x": 9, "y": 9, "z": 9}
                      ]
                    }
                  },
                  "return": {"enabled": false, "location": {"x": 0, "y": 60, "z": 0}}
                }
                """);
        assertFalse(plugin.reloadConfig());
        assertEquals(1, plugin.getPlayerConfigs().get("Steve").getLocation("1").getX());
    }

    @Test
    void initialLoadMigratesLegacyConfigAndWritesBackup() throws IOException {
        writeConfig("""
                {
                  "Steve": {"x": 100, "y": 64, "z": 200}
                }
                """);

        plugin.onLoad();

        assertTrue(Files.exists(BACKUP_PATH));
        String rewritten = Files.readString(CONFIG_PATH);
        assertTrue(rewritten.contains("\"players\""));
        assertTrue(rewritten.contains("\"Steve\""));
        assertEquals(100, plugin.getPlayerConfigs().get("Steve").getLocation("1").getX());
    }

    @Test
    void initialLoadKeepsValidEntriesWhenSomeAreInvalid() throws IOException {
        writeConfig("""
                {
                  "players": {
                    "Good": {"locations": [{"number": "1", "x": 1, "y": 2, "z": 3}]},
                    "Bad": {"locations": [{"number": "1", "x": 1}]}
                  },
                  "return": {"enabled": false, "location": {"x": 0, "y": 60, "z": 0}}
                }
                """);

        plugin.onLoad();

        assertTrue(plugin.getPlayerConfigs().containsKey("Good"));
        assertFalse(plugin.getPlayerConfigs().containsKey("Bad"));
    }
}

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCommandTest {
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
        Files.deleteIfExists(Path.of(BackToTheBase.config_name));
        Files.deleteIfExists(Path.of(BackToTheBase.config_name + ".bak"));
    }

    private List<String> console(String... args) {
        return plugin.handleManagementCommand("console", true, args);
    }

    private List<String> game(String sender, String... args) {
        return plugin.handleManagementCommand(sender, false, args);
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void unknownCommandIsRejected() {
        assertTrue(joined(console("foo")).contains("未知命令"));
        assertTrue(joined(console()).contains("未知命令"));
    }

    @Test
    void returnEnableTogglesConfigAndPersists() {
        assertTrue(joined(console("returnenable", "true")).contains("已开启"));
        assertTrue(plugin.getBaseConfig().getReturnConfig().isEnabled());
        assertTrue(Files.exists(Path.of(BackToTheBase.config_name)));

        assertTrue(joined(console("returnenable", "false")).contains("已关闭"));
        assertFalse(plugin.getBaseConfig().getReturnConfig().isEnabled());

        assertTrue(joined(console("returnenable", "maybe")).contains("用法"));
        assertTrue(joined(console("returnenable")).contains("用法"));
    }

    @Test
    void returnPointSetsCoordinates() {
        assertTrue(joined(console("returnpoint", "10", "64", "-20")).contains("已设置返回坐标"));
        PlayerBaseConfig.ReturnLocation location = plugin.getBaseConfig().getReturnConfig().getLocation();
        assertEquals(10, location.getX());
        assertEquals(64, location.getY());
        assertEquals(-20, location.getZ());

        assertTrue(joined(console("returnpoint", "a", "b", "c")).contains("必须是整数"));
        assertTrue(joined(console("returnpoint", "1", "2")).contains("用法"));
    }

    @Test
    void playerAddRemoveConfirmFlow() {
        assertTrue(joined(console("player", "add", "Steve")).contains("已添加玩家"));
        assertTrue(plugin.getPlayerConfigs().containsKey("Steve"));
        assertTrue(joined(console("player", "add", "Steve")).contains("已存在"));

        assertTrue(joined(console("player", "list")).contains("Steve"));

        assertTrue(joined(console("player", "remove", "Steve")).contains("等待确认"));
        assertTrue(plugin.getPlayerConfigs().containsKey("Steve"));
        assertTrue(joined(console("confirm")).contains("删除玩家 Steve"));
        assertFalse(plugin.getPlayerConfigs().containsKey("Steve"));

        assertTrue(joined(console("confirm")).contains("没有需要确认的操作"));
        assertTrue(joined(console("player", "remove", "Nobody")).contains("不存在"));
    }

    @Test
    void locAddSetAndValidation() {
        assertTrue(joined(console("loc", "add", "Steve", "1", "100", "64", "200")).contains("已创建玩家 Steve"));
        ButtonLocation location = plugin.getPlayerConfigs().get("Steve").getLocation("1");
        assertNotNull(location);
        assertEquals(100, location.getX());

        assertTrue(joined(console("loc", "add", "Steve", "1", "0", "0", "0")).contains("已存在"));
        assertEquals(100, plugin.getPlayerConfigs().get("Steve").getLocation("1").getX());

        assertTrue(joined(console("loc", "set", "Steve", "1", "5", "6", "7")).contains("已更新"));
        assertEquals(5, plugin.getPlayerConfigs().get("Steve").getLocation("1").getX());

        assertTrue(joined(console("loc", "add", "Steve", "0", "1", "2", "3")).contains("必须是整数"));
        assertTrue(joined(console("loc", "add", "Steve", "2", "x", "2", "3")).contains("必须是整数"));
        assertTrue(joined(console("loc", "list", "Steve")).contains("珍珠坐标"));
        assertTrue(joined(console("loc", "list", "Nobody")).contains("不存在"));
    }

    @Test
    void locRemoveKeepsLastLocation() {
        console("loc", "add", "Steve", "1", "1", "2", "3");
        assertTrue(joined(console("loc", "remove", "Steve", "1")).contains("最后一个珍珠坐标"));

        console("loc", "add", "Steve", "2", "4", "5", "6");
        assertTrue(joined(console("loc", "remove", "Steve", "1")).contains("等待确认"));
        assertTrue(joined(console("confirm")).contains("删除 Steve 的珍珠坐标 1"));
        assertNull(plugin.getPlayerConfigs().get("Steve").getLocation("1"));
        assertNotNull(plugin.getPlayerConfigs().get("Steve").getLocation("2"));
    }

    @Test
    void confirmCannotRemoveLastLocationAcrossScopes() {
        console("loc", "add", "Steve", "1", "1", "2", "3");
        console("loc", "add", "Steve", "2", "4", "5", "6");

        assertTrue(joined(console("loc", "remove", "Steve", "1")).contains("等待确认"));
        assertTrue(joined(game("Admin", "loc", "remove", "Steve", "2")).contains("等待确认"));

        assertTrue(joined(console("confirm")).contains("删除 Steve 的珍珠坐标 1"));

        assertTrue(joined(game("Admin", "confirm")).contains("最后一个珍珠坐标"));
        assertNotNull(plugin.getPlayerConfigs().get("Steve").getLocation("2"));
    }

    @Test
    void adminCommandsAreConsoleOnly() {
        assertTrue(joined(game("Steve", "admin", "add", "Alex")).contains("只能在控制台使用"));
        assertTrue(joined(game("Steve", "adminenable", "true")).contains("只能在控制台使用"));
        assertFalse(plugin.getBaseConfig().getAdmin().isEnabled());
    }

    @Test
    void adminAddRespectsLimitAndRemoveWorks() {
        assertTrue(joined(console("admin", "add", "A")).contains("已添加管理员"));
        assertTrue(joined(console("admin", "add", "A")).contains("已存在"));
        console("admin", "add", "B");
        console("admin", "add", "C");
        assertTrue(joined(console("admin", "add", "D")).contains("上限"));
        assertEquals(List.of("A", "B", "C"), plugin.getBaseConfig().getAdmin().getPlayers());

        assertTrue(joined(console("admin", "remove", "B")).contains("已删除管理员"));
        assertEquals(List.of("A", "C"), plugin.getBaseConfig().getAdmin().getPlayers());
        assertTrue(joined(console("admin", "remove", "Nobody")).contains("不存在"));
    }

    @Test
    void adminEnableToggles() {
        assertTrue(joined(console("adminenable", "true")).contains("已开启"));
        assertTrue(plugin.getBaseConfig().getAdmin().isEnabled());
        assertTrue(joined(console("adminenable", "false")).contains("已关闭"));
        assertFalse(plugin.getBaseConfig().getAdmin().isEnabled());
        assertTrue(joined(console("adminenable", "yes")).contains("用法"));
    }

    @Test
    void statListsConfiguredState() {
        console("loc", "add", "Steve", "1", "1", "2", "3");
        String consoleStat = joined(console("stat"));
        assertTrue(consoleStat.contains("Steve"));
        assertTrue(consoleStat.contains("玩家数量: 1"));

        String gameStat = joined(game("Admin", "stat"));
        assertTrue(gameStat.contains("玩家=1"));
        assertTrue(gameStat.contains("珍珠坐标=1"));
    }
}

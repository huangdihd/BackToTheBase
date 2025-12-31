package huangdihd.xinbot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.Getter;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.plugin.Plugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;

public class BackToTheBase implements Plugin {
    @Getter
    private static BackToTheBase Instance;
    public Map<String, Button> buttons;
    public static final String config_name = "base_config.json";
    @Override
    public void onLoad() {
        Instance = this;

        File configFile = new File(config_name);
        if (!configFile.exists()) {
            try {
                if (!configFile.createNewFile()) {
                    getLogger().error("Failed to create config file");
                    System.exit(-1);
                }
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write("{}");
                }
            } catch (IOException e) {
                getLogger().error("Failed to create config file", e);
                System.exit(-1);
            }
        }
        if (configFile.isFile()) {
            try {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, Button>>() {}.getType();
                FileReader reader = new FileReader(configFile);
                buttons = gson.fromJson(reader, type);
                reader.close();
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
        Bot.Instance.getPluginManager().events().registerEvents(new OnPrivateChat(), this);
    }

    @Override
    public void onDisable() {

    }
}

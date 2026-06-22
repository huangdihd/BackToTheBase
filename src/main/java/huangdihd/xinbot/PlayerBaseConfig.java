package huangdihd.xinbot;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlayerBaseConfig {
    public static final int DEFAULT_RETURN_X = 0;
    public static final int DEFAULT_RETURN_Y = 60;
    public static final int DEFAULT_RETURN_Z = 0;

    private List<ButtonLocation> locations = new ArrayList<>();

    public PlayerBaseConfig() {
    }

    public PlayerBaseConfig(List<ButtonLocation> locations) {
        this.locations = locations;
    }

    public List<ButtonLocation> getLocations() {
        if (locations == null) {
            locations = new ArrayList<>();
        }
        return locations;
    }

    public void setLocations(List<ButtonLocation> locations) {
        this.locations = locations;
    }

    public ButtonLocation getLocation(String number) {
        if (number == null || locations == null) {
            return null;
        }
        for (ButtonLocation location : locations) {
            if (location != null && number.equals(location.getNumber())) {
                return location;
            }
        }
        return null;
    }

    public static class BaseConfig {
        private Map<String, PlayerBaseConfig> players = new LinkedHashMap<>();
        @SerializedName("return")
        private ReturnConfig returnConfig = new ReturnConfig();
        private AdminConfig admin = new AdminConfig();

        public Map<String, PlayerBaseConfig> getPlayers() {
            if (players == null) {
                players = new LinkedHashMap<>();
            }
            return players;
        }

        public void setPlayers(Map<String, PlayerBaseConfig> players) {
            this.players = players;
        }

        public ReturnConfig getReturnConfig() {
            if (returnConfig == null) {
                returnConfig = new ReturnConfig();
            }
            return returnConfig;
        }

        public void setReturnConfig(ReturnConfig returnConfig) {
            this.returnConfig = returnConfig;
        }

        public AdminConfig getAdmin() {
            if (admin == null) {
                admin = new AdminConfig();
            }
            return admin;
        }

        public void setAdmin(AdminConfig admin) {
            this.admin = admin;
        }
    }

    public static class ReturnConfig {
        private boolean enabled = false;
        private ReturnLocation location = new ReturnLocation();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ReturnLocation getLocation() {
            if (location == null) {
                location = new ReturnLocation();
            }
            return location;
        }

        public void setLocation(ReturnLocation location) {
            this.location = location;
        }
    }

    public static class ReturnLocation {
        private int x = DEFAULT_RETURN_X;
        private int y = DEFAULT_RETURN_Y;
        private int z = DEFAULT_RETURN_Z;

        public ReturnLocation() {
        }

        public ReturnLocation(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }

        public int getZ() {
            return z;
        }

        public void setZ(int z) {
            this.z = z;
        }
    }

    public static class AdminConfig {
        private boolean enabled = false;
        private List<String> players = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getPlayers() {
            if (players == null) {
                players = new ArrayList<>();
            }
            return players;
        }

        public void setPlayers(List<String> players) {
            this.players = players;
        }
    }
}

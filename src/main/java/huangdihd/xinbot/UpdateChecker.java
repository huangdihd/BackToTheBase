package huangdihd.xinbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UpdateChecker {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern SEMANTIC_VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final List<URI> DEFAULT_ENDPOINTS = List.of(
            URI.create("https://api.github.com/repos/huangdihd/BackToTheBase/releases/latest"),
            URI.create("https://cdn.jsdelivr.net/gh/huangdihd/BackToTheBase@main/version.json"),
            URI.create("https://cdn.staticdelivr.com/gh/huangdihd/BackToTheBase/main/version.json")
    );

    private final Logger logger;
    private final String currentVersion;
    private final List<URI> endpoints;
    private final HttpClient client;

    UpdateChecker(Logger logger, String currentVersion) {
        this(logger, currentVersion, DEFAULT_ENDPOINTS);
    }

    UpdateChecker(Logger logger, String currentVersion, List<URI> endpoints) {
        this.logger = logger;
        this.currentVersion = currentVersion;
        this.endpoints = List.copyOf(endpoints);
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    Thread startAsync() {
        Thread thread = new Thread(this::check, "BackToTheBase-UpdateChecker");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    Optional<ReleaseInfo> check() {
        for (URI endpoint : endpoints) {
            try {
                ReleaseInfo release = fetch(endpoint);
                if (isNewer(release.latestVersion(), currentVersion)) {
                    String releaseUrl = release.releaseUrl() != null
                            ? release.releaseUrl()
                            : release.downloadUrl();
                    logger.info("BackToTheBase {} is available: {}", release.latestVersion(), releaseUrl);
                    return Optional.of(release);
                }
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Try the next source. Update checks must never affect plugin startup.
            }
        }
        logger.warn("Unable to check for BackToTheBase updates from all configured sources.");
        return Optional.empty();
    }

    private ReleaseInfo fetch(URI endpoint) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BackToTheBase-UpdateChecker")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    private ReleaseInfo parse(String body) throws IOException {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IOException("Update response must be a JSON object");
        }
        JsonObject json = parsed.getAsJsonObject();
        String latestVersion = firstString(json, "tag_name", "latest_version");
        if (latestVersion == null) {
            throw new IOException("Update response is missing a version");
        }

        String downloadUrl = string(json, "download_url");
        JsonElement assets = json.get("assets");
        if (downloadUrl == null && assets != null && assets.isJsonArray() && !assets.getAsJsonArray().isEmpty()) {
            JsonElement firstAsset = assets.getAsJsonArray().get(0);
            if (firstAsset.isJsonObject()) {
                downloadUrl = string(firstAsset.getAsJsonObject(), "browser_download_url");
            }
        }
        return new ReleaseInfo(
                latestVersion,
                downloadUrl,
                firstString(json, "html_url", "release_url")
        );
    }

    private static String firstString(JsonObject json, String first, String second) {
        String value = string(json, first);
        return value != null ? value : string(json, second);
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    static boolean isNewer(String latestVersion, String currentVersion) {
        int[] latest = semanticVersion(latestVersion);
        int[] current = semanticVersion(currentVersion);
        if (latest == null || current == null) {
            return false;
        }
        for (int i = 0; i < latest.length; i++) {
            if (latest[i] != current[i]) {
                return latest[i] > current[i];
            }
        }
        return currentVersion.trim().toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT")
                && latestVersion.trim().matches("(?i)v?\\d+\\.\\d+\\.\\d+(?:-RELEASE)?");
    }

    private static int[] semanticVersion(String version) {
        Matcher matcher = SEMANTIC_VERSION.matcher(version);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record ReleaseInfo(String latestVersion, String downloadUrl, String releaseUrl) {
    }
}

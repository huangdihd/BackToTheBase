package huangdihd.xinbot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void githubReleaseResponseFindsNewVersion() {
        addResponse("/github", 200, """
                {
                  "tag_name": "v1.8.1",
                  "html_url": "https://github.com/huangdihd/BackToTheBase/releases/tag/v1.8.1"
                }
                """, new AtomicInteger());

        Optional<UpdateChecker.ReleaseInfo> result = checker("/github").check();

        assertEquals("v1.8.1", result.orElseThrow().latestVersion());
        assertEquals(
                "https://github.com/huangdihd/BackToTheBase/releases/tag/v1.8.1",
                result.orElseThrow().releaseUrl()
        );
    }

    @Test
    void failedGithubRequestFallsBackToStaticJson() {
        AtomicInteger githubRequests = new AtomicInteger();
        AtomicInteger fallbackRequests = new AtomicInteger();
        AtomicInteger lastRequests = new AtomicInteger();
        addResponse("/github", 503, "", githubRequests);
        addResponse("/fallback", 200, """
                {
                  "latest_version": "1.8.1-RELEASE",
                  "download_url": "https://example.test/BackToTheBase.jar",
                  "release_url": "https://example.test/release",
                  "updated_at": "2026-08-03T00:00:00Z"
                }
                """, fallbackRequests);
        addResponse("/last", 200, "{}", lastRequests);

        UpdateChecker checker = checker("/github", "/fallback", "/last");

        assertEquals("1.8.1-RELEASE", checker.check().orElseThrow().latestVersion());
        assertEquals(1, githubRequests.get());
        assertEquals(1, fallbackRequests.get());
        assertEquals(0, lastRequests.get());
    }

    @Test
    void allFailuresAreContained() {
        addResponse("/one", 500, "", new AtomicInteger());
        addResponse("/two", 429, "", new AtomicInteger());
        addResponse("/three", 200, "not json", new AtomicInteger());

        Optional<UpdateChecker.ReleaseInfo> result = assertDoesNotThrow(
                () -> checker("/one", "/two", "/three").check()
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void asynchronousCheckUsesDaemonThread() throws InterruptedException {
        addResponse("/current", 200, """
                {"latest_version": "1.8.0-RELEASE"}
                """, new AtomicInteger());

        Thread thread = checker("/current").startAsync();

        assertTrue(thread.isDaemon());
        thread.join(2_000);
        assertFalse(thread.isAlive());
    }

    private UpdateChecker checker(String... paths) {
        List<URI> endpoints = java.util.Arrays.stream(paths)
                .map(this::endpoint)
                .toList();
        return new UpdateChecker(
                LoggerFactory.getLogger(UpdateCheckerTest.class),
                "1.8.0-RELEASE",
                endpoints
        );
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void addResponse(String path, int status, String body, AtomicInteger requests) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }
}

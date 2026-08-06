package moe.vitamin.minecraft.mcp.orchestrator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches Paper server jars. */
public final class PaperDownloader {

    private static final String API = "https://fill.papermc.io/v3/projects/paper";

    /** Identifies this client, which the API asks for and its bot protection acts on. */
    private static final String USER_AGENT = "VitaminMCP/1.0 (minecraft plugin test harness)";

    /** First build entry in the list, which the API returns newest first. */
    private static final Pattern FIRST_BUILD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static final Pattern DEFAULT_DOWNLOAD = Pattern.compile(
            "\"server:default\"\\s*:\\s*\\{.*?\"sha256\"\\s*:\\s*\"([0-9a-f]+)\".*?\"url\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Path cacheDirectory;

    /** Caches into a directory of this class's choosing, shared across runs. */
    public PaperDownloader() {
        this(defaultCacheDirectory());
    }

    public PaperDownloader(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    /** Where jars are kept when the caller does not say. */
    public static Path defaultCacheDirectory() {
        String configured = System.getProperty("vitaminmcp.paperCache");
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".vitaminmcp", "paper");
    }

    /** Returns a Paper jar for {@code version}, downloading it if it is not already cached. */
    public Path fetch(String version, int build) throws IOException, InterruptedException {
        String listing = get(API + "/versions/" + version + "/builds");

        int resolved = build > 0 ? build : firstBuild(listing, version);
        Path jar = cacheDirectory.resolve("paper-" + version + "-" + resolved + ".jar");
        if (Files.exists(jar) && Files.size(jar) > 1_000_000) {
            return jar;
        }

        String buildDetail = build > 0
                ? get(API + "/versions/" + version + "/builds/" + resolved)
                : listing;
        Matcher download = DEFAULT_DOWNLOAD.matcher(buildDetail);
        if (!download.find()) {
            throw new IOException("Paper " + version + " build " + resolved
                    + " lists no server:default download");
        }
        String expectedSha = download.group(1);
        String url = download.group(2);

        Files.createDirectories(cacheDirectory);

        Path partial = Files.createTempFile(cacheDirectory, "paper-", ".part");
        HttpResponse<Path> response = http.send(
                request(url).timeout(Duration.ofMinutes(10)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(partial));

        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial);
            throw new IOException("Paper " + version + " build " + resolved
                    + " could not be downloaded (HTTP " + response.statusCode() + ")");
        }

        String actualSha = sha256(partial);
        if (!expectedSha.equalsIgnoreCase(actualSha)) {
            Files.deleteIfExists(partial);
            throw new IOException("Downloaded Paper " + version + " build " + resolved
                    + " does not match its published checksum");
        }

        Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    private static int firstBuild(String listing, String version) throws IOException {
        Matcher build = FIRST_BUILD.matcher(listing);
        if (!build.find()) {
            throw new IOException("No builds listed for Paper " + version
                    + ". Is that a version PaperMC publishes?");
        }
        return Integer.parseInt(build.group(1));
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                request(url).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("PaperMC API returned HTTP " + response.statusCode()
                    + " for " + url);
        }
        return response.body();
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

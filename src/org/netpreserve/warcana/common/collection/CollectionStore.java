package org.netpreserve.warcana.common.collection;

import org.netpreserve.warcana.common.SocialJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Defines and persists an append-only Warcana collection directory.
 */
public class CollectionStore {
    private final Path root;

    public CollectionStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path accountsFile() {
        return root.resolve("accounts.txt");
    }

    public Path warcsDirectory() {
        return root.resolve("warcs");
    }

    public Path indexDirectory() {
        return root.resolve("index");
    }

    public Path resourceIndexDirectory() {
        return root.resolve("resource-index");
    }

    public Path logsDirectory() {
        return root.resolve("logs");
    }

    public Path stateDirectory() {
        return root.resolve("state");
    }

    public Path jobsDirectory() {
        return stateDirectory().resolve("jobs");
    }

    public Path collectionStateFile() {
        return stateDirectory().resolve("collection.json");
    }

    public Path lockFile() {
        return stateDirectory().resolve("collection.lock");
    }

    public void initialize() throws IOException {
        Files.createDirectories(warcsDirectory());
        Files.createDirectories(logsDirectory());
        Files.createDirectories(jobsDirectory());
        if (!Files.exists(accountsFile())) {
            Files.writeString(
                    accountsFile(),
                    """
                    # One account per line. Ambiguous handles require a platform prefix:
                    # mastodon @alice@example.social
                    # bluesky alice.bsky.social
                    """,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        }
        if (!Files.exists(collectionStateFile())) {
            saveCollectionState(new CollectionState());
        }
    }

    public boolean isCollection() {
        return Files.isRegularFile(accountsFile()) &&
                Files.isDirectory(warcsDirectory()) &&
                Files.isDirectory(stateDirectory());
    }

    public List<CollectionAccount> readAccounts() throws IOException {
        return parseAccounts(Files.readString(accountsFile(), StandardCharsets.UTF_8));
    }

    public synchronized void writeAccounts(String contents) throws IOException {
        parseAccounts(contents);
        Path temporary = accountsFile().resolveSibling("accounts.txt.tmp");
        Files.writeString(
                temporary, contents, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(temporary, accountsFile(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, accountsFile(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static List<CollectionAccount> parseAccounts(String contents)
            throws IOException {
        var accounts = new ArrayList<CollectionAccount>();
        int lineNumber = 0;
        for (String raw : contents.lines().toList()) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String platform = null;
            String account = line;
            int separator = firstWhitespace(line);
            if (separator > 0) {
                String candidate = line.substring(0, separator).toLowerCase(Locale.ROOT);
                if ("mastodon".equals(candidate) || "bluesky".equals(candidate)) {
                    platform = candidate;
                    account = line.substring(separator).strip();
                }
            }
            if (account.isBlank()) {
                throw new IOException("Missing account in accounts.txt line " + lineNumber);
            }
            accounts.add(new CollectionAccount(platform, account));
        }
        return List.copyOf(accounts);
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    public CollectionState loadCollectionState() throws IOException {
        if (!Files.exists(collectionStateFile())) {
            return new CollectionState();
        }
        CollectionState state = SocialJson.mapper.readValue(
                collectionStateFile().toFile(), CollectionState.class);
        if (state.version != CollectionState.CURRENT_VERSION) {
            throw new IOException(
                    "Unsupported collection state version " + state.version +
                            " (expected " +
                            CollectionState.CURRENT_VERSION + ")");
        }
        return state;
    }

    public synchronized void saveCollectionState(CollectionState state) throws IOException {
        atomicJsonWrite(collectionStateFile(), state);
    }

    public Path jobFile(String key) {
        return jobsDirectory().resolve(key + ".json");
    }

    public CollectionJob loadJob(String key) throws IOException {
        Path path = jobFile(key);
        if (!Files.exists(path)) {
            return null;
        }
        CollectionJob job = SocialJson.mapper.readValue(
                path.toFile(), CollectionJob.class);
        if (job.version != CollectionJob.CURRENT_VERSION) {
            throw new IOException(
                    "Unsupported collection job version " + job.version +
                            " (expected " +
                            CollectionJob.CURRENT_VERSION + "): " + path);
        }
        return job;
    }

    public synchronized void saveJob(CollectionJob job) throws IOException {
        atomicJsonWrite(jobFile(job.key), job);
    }

    public void deleteJob(String key) throws IOException {
        Files.deleteIfExists(jobFile(key));
    }

    public List<Path> activeJobFiles() throws IOException {
        if (!Files.isDirectory(jobsDirectory())) {
            return List.of();
        }
        try (var stream = Files.list(jobsDirectory())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    public List<Path> completedWarcs() throws IOException {
        if (!Files.isDirectory(warcsDirectory())) {
            return List.of();
        }
        try (var stream = Files.list(warcsDirectory())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".warc") ||
                                name.endsWith(".warc.gz");
                    })
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
        }
    }

    public List<Path> completedPostWarcs() throws IOException {
        return completedWarcs().stream()
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return !name.startsWith("warcana-media-") &&
                            !name.startsWith("warcana-video-");
                })
                .toList();
    }

    public List<Path> completedResourceWarcs() throws IOException {
        return completedWarcs();
    }

    public void log(String message) throws IOException {
        Files.createDirectories(logsDirectory());
        String line = Instant.now() + " " + message + System.lineSeparator();
        Files.writeString(
                logsDirectory().resolve("archive.log"),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    public static String accountKey(String platform, String account) {
        String identity = platform.toLowerCase(Locale.ROOT) + "\n" +
                account.toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static String filenameComponent(String account) {
        String value = account.replaceFirst("^@", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return value.isBlank() ? "account" : value;
    }

    private static void atomicJsonWrite(Path destination, Object value) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        byte[] json = SocialJson.mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(value);
        Files.write(
                temporary,
                json,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

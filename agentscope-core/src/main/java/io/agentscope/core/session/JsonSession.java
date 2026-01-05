/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session;

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * JSON file-based session implementation.
 *
 * <p>This implementation stores session state as JSON files on the filesystem. Each session is
 * stored in a directory named by the session ID.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Multi-module session support
 *   <li>Atomic file operations
 *   <li>UTF-8 encoding
 *   <li>Graceful handling of missing sessions
 *   <li>Configurable storage directory
 * </ul>
 */
public class JsonSession implements Session {

    private final Path sessionDirectory;

    /**
     * Create a JsonSession with the default session directory.
     *
     * <p>Uses the user's home directory with ".agentscope/sessions" as the default storage location
     * for session files.
     */
    public JsonSession() {
        this(Paths.get(System.getProperty("user.home"), ".agentscope", "sessions"));
    }

    /**
     * Create a JsonSession with a custom session directory.
     *
     * @param sessionDirectory Directory to store session files
     */
    public JsonSession(Path sessionDirectory) {
        this.sessionDirectory = sessionDirectory;

        // Create directory if it doesn't exist
        try {
            Files.createDirectories(sessionDirectory);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to create session directory: " + sessionDirectory, e);
        }
    }

    /**
     * Save a single state value to a JSON file.
     *
     * <p>The state is stored in the session directory as {key}.json with pretty formatting.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "agent_meta", "toolkit_activeGroups")
     * @param value the state value to save
     */
    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        Path file = getStatePath(sessionKey, key);
        ensureDirectoryExists(file.getParent());

        try {
            String json = JsonUtils.getJsonCodec().toPrettyJson(value);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    /**
     * Save a list of state values to a JSONL file with hash-based change detection.
     *
     * <p>This method uses hash-based change detection to handle both append-only and mutable lists:
     *
     * <ul>
     *   <li>If the hash changes (list was modified), the entire file is rewritten
     *   <li>If the list shrinks, the entire file is rewritten
     *   <li>If the list only grows (append-only), only new items are appended
     *   <li>If nothing changes, the operation is skipped
     * </ul>
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the list of state values to save
     */
    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        Path file = getListPath(sessionKey, key);
        Path hashFile = getHashPath(sessionKey, key);
        ensureDirectoryExists(file.getParent());

        try {
            // Compute current hash
            String currentHash = ListHashUtil.computeHash(values);

            // Read stored hash (may be null if file doesn't exist)
            String storedHash = readHashFile(hashFile);

            // Get the count of already stored items
            long existingCount = countLines(file);

            // Determine if full rewrite is needed
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(
                            currentHash, storedHash, values.size(), (int) existingCount);

            if (needsFullRewrite) {
                // Full rewrite: delete existing file and write all items
                rewriteEntireList(file, values);
            } else if (values.size() > existingCount) {
                // Incremental append: only write new items
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                appendToList(file, newItems);
            }
            // else: no change, skip writing

            // Update hash file
            writeHashFile(hashFile, currentHash);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    /**
     * Rewrite the entire list file with all values.
     *
     * @param file the file to write to
     * @param values the values to write
     * @throws IOException if writing fails
     */
    private void rewriteEntireList(Path file, List<? extends State> values) throws IOException {
        // Delete existing file if it exists
        Files.deleteIfExists(file);

        // Write all items
        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE)) {

            for (State item : values) {
                String json = JsonUtils.getJsonCodec().toJson(item);
                writer.write(json);
                writer.newLine();
            }
        }
    }

    /**
     * Append items to an existing list file.
     *
     * @param file the file to append to
     * @param items the items to append
     * @throws IOException if writing fails
     */
    private void appendToList(Path file, List<? extends State> items) throws IOException {
        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {

            for (State item : items) {
                String json = JsonUtils.getJsonCodec().toJson(item);
                writer.write(json);
                writer.newLine();
            }
        }
    }

    /**
     * Get a single state value from a JSON file.
     *
     * @param sessionKey the session identifier
     * @param key the state key
     * @param type the expected state type
     * @param <T> the state type
     * @return the state value, or empty if not found
     */
    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        Path file = getStatePath(sessionKey, key);

        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load state: " + key, e);
        }
    }

    /**
     * Get a list of state values from a JSONL file.
     *
     * @param sessionKey the session identifier
     * @param key the state key
     * @param itemType the expected item type
     * @param <T> the item type
     * @return the list of state values, or empty list if not found
     */
    @Override
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        Path file = getListPath(sessionKey, key);

        if (!Files.exists(file)) {
            return List.of();
        }

        try {
            List<T> result = new ArrayList<>();

            // Read JSONL format - one JSON object per line
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        T item = JsonUtils.getJsonCodec().fromJson(line, itemType);
                        result.add(item);
                    }
                }
            }

            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load list: " + key, e);
        }
    }

    /**
     * Check if a session exists.
     *
     * @param sessionKey the session identifier
     * @return true if the session directory exists
     */
    @Override
    public boolean exists(SessionKey sessionKey) {
        return Files.exists(getSessionDir(sessionKey));
    }

    /**
     * Delete a session and all its data.
     *
     * @param sessionKey the session identifier
     */
    @Override
    public void delete(SessionKey sessionKey) {
        Path sessionDir = getSessionDir(sessionKey);
        if (Files.exists(sessionDir)) {
            deleteDirectory(sessionDir);
        }
    }

    /**
     * List all session keys.
     *
     * @return set of all session keys
     */
    @Override
    public Set<SessionKey> listSessionKeys() {
        if (!Files.exists(sessionDirectory)) {
            return Set.of();
        }

        try (Stream<Path> dirs = Files.list(sessionDirectory)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> SimpleSessionKey.of(p.getFileName().toString()))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /** Pattern for file-system safe characters: alphanumeric, underscore, hyphen, dot. */
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");

    /**
     * Get the directory path for a session.
     *
     * <p>Uses the session key's identifier. If the identifier contains only file-system safe
     * characters (alphanumeric, underscore, hyphen, dot), it is used directly. Otherwise, Base64
     * URL-safe encoding is applied for file system compatibility.
     *
     * @param sessionKey the session key
     * @return Path to the session directory
     */
    private Path getSessionDir(SessionKey sessionKey) {
        String identifier = sessionKey.toIdentifier();
        // If identifier contains special characters, encode it for file system safety
        if (!SAFE_FILENAME_PATTERN.matcher(identifier).matches()) {
            String encoded =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(identifier.getBytes(StandardCharsets.UTF_8));
            return sessionDirectory.resolve(encoded);
        }
        return sessionDirectory.resolve(identifier);
    }

    /**
     * Get the file path for a single state value.
     *
     * @param sessionKey the session key
     * @param key the state key
     * @return Path to the state file ({sessionDir}/{key}.json)
     */
    private Path getStatePath(SessionKey sessionKey, String key) {
        return getSessionDir(sessionKey).resolve(key + ".json");
    }

    /**
     * Get the file path for a list state value.
     *
     * @param sessionKey the session key
     * @param key the state key
     * @return Path to the list file ({sessionDir}/{key}.jsonl)
     */
    private Path getListPath(SessionKey sessionKey, String key) {
        return getSessionDir(sessionKey).resolve(key + ".jsonl");
    }

    /**
     * Get the file path for a list hash file.
     *
     * @param sessionKey the session key
     * @param key the state key
     * @return Path to the hash file ({sessionDir}/{key}.hash)
     */
    private Path getHashPath(SessionKey sessionKey, String key) {
        return getSessionDir(sessionKey).resolve(key + ".hash");
    }

    /**
     * Read the stored hash from a hash file.
     *
     * @param hashFile the hash file path
     * @return the stored hash, or null if file doesn't exist
     */
    private String readHashFile(Path hashFile) {
        if (!Files.exists(hashFile)) {
            return null;
        }
        try {
            return Files.readString(hashFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            // If we can't read the hash, treat as no hash (will trigger full rewrite)
            return null;
        }
    }

    /**
     * Write a hash value to a hash file.
     *
     * @param hashFile the hash file path
     * @param hash the hash value to write
     * @throws IOException if writing fails
     */
    private void writeHashFile(Path hashFile, String hash) throws IOException {
        Files.writeString(hashFile, hash, StandardCharsets.UTF_8);
    }

    /**
     * Count the number of non-blank lines in a file.
     *
     * @param file the file to count lines in
     * @return number of non-blank lines, or 0 if file doesn't exist
     */
    private long countLines(Path file) {
        if (!Files.exists(file)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Ensure a directory exists, creating it if necessary.
     *
     * @param dir the directory to ensure exists
     */
    private void ensureDirectoryExists(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     *
     * @param dir the directory to delete
     */
    private void deleteDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder()) // Delete files before directories
                            .forEach(
                                    path -> {
                                        try {
                                            Files.delete(path);
                                        } catch (IOException e) {
                                            throw new RuntimeException(
                                                    "Failed to delete: " + path, e);
                                        }
                                    });
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete directory: " + dir, e);
        }
    }

    /**
     * Get the session directory path.
     *
     * @return Path to the session directory
     */
    public Path getSessionDirectory() {
        return sessionDirectory;
    }

    /**
     * Clear all sessions (for testing or cleanup).
     *
     * @return Mono that completes when all sessions are deleted
     */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try {
                                if (!Files.exists(sessionDirectory)) {
                                    return 0;
                                }

                                int deletedCount = 0;
                                try (Stream<Path> files = Files.list(sessionDirectory)) {
                                    for (Path file : files.filter(Files::isDirectory).toList()) {
                                        deleteDirectory(file);
                                        deletedCount++;
                                    }
                                }
                                return deletedCount;
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

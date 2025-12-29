/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.state.StateModule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * JSON file-based session implementation.
 *
 * This implementation stores session state as JSON files on the filesystem.
 * Each session is stored as a single JSON file named by the session ID.
 *
 * Features:
 * - Multi-module session support
 * - Atomic file operations
 * - UTF-8 encoding
 * - Graceful handling of missing sessions
 * - Configurable storage directory
 */
public class JsonSession implements Session {

    private final Path sessionDirectory;
    private final ObjectMapper objectMapper;

    /**
     * Create a JsonSession with the default session directory.
     *
     * Uses the user's home directory with ".agentscope/sessions" as the default
     * storage location for session files.
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
        this.objectMapper = new ObjectMapper();

        // Create directory if it doesn't exist
        try {
            Files.createDirectories(sessionDirectory);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to create session directory: " + sessionDirectory, e);
        }
    }

    // ==================== New API (Recommended) ====================

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
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    /**
     * Save a list of state values to a JSONL file (incremental append).
     *
     * <p>Only new elements (beyond what's already persisted) are appended to the file. This enables
     * efficient incremental storage for large lists like message histories.
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the list of state values to save
     */
    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        Path file = getListPath(sessionKey, key);
        ensureDirectoryExists(file.getParent());

        try {
            // Get the count of already stored items
            long existingCount = countLines(file);

            // Only append new elements
            if (values.size() > existingCount) {
                List<? extends State> newItems = values.subList((int) existingCount, values.size());

                // Append mode - write each item as a JSON line
                try (BufferedWriter writer =
                        Files.newBufferedWriter(
                                file,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND)) {

                    for (State item : newItems) {
                        String json = objectMapper.writeValueAsString(item);
                        writer.write(json);
                        writer.newLine();
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save list: " + key, e);
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
            return Optional.of(objectMapper.readValue(json, type));
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
                        T item = objectMapper.readValue(line, itemType);
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

    // ==================== Legacy API (Deprecated) ====================

    /**
     * Save the state of multiple StateModules to a JSON file.
     *
     * This implementation persists the state of all provided StateModules as a single
     * JSON file named by the session ID. The method collects state dictionaries from
     * all modules and writes them to the file with pretty formatting.
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances
     * @throws RuntimeException if file I/O operations fail
     */
    @Override
    public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
        validateSessionId(sessionId);

        try {
            // Collect state from all modules
            Map<String, Object> sessionState = new HashMap<>();
            for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                sessionState.put(entry.getKey(), entry.getValue().stateDict());
            }

            // Write to JSON file atomically
            Path sessionFile = getSessionPath(sessionId);

            // Write session state directly to JSON file
            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(sessionFile.toFile(), sessionState);

        } catch (IOException e) {
            throw new RuntimeException("Failed to save session: " + sessionId, e);
        }
    }

    /**
     * Load session state from a JSON file into multiple StateModules.
     *
     * This implementation restores the state of all provided StateModules from a
     * JSON file. The method reads the JSON file, extracts component states, and
     * loads them into the corresponding StateModule instances using non-strict loading.
     *
     * @param sessionId Unique identifier for the session
     * @param allowNotExist Whether to allow loading from non-existent sessions
     * @param stateModules Map of component names to StateModule instances to load into
     * @throws RuntimeException if file I/O operations fail or session doesn't exist when allowNotExist is false
     */
    @Override
    public void loadSessionState(
            String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules) {
        validateSessionId(sessionId);

        Path sessionFile = getSessionPath(sessionId);

        if (!Files.exists(sessionFile)) {
            if (allowNotExist) {
                return; // Silently ignore missing session
            } else {
                throw new RuntimeException("Session not found: " + sessionId);
            }
        }

        try {
            // Read session state from JSON file
            @SuppressWarnings("unchecked")
            Map<String, Object> sessionState =
                    objectMapper.readValue(sessionFile.toFile(), Map.class);

            // Load state into each module
            for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                String componentName = entry.getKey();
                StateModule module = entry.getValue();

                if (sessionState.containsKey(componentName)) {
                    Object componentState = sessionState.get(componentName);
                    if (componentState instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> componentStateMap =
                                (Map<String, Object>) componentState;
                        module.loadStateDict(componentStateMap, false); // Use non-strict loading
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to load session: " + sessionId, e);
        }
    }

    /**
     * Check if a session JSON file exists in storage.
     *
     * This implementation checks for the existence of the JSON file
     * corresponding to the given session ID.
     *
     * @param sessionId Unique identifier for the session
     * @return true if the session JSON file exists
     */
    @Override
    public boolean sessionExists(String sessionId) {
        validateSessionId(sessionId);
        return Files.exists(getSessionPath(sessionId));
    }

    /**
     * Delete a session JSON file from storage.
     *
     * This implementation removes the JSON file corresponding to the given
     * session ID from the filesystem.
     *
     * @param sessionId Unique identifier for the session
     * @return true if the session file was deleted, false if it didn't exist
     * @throws RuntimeException if file I/O operations fail
     */
    @Override
    public boolean deleteSession(String sessionId) {
        validateSessionId(sessionId);

        try {
            Path sessionFile = getSessionPath(sessionId);
            return Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete session: " + sessionId, e);
        }
    }

    /**
     * Get a list of all session IDs from JSON files in the session directory.
     *
     * This implementation scans the session directory for JSON files and
     * returns their filenames (without the .json extension) as session IDs,
     * sorted alphabetically.
     *
     * @return List of session IDs, or empty list if no sessions exist
     * @throws RuntimeException if file I/O operations fail
     */
    @Override
    public List<String> listSessions() {
        try {
            if (!Files.exists(sessionDirectory)) {
                return List.of();
            }

            try (Stream<Path> files = Files.list(sessionDirectory)) {
                return files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".json"))
                        .map(
                                path -> {
                                    String fileName = path.getFileName().toString();
                                    return fileName.substring(
                                            0, fileName.length() - 5); // Remove .json
                                    // extension
                                })
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /**
     * Get information about a session from its JSON file.
     *
     * This implementation reads the session JSON file to determine file size,
     * last modification time, and the number of state components stored in the session.
     *
     * @param sessionId Unique identifier for the session
     * @return Session information including size, last modified time, and component count
     * @throws RuntimeException if file I/O operations fail or session doesn't exist
     */
    @Override
    public SessionInfo getSessionInfo(String sessionId) {
        validateSessionId(sessionId);

        Path sessionFile = getSessionPath(sessionId);
        if (!Files.exists(sessionFile)) {
            throw new RuntimeException("Session not found: " + sessionId);
        }

        try {
            long size = Files.size(sessionFile);
            long lastModified = Files.getLastModifiedTime(sessionFile).toMillis();

            // Count components by reading the JSON file
            @SuppressWarnings("unchecked")
            Map<String, Object> sessionState =
                    objectMapper.readValue(sessionFile.toFile(), Map.class);
            int componentCount = sessionState.size();

            return new SessionInfo(sessionId, size, lastModified, componentCount);

        } catch (IOException e) {
            throw new RuntimeException("Failed to get session info: " + sessionId, e);
        }
    }

    /**
     * Validate a session ID format.
     *
     * @param sessionId Session ID to validate
     * @throws IllegalArgumentException if session ID is invalid
     */
    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        if (sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("Session ID cannot contain path separators");
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
        }
    }

    /**
     * Get the file path for a session.
     *
     * @param sessionId Session ID
     * @return Path to the session file
     */
    private Path getSessionPath(String sessionId) {
        return sessionDirectory.resolve(sessionId + ".json");
    }

    /**
     * Get the session directory path.
     *
     * @return Path to the session directory
     */
    public Path getSessionDirectory() {
        return sessionDirectory;
    }

    // ==================== Helper Methods for New API ====================

    /**
     * Get the directory path for a session.
     *
     * <p>For SimpleSessionKey, uses the sessionId directly as the directory name. For other
     * SessionKey types, serializes to JSON as the directory name.
     *
     * @param sessionKey the session key
     * @return Path to the session directory
     */
    private Path getSessionDir(SessionKey sessionKey) {
        if (sessionKey instanceof SimpleSessionKey simple) {
            return sessionDirectory.resolve(simple.sessionId());
        }
        // For custom SessionKey types, use JSON serialization
        try {
            String keyJson = objectMapper.writeValueAsString(sessionKey);
            // Sanitize for filesystem (replace invalid chars)
            String sanitized = keyJson.replaceAll("[/\\\\:*?\"<>|]", "_");
            return sessionDirectory.resolve(sanitized);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize SessionKey", e);
        }
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
                    paths.sorted((a, b) -> -a.compareTo(b)) // Delete files before directories
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
                                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                                        if (file.toString().endsWith(".json")) {
                                            Files.delete(file);
                                            deletedCount++;
                                        }
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

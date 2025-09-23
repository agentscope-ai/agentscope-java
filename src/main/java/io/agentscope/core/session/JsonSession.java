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
import io.agentscope.core.state.StateModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * JSON file-based session implementation.
 *
 * This implementation stores session state as JSON files on the filesystem,
 * following the Python agentscope JSONSession pattern. Each session is
 * stored as a single JSON file named by the session ID.
 *
 * Features:
 * - Multi-module session support
 * - Atomic file operations
 * - UTF-8 encoding
 * - Graceful handling of missing sessions
 * - Configurable storage directory
 */
public class JsonSession extends SessionBase {

    private final Path sessionDirectory;
    private final ObjectMapper objectMapper;

    /**
     * Create a JsonSession with the default session directory.
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

            // Write to temporary file first
            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(sessionFile.toFile(), sessionState);

        } catch (IOException e) {
            throw new RuntimeException("Failed to save session: " + sessionId, e);
        }
    }

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

    @Override
    public boolean sessionExists(String sessionId) {
        validateSessionId(sessionId);
        return Files.exists(getSessionPath(sessionId));
    }

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
                        .collect(java.util.stream.Collectors.toList());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

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

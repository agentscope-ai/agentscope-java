/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.message.Msg;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Local file-based implementation of ContextOffLoader.
 *
 * <p>Stores offloaded context messages as JSON files in a local directory.
 * Uses Jackson ObjectMapper for serialization and deserialization.
 *
 * <p>The ObjectMapper is configured to handle polymorphic types (ContentBlock subtypes)
 * using the @JsonTypeInfo annotations defined in the message classes.
 */
public class LocalFileContextOffLoader implements ContextOffLoader {

    private final String baseDir;

    /**
     * Creates a new LocalFileContextOffLoader with the specified base directory.
     *
     * @param baseDir the base directory where offloaded context files will be stored
     */
    public LocalFileContextOffLoader(String baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Offloads messages to a local file.
     *
     * <p>The messages are serialized to JSON and written to a file named after the UUID
     * in the base directory. If a file with the same UUID already exists, it will be
     * replaced. Parent directories are created automatically if they don't exist.
     *
     * @param uuid the unique identifier for this offloaded context
     * @param messages the messages to offload
     * @throws RuntimeException if file operations fail
     */
    @Override
    public void offload(String uuid, List<Msg> messages) {
        try {
            Path file = getPath(uuid);

            // Delete existing file if it exists
            if (Files.exists(file)) {
                Files.delete(file);
            }

            // Ensure parent directory exists
            Path parentDir = file.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // Serialize messages to JSON using Jackson
            List<String> json = (List<String>) MsgUtils.serializeMsgList(messages);
            Files.writeString(file, MsgUtils.serializeMsgStrings(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to offload context with UUID: " + uuid, e);
        }
    }

    /**
     * Gets the file path for the given UUID.
     *
     * @param uuid the UUID identifier
     * @return the file path
     */
    private Path getPath(String uuid) {
        return Paths.get(baseDir, uuid);
    }

    /**
     * Reloads messages from a local file.
     *
     * <p>Reads the JSON file for the given UUID, deserializes it, and returns the messages.
     * If the file doesn't exist, returns an empty list.
     *
     * @param uuid the unique identifier of the offloaded context to retrieve
     * @return the list of messages that were offloaded, or an empty list if the file doesn't exist
     * @throws RuntimeException if file operations or deserialization fails
     */
    @Override
    public List<Msg> reload(String uuid) {
        try {
            Path file = getPath(uuid);
            if (!Files.exists(file)) {
                return List.of();
            }

            // Read JSON content from file
            String json = Files.readString(file, StandardCharsets.UTF_8);

            List<String> strings = MsgUtils.deserializeMsgStrings(json);
            return (List<Msg>) MsgUtils.deserializeMsgList(strings);
        } catch (com.fasterxml.jackson.databind.exc.InvalidTypeIdException e) {
            throw new RuntimeException(
                    "Failed to reload context with UUID: "
                            + uuid
                            + ". Missing or invalid 'type' field in ContentBlock. "
                            + "This may indicate the file was serialized with a different format. "
                            + "Error: "
                            + e.getMessage(),
                    e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload context with UUID: " + uuid, e);
        }
    }

    /**
     * Clears messages from a local file.
     *
     * <p>Deletes the file associated with the given UUID. If the file doesn't exist,
     * this operation is a no-op.
     *
     * @param uuid the unique identifier of the offloaded context to clear
     * @throws RuntimeException if file deletion fails
     */
    @Override
    public void clear(String uuid) {
        try {
            Path file = getPath(uuid);
            if (Files.exists(file)) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear context with UUID: " + uuid, e);
        }
    }
}

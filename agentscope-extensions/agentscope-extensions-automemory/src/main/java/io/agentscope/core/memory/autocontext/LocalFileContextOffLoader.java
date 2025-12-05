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
            String json = MsgUtils.serializeMsgs(messages);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
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

    @Override
    public List<Msg> reload(String uuid) {
        try {
            Path file = getPath(uuid);
            if (!Files.exists(file)) {
                return List.of();
            }

            // Read JSON content from file
            String json = Files.readString(file, StandardCharsets.UTF_8);

            return MsgUtils.deserializeMsgs(json);
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

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.agent.message;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

/**
 * Reads and writes the metadata of A2A parts and messages.
 */
public final class MetadataUtil {

    private MetadataUtil() {}

    /**
     * Build metadata with content block type in {@link Part}.
     *
     * @param type the content block type, see {@link ContentBlock}.
     * @return metadata with content block type.
     */
    public static Map<String, Object> buildTypeMetadata(String type) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MessageConstants.BLOCK_TYPE_METADATA_KEY, type);
        return metadata;
    }

    /**
     * Build the metadata of a tool block. Reserved entries override user entries, so callers
     * cannot spoof the wire representation of a tool call.
     *
     * @param type the tool block type
     * @param toolName the tool name
     * @param toolCallId the tool call id
     * @param userMetadata the metadata supplied by the caller
     * @return the metadata with reserved tool entries
     */
    static Map<String, Object> toolMetadata(
            String type, String toolName, String toolCallId, Map<String, Object> userMetadata) {
        Map<String, Object> metadata =
                new HashMap<>(null == userMetadata ? Map.of() : userMetadata);
        metadata.put(MessageConstants.BLOCK_TYPE_METADATA_KEY, type);
        metadata.put(MessageConstants.TOOL_NAME_METADATA_KEY, toolName);
        metadata.put(MessageConstants.TOOL_CALL_ID_METADATA_KEY, toolCallId);
        return metadata;
    }

    /**
     * Get the metadata of a {@link Part}.
     *
     * @param part the part
     * @return the metadata, or {@code null} if the part has none
     */
    public static Map<String, Object> metadataOf(Part<?> part) {
        if (part instanceof TextPart textPart) {
            return textPart.metadata();
        }
        if (part instanceof DataPart dataPart) {
            return dataPart.metadata();
        }
        if (part instanceof FilePart filePart) {
            return filePart.metadata();
        }
        return null;
    }

    /**
     * Get a metadata value of a {@link Part} as a string.
     *
     * @param part the part
     * @param key the metadata key
     * @return the value, or {@code null} if the part has no such value
     */
    public static String metadataValue(Part<?> part, String key) {
        Map<String, Object> metadata = metadataOf(part);
        Object value = null == metadata ? null : metadata.get(key);
        return null == value ? null : value.toString();
    }

    /**
     * Copy a {@link Part} with {@code extra} added to its metadata. Parts are immutable. The
     * metadata, and the data of a {@link DataPart}, are converted with {@link #toJsonValues(Map)}.
     *
     * @param part the part to copy
     * @param extra the metadata to add, overriding entries with the same key
     * @return the copy
     */
    public static Part<?> withMetadata(Part<?> part, Map<String, Object> extra) {
        Map<String, Object> metadata = new HashMap<>();
        if (metadataOf(part) != null) {
            metadata.putAll(metadataOf(part));
        }
        metadata.putAll(extra);
        if (part instanceof TextPart textPart) {
            return new TextPart(textPart.text(), toJsonValues(metadata));
        }
        if (part instanceof DataPart dataPart) {
            return new DataPart(toJsonValue(dataPart.data()), toJsonValues(metadata));
        }
        if (part instanceof FilePart filePart) {
            return new FilePart(filePart.file(), toJsonValues(metadata));
        }
        return part;
    }

    /**
     * Convert values to JSON values: maps, lists, strings, numbers, booleans and {@code null}. The
     * A2A SDK writes any other object, such as a POJO or an enum, as {@code null}.
     *
     * @param values the values to convert
     * @return the converted values, or {@code null} if {@code values} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toJsonValues(Map<String, Object> values) {
        return (Map<String, Object>) toJsonValue(values);
    }

    private static Object toJsonValue(Object value) {
        return JsonUtils.getJsonCodec().convertValue(value, Object.class);
    }
}

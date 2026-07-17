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

package io.agentscope.core.a2a.agent.utils;

import io.agentscope.core.a2a.agent.message.ContentBlockParserRouter;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.message.PartParserRouter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

/**
 * Message Converter between Agentscope {@link Msg} and A2A {@link Message} or {@link Artifact}.
 */
public class MessageConvertUtil {

    private static final PartParserRouter PART_PARSER = new PartParserRouter();

    private static final ContentBlockParserRouter CONTENT_BLOCK_PARSER =
            new ContentBlockParserRouter();

    /**
     * Convert a single {@link Artifact} to {@link Msg}.
     *
     * @param artifact the artifact to convert
     * @param agentName the name of the agent that generated the artifact
     * @return the converted Msg object
     */
    public static Msg convertFromArtifact(Artifact artifact, String agentName) {
        return convertFromArtifact(List.of(artifact), agentName);
    }

    /**
     * Convert a list of {@link Artifact} to {@link Msg}.
     *
     * @param artifacts the list of artifacts to convert
     * @param agentName the name of the agent that generated the artifacts
     * @return the converted Msg object
     */
    public static Msg convertFromArtifact(List<Artifact> artifacts, String agentName) {
        Msg.Builder builder = Msg.builder();
        List<Part<?>> parts = new LinkedList<>();
        List<Artifact> safeArtifacts = artifacts == null ? List.of() : artifacts;
        safeArtifacts.stream()
                .filter(Objects::nonNull)
                .filter(artifact -> isNotEmptyCollection(artifact.parts()))
                .forEach(
                        artifact -> {
                            builder.id(artifact.artifactId());
                            builder.name(null != agentName ? agentName : artifact.name());
                            builder.metadata(artifact.metadata());
                            parts.addAll(artifact.parts());
                        });
        builder.role(MsgRole.ASSISTANT);
        builder.content(convertFromParts(parts));
        return builder.build();
    }

    /**
     * Convert a single {@link Message} to {@link Msg}.
     *
     * @param message   the message to convert
     * @param agentName the name of the agent that generated the message
     * @return the converted Msg object
     */
    public static Msg convertFromMessage(Message message, String agentName) {
        Msg.Builder builder = Msg.builder();
        builder.id(message.messageId());
        builder.name(agentName);
        builder.metadata(null != message.metadata() ? message.metadata() : Map.of());
        builder.role(MsgRole.ASSISTANT);
        builder.content(convertFromParts(message.parts()));
        return builder.build();
    }

    /**
     * Convert a list of {@link Msg} to {@link Message}.
     *
     * @param msgs the list of Msg to convert
     * @return the converted Message object
     */
    public static Message convertFromMsg(List<Msg> msgs) {
        return convertFromMsg(msgs, null, Map.of());
    }

    /**
     * Convert a list of {@link Msg} to {@link Message}.
     *
     * @param msgs the list of Msg
     * @param contextId A2A context id
     * @param requestMetadata request-level metadata to put at top-level
     * @return the converted Message object
     */
    public static Message convertFromMsg(
            List<Msg> msgs, String contextId, Map<String, Object> requestMetadata) {
        Message.Builder builder = Message.builder();
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Part<?>> parts = new LinkedList<>();
        msgs.stream()
                .filter(Objects::nonNull)
                .filter(msg -> isNotEmptyCollection(msg.getContent()))
                .forEach(
                        msg -> {
                            if (null != msg.getMetadata() && !msg.getMetadata().isEmpty()) {
                                metadata.put(msg.getId(), msg.getMetadata());
                            }
                            parts.addAll(
                                    msg.getContent().stream()
                                            .map(CONTENT_BLOCK_PARSER::parse)
                                            .filter(Objects::nonNull)
                                            .map(part -> withMsgMetadata(part, msg))
                                            .toList());
                        });
        if (requestMetadata != null && !requestMetadata.isEmpty()) {
            metadata.putAll(requestMetadata);
        }
        return builder.contextId(contextId)
                .parts(parts)
                .metadata(metadata)
                .role(resolveMessageRole(msgs))
                .build();
    }

    private static Message.Role resolveMessageRole(List<Msg> msgs) {
        List<Message.Role> roles =
                msgs.stream()
                        .filter(Objects::nonNull)
                        .map(Msg::getRole)
                        .filter(Objects::nonNull)
                        .map(MessageConvertUtil::convertRole)
                        .distinct()
                        .toList();
        if (roles.size() == 1) {
            return roles.get(0);
        }
        return Message.Role.ROLE_USER;
    }

    private static Message.Role convertRole(MsgRole role) {
        if (role == MsgRole.ASSISTANT || role == MsgRole.TOOL) {
            return Message.Role.ROLE_AGENT;
        }
        return Message.Role.ROLE_USER;
    }

    private static boolean isNotEmptyCollection(Collection<?> collection) {
        return null != collection && !collection.isEmpty();
    }

    private static List<ContentBlock> convertFromParts(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        List<ContentBlock> contentBlocks = new LinkedList<>();
        StreamingChunkAccumulator accumulator = null;

        for (Part<?> part : parts) {
            if (part == null) {
                continue;
            }
            ContentBlock block = PART_PARSER.parse(part);
            if (block == null) {
                continue;
            }

            StreamingChunkIdentity identity = streamingChunkIdentity(part, block);
            if (identity == null) {
                if (accumulator != null) {
                    contentBlocks.add(accumulator.build());
                    accumulator = null;
                }
                contentBlocks.add(block);
                continue;
            }

            if (accumulator != null && accumulator.matches(identity)) {
                accumulator.append(block);
            } else {
                if (accumulator != null) {
                    contentBlocks.add(accumulator.build());
                }
                accumulator = new StreamingChunkAccumulator(identity, block);
            }
        }

        if (accumulator != null) {
            contentBlocks.add(accumulator.build());
        }
        return contentBlocks;
    }

    private static StreamingChunkIdentity streamingChunkIdentity(Part<?> part, ContentBlock block) {
        if (!Boolean.parseBoolean(
                metadataValue(part, MessageConstants.STREAM_CHUNK_METADATA_KEY))) {
            return null;
        }
        String msgId = metadataValue(part, MessageConstants.MSG_ID_METADATA_KEY);
        String blockId = metadataValue(part, MessageConstants.BLOCK_ID_METADATA_KEY);
        String source = metadataValue(part, MessageConstants.EVENT_SOURCE_METADATA_KEY);
        String blockType = metadataValue(part, MessageConstants.BLOCK_TYPE_METADATA_KEY);
        String parsedType = mergeableBlockType(block);
        if (msgId == null
                || blockId == null
                || blockType == null
                || !blockType.equals(parsedType)) {
            return null;
        }
        return new StreamingChunkIdentity(msgId, blockId, source, blockType);
    }

    private static String metadataValue(Part<?> part, String key) {
        Map<String, Object> metadata = null;
        if (part instanceof TextPart textPart) {
            metadata = textPart.metadata();
        } else if (part instanceof DataPart dataPart) {
            metadata = dataPart.metadata();
        } else if (part instanceof FilePart filePart) {
            metadata = filePart.metadata();
        }
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static String mergeableBlockType(ContentBlock block) {
        if (block instanceof TextBlock) {
            return MessageConstants.BlockContent.TYPE_TEXT;
        }
        if (block instanceof ThinkingBlock) {
            return MessageConstants.BlockContent.TYPE_THINKING;
        }
        return null;
    }

    private record StreamingChunkIdentity(
            String msgId, String blockId, String source, String blockType) {}

    private static final class StreamingChunkAccumulator {

        private final StreamingChunkIdentity identity;

        private final StringBuilder text = new StringBuilder();

        private StreamingChunkAccumulator(
                StreamingChunkIdentity identity, ContentBlock initialBlock) {
            this.identity = identity;
            append(initialBlock);
        }

        private boolean matches(StreamingChunkIdentity candidate) {
            return identity.equals(candidate);
        }

        private void append(ContentBlock block) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                text.append(thinkingBlock.getThinking());
            }
        }

        private ContentBlock build() {
            if (MessageConstants.BlockContent.TYPE_THINKING.equals(identity.blockType())) {
                return ThinkingBlock.builder().thinking(text.toString()).build();
            }
            return TextBlock.builder().text(text.toString()).build();
        }
    }

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
     * Convert arbitrary tool payload values to protobuf Struct-compatible values.
     *
     * @param value arbitrary value from tool input/output
     * @return value supported by protobuf JSON Struct conversion
     */
    public static Object protobufSafeValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Double
                || value instanceof Float
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte) {
            return value;
        }
        if (value instanceof BigDecimal || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach(
                    (key, mapValue) -> {
                        if (mapValue != null) {
                            Object safeValue = protobufSafeValue(mapValue);
                            if (safeValue != null) {
                                result.put(String.valueOf(key), safeValue);
                            }
                        }
                    });
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new LinkedList<>();
            for (Object element : collection) {
                Object safeValue = protobufSafeValue(element);
                if (safeValue != null) {
                    result.add(safeValue);
                }
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new LinkedList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                Object safeValue = protobufSafeValue(element);
                if (safeValue != null) {
                    result.add(safeValue);
                }
            }
            return result;
        }
        String rendered = String.valueOf(value);
        String defaultToStringPrefix = value.getClass().getName() + "@";
        return rendered.startsWith(defaultToStringPrefix) ? null : rendered;
    }

    /**
     * Convert map values to protobuf Struct-compatible values and force keys to strings.
     *
     * @param value map to convert
     * @return protobuf-safe map
     */
    public static Map<String, Object> protobufSafeMap(Map<?, ?> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach(
                (key, mapValue) -> {
                    if (mapValue != null) {
                        Object safeValue = protobufSafeValue(mapValue);
                        if (safeValue != null) {
                            result.put(String.valueOf(key), safeValue);
                        }
                    }
                });
        return result;
    }

    /** Convert a typed content block to a protobuf-safe map without Java type names. */
    public static Map<String, Object> contentBlockToProtobufSafeMap(ContentBlock block) {
        if (block == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = JsonUtils.getJsonCodec().convertValue(block, Map.class);
            return protobufSafeMap(value);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    /** Restore a content block from the tool-result wire representation. */
    public static ContentBlock contentBlockFromProtobufSafeValue(Object value) {
        if (value instanceof ContentBlock block) {
            return block;
        }
        if (value instanceof String text) {
            return TextBlock.builder().text(text).build();
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, mapValue) -> normalized.put(String.valueOf(key), mapValue));
        try {
            return JsonUtils.getJsonCodec().convertValue(normalized, ContentBlock.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Part<?> withMsgMetadata(Part<?> part, Msg msg) {
        Map<String, Object> metadata = new HashMap<>(readMetadata(part));
        if (msg.getId() != null) {
            metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msg.getId());
        }
        if (msg.getName() != null) {
            metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msg.getName());
        }
        if (msg.getRole() != null) {
            metadata.put(MessageConstants.MSG_ROLE_METADATA_KEY, msg.getRole().name());
        }
        if (part instanceof TextPart textPart) {
            return new TextPart(textPart.text(), metadata);
        } else if (part instanceof DataPart dataPart) {
            return new DataPart(dataPart.data(), metadata);
        } else if (part instanceof FilePart filePart) {
            return new FilePart(filePart.file(), metadata);
        }
        return part;
    }

    private static Map<String, Object> readMetadata(Part<?> part) {
        Map<String, Object> metadata = null;
        if (part instanceof TextPart textPart) {
            metadata = textPart.metadata();
        } else if (part instanceof DataPart dataPart) {
            metadata = dataPart.metadata();
        } else if (part instanceof FilePart filePart) {
            metadata = filePart.metadata();
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Part metadata must not be null");
        }
        return metadata;
    }
}

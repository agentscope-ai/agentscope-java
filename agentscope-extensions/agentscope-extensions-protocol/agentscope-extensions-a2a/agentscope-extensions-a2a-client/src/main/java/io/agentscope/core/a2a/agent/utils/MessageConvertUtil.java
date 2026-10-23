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

import static io.agentscope.core.a2a.agent.message.MetadataUtil.metadataValue;
import static io.agentscope.core.a2a.agent.message.MetadataUtil.toJsonValues;
import static io.agentscope.core.a2a.agent.message.MetadataUtil.withMetadata;

import io.agentscope.core.a2a.agent.message.ContentBlockParserRouter;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.message.PartParserRouter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;

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
        artifacts.stream()
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
        Message.Builder builder = Message.builder();
        Map<String, Object> metadata = new HashMap<>();
        List<Part<?>> parts = new LinkedList<>();
        msgs.stream()
                .filter(Objects::nonNull)
                .filter(msg -> isNotEmptyCollection(msg.getContent()))
                .forEach(
                        msg -> {
                            if (null != msg.getMetadata() && !msg.getMetadata().isEmpty()) {
                                metadata.put(msg.getId(), toJsonValues(msg.getMetadata()));
                            }
                            Map<String, Object> msgMetadata = new HashMap<>();
                            msgMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, msg.getId());
                            msgMetadata.put(
                                    MessageConstants.SOURCE_NAME_METADATA_KEY, msg.getName());
                            if (msg.getRole() != null) {
                                msgMetadata.put(
                                        MessageConstants.MSG_ROLE_METADATA_KEY,
                                        msg.getRole().name());
                            }
                            parts.addAll(
                                    msg.getContent().stream()
                                            .map(CONTENT_BLOCK_PARSER::parse)
                                            .filter(Objects::nonNull)
                                            .map(part -> withMetadata(part, msgMetadata))
                                            .toList());
                        });
        return builder.parts(parts).metadata(metadata).role(resolveMessageRole(msgs)).build();
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
        List<ContentBlock> contentBlocks = new LinkedList<>();
        StreamingChunkAccumulator accumulator = null;

        for (Part<?> part : parts) {
            ContentBlock block = PART_PARSER.parse(part);
            if (block == null) {
                continue;
            }

            String kind = isStreamingChunk(part) ? getMergeableChunkKind(block) : null;
            if (kind == null) {
                if (accumulator != null) {
                    contentBlocks.add(accumulator.build());
                    accumulator = null;
                }
                contentBlocks.add(block);
                continue;
            }

            String msgId = metadataValue(part, MessageConstants.MSG_ID_METADATA_KEY);
            if (accumulator != null && accumulator.matches(msgId, kind)) {
                accumulator.append(block);
            } else {
                if (accumulator != null) {
                    contentBlocks.add(accumulator.build());
                }
                accumulator = new StreamingChunkAccumulator(msgId, kind, block);
            }
        }

        if (accumulator != null) {
            contentBlocks.add(accumulator.build());
        }
        return contentBlocks;
    }

    private static boolean isStreamingChunk(Part<?> part) {
        String value = metadataValue(part, MessageConstants.STREAM_CHUNK_METADATA_KEY);
        return Boolean.TRUE.toString().equalsIgnoreCase(value);
    }

    private static String getMergeableChunkKind(ContentBlock block) {
        if (block instanceof TextBlock) {
            return MessageConstants.BlockContent.TYPE_TEXT;
        }
        if (block instanceof ThinkingBlock) {
            return MessageConstants.BlockContent.TYPE_THINKING;
        }
        return null;
    }

    private static final class StreamingChunkAccumulator {

        private final String msgId;

        private final String kind;

        private final StringBuilder text = new StringBuilder();

        private StreamingChunkAccumulator(String msgId, String kind, ContentBlock block) {
            this.msgId = msgId;
            this.kind = kind;
            append(block);
        }

        private boolean matches(String msgId, String kind) {
            return Objects.equals(this.msgId, msgId) && Objects.equals(this.kind, kind);
        }

        private void append(ContentBlock block) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                text.append(thinkingBlock.getThinking());
            }
        }

        private ContentBlock build() {
            if (MessageConstants.BlockContent.TYPE_THINKING.equals(kind)) {
                return ThinkingBlock.builder().thinking(text.toString()).build();
            }
            return TextBlock.builder().text(text.toString()).build();
        }
    }
}

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
import java.util.Collection;
import java.util.HashMap;
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
        List<ContentBlock> contentBlocks = new LinkedList<>();
        artifacts.stream()
                .filter(Objects::nonNull)
                .filter(artifact -> isNotEmptyCollection(artifact.parts()))
                .forEach(
                        artifact -> {
                            builder.id(artifact.artifactId());
                            builder.name(null != agentName ? agentName : artifact.name());
                            builder.metadata(artifact.metadata());
                            contentBlocks.addAll(convertFromParts(artifact.parts()));
                        });
        builder.role(MsgRole.ASSISTANT);
        builder.content(contentBlocks);
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
                                metadata.put(msg.getId(), msg.getMetadata());
                            }
                            parts.addAll(
                                    msg.getContent().stream()
                                            .map(CONTENT_BLOCK_PARSER::parse)
                                            .filter(Objects::nonNull)
                                            .map(
                                                    part -> {
                                                        Map<String, Object> meta =
                                                                new HashMap<>(
                                                                        getPartMetadata(part));
                                                        if (msg.getId() != null) {
                                                            meta.put(
                                                                    MessageConstants
                                                                            .MSG_ID_METADATA_KEY,
                                                                    msg.getId());
                                                        }
                                                        if (msg.getName() != null) {
                                                            meta.put(
                                                                    MessageConstants
                                                                            .SOURCE_NAME_METADATA_KEY,
                                                                    msg.getName());
                                                        }
                                                        return withMetadata(part, meta);
                                                    })
                                            .toList());
                        });
        return builder.parts(parts).metadata(metadata).role(Message.Role.ROLE_USER).build();
    }

    private static boolean isNotEmptyCollection(Collection<?> collection) {
        return null != collection && !collection.isEmpty();
    }

    private static List<ContentBlock> convertFromParts(List<Part<?>> parts) {
        return parts.stream().map(PART_PARSER::parse).filter(Objects::nonNull).toList();
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

    private static Part<?> withMetadata(Part<?> part, Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = new HashMap<>();
        metadata.forEach(
                (k, v) -> {
                    if (k != null && v != null) {
                        safeMetadata.put(k, v);
                    }
                });
        if (part instanceof TextPart textPart) {
            return new TextPart(textPart.text(), safeMetadata);
        } else if (part instanceof DataPart dataPart) {
            return new DataPart(dataPart.data(), safeMetadata);
        } else if (part instanceof FilePart filePart) {
            return new FilePart(filePart.file(), safeMetadata);
        }
        return part;
    }

    public static Map<String, Object> getPartMetadata(Part<?> part) {
        if (part instanceof TextPart textPart) {
            return textPart.metadata();
        } else if (part instanceof DataPart dataPart) {
            return dataPart.metadata();
        } else if (part instanceof FilePart filePart) {
            return filePart.metadata();
        }
        return new HashMap<>();
    }
}

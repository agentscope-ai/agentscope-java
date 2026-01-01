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

import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
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
     * @return the converted Msg object
     */
    public static Msg convertFromArtifact(Artifact artifact) {
        return convertFromArtifact(List.of(artifact));
    }

    /**
     * Convert a list of {@link Artifact} to {@link Msg}.
     *
     * @param artifacts the list of artifacts to convert
     * @return the converted Msg object
     */
    public static Msg convertFromArtifact(List<Artifact> artifacts) {
        Msg.Builder builder = Msg.builder();
        List<ContentBlock> contentBlocks = new LinkedList<>();
        artifacts.stream()
                .filter(Objects::nonNull)
                .filter(artifact -> isNotEmptyCollection(artifact.parts()))
                .forEach(
                        artifact -> {
                            builder.id(artifact.artifactId());
                            // TODO agentscope msg name might be agent name.
                            builder.name(artifact.name());
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
     * @param message the message to convert
     * @return the converted Msg object
     */
    public static Msg convertFromMessage(Message message) {
        Msg.Builder builder = Msg.builder();
        builder.id(message.getMessageId());
        builder.metadata(null != message.getMetadata() ? message.getMetadata() : Map.of());
        builder.role(MsgRole.ASSISTANT);
        builder.content(convertFromParts(message.getParts()));
        return builder.build();
    }

    /**
     * Convert a list of {@link Msg} to {@link Message}.
     *
     * @param msgs the list of Msg to convert
     * @return the converted Message object
     */
    public static Message convertFromMsg(List<Msg> msgs) {
        Message.Builder builder = new Message.Builder();
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
                                            .peek(
                                                    part -> {
                                                        part.getMetadata()
                                                                .put(
                                                                        MessageConstants
                                                                                .MSG_ID_METADATA_KEY,
                                                                        msg.getId());
                                                        part.getMetadata()
                                                                .put(
                                                                        MessageConstants
                                                                                .SOURCE_NAME_METADATA_KEY,
                                                                        msg.getName());
                                                    })
                                            .toList());
                        });
        return builder.parts(parts).metadata(metadata).role(Message.Role.USER).build();
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
}

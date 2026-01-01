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

package io.agentscope.core.a2a.server.utils;

import static io.agentscope.core.a2a.agent.utils.MessageConvertUtil.convertFromMsg;

import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.agentscope.core.a2a.agent.message.ContentBlockParserRouter;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.message.PartParserRouter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Message Converter between Agentscope {@link Msg} and A2A {@link Message} or {@link Artifact}.
 */
public class MessageConvertUtil {

    private static final PartParserRouter PART_PARSER = new PartParserRouter();

    private static final ContentBlockParserRouter CONTENT_BLOCK_PARSER =
            new ContentBlockParserRouter();

    /**
     * Convert a list of {@link Msg} to {@link Message}.
     *
     * @param msgs the list of Msg to convert
     * @param taskId the taskId
     * @param contextId the contextId
     * @return the converted Message object
     */
    public static Message convertFromMsgToMessage(List<Msg> msgs, String taskId, String contextId) {
        Message.Builder builder = new Message.Builder(convertFromMsg(msgs));
        return builder.taskId(taskId).contextId(contextId).build();
    }

    /**
     * Convert a {@link Msg} to {@link Message}.
     *
     * @param msg the Msg to convert
     * @param taskId the taskId
     * @param contextId the contextId
     * @return the converted Message object
     */
    public static Message convertFromMsgToMessage(Msg msg, String taskId, String contextId) {
        Message.Builder builder = new Message.Builder();
        Map<String, Object> metadata = new HashMap<>();
        if (null != msg.getMetadata() && !msg.getMetadata().isEmpty()) {
            metadata.put(msg.getId(), msg.getMetadata());
        }
        return builder.parts(convertFromContentBlocks(msg))
                .metadata(metadata)
                .role(Message.Role.AGENT)
                .taskId(taskId)
                .contextId(contextId)
                .build();
    }

    /**
     * Convert content blocks in {@link Msg} to list of {@link Part}.
     *
     * @param msg the Msg saved content blocks to convert
     * @return list of Part
     */
    public static List<Part<?>> convertFromContentBlocks(Msg msg) {
        return new LinkedList<>(
                msg.getContent().stream()
                        .map(CONTENT_BLOCK_PARSER::parse)
                        .filter(Objects::nonNull)
                        .peek(
                                part -> {
                                    part.getMetadata()
                                            .put(MessageConstants.MSG_ID_METADATA_KEY, msg.getId());
                                    part.getMetadata()
                                            .put(
                                                    MessageConstants.SOURCE_NAME_METADATA_KEY,
                                                    msg.getName());
                                })
                        .toList());
    }

    /**
     * Convert a A2A {@link Message} to List of {@link Msg}.
     *
     * <p>Convert rule is revert from List of {@link Msg} to A2A {@link Message} in A2aAgent, step with following:
     *
     * <ol>
     *     <li>Traversal all {@link Part} from A2A {@link Message} and Convert to target {@link ContentBlock}.</li>
     *     <li>Try to read msgId and msgName from each {@link Part} metadata and keep them with order.</li>
     *     <li>Combine all {@link ContentBlock} by msgId, If no msgId from {@link Part}, create a random single id for it.</li>
     *     <li>Traversal all msgId with order, and build {@link Msg} with msgId, msgName, it's {@link ContentBlock} and
     *     metadata if found from {@link Message} metadata.</li>
     * </ol>
     *
     * <p>If A2A {@link Message} from no agentscope client, parts might not include msgId and msgName,
     * it will degrade to one {@link Part} to one {@link Msg} with single {@link ContentBlock}.
     *
     * @param message a2a protocol message from a2a client.
     * @return list of {@link Msg}
     */
    public static List<Msg> convertFromMessageToMsgs(Message message) {
        List<Msg> result = new LinkedList<>();
        Set<String> msgIds = new LinkedHashSet<>();
        Map<String, List<ContentBlock>> partsByMsgId = new HashMap<>();
        Map<String, String> msgIdToName = new HashMap<>();
        message.getParts().stream()
                .filter(Objects::nonNull)
                .forEach(
                        part -> {
                            String msgId = getMsgId(part);
                            partsByMsgId
                                    .compute(
                                            msgId,
                                            (key, value) -> {
                                                if (null == value) {
                                                    value = new LinkedList<>();
                                                }
                                                return value;
                                            })
                                    .add(PART_PARSER.parse(part));
                            msgIds.add(msgId);
                            msgIdToName.put(msgId, getMsgName(part));
                        });
        msgIds.forEach(
                msgId ->
                        result.add(
                                Msg.builder()
                                        .id(msgId)
                                        .name(msgIdToName.get(msgId))
                                        .role(MsgRole.USER)
                                        .content(partsByMsgId.get(msgId))
                                        .metadata(getMsgMetadata(message, msgId))
                                        .build()));
        return result;
    }

    private static String getMsgId(Part<?> part) {
        if (null == part.getMetadata()
                || null == part.getMetadata().get(MessageConstants.MSG_ID_METADATA_KEY)) {
            return UUID.randomUUID().toString();
        }
        return part.getMetadata().get(MessageConstants.MSG_ID_METADATA_KEY).toString();
    }

    private static String getMsgName(Part<?> part) {
        if (null == part.getMetadata()) {
            return null;
        }
        if (null == part.getMetadata().get(MessageConstants.SOURCE_NAME_METADATA_KEY)) {
            return null;
        }
        return part.getMetadata().get(MessageConstants.SOURCE_NAME_METADATA_KEY).toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMsgMetadata(Message message, String msgId) {
        if (null == message || null == message.getMetadata()) {
            return Map.of();
        }
        Object metadata = message.getMetadata().get(msgId);
        if (null == metadata) {
            return Map.of();
        }
        if (metadata instanceof Map) {
            return (Map<String, Object>) metadata;
        }
        return Map.of();
    }
}

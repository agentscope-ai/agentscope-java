/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.agentscope.core.a2a.agent.message.ContentBlockParserRouter;
import io.agentscope.core.a2a.agent.message.PartParserRouter;
import io.agentscope.core.message.Msg;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Message Converter between Agentscope {@link Msg} and A2A {@link Message} or {@link Artifact}.
 */
public class MessageConvertUtil {

    public static final String SOURCE_NAME_METADATA_KEY = "_agentscope_msg_source";

    public static final String MSG_ID_METADATA_KEY = "_agentscope_msg_id";

    private static final PartParserRouter PART_PARSER = new PartParserRouter();

    private static final ContentBlockParserRouter CONTENT_BLOCK_PARSER =
            new ContentBlockParserRouter();

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
        convertFromContentBlocks(msg);
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
                                    part.getMetadata().put(MSG_ID_METADATA_KEY, msg.getId());
                                    part.getMetadata().put(SOURCE_NAME_METADATA_KEY, msg.getName());
                                })
                        .toList());
    }
}

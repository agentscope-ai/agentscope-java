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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Seeding helpers for SessionLog-backed AG-UI tests. */
final class AguiSessionLogTestSupport {

    static final String THREAD_ID = "thread-1";
    static final String RUN_ID = "exec-1";
    static final String TURN_ID = "turn-1";

    private AguiSessionLogTestSupport() {}

    static SessionLog newLog() {
        return new InMemorySessionLogStore()
                .open(
                        new SessionKey("user-1", "agent-1", THREAD_ID),
                        RuntimeContext.builder().userId("user-1").build());
    }

    static SessionLog.Writer writer(SessionLog log) {
        return log.acquire(RUN_ID, Duration.ofMinutes(5));
    }

    static long append(
            SessionLog log, SessionLog.Writer writer, String type, Map<String, Object> data) {
        return append(log, writer, type, RUN_ID, data);
    }

    static long append(
            SessionLog log,
            SessionLog.Writer writer,
            String type,
            String executionRunId,
            Map<String, Object> data) {
        long seq = log.head().seq() + 1;
        SessionEvent event =
                new SessionEvent(
                        1,
                        UUID.randomUUID().toString(),
                        seq,
                        System.currentTimeMillis(),
                        type,
                        executionRunId,
                        TURN_ID,
                        true,
                        JsonUtils.getJsonCodec().toJson(data));
        log.commit(writer, "batch-" + seq, seq - 1, List.of(event));
        return seq;
    }

    static Map<String, Object> message(Msg message) {
        return Map.of("message", message);
    }

    static Msg user(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    static Msg assistant(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    static Msg assistantThinking(String id, String thinking) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.ASSISTANT)
                .content(ThinkingBlock.builder().thinking(thinking).build())
                .build();
    }

    static Msg assistantToolCall(String id, String text, ToolUseBlock toolUse) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build(), toolUse)
                .build();
    }

    static Msg toolResult(String id, ToolResultBlock result) {
        return Msg.builder().id(id).role(MsgRole.TOOL).content(result).build();
    }

    static ToolUseBlock toolUse(String id, String name, String content) {
        return ToolUseBlock.builder().id(id).name(name).content(content).build();
    }

    static ToolResultBlock toolResultBlock(String id, String name, String text) {
        return ToolResultBlock.builder()
                .id(id)
                .name(name)
                .output(TextBlock.builder().text(text).build())
                .state(ToolResultState.SUCCESS)
                .build();
    }

    static Map<String, Object> modelChunk(String modelCallId, String chunkId, Object... blocks) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", chunkId);
        chunk.put("content", List.of(blocks));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("modelCallId", modelCallId);
        data.put("purpose", "REASONING");
        data.put("chunk", chunk);
        return data;
    }

    static Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    static Map<String, Object> thinkingBlock(String thinking) {
        return Map.of("type", "thinking", "thinking", thinking);
    }

    static Map<String, Object> toolUseBlock(
            String id, String name, Map<String, Object> input, String content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", name);
        if (input != null) {
            block.put("input", input);
        }
        if (content != null) {
            block.put("content", content);
        }
        return block;
    }
}

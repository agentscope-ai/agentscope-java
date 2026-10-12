/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chat;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/** Deterministic, offline model so the complete persistence/recovery demo needs no API key. */
final class DemoChatModel implements Model {
    private final Duration slowDelay;
    private final Duration toolDelay;

    DemoChatModel() {
        this(Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    DemoChatModel(Duration slowDelay, Duration toolDelay) {
        this.slowDelay = slowDelay;
        this.toolDelay = toolDelay;
    }

    Duration toolDelay() {
        return toolDelay;
    }

    public String getModelName() {
        return "demo";
    }

    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        List<Msg> users = messages.stream().filter(m -> m.getRole() == MsgRole.USER).toList();
        int taskStart = messages.size() - 1;
        while (taskStart > 0 && !isTaskInput(messages.get(taskStart))) taskStart--;
        String prompt = users.isEmpty() ? "Hello" : messages.get(taskStart).getTextContent();
        if (prompt.startsWith("/slow"))
            return workflow(messages.subList(taskStart, messages.size()));
        Msg last = messages.get(messages.size() - 1);
        String reply;
        if (!last.getContentBlocks(ToolResultBlock.class).isEmpty()) {
            reply =
                    "收到你的补充："
                            + last
                                    .getContentBlocks(ToolResultBlock.class)
                                    .get(0)
                                    .getOutput()
                                    .stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .collect(Collectors.joining())
                            + "。这次续跑沿用了原 turn，并创建了新的 run。";
        } else if (prompt.startsWith("/ask")) {
            ContentBlock call =
                    ToolUseBlock.builder()
                            .id(UUID.randomUUID().toString())
                            .name("ask_user")
                            .input(Map.of("question", "请补充你希望优先考虑的条件。"))
                            .content(
                                    JsonUtils.getJsonCodec()
                                            .toJson(Map.of("question", "请补充你希望优先考虑的条件。")))
                            .build();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(call))
                            .finishReason("tool_calls")
                            .build());
        } else {
            String remembered = users.isEmpty() ? "" : users.get(0).getTextContent();
            reply =
                    "这是离线演示回复。我在当前工作上下文中看到了 "
                            + users.size()
                            + " 条用户消息。"
                            + "最早的一条是「"
                            + remembered
                            + "」。你刚才说：「"
                            + prompt
                            + "」。"
                            + "你可以刷新页面、断开事件连接，或重启服务后继续聊天。";
        }
        return text(reply, UUID.randomUUID().toString(), Duration.ofMillis(12));
    }

    private Flux<ChatResponse> workflow(List<Msg> messages) {
        var results =
                messages.stream()
                        .flatMap(
                                message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();
        String response = UUID.randomUUID().toString();
        if (results.stream().noneMatch(result -> "demo_lookup".equals(result.getName()))) {
            return text(
                            "我先查询会话历史和事件续传两份资料。接下来会生成两个独立的工具调用；可以在参数生成或工具执行期间刷新页面。",
                            response,
                            slowDelay)
                    .concatWith(calls(response, List.of("会话历史", "事件续传"), "demo_lookup"));
        }
        if (results.stream().noneMatch(result -> "demo_verify".equals(result.getName()))) {
            return text(
                            "两份查询已经返回。我会保留它们的 ToolCall 和 ToolResult，再启动一次核对。离开页面不会中断这些操作。",
                            response,
                            slowDelay)
                    .concatWith(calls(response, List.of("恢复完整性"), "demo_verify"));
        }
        return text(
                "核对完成：这轮请求经历了三次模型输出和三次工具调用。刷新页面后，之前生成的文字、工具参数、执行进度及结果都应该完整显示。当前回复也会从已经提交的前缀继续增长。",
                response,
                slowDelay);
    }

    private boolean isTaskInput(Msg message) {
        return message.getRole() == MsgRole.USER
                && !message.getMetadata().containsKey("chat_input_kind");
    }

    private Flux<ChatResponse> calls(String response, List<String> topics, String name) {
        var chunks = new ArrayList<ChatResponse>();
        for (String topic : topics) {
            String id = UUID.randomUUID().toString();
            String arguments = JsonUtils.getJsonCodec().toJson(Map.of("topic", topic));
            for (int offset = 0; offset < arguments.length(); offset += 3) {
                var call =
                        ToolUseBlock.builder()
                                .id(id)
                                .name(name)
                                .content(
                                        arguments.substring(
                                                offset, Math.min(arguments.length(), offset + 3)))
                                .build();
                chunks.add(ChatResponse.builder().id(response).content(List.of(call)).build());
            }
        }
        return Flux.fromIterable(chunks).delayElements(slowDelay.multipliedBy(2));
    }

    private Flux<ChatResponse> text(String reply, String responseId, Duration delay) {
        List<String> chunks =
                reply.codePoints().mapToObj(cp -> new String(Character.toChars(cp))).toList();
        return Flux.fromIterable(chunks)
                .delayElements(delay)
                .map(
                        chunk ->
                                ChatResponse.builder()
                                        .id(responseId)
                                        .content(List.of(TextBlock.builder().text(chunk).build()))
                                        .build());
    }
}

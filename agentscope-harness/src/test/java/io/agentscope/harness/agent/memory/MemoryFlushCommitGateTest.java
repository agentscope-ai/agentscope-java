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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class MemoryFlushCommitGateTest {
    @TempDir Path workspace;

    private static final RuntimeContext RC =
            RuntimeContext.builder()
                    .sessionId("flush-session")
                    .put(
                            Clock.class,
                            Clock.fixed(Instant.parse("2026-09-08T10:15:30Z"), ZoneOffset.UTC))
                    .build();
    private static final List<Msg> INPUT =
            List.of(
                    Msg.builderForRole(MsgRole.USER)
                            .content(TextBlock.builder().text("The new owner is Bob.").build())
                            .build());
    private static final String DAILY = "memory/2026-09-08.md";

    private static ChatResponse chunk(String text, String finish) {
        return ChatResponse.builder()
                .id("flush-chunk")
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason(finish)
                .build();
    }

    private static ChatResponse mixed(ContentBlock block) {
        return ChatResponse.builder().id("mixed").content(List.of(block)).build();
    }

    static Stream<Arguments> refusedStreams() {
        ChatResponse incomplete =
                ChatResponse.builder()
                        .id("incomplete")
                        .content(List.of())
                        .finishReason("incomplete")
                        .build();
        ChatResponse metadata =
                ChatResponse.builder()
                        .id("metadata")
                        .content(List.of())
                        .metadata(Map.of("openai.response.incomplete_reason", "max_output_tokens"))
                        .build();
        ChatResponse overwrittenMetadata =
                ChatResponse.builder()
                        .id("metadata-overwritten")
                        .content(List.of())
                        .metadata(Map.of("openai.response.incomplete_reason", "content_filter"))
                        .finishReason("stop")
                        .build();
        return Stream.of(
                Arguments.of(
                        "length then stop",
                        Flux.just(chunk("- partial", "length"), chunk(" tail", "stop"))),
                Arguments.of("max_tokens", Flux.just(chunk("- partial", "MAX_TOKENS"))),
                Arguments.of(
                        "incomplete without metadata", Flux.just(chunk("- partial", "incomplete"))),
                Arguments.of(
                        "incomplete without metadata then stop",
                        Flux.just(chunk("- partial", "INCOMPLETE"), chunk(" tail", "stop"))),
                Arguments.of(
                        "content filter incomplete",
                        Flux.just(
                                ChatResponse.builder()
                                        .id("filtered")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("- partial")
                                                                .build()))
                                        .finishReason("incomplete")
                                        .metadata(
                                                Map.of(
                                                        "openai.response.incomplete_reason",
                                                        "content_filter"))
                                        .build())),
                Arguments.of(
                        "content filter metadata after incomplete then stop",
                        Flux.just(chunk("- partial", "incomplete"), overwrittenMetadata)),
                Arguments.of(
                        "incomplete without reason in metadata",
                        Flux.just(
                                ChatResponse.builder()
                                        .id("no-reason")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("- partial")
                                                                .build()))
                                        .finishReason("incomplete")
                                        .metadata(Map.of("provider", "fake"))
                                        .build(),
                                chunk(" tail", "stop"))),
                Arguments.of(
                        "split metadata",
                        Flux.just(
                                chunk("- partial", null),
                                incomplete,
                                metadata,
                                overwrittenMetadata)),
                Arguments.of(
                        "metadata before finish",
                        Flux.just(metadata, chunk("- partial", "incomplete"), chunk("", "stop"))),
                Arguments.of(
                        "tool use in earlier chunk",
                        Flux.just(
                                chunk("- plausible", null),
                                mixed(
                                        ToolUseBlock.builder()
                                                .id("call")
                                                .name("save")
                                                .input(Map.of())
                                                .build()),
                                chunk(" tail", "stop"))),
                Arguments.of(
                        "tool result in earlier chunk",
                        Flux.just(
                                chunk("- plausible", null),
                                mixed(ToolResultBlock.success("observed")),
                                chunk(" tail", "stop"))),
                Arguments.of("blank", Flux.just(chunk(" \n\t", "stop"))),
                Arguments.of(
                        "no reply across chunks",
                        Flux.just(chunk(" NO_", null), chunk("REPLY \n", "stop"))),
                Arguments.of("empty stream", Flux.<ChatResponse>empty()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedStreams")
    void incompleteMixedOrEmptyStreamsNeverAppend(String description, Flux<ChatResponse> script) {
        try (WorkspaceManager manager = spy(new WorkspaceManager(workspace))) {
            new MemoryFlushManager(manager, model(script)).flushMemories(RC, INPUT).block();
            verify(manager, never()).appendMemoryFileUtf8(any(), anyString(), anyString());
            verify(manager, never()).writeMemoryFileUtf8(any(), anyString(), anyString());
            assertEquals("", manager.readMemoryMd(RC), description);
            assertEquals("", manager.readMemoryFileUtf8(RC, DAILY), description);
        }
    }

    @Test
    void partialThenErrorNeverAppendsAndPreservesTheError() {
        try (WorkspaceManager manager = spy(new WorkspaceManager(workspace))) {
            Flux<ChatResponse> script =
                    Flux.just(chunk("- partial", null))
                            .concatWith(Flux.error(new IllegalStateException("model failure")));
            StepVerifier.create(
                            new MemoryFlushManager(manager, model(script)).flushMemories(RC, INPUT))
                    .expectErrorMessage("model failure")
                    .verify();
            verify(manager, never()).appendMemoryFileUtf8(any(), anyString(), anyString());
        }
    }

    @Test
    void cancellationBeforeStreamCompletionNeverAppends() {
        try (WorkspaceManager manager = spy(new WorkspaceManager(workspace))) {
            Flux<ChatResponse> script =
                    Flux.just(chunk("- partial", "stop")).concatWith(Flux.never());
            Disposable subscription =
                    new MemoryFlushManager(manager, model(script))
                            .flushMemories(RC, INPUT)
                            .subscribe();
            subscription.dispose();
            verify(manager, never()).appendMemoryFileUtf8(any(), anyString(), anyString());
            assertEquals("", manager.readMemoryFileUtf8(RC, DAILY));
        }
    }

    @Test
    void completeTextWithMissingFinishReasonRemainsCompatibleAndCollectorIsPerSubscription() {
        try (WorkspaceManager manager = new WorkspaceManager(workspace)) {
            var flush =
                    new MemoryFlushManager(
                                    manager,
                                    model(
                                            Flux.just(
                                                    chunk("- complete", null),
                                                    chunk(" fact", null))))
                            .flushMemories(RC, INPUT);
            flush.block();
            String first = manager.readMemoryFileUtf8(RC, DAILY);
            assertTrue(first.contains("Memory Flush — 2026-09-08T10:15:30Z\n- complete fact"));
            flush.block();
            assertEquals(first + first, manager.readMemoryFileUtf8(RC, DAILY));
            assertEquals("", manager.readMemoryMd(RC));
        }
    }

    private static Model model(Flux<ChatResponse> script) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertEquals(2, messages.size());
                assertTrue(tools.isEmpty(), "auxiliary extraction has no business tools");
                return script;
            }

            @Override
            public String getModelName() {
                return "fake-flush-gate";
            }
        };
    }
}

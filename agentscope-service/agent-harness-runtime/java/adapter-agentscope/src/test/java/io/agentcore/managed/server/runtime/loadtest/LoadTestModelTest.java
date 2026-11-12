/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel.Profile;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class LoadTestModelTest {
    final Profile profile =
            new Profile(
                    1,
                    1,
                    4,
                    8,
                    "longwait__long_wait",
                    Map.of("marker", "{{marker}}", "seconds", 30),
                    1,
                    1);
    final Msg input =
            Msg.builder()
                    .role(MsgRole.USER)
                    .textContent("LOADTEST {\"request_id\":\"request-1\",\"marker\":\"marker-1\"}")
                    .build();
    final List<ToolSchema> tools =
            List.of(ToolSchema.builder().name("longwait__long_wait").description("test").build());

    List<ChatResponse> run(LoadTestModel model, List<Msg> messages) {
        return model.stream(messages, tools, null).collectList().block(Duration.ofSeconds(3));
    }

    @Test
    void replacementAndSerializedReplayConsumeOriginalToolResultWithStableIdentity()
            throws Exception {
        var first = new LoadTestModel("loadtest:tool", profile);
        var dispatch = run(first, List.of(input));
        assertEquals(4, dispatch.size());
        var call = (ToolUseBlock) dispatch.get(3).getContent().get(1);
        assertEquals(Map.of("marker", "marker-1", "seconds", 30), call.getInput());
        var replay = run(new LoadTestModel("loadtest:tool", profile), List.of(input));
        assertEquals(call.getId(), ((ToolUseBlock) replay.get(3).getContent().get(1)).getId());
        var history =
                List.of(
                        input,
                        Msg.builder().role(MsgRole.ASSISTANT).content(call).build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id(call.getId())
                                                .name(call.getName())
                                                .output(
                                                        TextBlock.builder()
                                                                .text(
                                                                        "{\"call_id\":\"physical-123\",\"marker\":\"marker-1\"}")
                                                                .build())
                                                .build())
                                .build());
        var json = new ObjectMapper();
        List<Msg> restored =
                json.readValue(
                        json.writeValueAsBytes(history),
                        json.getTypeFactory().constructCollectionType(List.class, Msg.class));
        var replacement = new LoadTestModel("loadtest:tool", profile);
        var answer = run(replacement, restored);
        assertTrue(text(answer).contains("physical-123"));
        assertTrue(text(answer).contains("marker-1"));
        assertTrue(
                answer.stream()
                        .flatMap(c -> c.getContent().stream())
                        .noneMatch(ToolUseBlock.class::isInstance));
        var duplicate = new ArrayList<>(restored);
        duplicate.add(restored.get(2));
        assertEquals(
                text(answer), text(run(new LoadTestModel("loadtest:tool", profile), duplicate)));
        assertEquals(1, answer.stream().filter(c -> c.getUsage() != null).count());
        assertTrue(
                answer.stream()
                        .allMatch(c -> Boolean.TRUE.equals(c.getMetadata().get("synthetic"))));
    }

    @Test
    void parallelWavesRecoverFromMessageHistoryWithResultsInEitherOrder() {
        var wave =
                new Profile(
                        0, 0, 2, 8, "longwait__long_wait", Map.of("marker", "{{marker}}"), 2, 2);
        var history = new ArrayList<>(List.of(input));
        for (int round = 0; round < 2; round++) {
            var chunks = run(new LoadTestModel("loadtest:waves", wave), history);
            var calls =
                    chunks.stream()
                            .flatMap(c -> c.getContent().stream())
                            .filter(ToolUseBlock.class::isInstance)
                            .map(ToolUseBlock.class::cast)
                            .toList();
            assertEquals(2, calls.size());
            history.add(
                    Msg.builder().role(MsgRole.ASSISTANT).content(new ArrayList<>(calls)).build());
            for (int i = 1; i >= 0; i--) {
                var call = calls.get(i);
                assertEquals("marker-1-r" + round + "-c" + i, call.getInput().get("marker"));
                history.add(
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id(call.getId())
                                                .name(call.getName())
                                                .output(
                                                        TextBlock.builder()
                                                                .text("original-" + round + "-" + i)
                                                                .build())
                                                .build())
                                .build());
            }
        }
        var answer = run(new LoadTestModel("loadtest:waves", wave), history);
        assertTrue(text(answer).contains("original-0-0"));
        assertTrue(text(answer).contains("original-1-1"));
        assertTrue(
                answer.stream()
                        .flatMap(c -> c.getContent().stream())
                        .noneMatch(ToolUseBlock.class::isInstance));
    }

    @Test
    void coldStreamingHasFirstChunkDelayAndCancellationReleasesScheduledWork() throws Exception {
        var model =
                new LoadTestModel("loadtest:slow", new Profile(80, 1000, 4, 8, "", Map.of(), 0, 1));
        var received = new CountDownLatch(1);
        long begin = System.nanoTime();
        var subscription =
                model.stream(List.of(input), List.of(), null).subscribe(c -> received.countDown());
        assertTrue(received.await(2, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - begin >= Duration.ofMillis(60).toNanos());
        subscription.dispose();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (model.stats().active.get() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(0, model.stats().active.get());
        assertEquals(1, model.stats().chunks.get());
        assertEquals(1, model.stats().cancelled.get());
        assertEquals(0, model.stats().completed.get());
    }

    @Test
    void missingToolUnresolvedCallAndConflictingResultsFailWithoutFabricatingSuccess() {
        var model = new LoadTestModel("loadtest:tool", profile);
        assertThrows(
                RuntimeException.class,
                () -> model.stream(List.of(input), List.of(), null).blockLast());
        var call = (ToolUseBlock) run(model, List.of(input)).get(3).getContent().get(1);
        var history =
                new ArrayList<>(
                        List.of(
                                input,
                                Msg.builder().role(MsgRole.ASSISTANT).content(call).build()));
        assertThrows(RuntimeException.class, () -> run(model, history));
        for (String output : List.of("first", "conflicting"))
            history.add(
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.builder()
                                            .id(call.getId())
                                            .name(call.getName())
                                            .output(TextBlock.builder().text(output).build())
                                            .build())
                            .build());
        assertThrows(RuntimeException.class, () -> run(model, history));
    }

    @Test
    void modelExecutionTimeoutIsHonoredAndAuxiliaryCallsNeverDispatchTools() {
        var slow =
                new LoadTestModel("loadtest:slow", new Profile(5000, 1, 2, 8, "", Map.of(), 0, 1));
        var options =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder().timeout(Duration.ofMillis(20)).build())
                        .build();
        assertThrows(
                RuntimeException.class,
                () ->
                        slow.stream(List.of(input), List.of(), options)
                                .blockLast(Duration.ofSeconds(2)));
        var memory =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent("You are a memory extraction assistant")
                        .build();
        var chunks = run(new LoadTestModel("loadtest:tool", profile), List.of(memory, input));
        assertTrue(
                chunks.stream()
                        .flatMap(c -> c.getContent().stream())
                        .noneMatch(ToolUseBlock.class::isInstance));
    }

    static String text(List<ChatResponse> chunks) {
        return chunks.stream()
                .flatMap(c -> c.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}

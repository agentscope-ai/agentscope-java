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

package io.agentscope.examples.jev;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.context.FileJevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextCompactor;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Offline Harness run: a historical read pair is archived, the required test evidence stays. */
public final class JevContextCompactionExample {
    public record Run(String answer, int modelCalls, int decisions, int restoredMessages) {}

    private JevContextCompactionExample() {}

    public static void main(String[] args) throws Exception {
        var path = Files.createTempDirectory("jev-context-demo-");
        System.out.println(runOffline(path));
        System.out.println("Synthetic decisions; archive stored at " + path.resolve("archive"));
    }

    public static Run runOffline(Path workspace) {
        var archive = new FileJevContextArchive(workspace.resolve("archive"), 1_000_000);
        AtomicReference<String> reference = new AtomicReference<>();
        AtomicReference<String> outcome = new AtomicReference<>();
        AtomicInteger decisions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        var options =
                new JevExecution.Options(
                        JevExecution.Mode.ENFORCE,
                        Duration.ofSeconds(3),
                        "offline-demo",
                        (ctx, record) -> {
                            outcome.set(record.status() + ":" + record.reason());
                            if (record.recommendation().containsKey("archive_reference"))
                                reference.set(record.recommendation().get("archive_reference"));
                        });
        var cfg =
                new JevContextCompactor.Config(
                        .2,
                        .8,
                        1,
                        25000,
                        30000,
                        128,
                        100,
                        .05,
                        1_000_000,
                        Set.of("read"),
                        Set.of());
        var compactor =
                new JevContextCompactor(
                        request -> {
                            decisions.incrementAndGet();
                            Map<String, Answer> answers = new HashMap<>();
                            request.questions()
                                    .keySet()
                                    .forEach(
                                            key ->
                                                    answers.put(
                                                            key,
                                                            new NoulAnswer(
                                                                    key.endsWith("t1")
                                                                            ? .01
                                                                            : .99)));
                            return Mono.just(
                                    new SystemOneResult("fixture", answers, new Usage(0, 0)));
                        },
                        cfg,
                        options,
                        archive);
        var model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "offline";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        modelCalls.incrementAndGet();
                        String serialized =
                                io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(messages);
                        if (serialized.contains("obsolete file contents")
                                || !serialized.contains("expected count 3")
                                || !serialized.contains("Never edit generated files"))
                            return Flux.error(
                                    new IllegalStateException(
                                            "compaction did not preserve the expected evidence"
                                                    + " boundary: "
                                                    + outcome.get()
                                                    + ", obsolete="
                                                    + serialized.contains("obsolete file contents")
                                                    + ", evidence="
                                                    + serialized.contains("expected count 3")
                                                    + ", constraint="
                                                    + serialized.contains(
                                                            "Never edit generated files")));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text(
                                                                        "The test expects count 3;"
                                                                            + " generated files"
                                                                            + " remain unchanged.")
                                                                .build()))
                                        .build());
                    }
                };
        try (var agent =
                HarnessAgent.builder()
                        .name("compaction-demo")
                        .sysPrompt("Use only the supplied evidence.")
                        .model(model)
                        .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
                        .workspace(workspace)
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.never())
                                        .build())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(2)
                                        .keepTokens(0)
                                        .prune(null)
                                        .flushBeforeCompact(false)
                                        .strategy(compactor)
                                        .build())
                        .build()) {
            var ctx = RuntimeContext.builder().userId("demo").sessionId("context-demo").build();
            var result =
                    agent.streamEvents(history(), ctx)
                            .ofType(AgentResultEvent.class)
                            .single()
                            .block(Duration.ofSeconds(10));
            var restored =
                    archive.restore(
                                    new JevContextArchive.Scope(
                                            "demo", "compaction-demo", "context-demo"),
                                    reference.get())
                            .block();
            return new Run(
                    result.getResult().getTextContent(),
                    modelCalls.get(),
                    decisions.get(),
                    restored.size());
        }
    }

    public static List<Msg> history() {
        return List.of(
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("Never edit generated files. Fix the failing count test.")
                        .build(),
                use("obsolete", "old.txt"),
                result("obsolete", "obsolete file contents ".repeat(400)),
                use("needed", "current-test.txt"),
                result("needed", "expected count 3 ".repeat(400)),
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("Continue using the current test result.")
                        .build());
    }

    private static Msg use(String id, String path) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id(id)
                                .name("read")
                                .input(Map.of("path", path))
                                .build())
                .build();
    }

    private static Msg result(String id, String text) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(id)
                                .name("read")
                                .state(ToolResultState.SUCCESS)
                                .output(TextBlock.builder().text(text).build())
                                .build())
                .build();
    }
}

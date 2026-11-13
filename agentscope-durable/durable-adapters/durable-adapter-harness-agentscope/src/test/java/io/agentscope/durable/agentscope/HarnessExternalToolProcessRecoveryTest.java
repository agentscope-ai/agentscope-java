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
package io.agentscope.durable.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.SessionBackend;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** A durable Harness interaction survives separate Brain JVMs without replaying an applied result. */
public class HarnessExternalToolProcessRecoveryTest {
    private static final String TOOL_ID = "external-call-1";

    @TempDir Path root;

    @Test
    void waitingTurnContinuesInAnotherProcessAndRejectsAnAlreadyAppliedResult() throws Exception {
        runProcess("suspend");
        runProcess("resume");
        runProcess("duplicate");
    }

    private void runProcess(String phase) throws Exception {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path output = root.resolve(phase + ".log");
        Process process =
                new ProcessBuilder(
                                java.toString(),
                                "-Dagentscope.state.home="
                                        + root.resolve("native-state").toAbsolutePath(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                HarnessExternalToolProcessRecoveryTest.class.getName(),
                                root.toString(),
                                phase)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), phase + " timed out");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (Exception error) {
            return error.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String phase = args[1];
        var calls = new AtomicInteger();
        var toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("remote_tool")
                        .description("Executed by a separate worker")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build());
        Model model =
                new Model() {
                    @Override
                    public String getModelName() {
                        return "process-recovery-fixture";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        assertEquals(0, calls.getAndIncrement());
                        if (phase.equals("suspend"))
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id(TOOL_ID)
                                                                    .name("remote_tool")
                                                                    .input(Map.of())
                                                                    .build()))
                                            .build());
                        List<ToolResultBlock> results =
                                messages.stream()
                                        .flatMap(
                                                m ->
                                                        m
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .toList();
                        assertEquals(1, results.size());
                        assertEquals(TOOL_ID, results.get(0).getId());
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                };
        var key = new SessionKey("user-1", "session-1");
        var thread = new ThreadKey(key, "main");
        var adapter =
                AgentScopeAdapter.forHarness(
                        binding ->
                                HarnessAgent.builder()
                                        .name("process-recovery")
                                        .model(model)
                                        .toolkit(toolkit)
                                        .workspace(root.resolve("workspace"))
                                        .disableFilesystemTools()
                                        .disableShellTool()
                                        .disableMemoryTools()
                                        .disableMemoryHooks()
                                        .disableCompaction()
                                        .disableToolResultEviction(),
                        Map.of());
        try (var backend = LocalBackend.open(root.resolve("backend"));
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("agent", "v1", adapter)
                                .pollInterval(Duration.ofMillis(1))
                                .build()) {
            if (phase.equals("suspend")) {
                var turn =
                        runtime.createSession(key, "agent")
                                .submit("request-1", "Execute the remote tool");
                runtime.tick(thread);
                assertNull(runtime.lastFailure(thread));
                assertEquals(TurnStatus.WAITING, turn.status().status());
                assertEquals(TOOL_ID, turn.pendingInteractions().get(0).requestId());
                assertTrue(!backend.checkpoints(thread, 0, 1000).isEmpty());
                assertEquals(1, calls.get());
                Files.writeString(root.resolve("turn-id"), turn.turnId());
                Files.writeString(
                        root.resolve("run-id"), turn.listRuns(null, 10).items().get(0).runId());
            } else {
                var turn =
                        runtime.getSession(key).getTurn(Files.readString(root.resolve("turn-id")));
                if (phase.equals("resume")) {
                    var interaction = turn.pendingInteractions().get(0);
                    Files.writeString(root.resolve("interaction-id"), interaction.id());
                    turn.respond(
                            "response-1",
                            interaction.id(),
                            DurableJson.value("persisted worker result"));
                    runtime.tick(thread);
                    assertNull(runtime.lastFailure(thread));
                    assertEquals(TurnStatus.SUCCEEDED, turn.result().status());
                    assertTrue(turn.pendingInteractions().isEmpty());
                    var runs = turn.listRuns(null, 10).items();
                    assertEquals(2, runs.size());
                    assertEquals(Files.readString(root.resolve("run-id")), runs.get(0).runId());
                    assertNotEquals(runs.get(0).runId(), runs.get(1).runId());
                    assertEquals(1, calls.get());
                } else if (phase.equals("duplicate")) {
                    var position = backend.snapshot(key).cursor();
                    assertThrows(
                            DurableException.class,
                            () ->
                                    turn.respond(
                                            "response-2",
                                            Files.readString(root.resolve("interaction-id")),
                                            DurableJson.value("persisted worker result")));
                    assertEquals(position, backend.snapshot(key).cursor());
                    runtime.tick(thread);
                    assertEquals(0, calls.get());
                    assertEquals(2, turn.listRuns(null, 10).items().size());
                } else throw new IllegalArgumentException("Unknown test phase: " + phase);
                assertEquals(1, toolResults(backend, thread));
            }
        }
    }

    private static long toolResults(SessionBackend backend, ThreadKey thread) {
        var checkpoints = backend.checkpoints(thread, 0, 1000);
        long position = Long.parseLong(checkpoints.get(checkpoints.size() - 1).position());
        return backend.checkpointJournal(thread, position, 0, 1000).stream()
                .map(value -> DurableJson.convert(value, JournalEvent.class))
                .filter(event -> "tool/result".equals(event.type()))
                .map(
                        event ->
                                JsonUtils.getJsonCodec()
                                        .convertValue(event.data().get("message"), Msg.class))
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> TOOL_ID.equals(result.getId()))
                .count();
    }
}

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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Verifies the Service's remote-tool prerequisite using separate JVMs and a durable journal. */
public class HarnessExternalToolProcessRecoveryTest {
    private static final String TOOL_ID = "external-call-1";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir Path root;

    @Test
    void pausedTurnContinuesInAnotherProcessAndRejectsAnAlreadyAppliedResult() throws Exception {
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
        try (var agent =
                HarnessAgent.builder()
                        .name("process-recovery")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(root.resolve("workspace"))
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(
                                        new LocalFilesystem(root.resolve("journal"))))
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableCompaction()
                        .disableToolResultEviction()
                        .build()) {
            var context = RuntimeContext.builder().userId("user-1").sessionId("session-1").build();
            var session = agent.session(context);
            if (phase.equals("suspend")) {
                var task = session.submit("request-1", "Execute the remote tool");
                var paused = session.await(task).block(WAIT);
                assertNotNull(paused);
                assertEquals("suspended", paused.status());
                assertTrue(session.pending().containsKey(TOOL_ID));
                assertTrue(
                        StreamSupport.stream(
                                        session.log()
                                                .scan(0, session.log().head().seq())
                                                .spliterator(),
                                        false)
                                .anyMatch(event -> event.type().equals("state/checkpoint")));
                Files.writeString(root.resolve("turn-id"), task.turnId());
            } else if (phase.equals("resume")) {
                assertTrue(session.pending().containsKey(TOOL_ID));
                var task = session.respond(TOOL_ID, "persisted worker result");
                assertEquals(Files.readString(root.resolve("turn-id")), task.turnId());
                var completed = session.await(task).block(WAIT);
                assertNotNull(completed);
                assertEquals("completed", completed.status());
                assertTrue(session.pending().isEmpty());
                assertEquals(1, calls.get());
                assertEquals(1, toolResults(session));
            } else if (phase.equals("duplicate")) {
                long position = session.log().head().seq();
                assertThrows(
                        IllegalArgumentException.class,
                        () -> session.respond(TOOL_ID, "persisted worker result"));
                assertEquals(position, session.log().head().seq());
                assertEquals(0, calls.get());
                assertEquals(1, toolResults(session));
            } else {
                throw new IllegalArgumentException("Unknown test phase: " + phase);
            }
        }
    }

    private static long toolResults(AgentSession session) {
        return session.transcript().messages().stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> TOOL_ID.equals(result.getId()))
                .count();
    }
}

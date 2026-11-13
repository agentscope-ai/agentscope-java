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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.sandbox.DirectSandboxExecutor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

/** Launches and kills a separate Brain JVM; the actual detached tool process outlives it. */
public class ProcessRecoveryTest {
    @TempDir Path directory;
    static final SessionKey KEY = new SessionKey("fixture", "process-recovery");

    static DurableRuntime runtime(SessionBackend backend, Path workspace) {
        Model model =
                new Model() {
                    public String getModelName() {
                        return "process-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        try {
                            Files.writeString(
                                    workspace.resolve("model-calls"),
                                    "call\n",
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.APPEND);
                        } catch (Exception error) {
                            return Flux.error(error);
                        }
                        boolean applied =
                                messages.stream()
                                        .flatMap(
                                                m ->
                                                        m
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .anyMatch(r -> "job".equals(r.getId()) && !r.isSuspended());
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                applied
                                                        ? List.of(
                                                                TextBlock.builder()
                                                                        .text("recovered")
                                                                        .build())
                                                        : List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("job")
                                                                        .name("sandbox.exec")
                                                                        .input(
                                                                                Map.of(
                                                                                        "command",
                                                                                        "printf"
                                                                                            + " 'once\\n"
                                                                                            + "' >> effects;"
                                                                                            + " sleep"
                                                                                            + " 1; printf"
                                                                                            + " 'original-result'",
                                                                                        "timeout_seconds",
                                                                                        10))
                                                                        .build()))
                                        .build());
                    }
                };
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerSchema(
                                    ToolSchema.builder()
                                            .name("sandbox.exec")
                                            .description("fixture")
                                            .parameters(Map.of("type", "object"))
                                            .build());
                            return ReActAgent.builder()
                                    .name("fixture")
                                    .model(model)
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build());
                        },
                        Map.of("sandbox.exec", "sandbox"));
        var executor =
                new DirectSandboxExecutor(
                        "pinned-fixture",
                        (request, command) -> {
                            var process =
                                    new ProcessBuilder("sh", "-c", command)
                                            .directory(workspace.toFile())
                                            .start();
                            String result =
                                    new String(
                                            process.getInputStream().readAllBytes(),
                                            StandardCharsets.UTF_8);
                            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
                                throw new IllegalStateException("Control process failed");
                            return result;
                        });
        return DurableRuntime.builder()
                .backend(backend)
                .harness("agent", "v1", adapter)
                .executor("sandbox", executor)
                .leaseDuration(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(10))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[1]), workspace = Path.of(args[2]);
        SessionBackend backend = new TransactionalSessionBackend(JdbcRecordStore.sqlite(db));
        if ("after-accept".equals(args[0])) {
            SessionBackend target = backend;
            backend =
                    (SessionBackend)
                            Proxy.newProxyInstance(
                                    SessionBackend.class.getClassLoader(),
                                    new Class<?>[] {SessionBackend.class},
                                    (proxy, method, values) -> {
                                        Object result;
                                        try {
                                            result = method.invoke(target, values);
                                        } catch (InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                        if ("acceptResult".equals(method.getName())
                                                && ((ExecutionResult) values[2]).state().terminal())
                                            Runtime.getRuntime().halt(17);
                                        return result;
                                    });
        }
        try (var runtime = runtime(backend, workspace)) {
            runtime.createSession(KEY, "agent").mainThread().view().definition();
            runtime.getSession(KEY).submit("request", "execute");
            for (int i = 0; i < 500; i++) {
                runtime.tick(new ThreadKey(KEY, "main"));
                if (runtime.lastFailure(new ThreadKey(KEY, "main")) != null)
                    throw new IllegalStateException(
                            runtime.lastFailure(new ThreadKey(KEY, "main")));
                if (i == 1 && "during-tool".equals(args[0])) {
                    Files.writeString(workspace.resolve("ready"), "ready");
                    Thread.sleep(30000);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // The fixture must keep ticking until the acceptResult proxy records the
                    // terminal handoff. A runtime wakeup may interrupt this polling sleep.
                    Thread.interrupted();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"during-tool", "after-accept"})
    void replacementCollectsOriginalResultWithoutRepeatingEffect(String mode) throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("sandbox"));
        Path db = directory.resolve("runtime.db");
        String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                classpath,
                                ProcessRecoveryTest.class.getName(),
                                mode,
                                db.toString(),
                                workspace.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("brain.log").toFile())
                        .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            if ("during-tool".equals(mode)) {
                while (!Files.exists(workspace.resolve("ready"))
                        && process.isAlive()
                        && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(
                        Files.exists(workspace.resolve("ready")),
                        () -> read(directory.resolve("brain.log")));
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            } else {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(17, process.exitValue(), () -> read(directory.resolve("brain.log")));
            }
            var backend = new TransactionalSessionBackend(JdbcRecordStore.sqlite(db));
            try (var replacement = runtime(backend, workspace)) {
                var receipt = replacement.getSession(KEY).submit("request", "execute");
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!backend.turn(new ThreadKey(KEY, "main"), receipt.turnId())
                                .status()
                                .terminal()
                        && System.nanoTime() < deadline) {
                    replacement.tick(new ThreadKey(KEY, "main"));
                    assertNull(replacement.lastFailure(new ThreadKey(KEY, "main")));
                    Thread.sleep(20);
                }
                assertEquals(
                        TurnStatus.SUCCEEDED,
                        backend.turn(new ThreadKey(KEY, "main"), receipt.turnId()).status());
                assertEquals("once\n", Files.readString(workspace.resolve("effects")));
                assertEquals(2, Files.readAllLines(workspace.resolve("model-calls")).size());
                assertEquals(
                        1,
                        backend.events(KEY, null, 100).events().stream()
                                .filter(e -> "tool.result.applied".equals(e.type()))
                                .count());
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception error) {
            return error.toString();
        }
    }
}

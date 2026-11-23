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

package io.agentscope.extensions.durable.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectSandboxExecutorTest {
    @TempDir Path directory;

    DirectSandboxExecutor executor() {
        return new DirectSandboxExecutor(
                "sandbox-fixture",
                (request, command) -> {
                    assertTrue(command.length() < 32767, "Control command exceeds Windows limit");
                    var process =
                            new ProcessBuilder("sh", "-c", command)
                                    .directory(directory.toFile())
                                    .start();
                    String output =
                            new String(
                                    process.getInputStream().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                    assertEquals(
                            0,
                            process.exitValue(),
                            () -> {
                                try {
                                    return new String(
                                            process.getErrorStream().readAllBytes(),
                                            StandardCharsets.UTF_8);
                                } catch (Exception e) {
                                    return e.toString();
                                }
                            });
                    return output;
                });
    }

    ExecutionRequest request(String id, String command) {
        return new ExecutionRequest(
                id,
                new ThreadKey(new SessionKey("tenant", "session"), "main"),
                "turn",
                new ToolCall(
                        "call",
                        "sandbox",
                        "sandbox.exec",
                        DurableJson.value(Map.of("command", command, "timeout_seconds", 10))));
    }

    ExecutionResult await(DirectSandboxExecutor executor, ExecutionRequest request)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ExecutionResult result;
        do {
            result = executor.query(request, null);
            if (result.state().terminal()) return result;
            Thread.sleep(30);
        } while (System.nanoTime() < until);
        throw new AssertionError("Unfinished Sandbox operation: " + result);
    }

    @Test
    void detachedOperationSurvivesControlProcessAndExecutorReplacement() throws Exception {
        var request = request("one", "sleep 0.2; printf '你好'; printf 'once\\n' >> count");
        var first = executor();
        assertEquals(ExecutionState.RUNNING, first.startExecution(request).state());
        first.close();
        var replacement = executor();
        var result = await(replacement, request);
        assertEquals(ExecutionState.SUCCEEDED, result.state());
        assertEquals("你好", result.result().path("output").asText());
        replacement.startExecution(
                request); // Explicit duplicate admission uses original Sandbox receipt.
        assertEquals("once\n", Files.readString(directory.resolve("count")));
        var output = replacement.output(request, 0);
        assertEquals(
                "你好",
                new String(Base64.getDecoder().decode(output.data()), StandardCharsets.UTF_8));
        assertTrue(output.terminal());
        assertThrows(
                DurableException.class,
                () ->
                        new DirectSandboxExecutor("different", (r, c) -> "{}")
                                .query(request, result.handle()));
        assertFalse(
                first.capabilities()
                        .idempotentStart()); // Lost journal must never authorize a replay.
    }

    @Test
    void cancellationRequiresConfirmedRemoteTermination() throws Exception {
        var executor = executor();
        var request = request("cancel", "sleep 8; printf 'unexpected' > finished");
        executor.startExecution(request);
        executor.cancel(request, null);
        assertEquals(ExecutionState.CANCELLED, await(executor, request).state());
        assertFalse(Files.exists(directory.resolve("finished")));
    }

    @Test
    void missingOrPartialJournalNeverInventsSuccess() throws Exception {
        var executor = executor();
        var request = request("missing", "true");
        assertEquals(ExecutionState.NOT_FOUND, executor.query(request, null).state());
        String id = DurableJson.hash(List.of(request.session(), request.executionId()));
        Files.createDirectories(directory.resolve(".durable-executions").resolve(id));
        assertEquals(ExecutionState.UNKNOWN, executor.startExecution(request).state());
        assertEquals(ExecutionState.UNKNOWN, executor.query(request, null).state());
    }

    @Test
    void fileOperationsCannotReadPublishOrReplaceLifecycleEvidence() throws Exception {
        Files.writeString(directory.resolve(".durable-sandbox-identity"), "original");
        var executor = executor();
        int sequence = 0;
        for (String operation : List.of("fs.upload", "fs.download", "artifact.publish")) {
            for (String path :
                    List.of(
                            ".durable-sandbox-identity",
                            ".durable-executions",
                            ".durable-executions/private")) {
                var request =
                        new ExecutionRequest(
                                "private-" + sequence++,
                                new ThreadKey(new SessionKey("tenant", "session"), "main"),
                                "turn",
                                new ToolCall(
                                        "call",
                                        "sandbox",
                                        operation,
                                        DurableJson.value(
                                                Map.of("path", path, "content", "bmV3"))));
                executor.startExecution(request);
                assertEquals(ExecutionState.FAILED, await(executor, request).state());
            }
        }
        assertEquals("original", Files.readString(directory.resolve(".durable-sandbox-identity")));
        assertFalse(Files.exists(directory.resolve(".durable-executions/private")));
    }
}

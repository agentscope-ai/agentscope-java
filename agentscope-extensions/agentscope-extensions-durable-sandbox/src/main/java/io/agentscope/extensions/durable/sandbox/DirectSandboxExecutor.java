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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.Executor;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * Detached execution and an operation journal inside a pinned POSIX/Python 3 Sandbox.
 * Reuses any Harness Sandbox connection; never creates, snapshots, replaces or closes that Sandbox.
 */
public final class DirectSandboxExecutor implements Executor {
    @FunctionalInterface
    public interface Control {
        String execute(ExecutionRequest request, String command) throws Exception;
    }

    private static final String PROGRAM = program();
    private final String sandboxId;
    private final Control control;

    public DirectSandboxExecutor(String sandboxId, Control control) {
        this.sandboxId = DurableJson.text(sandboxId, "sandboxId");
        this.control = Objects.requireNonNull(control);
    }

    public DirectSandboxExecutor(String sandboxId, Sandbox sandbox) {
        this(
                sandboxId,
                (request, command) -> {
                    var context =
                            RuntimeContext.builder()
                                    .userId(request.session().namespace())
                                    .sessionId(request.session().sessionId())
                                    .turnId(request.turnId())
                                    .build();
                    var result = sandbox.exec(context, command, 30);
                    if (!result.ok() || result.truncated())
                        throw new DurableException(
                                DurableException.Code.UNAVAILABLE,
                                "Sandbox control response unavailable");
                    return result.stdout();
                });
    }

    @Override
    public Capabilities capabilities() {
        // A deleted operation journal is not proof that its side effect never happened.
        return new Capabilities(false, true, true, true);
    }

    private String operationId(ExecutionRequest request) {
        return DurableJson.hash(List.of(request.session(), request.executionId()));
    }

    private String handle(ExecutionRequest request) {
        return sandboxId + ":" + operationId(request);
    }

    private void checkHandle(ExecutionRequest request, String handle) {
        if (handle != null && !handle.equals(handle(request)))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Original Sandbox binding changed");
    }

    @Override
    public ExecutionResult startExecution(ExecutionRequest request) {
        var response = call("start", request, Map.of());
        if (!operationId(request).equals(response.path("handle").asText()))
            return new ExecutionResult(
                    ExecutionState.UNKNOWN,
                    handle(request),
                    null,
                    "Sandbox admission is unconfirmed");
        return new ExecutionResult(ExecutionState.RUNNING, handle(request), null, null);
    }

    @Override
    public ExecutionResult query(ExecutionRequest request, String handle) {
        checkHandle(request, handle);
        return outcome(request, call("query", request, Map.of()));
    }

    @Override
    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        checkHandle(request, handle);
        return outcome(request, call("cancel", request, Map.of()));
    }

    /** Byte offsets are independent of public event sequence. Data is base64; explicit gaps are retained. */
    public OutputPage output(ExecutionRequest request, long offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative output cursor");
        var reply = call("output", request, Map.of("offset", offset));
        if (reply.has("state"))
            return new OutputPage(offset, offset, "", offset, false, "source_unavailable");
        return DurableJson.convert(reply, OutputPage.class);
    }

    private ExecutionResult outcome(ExecutionRequest request, JsonNode response) {
        return new ExecutionResult(
                ExecutionState.valueOf(response.path("state").asText("UNKNOWN")),
                handle(request),
                response.get("result"),
                response.path("error").asText(null));
    }

    private JsonNode call(String operation, ExecutionRequest request, Map<String, Object> extra) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("operation", operation);
        payload.put("execution", operationId(request));
        payload.put("tool", request.call().name());
        payload.put("arguments", request.call().arguments());
        payload.put("program", PROGRAM);
        payload.putAll(extra);
        String encoded =
                Base64.getEncoder()
                        .encodeToString(
                                DurableJson.value(payload)
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8));
        String command =
                "python3 -c 'import base64,gzip;exec(gzip.decompress(base64.b64decode(\""
                        + PROGRAM
                        + "\")))' <<'DURABLE_REQUEST'\n"
                        + encoded
                        + "\nDURABLE_REQUEST\n";
        try {
            return DurableJson.parse(control.execute(request, command));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Sandbox control outcome is unconfirmed",
                    error);
        }
    }

    private static String program() {
        try (var stream =
                DirectSandboxExecutor.class.getResourceAsStream("/durable-executions.py")) {
            if (stream == null)
                throw new IllegalStateException("Sandbox execution program missing");
            var compressed = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(compressed)) {
                stream.transferTo(gzip);
            }
            return Base64.getEncoder().encodeToString(compressed.toByteArray());
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}

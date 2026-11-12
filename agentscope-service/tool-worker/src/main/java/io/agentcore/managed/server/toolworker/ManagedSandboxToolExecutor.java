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
package io.agentcore.managed.server.toolworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedTransportException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Queryable stable execution journal inside the sandbox, separate from the HarnessRuntime lifetime. */
public final class ManagedSandboxToolExecutor implements ManagedToolExecutor {
    @FunctionalInterface
    public interface Control {
        String execute(String command) throws Exception;
    }

    private static final String PROGRAM = program();
    private final ObjectMapper mapper;
    private final Control control;

    public ManagedSandboxToolExecutor(ObjectMapper mapper, Control control) {
        this.mapper = mapper;
        this.control = control;
    }

    public boolean idempotentStart() {
        // The sandbox journal may disappear. Recovery queries an original execution and cannot
        // treat a missing marker as proof that the side effect never happened.
        return false;
    }

    public String start(ManagedScope scope, ToolTask task) {
        var response = call("start", task);
        if (!task.executionId().equals(response.path("handle").asText()))
            throw new ManagedTransportException("Sandbox did not acknowledge the stable execution");
        return task.executionId();
    }

    public Outcome query(ManagedScope scope, ToolTask task) {
        return outcome(call("query", task));
    }

    public Outcome cancel(ManagedScope scope, ToolTask task) {
        return outcome(call("cancel", task));
    }

    public boolean streamsOutput(ToolTask task) {
        return "sandbox.exec".equals(task.name());
    }

    public OutputPage output(ManagedScope scope, ToolTask task, long offset) {
        try {
            return mapper.treeToValue(
                    call("output", task, Map.of("offset", offset)), OutputPage.class);
        } catch (Exception invalid) {
            throw new ManagedTransportException("Sandbox output source unavailable", invalid);
        }
    }

    private JsonNode call(String operation, ToolTask task) {
        return call(operation, task, Map.of());
    }

    private JsonNode call(String operation, ToolTask task, Map<String, Object> extra) {
        try {
            var request =
                    new LinkedHashMap<String, Object>(
                            Map.of(
                                    "operation",
                                    operation,
                                    "execution",
                                    task.executionId(),
                                    "tool",
                                    task.name(),
                                    "arguments",
                                    mapper.readTree(task.arguments()),
                                    "program",
                                    PROGRAM));
            request.putAll(extra);
            String payload = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(request));
            String command =
                    "python3 -c 'import base64;exec(base64.b64decode(\""
                            + PROGRAM
                            + "\"))' <<'MANAGED_REQUEST'\n"
                            + payload
                            + "\nMANAGED_REQUEST\n";
            return mapper.readTree(control.execute(command));
        } catch (Exception unavailable) {
            throw new ManagedTransportException("Sandbox control outcome unavailable", unavailable);
        }
    }

    private Outcome outcome(JsonNode response) {
        var state = State.valueOf(response.path("state").asText("UNKNOWN"));
        return new Outcome(
                state, response.hasNonNull("result") ? response.path("result").toString() : null);
    }

    private static String program() {
        try (var stream =
                ManagedSandboxToolExecutor.class.getResourceAsStream("/managed-tools.py")) {
            if (stream == null)
                throw new IllegalStateException("Sandbox execution program missing");
            return Base64.getEncoder().encodeToString(stream.readAllBytes());
        } catch (Exception unavailable) {
            throw new ExceptionInInitializerError(unavailable);
        }
    }
}

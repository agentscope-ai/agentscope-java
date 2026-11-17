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
package io.agentscope.durable.examples.brainhands.toolinsandbox;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.ToolBinding;
import java.util.List;
import java.util.Map;

/** Frozen tool contract shared by Brain and Hands, without either process's runtime wiring. */
final class SandboxTool {
    static final String COMMAND = "printf once >> count; printf recovered";
    static final String FINAL_ANSWER = "Sandbox result applied: recovered";
    private static final int COMMAND_TIMEOUT_SECONDS = 30;

    private SandboxTool() {}

    static ToolBinding tool() {
        return new ToolBinding(
                "sandbox.exec",
                "v1",
                "hands",
                DurableJson.value(
                        Map.of(
                                "name",
                                "sandbox.exec",
                                "description",
                                "Run the fixed demonstration command in the durable" + " Sandbox",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "command", Map.of("type", "string"),
                                                "timeout_seconds", Map.of("type", "integer")),
                                        "required",
                                        List.of("command", "timeout_seconds"),
                                        "additionalProperties",
                                        false))),
                DurableJson.value(Map.of("operation", "sandbox.exec")));
    }

    static Map<String, Object> commandArguments() {
        return Map.of("command", COMMAND, "timeout_seconds", COMMAND_TIMEOUT_SECONDS);
    }
}

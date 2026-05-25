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
package io.agentscope.core.tool.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolkitToolBaseIntegrationTest {

    private static class EchoToolBase extends io.agentscope.core.tool.permission.ToolBase {
        EchoToolBase() {
            super(
                    "echo_tool",
                    "Echoes the provided text",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("text", Map.of("type", "string")),
                            "required",
                            List.of("text")),
                    /* isReadOnly */ true,
                    /* isConcurrencySafe */ true,
                    /* isMcp */ false,
                    /* mcpName */ null,
                    /* isExternalTool */ false,
                    /* isStateInjected */ false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContext context) {
            return Mono.just(PermissionDecision.allow("read-only echo"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            String text = String.valueOf(param.getInput().getOrDefault("text", ""));
            return Mono.just(
                    ToolResultBlock.of(
                            param.getToolUseBlock().getId(),
                            getName(),
                            TextBlock.builder().text(text).build()));
        }
    }

    @Test
    void toolBaseSubclassRegistersAndExecutesViaToolkit() {
        Toolkit toolkit = new Toolkit();
        EchoToolBase tool = new EchoToolBase();
        toolkit.registerAgentTool(tool);

        Map<String, Object> input = Map.of("text", "hi");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("echo_tool")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolCall).input(input).build();

        StepVerifier.create(toolkit.callTool(param))
                .assertNext(
                        result -> {
                            assertNotNull(result);
                            assertEquals("call-1", result.getId());
                            assertEquals("echo_tool", result.getName());
                        })
                .verifyComplete();
    }
}

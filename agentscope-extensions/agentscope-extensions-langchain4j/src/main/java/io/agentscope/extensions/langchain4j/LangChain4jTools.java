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
package io.agentscope.extensions.langchain4j;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolService;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Adapts bound LangChain4j tools to AgentScope's normal Toolkit execution path.
 *
 * <p>The caller owns the scheduler and invocation-context mapping. Cancellation interrupts a
 * scheduled synchronous call but cannot roll back external side effects. Register tools before
 * sharing a Toolkit; registration must not race with another registry update.
 */
public final class LangChain4jTools {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private LangChain4jTools() {}

    /**
     * Reuses the bound annotation scanner from LangChain4j 1.22.0. That API is marked Internal;
     * upgrades must run the annotated-executor compatibility tests. Call {@link #register} with
     * explicit AiServiceTool entries to avoid depending on discovery.
     */
    public static List<AgentTool> registerAnnotated(
            Toolkit toolkit,
            Object object,
            Scheduler scheduler,
            Function<ToolCallParam, InvocationContext> contextMapper) {
        return register(toolkit, ToolService.findTools(object), scheduler, contextMapper);
    }

    /**
     * Validates the complete input before registering it; concurrent registry mutation is excluded.
     */
    public static List<AgentTool> register(
            Toolkit toolkit,
            List<AiServiceTool> tools,
            Scheduler scheduler,
            Function<ToolCallParam, InvocationContext> contextMapper) {
        Objects.requireNonNull(toolkit);
        List<AgentTool> adapted =
                tools.stream().map(tool -> adapt(tool, scheduler, contextMapper)).toList();
        HashSet<String> names = new HashSet<>();
        for (AgentTool tool : adapted) {
            if (!names.add(tool.getName()) || toolkit.getTool(tool.getName()) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.getName());
            }
        }
        adapted.forEach(toolkit::registerAgentTool);
        return adapted;
    }

    /** Adapts explicitly supplied tools without depending on the internal discovery scanner. */
    public static AgentTool adapt(
            AiServiceTool tool,
            Scheduler scheduler,
            Function<ToolCallParam, InvocationContext> contextMapper) {
        return new AdaptedTool(tool, scheduler, contextMapper);
    }

    // ToolBase is required: ReActAgent's permission engine does not gate legacy AgentTool objects.
    private static final class AdaptedTool extends ToolBase {
        private final AiServiceTool tool;
        private final Scheduler scheduler;
        private final Function<ToolCallParam, InvocationContext> contextMapper;

        private AdaptedTool(
                AiServiceTool tool,
                Scheduler scheduler,
                Function<ToolCallParam, InvocationContext> contextMapper) {
            super(
                    ToolBase.builder()
                            .name(tool.name())
                            .description(
                                    Objects.requireNonNullElse(
                                            tool.toolSpecification().description(), ""))
                            .inputSchema(parameters(tool))
                            .readOnly(false)
                            .concurrencySafe(false));
            this.tool = Objects.requireNonNull(tool);
            this.scheduler = Objects.requireNonNull(scheduler);
            this.contextMapper = Objects.requireNonNull(contextMapper);
            if (tool.returnBehavior() != ReturnBehavior.TO_LLM) {
                throw new UnsupportedOperationException(
                        "Only TO_LLM is supported: LangChain4j immediate modes have different"
                                + " mixed-batch semantics");
            }
        }

        @Override
        public Boolean getStrict() {
            return tool.toolSpecification().strict();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(
                            () -> {
                                Objects.requireNonNull(param);
                                if (param.getToolUseBlock() == null
                                        || !getName().equals(param.getToolUseBlock().getName())) {
                                    throw new IllegalArgumentException(
                                            "Tool call must name " + getName());
                                }
                                ToolExecutionRequest request =
                                        ToolExecutionRequest.builder()
                                                .id(param.getToolUseBlock().getId())
                                                .name(getName())
                                                .arguments(
                                                        JsonUtils.getJsonCodec()
                                                                .toJson(param.getInput()))
                                                .build();
                                InvocationContext context =
                                        Objects.requireNonNull(
                                                contextMapper.apply(param),
                                                "mapped invocation context");
                                ToolExecutionResult result =
                                        Objects.requireNonNull(
                                                tool.toolExecutor()
                                                        .executeWithContext(request, context));
                                List<ContentBlock> output = new ArrayList<>();
                                for (var content : result.resultContents()) {
                                    if (!(content instanceof TextContent text)) {
                                        throw new UnsupportedOperationException(
                                                "Only text tool results are supported: "
                                                        + content.getClass().getSimpleName());
                                    }
                                    output.add(TextBlock.builder().text(text.text()).build());
                                }
                                return ToolResultBlock.builder()
                                        .id(request.id())
                                        .name(getName())
                                        .output(output)
                                        .state(
                                                result.isError()
                                                        ? ToolResultState.ERROR
                                                        : ToolResultState.SUCCESS)
                                        .build();
                            })
                    .subscribeOn(scheduler);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(AiServiceTool tool) {
        // Public lossless serializer preserves $defs, raw constraints, anyOf and strictness.
        Map<String, Object> definition =
                JsonUtils.getJsonCodec().fromJson(tool.toolSpecification().toJson(), MAP);
        Object schema = definition.get("parameters");
        return (Map<String, Object>)
                immutable(
                        schema == null ? Map.of("type", "object", "properties", Map.of()) : schema);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutable(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(
                    list.stream().map(LangChain4jTools::immutable).toList());
        }
        return value;
    }
}

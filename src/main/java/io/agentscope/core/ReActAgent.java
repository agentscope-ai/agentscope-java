/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.ReActAgentBase;
import io.agentscope.core.formatter.FormatterBase;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ParallelToolExecutor;
import io.agentscope.core.tool.ToolResponse;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ReAct (Reasoning and Acting) Agent implementation.
 *
 * This agent follows the Python version's architecture and provides:
 * - Reasoning and acting steps in the ReAct algorithm
 * - Tool calling capabilities
 * - Memory management
 * - Streaming support
 *
 * Method names are aligned with the Python version:
 * - reply(): Main response generation (replaces call())
 * - stream(): Streaming response generation via Reactive Streams
 * - reasoning(): Reasoning step in ReAct loop
 * - acting(): Acting step in ReAct loop
 */
public class ReActAgent extends ReActAgentBase {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private final String sysPrompt;
    private final Model model;
    private final Toolkit toolkit;
    private final FormatterBase formatter;
    private final String finishFunctionName;
    private final boolean parallelToolCalls;

    public ReActAgent(String name, String sysPrompt, Model model, Toolkit toolkit, Memory memory) {
        this(
                name,
                sysPrompt,
                model,
                toolkit,
                new OpenAIChatFormatter(),
                memory,
                10,
                "generate_response",
                false);
    }

    public ReActAgent(
            String name,
            String sysPrompt,
            Model model,
            Toolkit toolkit,
            FormatterBase formatter,
            Memory memory,
            int maxIters,
            String finishFunctionName,
            boolean parallelToolCalls) {
        super(name, memory, maxIters);
        this.sysPrompt = sysPrompt;
        this.model = model;
        this.toolkit = toolkit;
        this.formatter = formatter;
        this.finishFunctionName = finishFunctionName;
        this.parallelToolCalls = parallelToolCalls;
    }

    /**
     * The reasoning step in ReAct algorithm.
     * This method generates reasoning based on the current context and input.
     */
    @Override
    public Flux<Msg> reasoning() {
        return Mono.just(new Object())
                .flatMapMany(
                        ignore ->
                                formatter
                                        .format(prepareMessageList())
                                        .flatMapMany(
                                                formattedMessages -> {
                                                    // No need to convert - use FormattedMessageList
                                                    // directly
                                                    List<ToolSchema> toolSchemas =
                                                            toToolSchemas(toolkit.getToolSchemas());
                                                    GenerateOptions options = new GenerateOptions();
                                                    List<Msg> collected =
                                                            new CopyOnWriteArrayList<>();
                                                    ToolCallAccumulator acc =
                                                            new ToolCallAccumulator();

                                                    Flux<Msg> streamed =
                                                            model.streamFlux(
                                                                            formattedMessages,
                                                                            toolSchemas,
                                                                            options)
                                                                    .flatMap(
                                                                            chunk ->
                                                                                    Flux
                                                                                            .fromIterable(
                                                                                                    processResponseChunk(
                                                                                                            chunk,
                                                                                                            acc)))
                                                                    .doOnNext(collected::add);

                                                    // On stream completion, if there is a pending
                                                    // tool
                                                    // call under assembly,
                                                    // emit a final ToolUse message so acting() can
                                                    // pick
                                                    // it up.
                                                    Flux<Msg> finalize =
                                                            Flux.defer(
                                                                            () -> {
                                                                                Msg pending =
                                                                                        acc
                                                                                                .buildIfPresent(
                                                                                                        getName());
                                                                                return pending
                                                                                                != null
                                                                                        ? Flux.just(
                                                                                                pending)
                                                                                        : Flux
                                                                                                .empty();
                                                                            })
                                                                    .doOnNext(collected::add)
                                                                    .doFinally(
                                                                            s ->
                                                                                    addToMemory(
                                                                                            aggregateRoundMessages(
                                                                                                    collected)));

                                                    return Flux.concat(streamed, finalize);
                                                }));
    }

    /**
     * Aggregate a sequence of streamed messages into one message for loop control.
     * Priority: use the latest ToolUseBlock if any; otherwise merge text blocks.
     */
    private Msg aggregateRoundMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            ContentBlock cb = messages.get(i).getContent();
            if (cb instanceof ToolUseBlock) {
                return messages.get(i);
            }
        }

        String id = null;
        StringBuilder combined = new StringBuilder();
        for (Msg m : messages) {
            id = m.getId();
            ContentBlock cb = m.getContent();
            if (cb instanceof TextBlock tb) {
                combined.append(tb.getText());
            }
        }

        if (!combined.isEmpty()) {
            return Msg.builder()
                    .id(id)
                    .name(getName())
                    .role(MsgRole.ASSISTANT)
                    .content(TextBlock.builder().text(combined.toString()).build())
                    .build();
        }

        return messages.get(messages.size() - 1);
    }

    /**
     * The acting step in ReAct algorithm.
     * This method executes actions based on reasoning results.
     */
    @Override
    public Flux<Msg> acting() {
        List<Msg> messages = getMemory().getMessages();
        if (messages == null || messages.isEmpty()) {
            return Flux.empty();
        }
        Msg x = messages.get(messages.size() - 1);
        List<ToolUseBlock> toolCalls = extractToolCalls(x);
        if (toolCalls.isEmpty()) {
            return Flux.just(x);
        }
        ParallelToolExecutor executor = new ParallelToolExecutor(toolkit);
        return executor.executeTools(toolCalls, parallelToolCalls)
                .flatMapMany(
                        responses -> {
                            // Create tool response messages with correct tool_call_id
                            List<Msg> toolResults = new ArrayList<>();
                            for (int i = 0; i < responses.size() && i < toolCalls.size(); i++) {
                                ToolResponse r = responses.get(i);
                                ToolUseBlock originalCall = toolCalls.get(i);

                                StringBuilder combined = new StringBuilder();
                                r.getContent()
                                        .forEach(
                                                cb -> {
                                                    if (cb instanceof TextBlock tb) {
                                                        if (!combined.isEmpty())
                                                            combined.append("\n");
                                                        combined.append(tb.getText());
                                                    }
                                                });

                                Msg toolMsg =
                                        Msg.builder()
                                                .name(getName())
                                                .role(MsgRole.TOOL)
                                                .content(
                                                        ToolResultBlock.builder()
                                                                .id(originalCall.getId())
                                                                .name(originalCall.getName())
                                                                .output(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        combined
                                                                                                .toString())
                                                                                .build())
                                                                .build())
                                                .build();
                                addToMemory(toolMsg);
                                toolResults.add(toolMsg);
                            }
                            return Flux.just(toolResults.toArray(new Msg[0]));
                        });
    }

    /**
     * Check if the message indicates the ReAct loop should finish.
     */
    @Override
    protected boolean isFinished(Msg msg) {
        // Check if the message contains a finish function call
        List<ToolUseBlock> toolCalls = extractToolCalls(msg);
        return toolCalls.stream()
                .noneMatch(toolCall -> toolkit.getTool(toolCall.getName()) != null);
    }

    // Reactive Streams entrypoints are provided by AgentBase.stream(...)

    private List<Msg> prepareMessageList() {
        List<Msg> messages = new ArrayList<>();

        // Add system prompt
        if (sysPrompt != null && !sysPrompt.trim().isEmpty()) {
            Msg systemMsg =
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(sysPrompt).build())
                            .build();
            messages.add(systemMsg);
        }

        // Add memory messages
        messages.addAll(getMemory().getMessages());

        return messages;
    }

    // Removed unused convertMsgToMap

    private List<Msg> processResponseChunk(ChatResponse response, ToolCallAccumulator acc) {
        List<Msg> out = new ArrayList<>();
        List<ContentBlock> contentBlocks = response.getContent();

        // Handle tool call chunks: merge into accumulator; do not emit until finalized
        for (ContentBlock block : contentBlocks) {
            if (block instanceof ToolUseBlock tub) {
                acc.merge(response);
                acc.merge(tub);
            }
        }

        // Emit text/thinking blocks for streaming UX
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock textBlock) {
                out.add(
                        Msg.builder()
                                .id(response.getId())
                                .name(getName())
                                .role(MsgRole.ASSISTANT)
                                .content(block)
                                .build());
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                out.add(
                        Msg.builder()
                                .id(response.getId())
                                .name(getName())
                                .role(MsgRole.ASSISTANT)
                                .content(block)
                                .build());
            }
        }

        return out;
    }

    private static class ToolCallAccumulator {
        private String msgId;
        private String toolId;
        private String name;
        private final Map<String, Object> args = new java.util.concurrent.ConcurrentHashMap<>();
        private final StringBuilder rawContent = new StringBuilder();

        void merge(ChatResponse msg) {
            msgId = msg.getId();
        }

        void merge(ToolUseBlock block) {
            // For DashScope fragmented tool calls:
            // - First chunk: has real name and id
            // - Subsequent chunks: have placeholder "__fragment__" name
            if (block.getId() != null && !block.getId().isEmpty()) {
                this.toolId = block.getId();
            }
            if (block.getName() != null && !"__fragment__".equals(block.getName())) {
                this.name = block.getName();
            }
            if (block.getInput() != null) {
                this.args.putAll(block.getInput());
            }

            // Accumulate raw content for fragmented tool call arguments
            if (block.getContent() != null) {
                rawContent.append(block.getContent());
            }
        }

        Msg buildIfPresent(String agentName) {
            if (name == null) return null;

            Map<String, Object> finalArgs = new HashMap<>(args);

            // If we have accumulated raw content and no parsed args, try to parse the complete
            // content
            if (finalArgs.isEmpty() && rawContent.length() > 0) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(rawContent.toString(), Map.class);
                    if (parsed != null) {
                        finalArgs.putAll(parsed);
                    }
                } catch (Exception ignored) {
                    // If parsing still fails, keep empty args
                }
            }

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(toolId != null ? toolId : genId())
                            .name(name)
                            .input(finalArgs)
                            .build();
            return Msg.builder().name(agentName).role(MsgRole.ASSISTANT).content(toolUse).build();
        }

        private String genId() {
            return "tool_call_" + System.currentTimeMillis();
        }
    }

    /**
     * Extract tool calls from a message.
     */
    private List<ToolUseBlock> extractToolCalls(Msg msg) {
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        ContentBlock content = msg.getContent();

        if (content instanceof ToolUseBlock toolUseBlock) {
            toolCalls.add(toolUseBlock);
        }

        return toolCalls;
    }

    private List<ToolSchema> toToolSchemas(List<Map<String, Object>> tools) {
        List<ToolSchema> out = new ArrayList<>();
        if (tools == null) return out;
        for (Map<String, Object> t : tools) {
            Object fn = t.get("function");
            if (fn instanceof Map<?, ?> fm) {
                ToolSchema.Builder b = ToolSchema.builder();
                Object name = fm.get("name");
                if (name != null) b.name(String.valueOf(name));
                Object desc = fm.get("description");
                if (desc != null) b.description(String.valueOf(desc));
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) fm.get("parameters");
                if (params != null) b.parameters(params);
                out.add(b.build());
            }
        }
        return out;
    }

    /**
     * Get the system prompt.
     */
    public String getSysPrompt() {
        return sysPrompt;
    }

    /**
     * Get the model.
     */
    public Model getModel() {
        return model;
    }

    /**
     * Get the toolkit.
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Get the formatter.
     */
    public FormatterBase getFormatter() {
        return formatter;
    }

    /**
     * Get the finish function name.
     */
    public String getFinishFunctionName() {
        return finishFunctionName;
    }

    /**
     * Check if parallel tool calls are enabled.
     */
    public boolean isParallelToolCalls() {
        return parallelToolCalls;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String sysPrompt;
        private Model model;
        private Toolkit toolkit;
        private FormatterBase formatter = new OpenAIChatFormatter();
        private Memory memory;
        private int maxIters = 10;
        private String finishFunctionName = "generate_response";
        private boolean parallelToolCalls = false;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder formatter(FormatterBase formatter) {
            this.formatter = formatter;
            return this;
        }

        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        public Builder finishFunctionName(String finishFunctionName) {
            this.finishFunctionName = finishFunctionName;
            return this;
        }

        public Builder parallelToolCalls(boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(
                    name,
                    sysPrompt,
                    model,
                    toolkit,
                    formatter,
                    memory,
                    maxIters,
                    finishFunctionName,
                    parallelToolCalls);
        }
    }
}

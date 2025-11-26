package io.agentscope.core.tracing;

import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.agent.accumulator.ThinkingAccumulator;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An aggregator for streaming {@link ChatResponse}.
 * */
final class StreamChatResponseAggregator {

    private String id;

    // Only text output is currently supported.
    private final TextAccumulator textAcc = new TextAccumulator();
    private final ThinkingAccumulator thinkingAcc = new ThinkingAccumulator();
    private final ToolCallsAccumulator toolCallsAcc = new ToolCallsAccumulator();

    // Usage
    private final AtomicInteger inputTokens = new AtomicInteger(0);
    private final AtomicInteger outputTokens = new AtomicInteger(0);
    private double time;

    private String finishReason;

    public void append(ChatResponse chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.getId() != null) {
            id = chunk.getId();
        }

        // See io.agentscope.core.agent.accumulator.ReasoningContext.processChunk for more
        // information
        List<ContentBlock> chunkContents = chunk.getContent();
        if (chunkContents != null) {
            for (ContentBlock block : chunkContents) {
                if (block instanceof TextBlock tb) {
                    textAcc.add(tb);
                } else if (block instanceof ThinkingBlock tb) {
                    thinkingAcc.add(tb);
                } else if (block instanceof io.agentscope.core.message.ToolUseBlock tub) {
                    toolCallsAcc.add(tub);
                }
            }
        }

        ChatUsage usage = chunk.getUsage();
        if (usage != null) {
            inputTokens.addAndGet(usage.getInputTokens());
            outputTokens.addAndGet(usage.getOutputTokens());
            time = usage.getTime();
        }

        if (chunk.getFinishReason() != null) {
            finishReason = chunk.getFinishReason();
        }
    }

    public ChatResponse getResponse() {
        List<ToolUseBlock> toolUseBlocks = toolCallsAcc.buildAllToolCalls();
        List<ContentBlock> contentBlocks = new ArrayList<>(toolUseBlocks.size() + 2);
        contentBlocks.add(textAcc.buildAggregated());
        contentBlocks.add(thinkingAcc.buildAggregated());
        contentBlocks.addAll(toolUseBlocks);

        return ChatResponse.builder()
                .id(id)
                .content(contentBlocks)
                .usage(
                        ChatUsage.builder()
                                .inputTokens(inputTokens.get())
                                .outputTokens(outputTokens.get())
                                .time(time)
                                .build())
                .finishReason(finishReason)
                .build();
    }

    public static StreamChatResponseAggregator create() {
        return new StreamChatResponseAggregator();
    }

    private StreamChatResponseAggregator() {}
}

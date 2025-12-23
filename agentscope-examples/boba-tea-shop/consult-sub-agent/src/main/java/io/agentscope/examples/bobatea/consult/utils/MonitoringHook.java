package io.agentscope.examples.bobatea.consult.utils;

import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import reactor.core.publisher.Mono;

public class MonitoringHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCall) {
            System.out.println(
                    "\n[HOOK] PreCallEvent - Agent started: " + preCall.getAgent().getName());

        } else if (event instanceof ReasoningChunkEvent reasoningChunk) {
            // Print streaming reasoning content as it arrives (incremental chunks)
            Msg chunk = reasoningChunk.getIncrementalChunk();
            String text = MsgUtils.getTextContent(chunk);
            if (text != null && !text.isEmpty()) {
                System.out.print(text);
            }

        } else if (event instanceof PreActingEvent preActing) {
            System.out.println(
                    "\n[HOOK] PreActingEvent - Tool: "
                            + preActing.getToolUse().getName()
                            + ", Input: "
                            + preActing.getToolUse().getInput());

        } else if (event instanceof ActingChunkEvent actingChunk) {
            // Receive progress updates from ToolEmitter
            ToolResultBlock chunk = actingChunk.getChunk();
            String output = chunk.getOutput().isEmpty() ? "" : chunk.getOutput().get(0).toString();
            System.out.println(
                    "[HOOK] ActingChunkEvent - Tool: "
                            + actingChunk.getToolUse().getName()
                            + ", Progress: "
                            + output);

        } else if (event instanceof PostActingEvent postActing) {
            ToolResultBlock result = postActing.getToolResult();
            String output =
                    result.getOutput().isEmpty() ? "" : result.getOutput().get(0).toString();
            System.out.println(
                    "[HOOK] PostActingEvent - Tool: "
                            + postActing.getToolUse().getName()
                            + ", Result: "
                            + output);

        } else if (event instanceof PostCallEvent) {
            System.out.println("[HOOK] PostCallEvent - Agent execution finished\n");
        }

        // Return the event unchanged
        return Mono.just(event);
    }
}

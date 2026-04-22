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

package io.agentscope.examples.bobatea.supervisor.utils;

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
                    "\n[HOOK] PreCallEvent - Agent started: " + preCall.getMemory().getMessages());
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

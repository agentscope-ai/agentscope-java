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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accumulates server tool results returned by a provider during one reasoning round.
 *
 * <p>Server tool results are already complete when they arrive. They are keyed by
 * {@code tool_use_id} so each result can be placed immediately after its matching tool call in
 * the final assistant message. Results without a matching call are preserved in arrival order
 * and logged, rather than silently disappearing.
 *
 * @hidden
 */
final class ServerToolResultAccumulator {

    private static final Logger log = LoggerFactory.getLogger(ServerToolResultAccumulator.class);

    private final Map<String, ToolResultBlock> resultsById = new LinkedHashMap<>();
    private final List<ToolResultBlock> resultsWithoutId = new ArrayList<>();

    /**
     * Adds a server tool result.
     *
     * <p>Duplicate IDs keep the first result. A non-server result is ignored because local tool
     * results are produced in the acting phase and are not part of reasoning output.
     *
     * @hidden
     * @param result the result block returned by the provider
     */
    void add(ToolResultBlock result) {
        if (result == null || !result.isServerTool()) {
            return;
        }

        String id = result.getId();
        if (id == null || id.isBlank()) {
            resultsWithoutId.add(result);
            return;
        }
        resultsById.putIfAbsent(id, result);
    }

    /**
     * Returns whether any server tool result has been accumulated.
     *
     * @hidden
     * @return true if at least one result is present
     */
    boolean hasContent() {
        return !resultsById.isEmpty() || !resultsWithoutId.isEmpty();
    }

    /**
     * Builds all accumulated results in arrival order.
     *
     * @hidden
     * @return an immutable list of server tool results
     */
    List<ToolResultBlock> buildAllToolResults() {
        List<ToolResultBlock> results = new ArrayList<>(resultsById.values());
        results.addAll(resultsWithoutId);
        return List.copyOf(results);
    }

    /**
     * Places each matched result immediately after its tool call. Unmatched results are appended
     * after the tool calls so the data remains visible while still providing a diagnostic.
     *
     * @hidden
     * @param toolCalls the tool calls accumulated during the same reasoning round
     * @return the tool calls with their matching server tool results inserted
     */
    List<ContentBlock> placeAfterToolCalls(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.copyOf(buildAllToolResults());
        }

        Map<String, ToolResultBlock> unmatched = new LinkedHashMap<>(resultsById);
        List<ContentBlock> blocks = new ArrayList<>();
        for (ToolUseBlock toolCall : toolCalls) {
            blocks.add(toolCall);
            ToolResultBlock result = unmatched.remove(toolCall.getId());
            if (result != null) {
                blocks.add(result);
            }
        }

        if (!unmatched.isEmpty() || !resultsWithoutId.isEmpty()) {
            log.warn(
                    "{} server tool result(s) have no matching tool call",
                    unmatched.size() + resultsWithoutId.size());
        }
        blocks.addAll(unmatched.values());
        blocks.addAll(resultsWithoutId);
        return blocks;
    }

    /**
     * Clears the accumulated server tool results.
     *
     * @hidden
     */
    void reset() {
        resultsById.clear();
        resultsWithoutId.clear();
    }
}

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
package io.agentscope.harness.agent.context;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Deterministic last-resort output reduction before either inference or summarization. */
public final class ToolResultBudgetGuard {
    private ToolResultBudgetGuard() {}

    public record Candidate(ModelCallInput input, List<Msg> history) {}

    public static Candidate prepare(
            Agent agent,
            RuntimeContext rc,
            ModelCallInput input,
            List<Msg> history,
            ToolResultEvictionMiddleware eviction,
            Predicate<ModelCallInput> fits) {
        if (fits.test(input)) return new Candidate(input, history);
        Map<String, ToolResultBlock> originals = new LinkedHashMap<>();
        for (Msg msg : input.messages()) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock result) originals.put(key(result), result);
            }
        }
        if (originals.isEmpty()) return new Candidate(input, history);
        Map<String, String> archives = new HashMap<>();
        Map<String, ToolResultBlock> replacements = new HashMap<>();
        ModelCallInput reduced = input;
        // A bounded number of passes, always derived from the original output. Storage is tried
        // only once per result; failed storage cannot turn the fallback into a retry loop.
        for (int cap = 4000; ; cap /= 2) {
            for (var entry : originals.entrySet()) {
                ToolResultBlock result = entry.getValue();
                String serialized = JsonUtils.getJsonCodec().toJson(result.getOutput());
                if (cap > 0 && serialized.length() <= cap) continue;
                if (!archives.containsKey(entry.getKey())) {
                    archives.put(
                            entry.getKey(),
                            eviction == null ? null : eviction.archiveForBudget(agent, rc, result));
                }
                String path = archives.get(entry.getKey());
                String notice =
                        path == null
                                ? "[Tool output reduced for context budget; full output could not"
                                        + " be archived. Omitted content is unavailable.]"
                                : "[Tool output reduced for context budget; full output JSON saved"
                                        + " to `"
                                        + path
                                        + "`. Retrieve only a targeted portion, not the entire"
                                        + " output.]";
                int end = Math.min(cap, serialized.length());
                if (end > 0
                        && end < serialized.length()
                        && Character.isHighSurrogate(serialized.charAt(end - 1))) end--;
                String preview =
                        end == 0
                                ? notice
                                : notice
                                        + "\nJSON preview (incomplete):\n"
                                        + serialized.substring(0, end);
                if (result.getOutput().stream().allMatch(TextBlock.class::isInstance)
                        && preview.length() >= serialized.length()) continue;
                Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
                metadata.put("agentscope.tool_result_budget_reduced", true);
                if (path != null) metadata.put("agentscope.tool_result_archive", path);
                replacements.put(
                        entry.getKey(),
                        new ToolResultBlock(
                                result.getId(),
                                result.getName(),
                                List.of(TextBlock.builder().text(preview).build()),
                                metadata,
                                result.getState(),
                                result.getExecutionDetails()));
            }
            reduced =
                    new ModelCallInput(
                            replace(input.messages(), replacements),
                            input.tools(),
                            input.options(),
                            input.model());
            if (fits.test(reduced) || cap == 0) break;
        }
        return new Candidate(reduced, replace(history, replacements));
    }

    private static String key(ToolResultBlock result) {
        // IDs alone are insufficient: a hook may supply a different view of the same result.
        return JsonUtils.getJsonCodec().toJson(result);
    }

    private static List<Msg> replace(
            List<Msg> messages, Map<String, ToolResultBlock> replacements) {
        List<Msg> rebuilt = new ArrayList<>();
        for (Msg msg : messages) {
            boolean changed = false;
            List<ContentBlock> blocks = new ArrayList<>();
            for (ContentBlock block : msg.getContent()) {
                ToolResultBlock replacement =
                        block instanceof ToolResultBlock result
                                ? replacements.get(key(result))
                                : null;
                blocks.add(replacement == null ? block : replacement);
                changed |= replacement != null;
            }
            rebuilt.add(
                    changed
                            ? Msg.builderForRole(msg.getRole())
                                    .id(msg.getId())
                                    .name(msg.getName())
                                    .content(blocks)
                                    .metadata(msg.getMetadata())
                                    .timestamp(msg.getTimestamp())
                                    .usage(msg.getUsage())
                                    .build()
                            : msg);
        }
        return List.copyOf(rebuilt);
    }
}

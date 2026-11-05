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
package io.agentscope.extensions.judge.openaidecisions.example;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

/** Shared evidence projection for the Decisions middleware examples. */
final class DecisionSupport {
    static final int MAX_QUESTIONS = 400;

    private DecisionSupport() {}

    static String latestUserText(List<Msg> messages) {
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Msg message = messages.get(i);
                if (message != null && message.getRole() == MsgRole.USER) {
                    String text = message.getTextContent();
                    return text == null ? "" : text.trim();
                }
            }
        }
        return "";
    }

    static List<Map<String, String>> recentMessages(
            List<Msg> messages, int maxMessages, int maxChars) {
        List<Map<String, String>> entries = new ArrayList<>();
        if (messages == null) return entries;
        int remaining = maxChars;
        for (int i = messages.size() - 1;
                i >= 0 && remaining > 0 && entries.size() < maxMessages;
                i--) {
            Msg message = messages.get(i);
            if (message == null) continue;
            String text =
                    message.getContent().stream()
                            .flatMap(
                                    block ->
                                            block instanceof ToolResultBlock result
                                                    ? result.getOutput().stream()
                                                    : Stream.of(block))
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(Collectors.joining("\n"));
            if (text == null || text.isBlank()) continue;
            if (text.length() > remaining) {
                int end = remaining;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                text = text.substring(0, end);
                if (text.isBlank()) continue;
            }
            remaining -= text.length();
            entries.add(
                    0,
                    Map.of(
                            "role",
                            message.getRole().name().toLowerCase(Locale.ROOT),
                            "text",
                            text));
        }
        return List.copyOf(entries);
    }

    static String json(Object evidence) {
        return JsonUtils.getJsonCodec().toJson(evidence);
    }

    static Mono<DecisionResult> call(
            Function<DecisionRequest, Mono<DecisionResult>> call, DecisionRequest request) {
        return Mono.defer(() -> call.apply(request))
                .switchIfEmpty(
                        Mono.error(new IllegalStateException("Decisions returned no result")));
    }

    static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be finite and between 0 and 1");
        }
    }
}

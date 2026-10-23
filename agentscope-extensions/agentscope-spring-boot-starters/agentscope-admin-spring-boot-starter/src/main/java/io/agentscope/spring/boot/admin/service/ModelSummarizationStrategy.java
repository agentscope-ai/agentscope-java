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
package io.agentscope.spring.boot.admin.service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default {@link SummarizationStrategy} that asks the agent's own model to produce a fresh
 * rolling summary.
 *
 * <p>The strategy is intentionally conservative: it never includes the previous summary in the
 * prompt verbatim (to avoid prompt explosion), only the existing summary plus the messages being
 * folded. The model is instructed to keep names, dates, and any user-stated facts.
 */
public final class ModelSummarizationStrategy implements SummarizationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ModelSummarizationStrategy.class);

    private static final String SYSTEM_PROMPT =
            "You are a conversation summarizer. Produce a concise, factual summary of the "
                    + "conversation below that preserves: user-stated goals, decisions made, named "
                    + "entities, numbers, dates, and any open questions. Do not invent details. Do "
                    + "not include analysis or recommendations. Output prose only, no lists.";

    @Override
    public Mono<String> summarize(Model model, String existingSummary, List<Msg> messagesToFold) {
        if (model == null) {
            return Mono.error(new IllegalStateException("No Model available for summarization"));
        }
        if (messagesToFold == null || messagesToFold.isEmpty()) {
            return Mono.just(existingSummary == null ? "" : existingSummary);
        }

        List<Msg> prompt = new ArrayList<>();
        prompt.add(textMsg(MsgRole.SYSTEM, "summarizer", SYSTEM_PROMPT));
        if (existingSummary != null && !existingSummary.isBlank()) {
            prompt.add(
                    textMsg(
                            MsgRole.USER,
                            "summarizer",
                            "Existing summary so far:\n" + existingSummary));
        }
        StringBuilder transcript = new StringBuilder("Conversation to fold:\n");
        for (Msg msg : messagesToFold) {
            String role = msg.getRole() == null ? "?" : msg.getRole().name().toLowerCase();
            String text = extractText(msg);
            if (text == null || text.isBlank()) continue;
            transcript.append("[").append(role).append("] ").append(text).append("\n");
        }
        prompt.add(textMsg(MsgRole.USER, "summarizer", transcript.toString()));

        StringBuilder out = new StringBuilder();
        return model.stream(prompt, null, null)
                .doOnNext(
                        chunk -> {
                            List<ContentBlock> content = chunk.getContent();
                            if (content == null) return;
                            for (ContentBlock block : content) {
                                if (block instanceof TextBlock tb && tb.getText() != null) {
                                    out.append(tb.getText());
                                }
                            }
                        })
                .then(Mono.fromSupplier(out::toString))
                .doOnError(e -> log.warn("Summarization model call failed: {}", e.toString()))
                .onErrorReturn(existingSummary == null ? "" : existingSummary);
    }

    private static Msg textMsg(MsgRole role, String name, String text) {
        return Msg.builder()
                .role(role)
                .name(name)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String extractText(Msg msg) {
        try {
            String t = msg.getTextContent();
            if (t != null && !t.isBlank()) return t;
        } catch (RuntimeException ignored) {
            // fall through to manual extraction
        }
        List<TextBlock> blocks = msg.getContentBlocks(TextBlock.class);
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TextBlock b : blocks) {
            if (b.getText() != null) sb.append(b.getText());
        }
        return sb.toString();
    }
}

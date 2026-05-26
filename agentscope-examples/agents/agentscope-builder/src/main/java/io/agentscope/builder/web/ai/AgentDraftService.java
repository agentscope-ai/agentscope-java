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
package io.agentscope.builder.web.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.catalog.AgentCatalogService.AgentDraft;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI-assisted drafting of a starter agent configuration. Given a one-sentence description, calls
 * the configured {@link Model} once with a low-temperature prompt and parses the JSON response into
 * an {@link AgentDraft}.
 *
 * <p>Returns 503 when no {@link Model} bean is available; the {@code aiAvailable} flag on
 * {@code GET /api/auth/me} mirrors this so the frontend can hide or disable the AI tab.
 */
@Service
public class AgentDraftService {

    private static final Logger log = LoggerFactory.getLogger(AgentDraftService.class);
    private static final String PROMPT_RESOURCE = "classpath:prompts/agent-draft.md";
    private static final String FALLBACK_PROMPT =
            "You are an agent designer. Given a one-sentence description, return strict JSON with"
                    + " keys name, description, sysPrompt, suggestedTools (array of strings),"
                    + " suggestedSkills (array of {name,content}), suggestedSubagents (array of"
                    + " {name,content}). User description: {{DESCRIPTION}}. Output JSON only.";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final Model model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String promptTemplate;

    public AgentDraftService(Optional<Model> modelOpt, ResourceLoader resourceLoader) {
        this.model = modelOpt.orElse(null);
        this.promptTemplate = loadPrompt(resourceLoader);
    }

    private static String loadPrompt(ResourceLoader resourceLoader) {
        try {
            Resource res = resourceLoader.getResource(PROMPT_RESOURCE);
            if (!res.exists()) {
                log.warn(
                        "AgentDraftService: prompt resource not found at {}, using fallback",
                        PROMPT_RESOURCE);
                return FALLBACK_PROMPT;
            }
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn(
                    "AgentDraftService: failed to load prompt resource {}: {} (using fallback)",
                    PROMPT_RESOURCE,
                    e.getMessage());
            return FALLBACK_PROMPT;
        }
    }

    /**
     * Drafts an agent configuration from a free-text description. Errors:
     *
     * <ul>
     *   <li>503 if no model is configured,
     *   <li>400 if the description is blank,
     *   <li>502 if the model returns malformed JSON or no text content.
     * </ul>
     */
    public Mono<AgentDraft> draft(String description) {
        if (description == null || description.isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required"));
        }
        if (model == null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "AI drafting not available — configure a model"));
        }

        String prompt = promptTemplate.replace("{{DESCRIPTION}}", description.trim());
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(prompt).build())
                        .build();

        return Mono.fromCallable(() -> callModelBlocking(userMsg))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::parseDraft);
    }

    /**
     * Subscribes to the model's streaming response and concatenates every emitted text block into a
     * single string. Blocks the caller until the stream completes or the timeout elapses.
     */
    private String callModelBlocking(Msg userMsg) {
        try {
            List<ChatResponse> responses =
                    model.stream(List.of(userMsg), null, null).collectList().block(CALL_TIMEOUT);
            if (responses == null || responses.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Model returned no response");
            }
            StringBuilder sb = new StringBuilder();
            for (ChatResponse r : responses) {
                if (r == null || r.getContent() == null) continue;
                for (ContentBlock cb : r.getContent()) {
                    if (cb instanceof TextBlock tb) {
                        String txt = tb.getText();
                        if (txt != null) sb.append(txt);
                    }
                }
            }
            String raw = sb.toString();
            if (raw.isBlank()) {
                // Some providers send the final content only on the last frame; if every frame was
                // delta-only and we still have nothing, surface as 502 so the UI can retry.
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Model returned empty content");
            }
            return raw;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Model call failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    e);
        }
    }

    /**
     * Lenient JSON parsing: strips ```json fences and any leading/trailing prose, then deserializes
     * into {@link AgentDraft}. Throws 502 with the raw output on failure.
     */
    private Mono<AgentDraft> parseDraft(String raw) {
        String stripped = stripCodeFence(raw).trim();
        // If the model included pre/post text, try to find the outermost JSON object.
        int firstBrace = stripped.indexOf('{');
        int lastBrace = stripped.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            stripped = stripped.substring(firstBrace, lastBrace + 1);
        }
        try {
            AgentDraft draft = mapper.readValue(stripped, AgentDraft.class);
            return Mono.just(draft);
        } catch (Exception e) {
            log.warn("AgentDraftService: failed to parse model output: {}", e.getMessage());
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Model returned non-JSON output: "
                                    + (raw.length() > 500 ? raw.substring(0, 500) + "..." : raw)));
        }
    }

    private static String stripCodeFence(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int closing = trimmed.lastIndexOf("```");
            if (closing >= 0) {
                trimmed = trimmed.substring(0, closing);
            }
        }
        return trimmed;
    }
}

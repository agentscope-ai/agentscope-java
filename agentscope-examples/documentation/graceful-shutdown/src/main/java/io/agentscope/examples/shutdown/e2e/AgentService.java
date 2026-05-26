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
package io.agentscope.examples.shutdown.e2e;

import static java.time.temporal.ChronoUnit.SECONDS;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Service for managing agent instances and chat operations.
 */
@Slf4j
@Service
public class AgentService {

    private static final String DATA_ANALYZE_SYS_PROMPT =
            "You are a data analysis assistant. "
                    + "When asked to analyze a dataset, follow these steps in order:\n"
                    + "1. Call analyze_dataset to get the raw statistics\n"
                    + "2. Based on the analysis results, write a detailed report "
                    + "including key findings, anomaly details, revenue trends, "
                    + "and your recommendations\n"
                    + "Do not skip any step. Do not ask clarifying questions.";

    private static final String ARTICLE_GENERATE_SYS_PROMPT =
            "You are a professional article writer. When given a topic, write a comprehensive,"
                + " well-structured article with the following sections:\n"
                + "1. Introduction - Hook the reader and state the thesis\n"
                + "2. Background - Provide relevant context and history\n"
                + "3. Main Body - At least 3 detailed sections with analysis, examples, and data\n"
                + "4. Conclusion - Summarize key points and provide a forward-looking"
                + " perspective\n\n"
                + "Requirements:\n"
                + "- The article should be at least 2000 words\n"
                + "- Use professional and engaging language\n"
                + "- Include specific examples and references where possible\n"
                + "- Do not ask clarifying questions, just write the article directly.In addition,"
                + " you can also have normal conversations.";

    private static final String DEFAULT_SESSION_ID = "default_01";

    private final Session session = new JsonSession();

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${agent.model-name:qwen3.5-plus}")
    private String modelName;

    @Value("${agent.shutdown-timeout-seconds:-1}")
    private int shutdownTime;

    @Value("${agent.shutdown-partial-reasoning-policy:save}")
    private String partialReasoningPolicy;

    @PostConstruct
    public void initShutdownConfig() {
        Duration gracefulShutdownTime =
                shutdownTime < 0 ? null : Duration.of(shutdownTime, SECONDS);
        PartialReasoningPolicy shutdownPartialReasoningPolicy =
                "save".equalsIgnoreCase(partialReasoningPolicy)
                        ? PartialReasoningPolicy.SAVE
                        : PartialReasoningPolicy.DISCARD;
        GracefulShutdownManager.getInstance()
                .setConfig(
                        new GracefulShutdownConfig(
                                gracefulShutdownTime, shutdownPartialReasoningPolicy));
    }

    /**
     * Process a data-analysis chat message with a tool-equipped agent.
     */
    public ResponseEntity<Map<String, Object>> chatDataAnalyze(String message, String sessionId) {
        return doChat(message, sessionId, this::createDataAnalyzeAgent);
    }

    /**
     * Process an article-generation chat message with a pure-reasoning agent.
     * No tools are registered so the LLM performs long-form text generation,
     * which is ideal for testing reasoning-phase interruption during shutdown.
     */
    public ResponseEntity<Map<String, Object>> chatArticleGenerate(
            String message, String sessionId) {
        return doChat(message, sessionId, this::createArticleGenerateAgent);
    }

    private ResponseEntity<Map<String, Object>> doChat(
            String message,
            String sessionId,
            java.util.function.Function<String, ReActAgent> agentFactory) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = DEFAULT_SESSION_ID;
        }

        ReActAgent agent = agentFactory.apply(sessionId);

        try {
            Msg response = agent.call(textMsg(message)).block();
            agent.saveTo(session, sessionId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("sessionId", sessionId);
            result.put("response", extractText(response));

            log.info("request success {}", result);
            return ResponseEntity.ok(result);

        } catch (AgentShuttingDownException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "interrupted");
            result.put("sessionId", sessionId);
            result.put("error", e.getMessage());
            result.put("memorySize", agent.getMemory().getMessages().size());

            log.warn(
                    "AgentShuttingDownException caught, business graceful shutdown perform"
                            + " successfully.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);

        } catch (Exception e) {
            log.warn("Exception caught", e);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("error", e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    private ReActAgent createDataAnalyzeAgent(String sessionId) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SlowAnalysisTool());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("DataAnalyst")
                        .sysPrompt(DATA_ANALYZE_SYS_PROMPT)
                        .model(buildModel())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .maxIters(5)
                        .build();
        agent.loadIfExists(session, sessionId);
        return agent;
    }

    private ReActAgent createArticleGenerateAgent(String sessionId) {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ArticleWriter")
                        .sysPrompt(ARTICLE_GENERATE_SYS_PROMPT)
                        .model(buildModel())
                        .memory(new InMemoryMemory())
                        .maxIters(3)
                        .build();
        agent.loadIfExists(session, sessionId);
        return agent;
    }

    private DashScopeChatModel buildModel() {
        return DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                .enableThinking(false)
                .formatter(new DashScopeChatFormatter())
                .build();
    }

    private static Msg textMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String extractText(Msg msg) {
        if (msg == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }
}

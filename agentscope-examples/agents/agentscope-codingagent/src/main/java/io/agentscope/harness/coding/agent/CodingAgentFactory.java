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
package io.agentscope.harness.coding.agent;

import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.coding.model.FallbackModel;
import io.agentscope.harness.coding.prompt.CodingSystemPrompt;
import java.nio.file.Path;

/**
 * Factory for the coding agent.
 *
 * <ul>
 *   <li>Coding-focused system prompt (workspace env, repo setup, GitHub via {@code
 *       github_api_request})
 *   <li>Custom toolkit (http, fetch_url, web_search, github_api_request, request_pr_review)
 *   <li>Compaction for long-running sessions
 *   <li>Configurable model from environment ({@code CODING_MODEL_ID})
 *   <li>Sandbox integration wired at phase 2
 * </ul>
 */
public final class CodingAgentFactory {

    private CodingAgentFactory() {}

    /** Builds a coding agent for the given workspace with default settings. */
    public static HarnessAgent create(Path workspace, Toolkit toolkit) {
        return create(workspace, toolkit, null);
    }

    /**
     * Builds a coding agent.
     *
     * @param workspace agent workspace directory
     * @param toolkit custom tools registered for the coding agent
     * @param linearCtx optional Linear context string (may be null — deferred)
     */
    public static HarnessAgent create(Path workspace, Toolkit toolkit, String linearCtx) {
        Model model = buildModel();
        String sandboxType = resolveSandboxType();
        String workingDir = resolveSandboxWorkingDir();
        String sysPrompt = CodingSystemPrompt.build(workingDir, linearCtx);

        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("agentscope-coding-agent")
                        .model(model)
                        .sysPrompt(sysPrompt)
                        .workspace(workspace)
                        .toolkit(toolkit != null ? toolkit : new Toolkit())
                        .maxIters(resolveMaxIters())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(40)
                                        .keepMessages(15)
                                        .flushBeforeCompact(true)
                                        .build());

        if ("docker".equalsIgnoreCase(sandboxType)) {
            String image = resolveSandboxImage();
            DockerFilesystemSpec sandboxSpec = new DockerFilesystemSpec();
            sandboxSpec.image(image);
            sandboxSpec.workspaceRoot("/home/agentscope/workspace");
            sandboxSpec.isolationScope(IsolationScope.SESSION);
            builder.filesystem(sandboxSpec);
        }

        return builder.build();
    }

    /** Resolves the sandbox working directory inside the container. */
    public static String resolveSandboxWorkingDir() {
        String env = System.getenv("SANDBOX_WORK_DIR");
        return (env != null && !env.isBlank()) ? env : "/home/agentscope/workspace";
    }

    public static String resolveSandboxType() {
        String env = System.getenv("SANDBOX_TYPE");
        return (env != null && !env.isBlank()) ? env.toLowerCase() : "none";
    }

    public static String resolveSandboxImage() {
        String env = System.getenv("SANDBOX_IMAGE");
        return (env != null && !env.isBlank()) ? env : "agentscope/coding-sandbox:latest";
    }

    // -----------------------------------------------------------------
    //  Model selection
    // -----------------------------------------------------------------

    /**
     * Builds the model from the {@code CODING_MODEL_ID} environment variable.
     *
     * <p>Supported prefixes: {@code dashscope:<name>} (default), {@code openai:<name>},
     * {@code anthropic:<name>}.
     *
     * <p>When {@code FALLBACK_MODEL_ID} is also set, the primary model is wrapped with a
     * {@link FallbackModel} so retryable errors transparently fail over.
     */
    public static Model buildModel() {
        Model primary = buildModelById(resolveModelId());
        String fallbackId = System.getenv("FALLBACK_MODEL_ID");
        if (fallbackId != null && !fallbackId.isBlank()) {
            return new FallbackModel(primary, buildModelById(fallbackId.trim()));
        }
        return primary;
    }

    private static Model buildModelById(String modelId) {
        if (modelId.startsWith("openai:")) {
            String name = modelId.substring("openai:".length());
            String apiKey = System.getenv("OPENAI_API_KEY");
            String baseUrl = System.getenv("OPENAI_BASE_URL");
            OpenAIChatModel.Builder b =
                    OpenAIChatModel.builder().apiKey(apiKey).modelName(name).stream(true);
            if (baseUrl != null && !baseUrl.isBlank()) {
                b.baseUrl(baseUrl);
            }
            return b.build();
        }
        if (modelId.startsWith("anthropic:")) {
            String name = modelId.substring("anthropic:".length());
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
            AnthropicChatModel.Builder b =
                    AnthropicChatModel.builder().apiKey(apiKey).modelName(name).stream(true);
            if (baseUrl != null && !baseUrl.isBlank()) {
                b.baseUrl(baseUrl);
            }
            return b.build();
        }
        String name =
                modelId.startsWith("dashscope:")
                        ? modelId.substring("dashscope:".length())
                        : modelId;
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(apiKey).modelName(name).stream(true).build();
    }

    public static String resolveModelId() {
        String env = System.getenv("CODING_MODEL_ID");
        return (env != null && !env.isBlank()) ? env : "dashscope:qwen-max";
    }

    private static int resolveMaxIters() {
        String env = System.getenv("CODING_MAX_ITERS");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 50;
    }
}

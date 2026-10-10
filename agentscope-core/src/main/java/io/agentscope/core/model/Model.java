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

/*
 * ============================================================================
 * 【学习笔记】AgentScope 模型抽象 - Model
 * ============================================================================
 *
 * 一、职责定位
 * -----------
 * Model 是框架的「LLM 抽象层」，屏蔽不同模型提供商的 API 差异：
 *   - 统一的流式调用接口
 *   - 统一的消息格式转换
 *   - 统一的工具调用协议
 *
 * 二、支持的模型提供商（通过扩展模块）
 * -----------------------------------
 *   模块                                    │ 提供商
 *   ────────────────────────────────────────┼─────────────
 *   agentscope-extensions-model-openai      │ OpenAI (GPT-4, o3)
 *   agentscope-extensions-model-anthropic   │ Anthropic (Claude)
 *   agentscope-extensions-model-dashscope   │ 阿里云 DashScope (Qwen)
 *   agentscope-extensions-model-gemini      │ Google Gemini
 *   agentscope-extensions-model-ollama      │ Ollama (本地模型)
 *
 * 三、核心方法
 * -----------
 *   stream()  - 流式生成，返回 Flux<ChatResponse>
 *
 * 四、能力探测
 * -----------
 *   supportsNativeStructuredOutput()           - 是否支持原生 JSON Schema 输出
 *   supportsNativeStructuredOutputWithTools()  - 是否支持工具+结构化输出共存
 *   getContextWindowSize()                     - 上下文窗口大小（用于压缩计算）
 *
 * 五、使用方式
 * -----------
 *   // 方式1: 通过 ModelRegistry 字符串解析
 *   Model model = ModelRegistry.resolve("openai:gpt-4.1");
 *
 *   // 方式2: 直接构建
 *   Model model = OpenAIChatModel.builder()
 *       .apiKey("sk-xxx")
 *       .model("gpt-4.1")
 *       .build();
 *
 * 六、ModelRegistry 字符串格式
 * ---------------------------
 *   "provider:model-name"
 *   例如:
 *     "openai:gpt-4.1"
 *     "anthropic:claude-sonnet-4-7"
 *     "dashscope:qwen-plus"
 *     "ollama:llama3"
 *
 * 七、线程安全
 * -----------
 *   Model 实例可跨多个 Agent 安全共享
 *
 * ============================================================================
 */

package io.agentscope.core.model;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

public interface Model {

    /**
     * Stream chat completion responses.
     * The model internally handles message formatting using its configured formatter.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools Optional list of tool schemas (null or empty if no tools)
     * @param options Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);

    /**
     * Get model name for logging and identification.
     *
     * @return model name
     */
    String getModelName();

    /**
     * Whether this model supports native structured output ({@code response_format} with
     * {@code json_schema}). When {@code true}, the agent can pass the output schema directly
     * to the model via {@link GenerateOptions#getResponseFormat()} instead of injecting a
     * synthetic {@code generate_response} tool.
     *
     * @return {@code true} if the model supports native structured output
     * @see #supportsNativeStructuredOutputWithTools()
     */
    default boolean supportsNativeStructuredOutput() {
        return false;
    }

    /**
     * Whether this model correctly handles native structured output ({@code response_format}
     * with {@code json_schema}) when tools are also present in the request.
     *
     * <p>Some OpenAI-compatible providers prioritise the {@code response_format} constraint
     * over tool invocations, causing the model to produce the structured JSON directly and
     * skip tool calling entirely. When this method returns {@code false} and tools are present,
     * the agent falls back to the synthetic {@code generate_response} tool approach, which is
     * fully compatible with the ReAct tool-calling loop.
     *
     * <p>Defaults to {@link #supportsNativeStructuredOutput()}.
     *
     * @return {@code true} if the model handles {@code response_format} alongside tools correctly
     */
    default boolean supportsNativeStructuredOutputWithTools() {
        return supportsNativeStructuredOutput();
    }

    /**
     * Returns the model's context window size in tokens, or {@code 0} if unknown.
     *
     * <p>Used by the compaction middleware to dynamically compute when to trigger
     * conversation summarization. Implementations should return the total context
     * window (input + output) for the configured model.
     *
     * @return context window size in tokens, or {@code 0} if not available
     */
    default int getContextWindowSize() {
        return 0;
    }
}

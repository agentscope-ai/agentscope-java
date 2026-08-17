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
package io.agentscope.examples.documentation2.model;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ModelRegistry;

/**
 * ModelRegistryExample - Demonstrates how extension model providers let you
 * create an agent by supplying only a model-ID string.
 *
 * <p>AgentScope pre-registers factory patterns for every supported provider. You do not need
 * to construct model objects yourself — just pass a {@code "provider:model-name"} string to
 * {@link ReActAgent.Builder#model(String)} and the framework resolves it automatically.
 *
 * <p><b>Provider strings and required environment variables:</b>
 * <pre>
 *   "openai:gpt-4o"               OPENAI_API_KEY
 *   "dashscope:qwen-max"          DASHSCOPE_API_KEY
 *   "qwen-plus"                   DASHSCOPE_API_KEY   (qwen-* shorthand, no prefix needed)
 *   "anthropic:claude-opus-4-5"   ANTHROPIC_API_KEY
 *   "gemini:gemini-2.0-flash"     GEMINI_API_KEY
 *   "ollama:llama3"               OLLAMA_BASE_URL     (default: http://localhost:11434)
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.model.ModelRegistryExample
 * </pre>
 */
public class ModelRegistryExample {

    /**
     * Runs the model-string resolution demonstration.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ModelRegistry Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Shows how to specify a model with a plain string — no manual construction"
                        + " needed.");
        System.out.println("=".repeat(60) + "\n");

        // ── 1. Check which providers are available ────────────────────────────────────
        //
        // ModelRegistry.canResolve() probes the registry without actually creating a model.
        // Use it at startup to give users an early, clear error message.
        System.out.println(
                "Available provider extensions (requires corresponding module and env var):");
        System.out.println(
                "  openai:gpt-4o               → " + ModelRegistry.canResolve("openai:gpt-4o"));
        System.out.println(
                "  dashscope:qwen-max           → "
                        + ModelRegistry.canResolve("dashscope:qwen-max"));
        System.out.println(
                "  qwen-plus  (shorthand)       → " + ModelRegistry.canResolve("qwen-plus"));
        System.out.println(
                "  anthropic:claude-opus-4-5    → "
                        + ModelRegistry.canResolve("anthropic:claude-opus-4-5"));
        System.out.println(
                "  gemini:gemini-2.0-flash      → "
                        + ModelRegistry.canResolve("gemini:gemini-2.0-flash"));
        System.out.println(
                "  ollama:llama3                → " + ModelRegistry.canResolve("ollama:llama3"));
        System.out.println();

        // ── 2. Create an agent with just a model-ID string ────────────────────────────
        //
        // ReActAgent.Builder.model(String) calls ModelRegistry.resolve() internally.
        // The framework reads DASHSCOPE_API_KEY from the environment and wires up the
        // DashScope model — no manual DashScopeChatModel.builder() call required.
        System.out.println("Building agent with model string \"qwen-plus\" ...");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ModelStringDemo")
                        .sysPrompt("You are a concise assistant. Reply in one sentence.")
                        .model("dashscope:qwen-plus") // ← the only thing needed to configure the
                        // model
                        .build();

        Msg response = agent.call(new UserMessage("user", "What is 2 + 2?")).block();
        System.out.println("Agent: " + (response != null ? response.getTextContent() : "(null)"));
        System.out.println();

        // ── 3. Switch provider by changing the string ─────────────────────────────────
        //
        // The provider prefix selects which backend to use.  Swapping providers only
        // requires updating the model-ID string — no other code changes.
        //
        // Uncomment one of these lines to try a different provider:
        //
        //   .model("openai:gpt-4o")              // requires OPENAI_API_KEY
        //   .model("dashscope:qwen-max")          // explicit DashScope prefix
        //   .model("anthropic:claude-opus-4-5")   // requires ANTHROPIC_API_KEY
        //   .model("gemini:gemini-2.0-flash")     // requires GEMINI_API_KEY
        //   .model("ollama:llama3")               // requires local Ollama instance
        System.out.println(
                "To switch providers, change the model-ID string — no other code changes needed.");
    }
}

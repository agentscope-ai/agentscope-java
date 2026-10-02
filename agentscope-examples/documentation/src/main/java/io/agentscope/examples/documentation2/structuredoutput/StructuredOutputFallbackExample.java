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
package io.agentscope.examples.documentation2.structuredoutput;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Structured output example using the {@code generate_response} tool fallback path.
 *
 * <p>When a model does not support native structured output ({@code response_format}
 * with {@code json_schema}), the agent injects a synthetic {@code generate_response}
 * tool. The model calls this tool to return structured data, and the agent loop
 * terminates naturally.
 *
 * <p>This example wraps a real model to force the fallback path, exercising
 * the same code path reported in
 * <a href="https://github.com/agentscope-ai/agentscope-java/issues/1722">#1722</a>.
 */
public class StructuredOutputFallbackExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Structured Output — generate_response Fallback Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example forces the generate_response tool fallback path\n"
                        + "by wrapping the model to disable native structured output.\n"
                        + "It exercises the same code path as issue #1722.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Model realModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-max").stream(true)
                        .enableThinking(false)
                        .formatter(new DashScopeChatFormatter())
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("FallbackAgent")
                        .sysPrompt(
                                "You are an intelligent analysis assistant. "
                                        + "Analyze user requests and provide structured responses.")
                        .model(new FallbackModel(realModel))
                        .toolkit(new Toolkit())
                        .middleware(new AgentTraceMiddleware())
                        .build();

        System.out.println("=== Product Requirements (fallback path) ===\n");
        runExample(agent);

        System.out.println("\n=== Contact Info (fallback path) ===\n");
        runContactExample(agent);

        System.out.println("\n=== All examples completed ===");
    }

    private static void runExample(ReActAgent agent) {
        String query =
                "I'm looking for a laptop. I need at least 16GB RAM, "
                        + "prefer Apple brand, and my budget is around $2000.";

        System.out.println("Query: " + query);
        System.out.println("Requesting structured output via generate_response tool...\n");

        Msg userMsg = new UserMessage("Extract the product requirements from this query: " + query);

        try {
            Msg msg =
                    agent.call(userMsg, StructuredOutputExample.ProductRequirements.class).block();
            StructuredOutputExample.ProductRequirements result =
                    msg.getStructuredData(StructuredOutputExample.ProductRequirements.class);

            System.out.println("Extracted structured data:");
            System.out.println("  Product Type: " + result.productType);
            System.out.println("  Brand: " + result.brand);
            System.out.println("  Min RAM: " + result.minRam + " GB");
            System.out.println("  Max Budget: $" + result.maxBudget);
            System.out.println("  Features: " + result.features);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runContactExample(ReActAgent agent) {
        String query =
                "Please contact John Smith at john.smith@example.com or "
                        + "call him at +1-555-123-4567. His company is TechCorp Inc.";

        System.out.println("Text: " + query);
        System.out.println("Extracting contact info via generate_response tool...\n");

        Msg userMsg = new UserMessage("Extract contact information from: " + query);

        try {
            Msg msg = agent.call(userMsg, StructuredOutputExample.ContactInfo.class).block();
            System.out.println("Raw: " + msg.getStructuredData(false));
            StructuredOutputExample.ContactInfo result =
                    msg.getStructuredData(StructuredOutputExample.ContactInfo.class);

            System.out.println("Extracted contact information:");
            System.out.println("  Name: " + result.name);
            System.out.println("  Email: " + result.email);
            System.out.println("  Phone: " + result.phone);
            System.out.println("  Company: " + result.company);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Decorator that wraps any Model and forces the fallback structured output path
     * by returning {@code false} from {@link #supportsNativeStructuredOutput()}.
     */
    static class FallbackModel implements Model {

        private final Model delegate;

        FallbackModel(Model delegate) {
            this.delegate = delegate;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return delegate.stream(messages, tools, options);
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return false;
        }

        @Override
        public int getContextWindowSize() {
            return delegate.getContextWindowSize();
        }
    }
}

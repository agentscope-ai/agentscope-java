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
package io.agentscope.examples.documentation2.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.examples.documentation2.common.ExampleUtils;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * SystemPromptMiddlewareExample - Demonstrates injecting dynamic content into the system prompt
 * by overriding {@link MiddlewareBase#onSystemPrompt(Agent, String)}.
 *
 * <p>{@code onSystemPrompt} is called once per {@code agent.call()} before any messages are
 * sent to the model. Each middleware in the chain receives the output of the previous one, so
 * multiple middlewares can layer information into the final system prompt.
 *
 * <p><b>Common use-cases:</b>
 * <ul>
 *   <li>Injecting the current date/time for time-aware reasoning</li>
 *   <li>Adding user-specific settings (locale, role, preferences)</li>
 *   <li>Appending environment-specific instructions (staging vs. production)</li>
 * </ul>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.middleware.SystemPromptMiddlewareExample
 * </pre>
 */
public class SystemPromptMiddlewareExample {

    /**
     * Runs the system prompt middleware example.
     *
     * @param args command-line arguments (ignored)
     */
    public static void main(String[] args) throws java.io.IOException {
        ExampleUtils.printWelcome(
                "System Prompt Middleware Example",
                "Demonstrates onSystemPrompt() to inject timestamp and environment info.\n"
                        + "Ask 'What time is it?' — the agent knows the current UTC time\n"
                        + "because the middleware injects it on every call.");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ContextAwareAgent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .middleware(new TimestampMiddleware())
                        .middleware(new EnvironmentMiddleware("demo", "user-42"))
                        .build();

        System.out.println("Try: 'What time is it?' or 'Who am I?'\n");

        Msg response =
                agent.call(new UserMessage("user", "What time is it and what environment am I in?"))
                        .block();
        System.out.println("Agent: " + (response != null ? response.getTextContent() : "(null)"));

        ExampleUtils.startChat(agent);
    }

    /**
     * Middleware that appends the current UTC timestamp to the system prompt.
     *
     * <p>The agent can reference this to answer time-related questions without a
     * clock tool.
     */
    public static class TimestampMiddleware implements MiddlewareBase {

        /**
         * Appends the current UTC timestamp to the system prompt.
         *
         * @param agent         the calling agent (available for context inspection)
         * @param currentPrompt the system prompt as built so far (output of any prior middleware)
         * @return updated system prompt with timestamp appended
         */
        @Override
        public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
            String timestamp = Instant.now().toString();
            String appended = currentPrompt + "\n\n[Context] Current UTC time: " + timestamp;
            return Mono.just(appended);
        }
    }

    /**
     * Middleware that appends environment and user information to the system prompt.
     */
    public static class EnvironmentMiddleware implements MiddlewareBase {

        private final String environment;
        private final String userId;

        /**
         * Constructs the environment middleware.
         *
         * @param environment deployment environment label (e.g. "demo", "production")
         * @param userId      current user identifier
         */
        public EnvironmentMiddleware(String environment, String userId) {
            this.environment = environment;
            this.userId = userId;
        }

        /**
         * Appends environment and user information to the system prompt.
         *
         * @param agent         the calling agent
         * @param currentPrompt the system prompt as built by prior middlewares
         * @return updated system prompt with environment context appended
         */
        @Override
        public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
            String appended =
                    currentPrompt
                            + "\n[Context] Environment: "
                            + environment
                            + " | User ID: "
                            + userId;
            return Mono.just(appended);
        }
    }
}

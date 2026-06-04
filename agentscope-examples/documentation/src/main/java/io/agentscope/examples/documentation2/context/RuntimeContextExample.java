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
package io.agentscope.examples.documentation2.context;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.documentation2.common.ExampleUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Demonstrates how to thread per-call metadata through {@link RuntimeContext} and
 * combine it with persistent {@link Session} storage so the same agent instance can
 * pick up history across runs.
 *
 * <p>{@link RuntimeContext} is a transient, per-call metadata bag — useful for
 * passing tenant ids, request ids, or anything else hooks and tools should see
 * during a single {@code call}. {@link Session} + {@link io.agentscope.core.state.SessionKey}
 * configured on the builder turn the same agent into one that loads and saves
 * conversation context to disk automatically.
 */
public class RuntimeContextExample {

    public static void main(String[] args) {
        ExampleUtils.printWelcome(
                "RuntimeContext Example",
                "Shows per-call RuntimeContext metadata and JsonSession persistence.");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        Path sessionPath =
                Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions");
        Session session = new JsonSession(sessionPath);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful assistant. Remember details from prior turns.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(new Toolkit())
                        .session(session)
                        .sessionKey(SimpleSessionKey.of("runtime-context-demo"))
                        .build();

        int loaded = agent.getState().getContext().size();
        System.out.println("Loaded " + loaded + " message(s) from session.");

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("runtime-context-demo")
                        .put("request_id", "req-001")
                        .build();

        Msg first = agent.call(List.of(new UserMessage("Hi, my name is Alice.")), ctx).block();
        System.out.println("Assistant: " + (first == null ? "" : first.getTextContent()));

        Msg second = agent.call(List.of(new UserMessage("What is my name?")), ctx).block();
        System.out.println("Assistant: " + (second == null ? "" : second.getTextContent()));

        System.out.println(
                "Final context size: " + agent.getState().getContext().size() + " message(s).");
    }
}

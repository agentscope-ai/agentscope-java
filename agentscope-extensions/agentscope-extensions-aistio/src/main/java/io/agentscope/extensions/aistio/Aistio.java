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
package io.agentscope.extensions.aistio;

import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;

/**
 * Entry point for reporting a self-deployed agent to the aistio control plane.
 *
 * <p>Full instrumentation, including the Level-2 event stream, needs the observer middleware
 * registered while the agent is being built, because a {@code ReActAgent}'s middleware list is
 * fixed at construction:
 *
 * <pre>{@code
 * AgentScopeAdapter adapter = new AgentScopeAdapter();
 * ReActAgent agent = ReActAgent.builder()
 *         .name("Friday")
 *         .model(model)
 *         .middleware(adapter.middleware())
 *         .build();
 *
 * SessionBridge bridge = Aistio.instrument(agent,
 *         AistioConfig.builder("my-agentscope-agent")
 *                 .controlPlaneHttp("http://localhost:8081")
 *                 .internalToken(System.getenv("BUILDER_INTERNAL_TOKEN"))
 *                 .namespace("default")
 *                 .enableEvents(true)
 *                 .contractHttpPort(18090)
 *                 .build(),
 *         adapter);
 * }</pre>
 *
 * <p>{@link #instrument(Object, AistioConfig)} also works on an agent that is already built. It
 * reports session snapshots, effective context, history and commands — everything that reads live
 * state — but emits no events, since there is no way to add a middleware after construction.
 *
 * <p>The returned bridge is {@link AutoCloseable}; close it to detach and release both channels.
 */
public final class Aistio {

    private Aistio() {}

    /** Instruments an already-built agent. No Level-2 events; see the class javadoc. */
    public static SessionBridge instrument(Object target, AistioConfig config) {
        return instrument(target, config, new AgentScopeAdapter());
    }

    /** Instruments {@code target} with a pre-built adapter, typically one already wired as a
     * middleware on the agent. */
    public static SessionBridge instrument(
            Object target, AistioConfig config, FrameworkAdapter adapter) {
        return new SessionBridge(config).attach(target, adapter).start();
    }

    /**
     * Creates a bridge without attaching or starting it, for callers that need to hold the adapter
     * before the agent exists. Call {@link SessionBridge#attach} then {@link SessionBridge#start}.
     */
    public static SessionBridge newBridge(AistioConfig config) {
        return new SessionBridge(config);
    }
}

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
package io.agentscope.core.agui;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import java.lang.reflect.Method;

/**
 * Helpers for inspecting AG-UI agent instances without taking a compile-time dependency on
 * {@code agentscope-harness}.
 *
 * <p>{@link ReActAgent} is {@link AutoCloseable}. Values returned by {@link #asReActAgent(Agent)}
 * are live session agents and must not be closed by the caller.
 */
public final class AguiUtil {

    private static final String HARNESS_AGENT_CLASS_NAME =
            "io.agentscope.harness.agent.HarnessAgent";

    private AguiUtil() {}

    /**
     * Returns {@code true} when {@code agent} is a {@code HarnessAgent} (or a subclass).
     *
     * <p>Class-name matching is used so the AG-UI module can stream harness agents without
     * depending on the harness artifact.
     *
     * @param agent the agent to inspect, may be {@code null}
     * @return {@code true} if the runtime type is a harness agent
     */
    public static boolean isHarnessAgent(Agent agent) {
        if (agent == null) {
            return false;
        }
        Class<?> type = agent.getClass();
        while (type != null) {
            if (HARNESS_AGENT_CLASS_NAME.equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /**
     * Unwraps a {@link ReActAgent} from {@code agent} when possible.
     *
     * <p>Harness agents expose the inner ReAct delegate via a public {@code getDelegate()} method.
     * The returned instance is the live agent used by the session; callers must not close it.
     *
     * @param agent the agent to unwrap, may be {@code null}
     * @return the ReAct agent, or {@code null} when it cannot be resolved
     */
    public static ReActAgent asReActAgent(Agent agent) {
        if (agent instanceof ReActAgent reactAgent) {
            return reactAgent;
        }
        if (agent == null) {
            return null;
        }
        try {
            Method getDelegate = agent.getClass().getMethod("getDelegate");
            Object delegate = getDelegate.invoke(agent);
            if (delegate instanceof ReActAgent reactAgent) {
                return reactAgent;
            }
        } catch (ReflectiveOperationException ignored) {
            // Not a harness-style wrapper; fall through.
        }
        return null;
    }
}

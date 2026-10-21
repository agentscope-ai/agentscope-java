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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Tool;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Model-facing delegation contract carried by the {@code agent_spawn} / {@code agent_send}
 * tool descriptions.
 */
class AgentSpawnToolDelegationSemanticsTest {

    private static String toolDescription(String methodName) {
        for (Method method : AgentSpawnTool.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    return tool.description();
                }
            }
        }
        throw new AssertionError("no @" + Tool.class.getSimpleName() + " method " + methodName);
    }

    @Test
    void agentSpawnDescription_carriesDelegationSemantics() {
        String description = toolDescription("agentSpawn");
        assertTrue(
                description.contains("does not inherit the parent conversation"),
                "spawn must distinguish parent context from the child's own history");
        assertTrue(
                description.contains("persistent sessions may retain their own history"),
                "spawn must account for persistent session reuse");
        assertTrue(
                description.contains("reworded to bypass a permission denial"),
                "spawn must restrict permission bypass, not normal task adjustments");
    }

    @Test
    void agentSendDescription_allowsSteeringWithoutPermissionBypass() {
        String description = toolDescription("agentSend");
        assertTrue(
                description.contains("steer the task or continue"),
                "send must support follow-ups within the child's existing context");
        assertTrue(
                description.contains("reworded to bypass a permission denial"),
                "send must restrict permission bypass, not normal task adjustments");
    }
}

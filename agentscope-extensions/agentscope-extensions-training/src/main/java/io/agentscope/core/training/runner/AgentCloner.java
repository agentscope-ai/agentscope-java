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

package io.agentscope.core.training.runner;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent Cloning Utility Class
 *
 * <p>Uses reflection to extract Agent configuration, then rebuilds new Agent instance with Builder
 *
 * <p>Design philosophy:
 * <ul>
 *   <li>No shared state (create new memory)</li>
 *   <li>Only replace model field</li>
 *   <li>Keep all other configurations identical</li>
 * </ul>
 */
public class AgentCloner {
    private static final Logger logger = LoggerFactory.getLogger(AgentCloner.class);

    /**
     * Clone Agent and replace model
     *
     * @param original Original Agent
     * @param newModel New model (e.g. TrinityModelWrapper)
     * @return Cloned Agent instance
     */
    public static Agent cloneWithModel(Agent original, Model newModel) {
        if (original instanceof ReActAgent) {
            return cloneReActAgent((ReActAgent) original, newModel);
        }

        // TODO: Support other Agent types
        throw new UnsupportedOperationException(
                "Agent cloning not implemented for: "
                        + original.getClass().getSimpleName()
                        + ". Please implement cloning logic for this agent type.");
    }

    /**
     * Clone ReActAgent
     */
    private static ReActAgent cloneReActAgent(ReActAgent original, Model newModel) {
        try {
            logger.debug("Cloning ReActAgent: {}", original.getName());

            // Extract configuration fields
            String sysPrompt = extractField(original, "sysPrompt");
            Toolkit toolkit = extractField(original, "toolkit");
            Integer maxIters = extractField(original, "maxIters");
            ExecutionConfig modelExecutionConfig = extractField(original, "modelExecutionConfig");
            ExecutionConfig toolExecutionConfig = extractField(original, "toolExecutionConfig");
            StructuredOutputReminder structuredOutputReminder =
                    extractField(original, "structuredOutputReminder");
            PlanNotebook planNotebook = extractField(original, "planNotebook");
            ToolExecutionContext toolExecutionContext =
                    extractField(original, "toolExecutionContext");

            // Rebuild Agent using Builder
            ReActAgent.Builder builder =
                    ReActAgent.builder()
                            .name(original.getName() + "-shadow")
                            .description(original.getDescription())
                            .sysPrompt(sysPrompt)
                            .model(newModel) // Replace model
                            .toolkit(toolkit)
                            .memory(new InMemoryMemory()); // New memory, no shared state

            // Set optional fields
            if (maxIters != null) {
                builder.maxIters(maxIters);
            }
            if (modelExecutionConfig != null) {
                builder.modelExecutionConfig(modelExecutionConfig);
            }
            if (toolExecutionConfig != null) {
                builder.toolExecutionConfig(toolExecutionConfig);
            }
            if (structuredOutputReminder != null) {
                builder.structuredOutputReminder(structuredOutputReminder);
            }
            if (planNotebook != null) {
                builder.planNotebook(planNotebook);
            }
            if (toolExecutionContext != null) {
                builder.toolExecutionContext(toolExecutionContext);
            }

            ReActAgent shadowAgent = builder.build();

            logger.debug(
                    "Successfully cloned ReActAgent: {} -> {}",
                    original.getName(),
                    shadowAgent.getName());

            return shadowAgent;

        } catch (Exception e) {
            throw new RuntimeException("Failed to clone ReActAgent: " + original.getName(), e);
        }
    }

    /**
     * Extract private final fields using reflection
     */
    @SuppressWarnings("unchecked")
    private static <T> T extractField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(obj);
        } catch (NoSuchFieldException e) {
            logger.warn("Field not found: {} in {}", fieldName, obj.getClass().getSimpleName());
            return null;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to extract field: " + fieldName + " from " + obj.getClass(), e);
        }
    }
}

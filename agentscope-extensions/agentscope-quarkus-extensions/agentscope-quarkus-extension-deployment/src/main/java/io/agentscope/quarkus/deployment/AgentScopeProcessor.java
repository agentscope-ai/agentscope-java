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
package io.agentscope.quarkus.deployment;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.quarkus.runtime.AgentScopeConfig;
import io.agentscope.quarkus.runtime.AgentScopeRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

/**
 * Quarkus build processor for AgentScope extension.
 * This class handles build-time processing including:
 * - Feature registration
 * - Reflection registration for native image
 * - CDI bean registration
 */
public class AgentScopeProcessor {

    private static final String FEATURE = "agentscope";

    /**
     * Register the AgentScope feature.
     */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Register classes for reflection (required for GraalVM native image).
     * This ensures that AgentScope classes can be accessed via reflection
     * in native compiled applications.
     */
    @BuildStep
    void registerForReflection(BuildProducer<ReflectiveClassBuildItem> reflective) {
        // Core agent classes
        reflective.produce(
                ReflectiveClassBuildItem.builder(AgentBase.class, ReActAgent.class)
                        .methods()
                        .fields()
                        .constructors()
                        .build());

        // Memory classes
        reflective.produce(
                ReflectiveClassBuildItem.builder(Memory.class, InMemoryMemory.class)
                        .methods()
                        .fields()
                        .constructors()
                        .build());

        // Model classes
        reflective.produce(
                ReflectiveClassBuildItem.builder(
                                Model.class,
                                io.agentscope.core.model.DashScopeChatModel.class,
                                io.agentscope.core.model.OpenAIChatModel.class,
                                io.agentscope.core.model.GeminiChatModel.class,
                                io.agentscope.core.model.AnthropicChatModel.class)
                        .methods()
                        .fields()
                        .constructors()
                        .build());

        // Message classes
        reflective.produce(
                ReflectiveClassBuildItem.builder(
                                Msg.class,
                                ContentBlock.class,
                                TextBlock.class,
                                ThinkingBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class)
                        .methods()
                        .fields()
                        .constructors()
                        .build());

        // Tool classes
        reflective.produce(
                ReflectiveClassBuildItem.builder(Tool.class, Toolkit.class)
                        .methods()
                        .fields()
                        .constructors()
                        .build());
    }

    /**
     * Register CDI beans that should not be removed during optimization.
     * This ensures that AgentScope components are available for injection
     * even if they are not directly referenced in the application code.
     */
    @BuildStep
    void addDependencies(BuildProducer<AdditionalBeanBuildItem> beans) {
        // Register AgentScopeProducer to enable auto-configuration
        beans.produce(
                AdditionalBeanBuildItem.builder()
                        .addBeanClass(io.agentscope.quarkus.runtime.AgentScopeProducer.class)
                        .setUnremovable()
                        .setDefaultScope(io.quarkus.arc.processor.DotNames.APPLICATION_SCOPED)
                        .build());

        // Make AgentBase unremovable so it can be injected
        beans.produce(AdditionalBeanBuildItem.unremovableOf(AgentBase.class));
        beans.produce(AdditionalBeanBuildItem.unremovableOf(Memory.class));
        beans.produce(AdditionalBeanBuildItem.unremovableOf(Model.class));
    }

    /**
     * Initialize AgentScope at runtime.
     * This method is called during the runtime init phase and uses the
     * AgentScopeRecorder to initialize the configuration.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void initializeAgentScope(AgentScopeRecorder recorder, AgentScopeConfig config) {
        recorder.initialize(config);
    }
}

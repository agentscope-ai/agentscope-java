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
package io.agentscope.core.benchmark;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.pipeline.SequentialPipeline;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Shared fixtures and helpers for JMH benchmarks. */
public final class BenchmarkSupport {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private BenchmarkSupport() {}

    public static ReActAgent createAgent(String name, String responseText) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt("You are a benchmark agent.")
                .model(new MockModel(responseText))
                .toolkit(new Toolkit())
                .memory(new InMemoryMemory())
                .build();
    }

    public static Msg createInputMessage(String text) {
        return TestUtils.createUserMessage("benchmark-user", text);
    }

    public static SequentialPipeline createSequentialPipeline(int agentCount) {
        List<AgentBase> agents = new ArrayList<>();
        for (int index = 0; index < agentCount; index++) {
            agents.add(createAgent("PipelineAgent" + index, "pipeline-response-" + index));
        }
        return new SequentialPipeline(agents);
    }

    public static MsgHub createEnteredHub(int participantCount) {
        List<AgentBase> participants = new ArrayList<>();
        for (int index = 0; index < participantCount; index++) {
            participants.add(createAgent("HubAgent" + index, "hub-response-" + index));
        }

        MsgHub hub = MsgHub.builder().name("BenchmarkHub").participants(participants).build();
        hub.enter().block(DEFAULT_TIMEOUT);
        return hub;
    }
}

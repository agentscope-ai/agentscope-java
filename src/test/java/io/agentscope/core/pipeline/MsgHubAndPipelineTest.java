/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled
public class MsgHubAndPipelineTest {

    private AgentBase alice;
    private AgentBase bob;
    private AgentBase charlie;

    @BeforeEach
    public void setUp() {
        // Create test agents using mock model (since we won't make real API calls)
        OpenAIChatModel model =
                OpenAIChatModel.builder().modelName("gpt-3.5-turbo").apiKey("test-key").build();

        alice =
                ReActAgent.builder()
                        .name("Alice")
                        .sysPrompt("I am Alice.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        bob =
                ReActAgent.builder()
                        .name("Bob")
                        .sysPrompt("I am Bob.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        charlie =
                ReActAgent.builder()
                        .name("Charlie")
                        .sysPrompt("I am Charlie.")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();
    }

    @Test
    public void testSequentialPipeline() throws Exception {
        List<AgentBase> agents = List.of(alice, bob, charlie);
        SequentialPipeline pipeline = new SequentialPipeline(agents);

        Msg input =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Start of pipeline").build())
                        .build();

        // Note: This test will fail with actual API calls due to test API key,
        // but we can test the pipeline structure and error handling
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> {
                    pipeline.execute(input).block();
                });

        assertEquals(3, pipeline.size());
        assertFalse(pipeline.isEmpty());
        assertEquals("SequentialPipeline[3 agents]", pipeline.getDescription());
    }

    @Test
    public void testFanoutPipeline() throws Exception {
        List<AgentBase> agents = List.of(alice, bob);
        FanoutPipeline pipeline = new FanoutPipeline(agents, true);

        assertTrue(pipeline.isConcurrentEnabled());
        assertEquals(2, pipeline.size());
        assertFalse(pipeline.isEmpty());

        // Test with sequential execution
        FanoutPipeline sequentialPipeline = new FanoutPipeline(agents, false);
        assertFalse(sequentialPipeline.isConcurrentEnabled());
    }

    @Test
    public void testEmptyPipelines() throws Exception {
        // Test empty sequential pipeline
        SequentialPipeline emptySequential = new SequentialPipeline(List.of());
        assertTrue(emptySequential.isEmpty());

        Msg input =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        Msg result = emptySequential.execute(input).block();
        assertEquals(input, result); // Should return input unchanged

        // Test empty fanout pipeline
        FanoutPipeline emptyFanout = new FanoutPipeline(List.of());
        assertTrue(emptyFanout.isEmpty());

        List<Msg> results = emptyFanout.execute(input).block();
        assertTrue(results.isEmpty());
    }

    @Test
    public void testPipelineBuilders() {
        // Test SequentialPipeline builder
        SequentialPipeline sequential =
                SequentialPipeline.builder()
                        .addAgent(alice)
                        .addAgent(bob)
                        .addAgents(List.of(charlie))
                        .build();

        assertEquals(3, sequential.size());
        assertEquals(
                List.of("Alice", "Bob", "Charlie"),
                sequential.getAgents().stream().map(AgentBase::getName).toList());

        // Test FanoutPipeline builder
        FanoutPipeline concurrent =
                FanoutPipeline.builder().addAgent(alice).addAgent(bob).concurrent().build();

        assertEquals(2, concurrent.size());
        assertTrue(concurrent.isConcurrentEnabled());

        FanoutPipeline sequential2 =
                FanoutPipeline.builder().addAgent(charlie).sequential().build();

        assertEquals(1, sequential2.size());
        assertFalse(sequential2.isConcurrentEnabled());
    }

    @Test
    public void testPipelineUtilityMethods() {
        List<AgentBase> agents = List.of(alice, bob);

        // Test static utility methods
        SequentialPipeline sequential = Pipelines.createSequential(agents);
        assertEquals(2, sequential.size());

        FanoutPipeline fanout = Pipelines.createFanout(agents);
        assertTrue(fanout.isConcurrentEnabled());

        FanoutPipeline fanoutSequential = Pipelines.createFanoutSequential(agents);
        assertFalse(fanoutSequential.isConcurrentEnabled());
    }

    @Test
    public void testPipelineComposition() throws Exception {
        SequentialPipeline first = new SequentialPipeline(List.of(alice));
        SequentialPipeline second = new SequentialPipeline(List.of(bob));

        Pipeline<Msg> composed = Pipelines.compose(first, second);
        assertNotNull(composed);
        assertTrue(composed.getDescription().startsWith("Composed["));
    }

    @Test
    public void testAgentSubscriberManagement() {
        // Test subscriber functionality
        assertFalse(alice.hasSubscribers());
        assertEquals(0, alice.getSubscriberCount());

        // Set up subscribers
        alice.resetSubscribers("test_hub", List.of(bob, charlie));
        assertTrue(alice.hasSubscribers());
        assertEquals(2, alice.getSubscriberCount());

        // Remove subscribers
        alice.removeSubscribers("test_hub");
        assertFalse(alice.hasSubscribers());
        assertEquals(0, alice.getSubscriberCount());
    }
}

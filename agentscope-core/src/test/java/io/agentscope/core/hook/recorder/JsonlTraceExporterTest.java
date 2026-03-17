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
package io.agentscope.core.hook.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.util.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JsonlTraceExporterTest {

    @TempDir Path tempDir;

    @Test
    void writesJsonlLinesForEvents() throws Exception {
        Path output = tempDir.resolve("trace.jsonl");

        JsonlTraceExporter exporter =
                JsonlTraceExporter.builder(output).append(false).flushEveryLine(true).build();

        Agent agent =
                new Agent() {
                    @Override
                    public String getAgentId() {
                        return "agent-1";
                    }

                    @Override
                    public String getName() {
                        return "TestAgent";
                    }

                    @Override
                    public void interrupt() {}

                    @Override
                    public void interrupt(Msg msg) {}

                    @Override
                    public Mono<Msg> call(Msg msg) {
                        return Mono.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Mono<Msg> call(List<Msg> msgs) {
                        return Mono.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
                        return Mono.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
                        return Mono.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Flux<Event> stream(Msg msg, StreamOptions options) {
                        return Flux.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
                        return Flux.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Flux<Event> stream(
                            Msg msg, StreamOptions options, Class<?> structuredModel) {
                        return Flux.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Flux<Event> stream(
                            List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
                        return Flux.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Mono<Void> observe(Msg msg) {
                        return Mono.error(new UnsupportedOperationException("not used"));
                    }

                    @Override
                    public Mono<Void> observe(List<Msg> msgs) {
                        return Mono.error(new UnsupportedOperationException("not used"));
                    }
                };

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("hello").build())
                        .build();

        Msg finalMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("world").build())
                        .build();

        exporter.onEvent(new PreCallEvent(agent, List.of(userMsg))).block();
        exporter.onEvent(new PostCallEvent(agent, finalMsg)).block();
        exporter.close();

        List<String> lines = Files.readAllLines(output);
        assertEquals(2, lines.size());

        Map<String, Object> line1 =
                JsonUtils.getJsonCodec()
                        .fromJson(lines.get(0), new TypeReference<Map<String, Object>>() {});
        assertEquals("PRE_CALL", line1.get("event_type"));
        assertEquals("agent-1", line1.get("agent_id"));
        assertEquals("TestAgent", line1.get("agent_name"));
        assertNotNull(line1.get("run_id"));
        assertNotNull(line1.get("turn_id"));
        assertNotNull(line1.get("step_id"));

        Map<String, Object> line2 =
                JsonUtils.getJsonCodec()
                        .fromJson(lines.get(1), new TypeReference<Map<String, Object>>() {});
        assertEquals("POST_CALL", line2.get("event_type"));
        assertTrue(line2.containsKey("final_message"));
    }
}

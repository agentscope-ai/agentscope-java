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
package io.agentscope.builder.web.managed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionEventMapperTest {

    private SessionEventMapper mapper;
    private SessionEventMapper.PreviewIds previewIds;

    @BeforeEach
    void setUp() {
        mapper = new SessionEventMapper(new ObjectMapper());
        previewIds = new SessionEventMapper.PreviewIds();
    }

    @Test
    void thinkingBlockDeltaMapsToAgentThinkingPreviewOnly() {
        SessionEventMapper.MappingResult result =
                mapper.map(
                        new ThinkingBlockDeltaEvent("reply-1", "block-1", "reasoning chunk"),
                        previewIds);

        assertThat(result.persisted()).isEmpty();
        assertThat(result.preview()).isPresent();
        SessionEventMapper.PreviewFrame frame = result.preview().get();
        assertThat(frame.streamType()).isEqualTo(SessionEventTypes.EVENT_DELTA);
        assertThat(frame.targetType()).isEqualTo(SessionEventTypes.AGENT_THINKING);
        assertThat(frame.delta()).isEqualTo("reasoning chunk");
        assertThat(frame.eventId()).startsWith("evt_");
    }

    @Test
    void agentResultReusesPreviewMessageEventId() {
        SessionEventMapper.MappingResult delta =
                mapper.map(new TextBlockDeltaEvent("r", "b", "Hel"), previewIds);
        String previewId = delta.preview().orElseThrow().eventId();

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hello").build();
        SessionEventMapper.MappingResult result = mapper.map(new AgentResultEvent(msg), previewIds);

        assertThat(result.preview()).isEmpty();
        assertThat(result.persisted()).isPresent();
        SessionEventMapper.PersistedEvent persisted = result.persisted().get();
        assertThat(persisted.type()).isEqualTo(SessionEventTypes.AGENT_MESSAGE);
        assertThat(persisted.payload().get("text")).isEqualTo("Hello");
        assertThat(persisted.eventId()).isEqualTo(previewId);
    }

    /**
     * The harness emits ModelCallEnd while finishing the model request, and AgentResult only at the
     * very end of the turn. Closing the request must not discard the preview id, or the client
     * renders the typewriter preview and the persisted message as two separate bubbles.
     */
    @Test
    void agentResultReusesPreviewMessageEventIdAcrossModelCallEnd() {
        mapper.map(new ModelCallStartEvent("r"), previewIds);
        SessionEventMapper.MappingResult delta =
                mapper.map(new TextBlockDeltaEvent("r", "b", "Hel"), previewIds);
        String previewId = delta.preview().orElseThrow().eventId();
        mapper.map(new ModelCallEndEvent("r", null), previewIds);

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hello").build();
        SessionEventMapper.MappingResult result = mapper.map(new AgentResultEvent(msg), previewIds);

        assertThat(result.persisted().orElseThrow().eventId()).isEqualTo(previewId);
    }

    /** Each model request opens a fresh preview window; the result reconciles with the last one. */
    @Test
    void multiRoundTurnReusesLastRoundPreviewMessageEventId() {
        mapper.map(new ModelCallStartEvent("r"), previewIds);
        String firstRoundId =
                mapper.map(new TextBlockDeltaEvent("r", "b", "thinking"), previewIds)
                        .preview()
                        .orElseThrow()
                        .eventId();
        mapper.map(new ModelCallEndEvent("r", null), previewIds);

        mapper.map(new ModelCallStartEvent("r"), previewIds);
        String secondRoundId =
                mapper.map(new TextBlockDeltaEvent("r", "b", "answer"), previewIds)
                        .preview()
                        .orElseThrow()
                        .eventId();
        mapper.map(new ModelCallEndEvent("r", null), previewIds);

        assertThat(secondRoundId).isNotEqualTo(firstRoundId);

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("answer").build();
        SessionEventMapper.MappingResult result = mapper.map(new AgentResultEvent(msg), previewIds);

        assertThat(result.persisted().orElseThrow().eventId()).isEqualTo(secondRoundId);
    }

    @Test
    void toolCallAccumulatesInputAndPersistsOnEnd() {
        assertThat(mapper.map(new ToolCallStartEvent("r", "tool-1", "bash"), previewIds).preview())
                .isPresent();

        SessionEventMapper.MappingResult d1 =
                mapper.map(new ToolCallDeltaEvent("r", "tool-1", "bash", "{\"cmd\":"), previewIds);
        assertThat(d1.persisted()).isEmpty();
        assertThat(d1.preview()).isPresent();

        mapper.map(new ToolCallDeltaEvent("r", "tool-1", "bash", "\"ls\"}"), previewIds);

        SessionEventMapper.MappingResult end =
                mapper.map(new ToolCallEndEvent("r", "tool-1", "bash"), previewIds);
        assertThat(end.persisted()).isPresent();
        SessionEventMapper.PersistedEvent persisted = end.persisted().get();
        assertThat(persisted.type()).isEqualTo(SessionEventTypes.AGENT_TOOL_USE);
        assertThat(persisted.eventId()).isEqualTo(d1.preview().get().eventId());
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) persisted.payload().get("input");
        assertThat(input.get("cmd")).isEqualTo("ls");
    }

    @Test
    void toolResultAccumulatesOutputAndPersistsOnEnd() {
        mapper.map(new ToolResultTextDeltaEvent("r", "tool-1", "bash", "file1\n"), previewIds);
        mapper.map(new ToolResultTextDeltaEvent("r", "tool-1", "bash", "file2\n"), previewIds);

        SessionEventMapper.MappingResult end =
                mapper.map(
                        new ToolResultEndEvent("r", "tool-1", "bash", ToolResultState.SUCCESS),
                        previewIds);

        assertThat(end.persisted()).isPresent();
        SessionEventMapper.PersistedEvent persisted = end.persisted().get();
        assertThat(persisted.type()).isEqualTo(SessionEventTypes.AGENT_TOOL_RESULT);
        assertThat(persisted.payload().get("output")).isEqualTo("file1\nfile2\n");
        assertThat(persisted.payload().get("text")).isEqualTo("file1\nfile2\n");
        assertThat(persisted.eventId()).startsWith("evt_");
    }
}

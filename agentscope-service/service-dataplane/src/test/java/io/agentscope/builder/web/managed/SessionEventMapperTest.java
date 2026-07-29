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

import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

class SessionEventMapperTest {

    private final SessionEventMapper mapper = new SessionEventMapper();
    private final SessionEventMapper.PreviewIds previewIds = new SessionEventMapper.PreviewIds();

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
    }

    @Test
    void agentResultMapsToPersistedAgentMessage() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("final answer").build();
        SessionEventMapper.MappingResult result = mapper.map(new AgentResultEvent(msg), previewIds);

        assertThat(result.preview()).isEmpty();
        assertThat(result.persisted()).isPresent();
        SessionEventMapper.PersistedEvent persisted = result.persisted().get();
        assertThat(persisted.type()).isEqualTo(SessionEventTypes.AGENT_MESSAGE);
        assertThat(persisted.payload().get("text")).isEqualTo("final answer");
    }

    @Test
    void toolCallDeltaIsPreviewOnlyNotPersisted() {
        SessionEventMapper.MappingResult result =
                mapper.map(
                        new ToolCallDeltaEvent("reply-1", "tool-1", "bash", "{\"cmd\":"),
                        previewIds);

        assertThat(result.persisted()).isEmpty();
        assertThat(result.preview()).isPresent();
        assertThat(result.preview().get().targetType()).isEqualTo(SessionEventTypes.AGENT_TOOL_USE);
    }
}

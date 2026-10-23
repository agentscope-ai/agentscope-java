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
package io.agentscope.spring.boot.admin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;

class AgentStateRestorerTest {

    private static Msg textMsg(MsgRole role, String name, String text) {
        return Msg.builder()
                .role(role)
                .name(name)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Test
    void restoresSummaryAndContextInPlace() {
        AgentState live =
                AgentState.builder()
                        .sessionId("sess-1")
                        .summary("v0 summary")
                        .addMessage(textMsg(MsgRole.USER, "user", "msg-1"))
                        .build();
        String snapshotJson = live.toJson();

        live.setSummary("v1 summary");
        live.contextMutable().add(textMsg(MsgRole.ASSISTANT, "bot", "msg-2"));
        assertThat(live.getSummary()).isEqualTo("v1 summary");
        assertThat(live.getContext()).hasSize(2);

        AgentStateRestorer.restore(live, snapshotJson);

        assertThat(live.getSummary()).isEqualTo("v0 summary");
        assertThat(live.getContext()).hasSize(1);
        assertThat(live.getContext().get(0).getTextContent()).isEqualTo("msg-1");
    }

    @Test
    void rejectsMismatchedSessionId() {
        AgentState live = AgentState.builder().sessionId("sess-1").build();
        AgentState other = AgentState.builder().sessionId("sess-2").summary("x").build();
        String json = other.toJson();
        assertThatThrownBy(() -> AgentStateRestorer.restore(live, json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sess-2")
                .hasMessageContaining("sess-1");
    }

    @Test
    void rejectsBlankJson() {
        AgentState live = AgentState.builder().sessionId("sess-1").build();
        assertThatThrownBy(() -> AgentStateRestorer.restore(live, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidJson() {
        AgentState live = AgentState.builder().sessionId("sess-1").build();
        assertThatThrownBy(() -> AgentStateRestorer.restore(live, "{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid AgentState snapshot");
    }
}

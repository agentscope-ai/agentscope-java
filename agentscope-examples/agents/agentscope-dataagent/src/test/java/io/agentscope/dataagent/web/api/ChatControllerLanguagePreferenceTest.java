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
package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.dataagent.web.binding.UserBinding;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ChatController} applies stored user preferences when shaping inbound messages.
 */
class ChatControllerLanguagePreferenceTest {

    @Test
    void injectsLanguageSystemInstructionWhenChatuiPreferenceExists() {
        UserBinding pref = new UserBinding("chatui", null, null, "zh-CN", null);
        List<Msg> msgs = ChatController.shapeInboundMessages(List.of(pref), "chatui", "hello");
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).getRole()).isEqualTo(MsgRole.SYSTEM);
        assertThat(msgs.get(0).getTextContent()).contains("zh-CN");
        assertThat(msgs.get(1).getRole()).isEqualTo(MsgRole.USER);
    }

    @Test
    void omitsSystemInstructionWhenNoPreference() {
        List<Msg> msgs = ChatController.shapeInboundMessages(List.of(), "chatui", "hello");
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getRole()).isEqualTo(MsgRole.USER);
    }

    @Test
    void ignoresPreferenceForOtherChannel() {
        UserBinding pref = new UserBinding("slack", null, null, "en", null);
        List<Msg> msgs = ChatController.shapeInboundMessages(List.of(pref), "chatui", "hello");
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getRole()).isEqualTo(MsgRole.USER);
    }

    @Test
    void omitsSystemInstructionWhenLanguageBlank() {
        UserBinding pref = new UserBinding("chatui", null, null, null, null);
        List<Msg> msgs = ChatController.shapeInboundMessages(List.of(pref), "chatui", "hello");
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getRole()).isEqualTo(MsgRole.USER);
    }
}

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.live.formatter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AbstractTextLiveFormatter Tests")
class AbstractTextLiveFormatterTest {

    /** Test implementation for AbstractTextLiveFormatter. */
    static class TestFormatter extends AbstractTextLiveFormatter {
        @Override
        protected String formatControl(ControlBlock controlBlock) {
            return "{\"type\":\"control\",\"action\":\"" + controlBlock.getControlType() + "\"}";
        }

        @Override
        protected String formatAudio(byte[] audioData) {
            return "{\"type\":\"audio\",\"data\":\"" + encodeBase64(audioData) + "\"}";
        }

        @Override
        protected LiveEvent parseOutputFromJson(String json) {
            return LiveEvent.unknown("test", json);
        }

        @Override
        protected String buildSessionConfigJson(LiveConfig config, List<ToolSchema> toolSchemas) {
            return "{\"type\":\"config\"}";
        }
    }

    @Test
    @DisplayName("Should format audio message to String")
    void shouldFormatAudioMessageToString() {
        TestFormatter formatter = new TestFormatter();
        byte[] audioData = new byte[] {1, 2, 3, 4};

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                AudioBlock.builder()
                                        .source(RawSource.pcm16kMono(audioData))
                                        .build())
                        .build();

        String result = formatter.formatInput(msg);

        assertNotNull(result);
        assertTrue(result.contains("audio"));
    }

    @Test
    @DisplayName("Should format control message to String")
    void shouldFormatControlMessageToString() {
        TestFormatter formatter = new TestFormatter();

        Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.interrupt()).build();

        String result = formatter.formatInput(msg);

        assertNotNull(result);
        assertTrue(result.contains("INTERRUPT"));
    }

    @Test
    @DisplayName("Should return null for null message")
    void shouldReturnNullForNullMessage() {
        TestFormatter formatter = new TestFormatter();

        String result = formatter.formatInput(null);

        assertNull(result);
    }

    @Test
    @DisplayName("Should parse output from String")
    void shouldParseOutputFromString() {
        TestFormatter formatter = new TestFormatter();
        String json = "{\"type\":\"test\"}";

        LiveEvent event = formatter.parseOutput(json);

        assertNotNull(event);
    }

    @Test
    @DisplayName("Should build session config as String")
    void shouldBuildSessionConfigAsString() {
        TestFormatter formatter = new TestFormatter();
        LiveConfig config = LiveConfig.defaults();

        String result = formatter.buildSessionConfig(config, null);

        assertNotNull(result);
        assertTrue(result.contains("config"));
    }

    @Test
    @DisplayName("Should return null for empty content message")
    void shouldReturnNullForEmptyContentMessage() {
        TestFormatter formatter = new TestFormatter();

        Msg msg = Msg.builder().role(MsgRole.USER).build();

        String result = formatter.formatInput(msg);

        assertNull(result);
    }
}

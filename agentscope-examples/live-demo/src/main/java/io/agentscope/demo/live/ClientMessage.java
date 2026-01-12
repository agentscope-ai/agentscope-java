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
package io.agentscope.demo.live;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import java.util.Base64;

/**
 * Client message types for WebSocket communication.
 *
 * <p>Uses Jackson polymorphic deserialization based on the "type" field.
 *
 * <p>Supported message types:
 * <ul>
 *   <li>audio - Audio data (Base64 encoded PCM 16kHz mono)
 *   <li>text - Text message
 *   <li>interrupt - Interrupt current response
 *   <li>commit - Commit audio buffer (manual mode)
 *   <li>createResponse - Trigger model response (manual mode)
 *   <li>clear - Clear audio buffer
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClientMessage.Audio.class, name = "audio"),
    @JsonSubTypes.Type(value = ClientMessage.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ClientMessage.Image.class, name = "image"),
    @JsonSubTypes.Type(value = ClientMessage.Interrupt.class, name = "interrupt"),
    @JsonSubTypes.Type(value = ClientMessage.Commit.class, name = "commit"),
    @JsonSubTypes.Type(value = ClientMessage.CreateResponse.class, name = "createResponse"),
    @JsonSubTypes.Type(value = ClientMessage.Clear.class, name = "clear")
})
public sealed interface ClientMessage {

    /**
     * Convert this client message to a framework Msg.
     *
     * @return the converted Msg
     */
    Msg toMsg();

    /**
     * Audio message containing Base64 encoded PCM data.
     *
     * @param data Base64 encoded audio data
     */
    record Audio(String data) implements ClientMessage {
        @Override
        public Msg toMsg() {
            byte[] audioData = Base64.getDecoder().decode(data);
            return Msg.builder()
                    .role(MsgRole.USER)
                    .content(AudioBlock.builder().source(RawSource.pcm16kMono(audioData)).build())
                    .build();
        }
    }

    /**
     * Text message.
     *
     * @param data text content
     */
    record Text(String data) implements ClientMessage {
        @Override
        public Msg toMsg() {
            return Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text(data).build())
                    .build();
        }
    }

    /**
     * Image message containing Base64 encoded image data.
     *
     * @param data Base64 encoded image data
     * @param mediaType MIME type of the image (e.g., "image/jpeg")
     */
    record Image(String data, String mediaType) implements ClientMessage {
        @Override
        public Msg toMsg() {
            Base64Source source = Base64Source.builder().data(data).mediaType(mediaType).build();
            return Msg.builder()
                    .role(MsgRole.USER)
                    .content(ImageBlock.builder().source(source).build())
                    .build();
        }
    }

    /** Interrupt control message to stop current response. */
    record Interrupt() implements ClientMessage {
        @Override
        public Msg toMsg() {
            return Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.interrupt()).build();
        }
    }

    /** Commit control message to signal end of speech in manual mode. */
    record Commit() implements ClientMessage {
        @Override
        public Msg toMsg() {
            return Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.commit()).build();
        }
    }

    /** CreateResponse control message to trigger model response in manual mode. */
    record CreateResponse() implements ClientMessage {
        @Override
        public Msg toMsg() {
            return Msg.builder()
                    .role(MsgRole.CONTROL)
                    .content(ControlBlock.createResponse())
                    .build();
        }
    }

    /** Clear control message to discard accumulated audio buffer. */
    record Clear() implements ClientMessage {
        @Override
        public Msg toMsg() {
            return Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.clear()).build();
        }
    }
}

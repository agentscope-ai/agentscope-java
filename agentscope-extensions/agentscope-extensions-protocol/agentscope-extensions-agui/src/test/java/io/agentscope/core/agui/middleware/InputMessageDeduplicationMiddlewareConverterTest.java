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
package io.agentscope.core.agui.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.middleware.InputMessageDeduplicationMiddleware;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * AG-UI converter contracts that the core {@link InputMessageDeduplicationMiddleware} anchor
 * matching relies on: message ids must survive the converter round trip, and content the
 * converter cannot represent must fail explicitly.
 */
class InputMessageDeduplicationMiddlewareConverterTest {

    @Nested
    @DisplayName("converter round-trip and failure surface (full pass-through)")
    class ConverterRoundTrip {

        private final AguiMessageConverter converter = new AguiMessageConverter();

        @Test
        @DisplayName("native multi-block assistant message: id survives the round trip and anchors")
        void multiBlockAssistant_roundTrip_anchorsById() {
            Msg nativeAssistant = assistantMsgWithImage("a1", "here is the chart", "img");
            List<Msg> context = List.of(userMsg("u1", "hello"), nativeAssistant);

            Msg back = converter.toMsg(converter.toAguiMessage(nativeAssistant));
            assertEquals("a1", back.getId(), "converter must preserve the message id (R2 basis)");

            List<Msg> delta =
                    InputMessageDeduplicationMiddleware.extractDelta(
                            context, new ArrayList<>(List.of(back, userMsg("u2", "again"))));

            assertEquals(List.of("u2"), idsOf(delta));
        }

        @Test
        @DisplayName("a resent history the converter cannot map fails explicitly")
        void unmappableStructuredContent_failsExplicitly() {
            AguiMessage structuredAssistant =
                    new AguiMessage(
                            "a1",
                            "assistant",
                            new MessageContent.Blocks(List.of(new TextInputContent("hi"))),
                            null,
                            null);

            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> converter.toMsg(structuredAssistant));

            assertTrue(
                    error.getMessage().contains("Structured content blocks"),
                    "the error must be locatable: " + error.getMessage());
        }
    }

    // ---------- fixtures ----------

    private static Msg userMsg(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg assistantMsgWithImage(String id, String text, String imageUrl) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.ASSISTANT)
                .content(
                        TextBlock.builder().text(text).build(),
                        ImageBlock.builder().source(new URLSource(imageUrl)).build())
                .build();
    }

    private static List<String> idsOf(List<Msg> msgs) {
        return msgs.stream().map(Msg::getId).toList();
    }
}

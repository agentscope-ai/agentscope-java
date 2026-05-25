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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behaviour spec for {@code Msg#validateRoleContent()}.
 *
 * <ul>
 *   <li>{@link MsgRole#USER} accepts the unified {@link DataBlock} <em>and</em>
 *       the legacy {@link ImageBlock} / {@link AudioBlock} / {@link VideoBlock}
 *       subclasses (kept for back-compat).</li>
 *   <li>{@link MsgRole#TOOL} is treated as unrestricted (same as assistant) to
 *       avoid cascading changes across the formatter/converter call sites that
 *       already build TOOL messages with arbitrary block lists.</li>
 * </ul>
 *
 * <p>Matrix actually enforced:
 *
 * <table>
 *   <caption>Role × block compatibility</caption>
 *   <tr><th>Role</th><th>Allowed blocks</th></tr>
 *   <tr><td>{@code USER}</td><td>{@link TextBlock}, {@link DataBlock}, {@link ImageBlock}, {@link AudioBlock}, {@link VideoBlock}</td></tr>
 *   <tr><td>{@code ASSISTANT}</td><td>any (no restriction)</td></tr>
 *   <tr><td>{@code SYSTEM}</td><td>{@link TextBlock} only</td></tr>
 *   <tr><td>{@code TOOL}</td><td>any (back-compat carve-out)</td></tr>
 * </table>
 */
class RoleContentValidationTest {

    private static TextBlock text() {
        return TextBlock.builder().text("hello").build();
    }

    private static DataBlock data() {
        return DataBlock.builder()
                .source(URLSource.builder().url("https://example.com/x.png").build())
                .name("x.png")
                .build();
    }

    private static ImageBlock image() {
        return ImageBlock.builder()
                .source(URLSource.builder().url("https://example.com/x.png").build())
                .build();
    }

    private static AudioBlock audio() {
        return new AudioBlock(URLSource.builder().url("https://example.com/x.mp3").build());
    }

    private static VideoBlock video() {
        return new VideoBlock(URLSource.builder().url("https://example.com/x.mp4").build());
    }

    private static HintBlock hint() {
        return new HintBlock("hint-1", "consider X");
    }

    private static ThinkingBlock thinking() {
        return ThinkingBlock.builder().thinking("reasoning...").build();
    }

    private static ToolUseBlock toolUse() {
        return new ToolUseBlock("call-1", "search", Map.of("q", "kittens"));
    }

    private static ToolResultBlock toolResult() {
        return ToolResultBlock.of("call-1", "search", text());
    }

    @Nested
    @DisplayName("USER role: text and data (incl. legacy image/audio/video) only")
    class UserRole {

        @Test
        @DisplayName("USER + TextBlock is valid")
        void userAcceptsText() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.USER).content(List.of(text())).build());
        }

        @Test
        @DisplayName("USER + DataBlock is valid")
        void userAcceptsData() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.USER).content(List.of(data())).build());
        }

        @Test
        @DisplayName("USER + legacy ImageBlock is valid")
        void userAcceptsImage() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.USER).content(List.of(image())).build());
        }

        @Test
        @DisplayName("USER + legacy AudioBlock is valid")
        void userAcceptsAudio() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.USER).content(List.of(audio())).build());
        }

        @Test
        @DisplayName("USER + legacy VideoBlock is valid")
        void userAcceptsVideo() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.USER).content(List.of(video())).build());
        }

        @Test
        @DisplayName("USER + HintBlock is rejected")
        void userRejectsHint() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .content(List.of(hint()))
                                            .build());
            assertTrue(ex.getMessage().contains("HintBlock"));
        }

        @Test
        @DisplayName("USER + ThinkingBlock is rejected")
        void userRejectsThinking() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .content(List.of(thinking()))
                                            .build());
            assertTrue(ex.getMessage().contains("ThinkingBlock"));
        }

        @Test
        @DisplayName("USER + ToolUseBlock is rejected")
        void userRejectsToolUse() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .content(List.of(toolUse()))
                                            .build());
            assertTrue(ex.getMessage().contains("ToolUseBlock"));
        }

        @Test
        @DisplayName("USER + ToolResultBlock is rejected")
        void userRejectsToolResult() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .content(List.of(toolResult()))
                                            .build());
            assertTrue(ex.getMessage().contains("ToolResultBlock"));
        }
    }

    @Nested
    @DisplayName("ASSISTANT role: unrestricted")
    class AssistantRole {

        @Test
        @DisplayName("ASSISTANT + TextBlock is valid")
        void assistantAcceptsText() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.ASSISTANT).content(List.of(text())).build());
        }

        @Test
        @DisplayName("ASSISTANT + HintBlock is valid")
        void assistantAcceptsHint() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.ASSISTANT).content(List.of(hint())).build());
        }

        @Test
        @DisplayName("ASSISTANT + ThinkingBlock is valid")
        void assistantAcceptsThinking() {
            assertDoesNotThrow(
                    () ->
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(thinking()))
                                    .build());
        }

        @Test
        @DisplayName("ASSISTANT + DataBlock is valid")
        void assistantAcceptsData() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.ASSISTANT).content(List.of(data())).build());
        }

        @Test
        @DisplayName("ASSISTANT + ToolUseBlock is valid")
        void assistantAcceptsToolUse() {
            assertDoesNotThrow(
                    () ->
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(toolUse()))
                                    .build());
        }

        @Test
        @DisplayName("ASSISTANT mixing thinking + text + tool_use is valid")
        void assistantAcceptsMixedReasoningTurn() {
            assertDoesNotThrow(
                    () ->
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(thinking(), text(), toolUse()))
                                    .build());
        }

        @Test
        @DisplayName("ASSISTANT + ToolResultBlock is valid (unrestricted)")
        void assistantAcceptsToolResult() {
            assertDoesNotThrow(
                    () ->
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(List.of(toolResult()))
                                    .build());
        }
    }

    @Nested
    @DisplayName("SYSTEM role: text only")
    class SystemRole {

        @Test
        @DisplayName("SYSTEM + TextBlock is valid")
        void systemAcceptsText() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.SYSTEM).content(List.of(text())).build());
        }

        @Test
        @DisplayName("SYSTEM + DataBlock is rejected")
        void systemRejectsData() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Msg.builder()
                                            .role(MsgRole.SYSTEM)
                                            .content(List.of(data()))
                                            .build());
            assertTrue(ex.getMessage().contains("DataBlock"));
        }

        @Test
        @DisplayName("SYSTEM + ImageBlock is rejected")
        void systemRejectsImage() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Msg.builder().role(MsgRole.SYSTEM).content(List.of(image())).build());
        }

        @Test
        @DisplayName("SYSTEM + HintBlock is rejected")
        void systemRejectsHint() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Msg.builder().role(MsgRole.SYSTEM).content(List.of(hint())).build());
        }

        @Test
        @DisplayName("SYSTEM + ThinkingBlock is rejected")
        void systemRejectsThinking() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Msg.builder().role(MsgRole.SYSTEM).content(List.of(thinking())).build());
        }

        @Test
        @DisplayName("SYSTEM + ToolUseBlock is rejected")
        void systemRejectsToolUse() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Msg.builder().role(MsgRole.SYSTEM).content(List.of(toolUse())).build());
        }
    }

    @Nested
    @DisplayName("TOOL role: unrestricted (Java back-compat carve-out)")
    class ToolRole {

        @Test
        @DisplayName("TOOL + ToolResultBlock is valid")
        void toolAcceptsToolResult() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.TOOL).content(List.of(toolResult())).build());
        }

        @Test
        @DisplayName("TOOL + TextBlock is valid (Java back-compat)")
        void toolAcceptsText() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.TOOL).content(List.of(text())).build());
        }

        @Test
        @DisplayName("TOOL + ToolUseBlock is valid (Java back-compat)")
        void toolAcceptsToolUse() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.TOOL).content(List.of(toolUse())).build());
        }

        @Test
        @DisplayName("TOOL + DataBlock is valid (Java back-compat)")
        void toolAcceptsData() {
            assertDoesNotThrow(
                    () -> Msg.builder().role(MsgRole.TOOL).content(List.of(data())).build());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty content list is allowed for any role")
        void emptyContentAlwaysValid() {
            for (MsgRole role : MsgRole.values()) {
                assertDoesNotThrow(
                        () -> Msg.builder().role(role).content(List.of()).build(),
                        "empty content rejected for role " + role);
            }
        }

        @Test
        @DisplayName("Null content is normalised to empty list and accepted")
        void nullContentNormalised() {
            Msg msg = Msg.builder().role(MsgRole.USER).build();
            assertNotNull(msg.getContent());
            assertEquals(0, msg.getContent().size());
        }

        @Test
        @DisplayName("One invalid block in a valid list rejects the whole message")
        void singleInvalidBlockRejectsMessage() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .content(List.of(text(), hint()))
                                            .build());
            assertTrue(ex.getMessage().contains("HintBlock"));
        }

        @Test
        @DisplayName("Jackson deserialization runs the same validator")
        void deserializationRunsValidator() {
            ObjectMapper mapper = new ObjectMapper();
            String json =
                    "{\"role\":\"system\",\"content\":[{\"type\":\"data\",\"source\":{\"type\":\"url\",\"url\":\"https://example.com/x.png\"}}]}";
            JsonMappingException ex =
                    assertThrows(
                            JsonMappingException.class, () -> mapper.readValue(json, Msg.class));
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            assertTrue(
                    cause.getMessage() == null
                            ? ex.getMessage().contains("SYSTEM")
                            : cause.getMessage().contains("SYSTEM"),
                    "expected SYSTEM-restriction message, got: " + ex.getMessage());
        }
    }
}

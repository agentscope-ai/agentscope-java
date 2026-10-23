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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;

class MsgSubclassesTest {

    // ---------- UserMessage ----------

    @Test
    void userMessage_text() {
        UserMessage msg = new UserMessage("hello");

        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals("hello", msg.getTextContent());
        assertEquals(1, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getFirstContentBlock());
        assertNull(msg.getName());
        assertNotNull(msg.getId());
        assertNotNull(msg.getTimestamp());
    }

    @Test
    void userMessage_nameAndText() {
        UserMessage msg = new UserMessage("alice", "hello");

        assertEquals("alice", msg.getName());
        assertEquals("hello", msg.getTextContent());
        assertEquals(MsgRole.USER, msg.getRole());
    }

    @Test
    void userMessage_varargsBlocks() {
        TextBlock a = TextBlock.builder().text("a").build();
        TextBlock b = TextBlock.builder().text("b").build();
        UserMessage msg = new UserMessage(a, b);

        assertEquals(2, msg.getContent().size());
        assertEquals("a\nb", msg.getTextContent());
    }

    @Test
    void userMessage_nameAndVarargsBlocks() {
        TextBlock a = TextBlock.builder().text("a").build();
        UserMessage msg = new UserMessage("alice", a);

        assertEquals("alice", msg.getName());
        assertEquals(1, msg.getContent().size());
    }

    @Test
    void userMessage_listOfBlocks() {
        UserMessage msg = new UserMessage(List.of(TextBlock.builder().text("x").build()));

        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals("x", msg.getTextContent());
    }

    // ---------- AssistantMessage ----------

    @Test
    void assistantMessage_text() {
        AssistantMessage msg = new AssistantMessage("sure");

        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertEquals("sure", msg.getTextContent());
    }

    @Test
    void assistantMessage_acceptsToolUseBlock() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("search")
                        .input(java.util.Map.of())
                        .build();
        AssistantMessage msg = new AssistantMessage(toolUse);

        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertInstanceOf(ToolUseBlock.class, msg.getFirstContentBlock());
    }

    // ---------- SystemMessage ----------

    @Test
    void systemMessage_text() {
        SystemMessage msg = new SystemMessage("you are helpful");

        assertEquals(MsgRole.SYSTEM, msg.getRole());
        assertEquals("you are helpful", msg.getTextContent());
    }

    @Test
    void systemMessage_rejectsNonTextBlock() {
        URLSource url = URLSource.builder().url("https://example.com/x.jpg").build();
        ImageBlock image = ImageBlock.builder().source(url).build();

        assertThrows(IllegalArgumentException.class, () -> new SystemMessage(image));
    }

    // ---------- ToolResultMessage ----------

    @Test
    void toolResultMessage_idNameText() {
        ToolResultMessage msg = new ToolResultMessage("call-1", "search", "result");

        assertEquals(MsgRole.TOOL, msg.getRole());
        assertEquals(1, msg.getContent().size());

        ToolResultBlock block = (ToolResultBlock) msg.getFirstContentBlock();
        assertEquals("call-1", block.getId());
        assertEquals("search", block.getName());
        assertEquals(1, block.getOutput().size());
        assertInstanceOf(TextBlock.class, block.getOutput().get(0));
        assertEquals("result", ((TextBlock) block.getOutput().get(0)).getText());
    }

    @Test
    void toolResultMessage_varargs() {
        ToolResultBlock r1 = new ToolResultBlock("c1", "s", TextBlock.builder().text("x").build());
        ToolResultBlock r2 = new ToolResultBlock("c2", "s", TextBlock.builder().text("y").build());
        ToolResultMessage msg = new ToolResultMessage(r1, r2);

        assertEquals(MsgRole.TOOL, msg.getRole());
        assertEquals(2, msg.getContent().size());
    }

    @Test
    void toolResultMessage_list() {
        ToolResultBlock r1 = new ToolResultBlock("c1", "s", TextBlock.builder().text("x").build());
        ToolResultMessage msg = new ToolResultMessage(List.of(r1));

        assertEquals(MsgRole.TOOL, msg.getRole());
        assertEquals(1, msg.getContent().size());
    }

    // ---------- JSON polymorphic round-trip ----------

    @Test
    void jsonRoundTrip_userMessage() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        Msg orig = new UserMessage("hi");
        String json = codec.toJson(orig);

        Msg restored = codec.fromJson(json, Msg.class);

        assertInstanceOf(UserMessage.class, restored);
        assertEquals("hi", restored.getTextContent());
        assertEquals(MsgRole.USER, restored.getRole());
    }

    @Test
    void jsonRoundTrip_assistantMessage() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        Msg orig = new AssistantMessage("ok");
        Msg restored = codec.fromJson(codec.toJson(orig), Msg.class);

        assertInstanceOf(AssistantMessage.class, restored);
        assertEquals("ok", restored.getTextContent());
    }

    @Test
    void jsonRoundTrip_systemMessage() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        Msg orig = new SystemMessage("be terse");
        Msg restored = codec.fromJson(codec.toJson(orig), Msg.class);

        assertInstanceOf(SystemMessage.class, restored);
        assertEquals("be terse", restored.getTextContent());
    }

    @Test
    void jsonRoundTrip_toolResultMessage() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        Msg orig = new ToolResultMessage("call-1", "search", "result");
        Msg restored = codec.fromJson(codec.toJson(orig), Msg.class);

        assertInstanceOf(ToolResultMessage.class, restored);
        assertEquals(MsgRole.TOOL, restored.getRole());
        ToolResultBlock block = (ToolResultBlock) restored.getFirstContentBlock();
        assertEquals("call-1", block.getId());
        assertEquals("search", block.getName());
    }

    // ---------- Legacy format backward compatibility ----------

    @Test
    void legacyBuilderJson_deserializesToSubclass() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        // Simulate JSON produced by the existing Msg.builder() path.
        Msg legacy = Msg.builder().role(MsgRole.ASSISTANT).textContent("x").build();
        String json = codec.toJson(legacy);

        Msg restored = codec.fromJson(json, Msg.class);

        assertInstanceOf(AssistantMessage.class, restored);
        assertEquals("x", restored.getTextContent());
    }

    // ---------- Subclass builders ----------

    @Test
    void userMessageBuilder_textContent() {
        UserMessage msg = UserMessage.builder().name("alice").textContent("hi").build();

        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals("alice", msg.getName());
        assertEquals("hi", msg.getTextContent());
        assertNotNull(msg.getId());
        assertNotNull(msg.getTimestamp());
    }

    @Test
    void userMessageBuilder_idAndExplicitContent() {
        TextBlock block = TextBlock.builder().text("x").build();
        UserMessage msg = UserMessage.builder().id("fixed-id").content(block).build();

        assertEquals("fixed-id", msg.getId());
        assertEquals(1, msg.getContent().size());
    }

    @Test
    void assistantMessageBuilder_returnsConcreteType() {
        AssistantMessage msg = AssistantMessage.builder().textContent("ok").build();

        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertEquals("ok", msg.getTextContent());
    }

    @Test
    void assistantMessageBuilder_generateReasonGoesIntoMetadata() {
        AssistantMessage msg =
                AssistantMessage.builder()
                        .textContent("done")
                        .generateReason(GenerateReason.MODEL_STOP)
                        .build();

        assertEquals(GenerateReason.MODEL_STOP, msg.getGenerateReason());
    }

    @Test
    void systemMessageBuilder_text() {
        SystemMessage msg = SystemMessage.builder().textContent("be terse").build();

        assertEquals(MsgRole.SYSTEM, msg.getRole());
        assertEquals("be terse", msg.getTextContent());
    }

    @Test
    void systemMessageBuilder_rejectsNonTextAtBuild() {
        URLSource url = URLSource.builder().url("https://example.com/x.jpg").build();
        ImageBlock image = ImageBlock.builder().source(url).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> SystemMessage.builder().content(image).build());
    }

    @Test
    void toolResultMessageBuilder_resultShorthandAccumulates() {
        ToolResultMessage msg =
                ToolResultMessage.builder()
                        .result("call-1", "search", "result-a")
                        .result("call-2", "search", "result-b")
                        .build();

        assertEquals(MsgRole.TOOL, msg.getRole());
        assertEquals(2, msg.getContent().size());
        ToolResultBlock first = (ToolResultBlock) msg.getContent().get(0);
        assertEquals("call-1", first.getId());
        assertEquals("result-a", ((TextBlock) first.getOutput().get(0)).getText());
        ToolResultBlock second = (ToolResultBlock) msg.getContent().get(1);
        assertEquals("call-2", second.getId());
    }

    @Test
    void toolResultMessageBuilder_resultsReplacesAccumulator() {
        ToolResultBlock fresh =
                new ToolResultBlock("c-final", "s", TextBlock.builder().text("final").build());
        ToolResultMessage msg =
                ToolResultMessage.builder().result("c-stale", "s", "stale").results(fresh).build();

        assertEquals(1, msg.getContent().size());
        assertEquals("c-final", ((ToolResultBlock) msg.getFirstContentBlock()).getId());
    }

    @Test
    void subclassBuildersProduceJsonRoundTripCompatibleMessages() {
        JsonCodec codec = JsonUtils.getJsonCodec();
        Msg built = UserMessage.builder().name("bob").textContent("hey").build();
        Msg restored = codec.fromJson(codec.toJson(built), Msg.class);

        assertInstanceOf(UserMessage.class, restored);
        assertEquals("bob", restored.getName());
        assertEquals("hey", restored.getTextContent());
    }
}

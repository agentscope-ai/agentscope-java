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

package io.agentscope.core.a2a.agent.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A2A client MessageConvertUtil Tests")
class MessageConvertUtilTest {

    @Test
    @DisplayName("Should send metadata and tool input objects as JSON values")
    void shouldSendObjectsAsJsonValues() {
        // The A2A SDK writes values other than maps, lists, strings, numbers and booleans as null.
        ChatUsage usage = ChatUsage.builder().inputTokens(3).outputTokens(5).build();
        Msg msg =
                Msg.builder()
                        .id("msg-1")
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("lookup")
                                        .input(Map.of("usage", usage, "role", MsgRole.USER))
                                        .metadata(Map.of("usage", usage))
                                        .build())
                        .metadata(Map.of(MessageMetadataKeys.CHAT_USAGE, usage))
                        .build();

        Message message = MessageConvertUtil.convertFromMsg(List.of(msg));

        Map<?, ?> msgMetadata = assertInstanceOf(Map.class, message.metadata().get("msg-1"));
        assertJsonUsage(msgMetadata.get(MessageMetadataKeys.CHAT_USAGE));
        DataPart part = assertInstanceOf(DataPart.class, message.parts().get(0));
        Map<?, ?> input = assertInstanceOf(Map.class, part.data());
        assertJsonUsage(input.get("usage"));
        assertEquals("USER", input.get("role"));
        assertJsonUsage(part.metadata().get("usage"));
    }

    private static void assertJsonUsage(Object value) {
        Map<?, ?> usage = assertInstanceOf(Map.class, value);
        assertEquals(3, usage.get("inputTokens"));
        assertEquals(5, usage.get("outputTokens"));
    }

    @Test
    @DisplayName("Should merge marked streaming text chunks from artifact")
    void shouldMergeMarkedStreamingTextChunksFromArtifact() {
        Map<String, Object> firstMetadata = streamingMetadata("msg-1");
        Map<String, Object> secondMetadata = streamingMetadata("msg-1");
        Artifact artifact =
                Artifact.builder()
                        .artifactId("msg-1")
                        .name("agent")
                        .parts(
                                new TextPart("Hel", firstMetadata),
                                new TextPart("lo", secondMetadata))
                        .build();

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(1, result.getContent().size());
        assertInstanceOf(TextBlock.class, result.getContent().get(0));
        assertEquals("Hello", ((TextBlock) result.getContent().get(0)).getText());
        assertEquals("Hello", result.getTextContent());
    }

    @Test
    @DisplayName("Should preserve unmarked text part boundaries")
    void shouldPreserveUnmarkedTextPartBoundaries() {
        Map<String, Object> firstMetadata = new HashMap<>();
        firstMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, "msg-1");
        Map<String, Object> secondMetadata = new HashMap<>();
        secondMetadata.put(MessageConstants.MSG_ID_METADATA_KEY, "msg-1");
        Artifact artifact =
                Artifact.builder()
                        .artifactId("msg-1")
                        .name("agent")
                        .parts(
                                new TextPart("First independent paragraph.", firstMetadata),
                                new TextPart("Second independent paragraph.", secondMetadata))
                        .build();

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(2, result.getContent().size());
        assertInstanceOf(TextBlock.class, result.getContent().get(0));
        assertInstanceOf(TextBlock.class, result.getContent().get(1));
        assertEquals(
                "First independent paragraph.\nSecond independent paragraph.",
                result.getTextContent());
    }

    @Test
    @DisplayName("Should flush streaming accumulator before unmarked part")
    void shouldFlushStreamingAccumulatorBeforeUnmarkedPart() {
        Artifact artifact =
                Artifact.builder()
                        .artifactId("msg-1")
                        .name("agent")
                        .parts(
                                new TextPart("Hel", streamingMetadata("msg-1")),
                                new TextPart(" independent "),
                                new TextPart("lo", streamingMetadata("msg-1")))
                        .build();

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(3, result.getContent().size());
        assertEquals("Hel", ((TextBlock) result.getContent().get(0)).getText());
        assertEquals(" independent ", ((TextBlock) result.getContent().get(1)).getText());
        assertEquals("lo", ((TextBlock) result.getContent().get(2)).getText());
    }

    @Test
    @DisplayName("Should merge marked streaming thinking chunks")
    void shouldMergeMarkedStreamingThinkingChunks() {
        Map<String, Object> firstMetadata = streamingMetadata("msg-1");
        firstMetadata.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                MessageConstants.BlockContent.TYPE_THINKING);
        Map<String, Object> secondMetadata = streamingMetadata("msg-1");
        secondMetadata.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                MessageConstants.BlockContent.TYPE_THINKING);
        Artifact artifact =
                Artifact.builder()
                        .artifactId("msg-1")
                        .name("agent")
                        .parts(
                                new TextPart("thin", firstMetadata),
                                new TextPart("king", secondMetadata))
                        .build();

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(1, result.getContent().size());
        ThinkingBlock thinkingBlock =
                assertInstanceOf(ThinkingBlock.class, result.getContent().get(0));
        assertEquals("thinking", thinkingBlock.getThinking());
    }

    @Test
    @DisplayName("Should split streaming chunks by message id and ignore unparsable parts")
    void shouldSplitStreamingChunksByMessageIdAndIgnoreUnparsableParts() {
        Map<String, Object> unsupportedMetadata = streamingMetadata("msg-2");
        unsupportedMetadata.put(MessageConstants.BLOCK_TYPE_METADATA_KEY, "unsupported");
        Artifact artifact =
                Artifact.builder()
                        .artifactId("msg-1")
                        .name("agent")
                        .parts(
                                new TextPart("A", streamingMetadata("msg-1")),
                                new TextPart("B", streamingMetadata("msg-2")),
                                new DataPart(Map.of(), unsupportedMetadata))
                        .build();

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(2, result.getContent().size());
        assertEquals("A", ((TextBlock) result.getContent().get(0)).getText());
        assertEquals("B", ((TextBlock) result.getContent().get(1)).getText());
    }

    private Map<String, Object> streamingMetadata(String msgId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        metadata.put(MessageConstants.STREAM_CHUNK_METADATA_KEY, Boolean.TRUE);
        return metadata;
    }
}

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A2A client MessageConvertUtil Tests")
class MessageConvertUtilTest {

    @Test
    @DisplayName("Should merge adjacent marked text chunks with identical stream identity")
    void shouldMergeMarkedTextChunksWithIdenticalIdentity() {
        Artifact artifact =
                artifact(
                        new TextPart("Hel", streamingMetadata("msg-1", "block-1", "agent")),
                        new TextPart("lo", streamingMetadata("msg-1", "block-1", "agent")));

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(1, result.getContent().size());
        assertEquals(
                "Hello", assertInstanceOf(TextBlock.class, result.getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should merge adjacent top-level chunks without a source")
    void shouldMergeTopLevelChunksWithoutSource() {
        Artifact artifact =
                artifact(
                        new TextPart("Hel", streamingMetadata("msg-1", "block-1", null)),
                        new TextPart("lo", streamingMetadata("msg-1", "block-1", null)));

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(1, result.getContent().size());
        assertEquals(
                "Hello", assertInstanceOf(TextBlock.class, result.getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should merge adjacent marked thinking chunks with identical stream identity")
    void shouldMergeMarkedThinkingChunksWithIdenticalIdentity() {
        Map<String, Object> first = streamingMetadata("msg-1", "block-1", "agent");
        first.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                MessageConstants.BlockContent.TYPE_THINKING);
        Map<String, Object> second = streamingMetadata("msg-1", "block-1", "agent");
        second.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                MessageConstants.BlockContent.TYPE_THINKING);
        Artifact artifact = artifact(new TextPart("thin", first), new TextPart("king", second));

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(1, result.getContent().size());
        assertEquals(
                "thinking",
                assertInstanceOf(ThinkingBlock.class, result.getContent().get(0)).getThinking());
    }

    @Test
    @DisplayName("Should split marked chunks when any stream identity field differs")
    void shouldSplitMarkedChunksWhenIdentityDiffers() {
        Artifact artifact =
                artifact(
                        new TextPart("A", streamingMetadata("msg-1", "block-1", "agent")),
                        new TextPart("B", streamingMetadata("msg-2", "block-1", "agent")),
                        new TextPart("C", streamingMetadata("msg-2", "block-2", "agent")),
                        new TextPart("D", streamingMetadata("msg-2", "block-2", "subagent")));

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(4, result.getContent().size());
        assertEquals("A\nB\nC\nD", result.getTextContent());
    }

    @Test
    @DisplayName("Should preserve unmarked peer parts and flush around them")
    void shouldPreserveUnmarkedPeerPartsAndFlushAroundThem() {
        Artifact artifact =
                artifact(
                        new TextPart("Hel", streamingMetadata("msg-1", "block-1", "agent")),
                        new TextPart(" independent "),
                        new TextPart("lo", streamingMetadata("msg-1", "block-1", "agent")),
                        new TextPart(" third-party "),
                        new TextPart("part"));

        Msg result = MessageConvertUtil.convertFromArtifact(List.of(artifact), "agent");

        assertEquals(5, result.getContent().size());
        assertEquals("Hel", ((TextBlock) result.getContent().get(0)).getText());
        assertEquals(" independent ", ((TextBlock) result.getContent().get(1)).getText());
        assertEquals("lo", ((TextBlock) result.getContent().get(2)).getText());
        assertEquals(" third-party ", ((TextBlock) result.getContent().get(3)).getText());
        assertEquals("part", ((TextBlock) result.getContent().get(4)).getText());
    }

    @Test
    @DisplayName("Should recursively sanitize metadata without leaking default Java class names")
    void shouldRecursivelySanitizeMetadataWithoutJavaClassNames() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("state", TestState.READY);
        nested.put("values", List.of(TestState.READY, 7));
        nested.put("unsupported", new Object());
        nested.put("nullValue", null);

        Map<String, Object> safe = MessageConvertUtil.protobufSafeMap(nested);

        assertEquals("READY", safe.get("state"));
        assertEquals(List.of("READY", 7), safe.get("values"));
        assertFalse(safe.containsKey("unsupported"));
        assertFalse(safe.containsKey("nullValue"));
        assertFalse(safe.toString().contains("java.lang.Object@"));
    }

    private Artifact artifact(TextPart... parts) {
        return Artifact.builder().artifactId("artifact-1").name("agent").parts(parts).build();
    }

    private Map<String, Object> streamingMetadata(String msgId, String blockId, String source) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msgId);
        metadata.put(MessageConstants.BLOCK_ID_METADATA_KEY, blockId);
        if (source != null) {
            metadata.put(MessageConstants.EVENT_SOURCE_METADATA_KEY, source);
        }
        metadata.put(MessageConstants.STREAM_CHUNK_METADATA_KEY, Boolean.TRUE);
        metadata.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY, MessageConstants.BlockContent.TYPE_TEXT);
        return metadata;
    }

    private enum TestState {
        READY
    }
}

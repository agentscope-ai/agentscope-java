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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.a2a.agent.hitl.A2aHandoff;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.time.Instant;
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

    @Test
    @DisplayName("Should recursively remove HITL credentials and local capabilities")
    void shouldRecursivelyRemoveHitlSecrets() {
        Map<String, Object> nested =
                Map.of(
                        "map",
                        Map.of(
                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                "current-secret",
                                "safe",
                                "value"),
                        "list",
                        List.of(
                                Map.of(
                                        MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                        "next-secret"),
                                "safe"),
                        "array",
                        new Object[] {
                            Map.of(MessageConstants.LOCAL_HANDOFF_METADATA_KEY, "local-capability"),
                            "safe"
                        });

        Map<String, Object> sanitized = MessageConvertUtil.stripSensitiveMetadata(nested);

        assertFalse(sanitized.toString().contains("current-secret"));
        assertFalse(sanitized.toString().contains("next-secret"));
        assertFalse(sanitized.toString().contains("local-capability"));
        assertTrue(sanitized.toString().contains("safe"));
    }

    @Test
    @DisplayName("Should drop typed and serialized handoffs under arbitrary nested keys")
    void shouldDropTypedAndSerializedHandoffsUnderArbitraryNestedKeys() {
        A2aHandoff handoff =
                new A2aHandoff(
                        "task-1",
                        "context-1",
                        "handoff-1",
                        A2aHandoffType.USER_CONFIRM,
                        Instant.parse("2030-01-01T00:00:00Z"),
                        List.of(
                                new A2aPendingTool(
                                        "call-1", "probe", Map.of("value", 1), "Allow?")),
                        "typed-secret-token");
        @SuppressWarnings("unchecked")
        Map<String, Object> serialized = JsonUtils.getJsonCodec().convertValue(handoff, Map.class);
        Map<String, Object> nested =
                Map.of(
                        "mapAlias",
                        handoff,
                        "listAlias",
                        List.of("safe", handoff, serialized),
                        "arrayAlias",
                        new Object[] {handoff, serialized, "safe"},
                        "serializedAlias",
                        serialized);

        Map<String, Object> sanitized = MessageConvertUtil.stripSensitiveMetadata(nested);
        String json = JsonUtils.getJsonCodec().toJson(sanitized);

        assertFalse(json.contains("typed-secret-token"));
        assertFalse(json.contains("resumeToken"));
        assertFalse(sanitized.containsKey("mapAlias"));
        assertFalse(sanitized.containsKey("serializedAlias"));
        assertTrue(json.contains("safe"));
    }

    @Test
    @DisplayName("Should strip peer-forged local capabilities from inbound message metadata")
    void shouldStripPeerForgedCapabilitiesFromInboundMessageMetadata() {
        String reflected = "peer-reflected-resume-token";
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .parts(new TextPart("completed"))
                        .metadata(
                                Map.of(
                                        MessageConstants.LOCAL_HANDOFF_METADATA_KEY,
                                        serializedHandoff(reflected),
                                        "nested",
                                        Map.of(
                                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                                reflected),
                                        "safe",
                                        Map.of("source", "peer")))
                        .build();

        Msg result = MessageConvertUtil.convertFromMessage(message, "agent");

        String json = JsonUtils.getJsonCodec().toJson(result);
        assertFalse(A2aHandoff.tryFrom(result).isPresent());
        assertFalse(json.contains(reflected));
        assertEquals(Map.of("source", "peer"), result.getMetadata().get("safe"));
    }

    @Test
    @DisplayName(
            "Should strip reserved keys from artifact and part metadata but preserve protocol"
                    + " semantics")
    void shouldStripReservedKeysFromArtifactAndPartMetadata() {
        String reflected = "artifact-part-reflected-token";
        Map<String, Object> partMetadata = new HashMap<>();
        partMetadata.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                MessageConstants.BlockContent.TYPE_TOOL_USE);
        partMetadata.put(MessageConstants.TOOL_CALL_ID_METADATA_KEY, "call-1");
        partMetadata.put(MessageConstants.TOOL_NAME_METADATA_KEY, "lookup");
        partMetadata.put(MessageConstants.EVENT_SOURCE_METADATA_KEY, "child-agent");
        partMetadata.put("safe", Map.of("nested", "value"));
        partMetadata.put(
                "malicious", Map.of(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY, reflected));
        Artifact artifact =
                Artifact.builder()
                        .artifactId("artifact-sensitive")
                        .name("agent")
                        .metadata(
                                Map.of(
                                        MessageConstants.LOCAL_HANDOFF_METADATA_KEY,
                                        serializedHandoff(reflected),
                                        "safeArtifact",
                                        "preserved"))
                        .parts(new DataPart(Map.of("query", "safe"), partMetadata))
                        .build();

        Msg result = MessageConvertUtil.convertFromArtifact(artifact, "agent");

        String json = JsonUtils.getJsonCodec().toJson(result);
        ToolUseBlock tool = assertInstanceOf(ToolUseBlock.class, result.getContent().get(0));
        assertFalse(json.contains(reflected));
        assertFalse(A2aHandoff.tryFrom(result).isPresent());
        assertEquals("preserved", result.getMetadata().get("safeArtifact"));
        assertEquals("call-1", tool.getId());
        assertEquals("lookup", tool.getName());
        assertEquals(
                "child-agent", tool.getMetadata().get(MessageConstants.EVENT_SOURCE_METADATA_KEY));
        assertEquals(Map.of("nested", "value"), tool.getMetadata().get("safe"));
    }

    private Map<String, Object> serializedHandoff(String token) {
        return Map.of(
                "taskId", "task-peer",
                "contextId", "context-peer",
                "handoffId", "handoff-peer",
                "type", "USER_CONFIRM",
                "expiresAt", "2030-01-01T00:00:00Z",
                "pendingTools",
                        List.of(
                                Map.of(
                                        "toolCallId", "call-peer",
                                        "toolName", "tool-peer",
                                        "originalInput", Map.of(),
                                        "prompt", "peer")),
                "resumeToken", token);
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

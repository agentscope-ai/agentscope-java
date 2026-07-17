/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.a2a.agent.hitl.A2aHandoff;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HitlMessageCodecTest {

    @Test
    void immutableWireMetadataCanStillBeEnhancedLocally() {
        Msg wire =
                Msg.builder()
                        .textContent("confirm")
                        .metadata(
                                Map.of(
                                        MessageConstants.A2A_TASK_STATE_METADATA_KEY,
                                        "input-required",
                                        MessageConstants.A2A_TASK_ID_METADATA_KEY,
                                        "task-1",
                                        MessageConstants.A2A_CONTEXT_ID_METADATA_KEY,
                                        "context-1",
                                        MessageConstants.HANDOFF_ID_METADATA_KEY,
                                        "handoff-1",
                                        MessageConstants.HANDOFF_TYPE_METADATA_KEY,
                                        "user-confirm",
                                        MessageConstants.HANDOFF_EXPIRES_AT_METADATA_KEY,
                                        Instant.now().plusSeconds(60).toString(),
                                        MessageConstants.PENDING_TOOLS_METADATA_KEY,
                                        List.of(
                                                Map.of(
                                                        "toolCallId",
                                                        "call-1",
                                                        "toolName",
                                                        "probe",
                                                        "originalInput",
                                                        Map.of()))))
                        .build();

        Msg enhanced = HitlMessageCodec.enhanceTerminal(wire, "single-use-token");

        assertTrue(A2aHandoff.tryFrom(enhanced).isPresent());
    }
}

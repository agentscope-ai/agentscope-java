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

package io.agentscope.core.a2a.server.hitl;

import io.agentscope.core.a2a.agent.hitl.A2aExternalToolResponse;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aHitlResponse;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rebuild trusted AgentScope resume messages from server-owned pending tool calls. */
public final class HitlResumeMessageFactory {

    /** Internal-only block that prevents pending recovery from synthesizing ASKING failures. */
    static final String CONFIRMATION_RECOVERY_GUARD_ID =
            "__agentscope_a2a_confirmation_recovery_guard__";

    private HitlResumeMessageFactory() {}

    public static List<Msg> create(
            HitlHandoffRecord handoff, List<? extends A2aHitlResponse> responses) {
        Map<String, A2aHitlResponse> byId = validateExactSet(handoff, responses);
        if (handoff.type() == A2aHandoffType.USER_CONFIRM) {
            return List.of(createConfirmationMessage(handoff, byId));
        }
        return List.of(createExternalResultMessage(handoff, byId));
    }

    private static Msg createConfirmationMessage(
            HitlHandoffRecord handoff, Map<String, A2aHitlResponse> byId) {
        List<ConfirmResult> confirmations = new ArrayList<>();
        for (ToolUseBlock original : handoff.pendingTools()) {
            A2aUserConfirmation response = (A2aUserConfirmation) byId.get(original.getId());
            Map<String, Object> input =
                    response.modifiedInput() == null
                            ? original.getInput()
                            : response.modifiedInput();
            ToolUseBlock trusted =
                    new ToolUseBlock(
                            original.getId(),
                            original.getName(),
                            input,
                            JsonUtils.getJsonCodec().toJson(input),
                            original.getMetadata(),
                            original.getState());
            confirmations.add(
                    new ConfirmResult(response.approved(), trusted, response.permissionRules()));
        }
        ToolResultBlock recoveryGuard =
                new ToolResultBlock(
                        CONFIRMATION_RECOVERY_GUARD_ID,
                        "agentscope_a2a_confirmation_guard",
                        List.of(),
                        Map.of("internal", true),
                        ToolResultState.RUNNING);
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(recoveryGuard)
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmations))
                .build();
    }

    private static Msg createExternalResultMessage(
            HitlHandoffRecord handoff, Map<String, A2aHitlResponse> byId) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (ToolUseBlock original : handoff.pendingTools()) {
            A2aExternalToolResponse response = (A2aExternalToolResponse) byId.get(original.getId());
            results.add(
                    new ToolResultBlock(
                            original.getId(),
                            original.getName(),
                            response.outputBlocks(),
                            response.metadata(),
                            response.state()));
        }
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(new ArrayList<ContentBlock>(results))
                .build();
    }

    private static Map<String, A2aHitlResponse> validateExactSet(
            HitlHandoffRecord handoff, List<? extends A2aHitlResponse> responses) {
        if (handoff == null) {
            throw new IllegalArgumentException("handoff must not be null");
        }
        List<? extends A2aHitlResponse> supplied = responses == null ? List.of() : responses;
        Set<String> expected = new HashSet<>();
        handoff.pendingTools().forEach(tool -> expected.add(tool.getId()));
        Map<String, A2aHitlResponse> byId = new HashMap<>();
        for (A2aHitlResponse response : supplied) {
            if (response == null || byId.putIfAbsent(response.toolCallId(), response) != null) {
                throw new IllegalArgumentException("Duplicate or null HITL response");
            }
            boolean rightType =
                    handoff.type() == A2aHandoffType.USER_CONFIRM
                            ? response instanceof A2aUserConfirmation
                            : response instanceof A2aExternalToolResponse;
            if (!rightType) {
                throw new IllegalArgumentException("HITL response type mismatch");
            }
        }
        if (!expected.equals(byId.keySet())) {
            throw new IllegalArgumentException(
                    "HITL responses must answer the exact pending tool set");
        }
        return byId;
    }
}

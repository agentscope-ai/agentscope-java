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

package io.agentscope.durable.agentscope;

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ThreadKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Policy frozen on the first native dispatch, and optional evidence lookup for an uncertain call. */
public final class NativeToolRecovery {
    private NativeToolRecovery() {}

    public enum Replay {
        SAFE,
        NEVER
    }

    /** The identity and post-interception arguments actually admitted by the original Brain. */
    public record Dispatch(
            ThreadKey thread, String turnId, String actionId, ToolUseBlock call, Replay replay) {}

    @SuppressWarnings("unchecked")
    static Dispatch dispatch(ThreadKey thread, JournalEvent event) {
        var data = event.data();
        var call =
                ToolUseBlock.builder()
                        .id((String) data.get("toolCallId"))
                        .name((String) data.get("name"))
                        .input((Map<String, Object>) data.get("arguments"))
                        .build();
        return new Dispatch(
                thread,
                event.turnId(),
                (String) data.get("actionId"),
                call,
                "safe".equals(data.get("replay")) ? Replay.SAFE : Replay.NEVER);
    }

    static boolean terminal(ToolResultBlock result) {
        return result != null
                && result.getState() != null
                && !result.isSuspended()
                && result.getState() != ToolResultState.RUNNING
                && !(result.getExecutionDetails() != null
                        && "UNKNOWN".equals(result.getExecutionDetails().outcome().name()));
    }

    public record Evidence(String evidenceRef, ToolResultBlock result) {
        public Evidence {
            DurableJson.text(evidenceRef, "evidenceRef");
            if (!terminal(result))
                throw new IllegalArgumentException("Verified terminal outcome required");
        }
    }

    /** Read-only lookup. Verify the external record against this thread, turn and original call.
     * Return empty when absent, inconsistent or inconclusive. The model's lookup hint is untrusted. */
    @FunctionalInterface
    public interface EvidenceLookup {
        Optional<Evidence> verify(Dispatch dispatch, String hint);
    }

    /** A separate model invocation with one read-only verification tool and no side-effect tools. */
    public record ModelCheck(Model model, EvidenceLookup lookup) {
        public ModelCheck {
            Objects.requireNonNull(model);
            Objects.requireNonNull(lookup);
        }

        Optional<Evidence> check(Dispatch dispatch) {
            var schema =
                    ToolSchema.builder()
                            .name("verify_outcome")
                            .description("Look up independent evidence for the original operation")
                            .parameters(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("hint", Map.of("type", "string")),
                                            "required",
                                            List.of("hint")))
                            .build();
            var prompt =
                    List.<Msg>of(
                            SystemMessage.builder()
                                    .textContent(
                                            "An interrupted operation may already have run. To"
                                                + " check it, call verify_outcome with a lookup"
                                                + " hint from the original request. If no lookup is"
                                                + " possible, abstain.")
                                    .build(),
                            UserMessage.builder()
                                    .textContent(DurableJson.value(dispatch).toString())
                                    .build());
            try {
                var capture = new ReasoningContext("native-recovery");
                model.stream(prompt, List.of(schema), null)
                        .doOnNext(capture::processChunk)
                        .blockLast();
                var message = capture.buildFinalMessage();
                if (message == null) return Optional.empty();
                var calls = message.getContentBlocks(ToolUseBlock.class);
                if (calls.size() != 1
                        || !"verify_outcome".equals(calls.get(0).getName())
                        || !(calls.get(0).getInput().get("hint") instanceof String hint)
                        || hint.isBlank()) return Optional.empty();
                return lookup.verify(dispatch, hint)
                        .filter(
                                evidence ->
                                        dispatch.call().getId().equals(evidence.result().getId())
                                                && dispatch.call()
                                                        .getName()
                                                        .equals(evidence.result().getName()));
            } catch (RuntimeException unavailable) {
                System.getLogger(ModelCheck.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Native evidence check unavailable for action {0} ({1})",
                                dispatch.actionId(),
                                unavailable.getClass().getSimpleName());
                return Optional.empty();
            }
        }
    }
}

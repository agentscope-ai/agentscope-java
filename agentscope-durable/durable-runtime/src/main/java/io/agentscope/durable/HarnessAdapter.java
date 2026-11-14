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
package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.TurnStatus;
import java.util.List;

/** A native Harness boundary, not another inference loop. Instances must support concurrent sessions. */
public interface HarnessAdapter extends AutoCloseable {
    enum RecoveryBoundary {
        NONE,
        CHECKPOINT,
        TOOL_BOUNDARY
    }

    enum EventDelivery {
        COMPLETED,
        DELTA
    }

    record Capabilities(
            String stateVersion,
            RecoveryBoundary recoveryBoundary,
            EventDelivery eventDelivery,
            boolean externalTools,
            List<String> inputContentTypes) {
        public Capabilities {
            DurableJson.text(stateVersion, "stateVersion");
            inputContentTypes = List.copyOf(inputContentTypes);
        }
    }

    Capabilities capabilities();

    /** Factory and native invocation turn off event loops; all authoritative writes use the context. */
    void execute(HarnessContext context) throws Exception;

    /**
     * Settle FAILED or CANCELLED only after external operations settle. Clear native pending work
     * without inference before committing the terminal task, so the next Turn can use this Thread.
     */
    default void settle(HarnessContext context, TurnStatus status, String error) throws Exception {
        if (status != TurnStatus.CANCELLED && status != TurnStatus.FAILED)
            throw new IllegalArgumentException("Settlement requires FAILED or CANCELLED");
        var frame = context.frame();
        context.commit(
                new Commit(
                        "settled:" + frame.turn().revision(),
                        frame.turn().revision(),
                        frame.checkpoint(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        status,
                        null,
                        null,
                        error));
    }

    @Override
    default void close() {}
}

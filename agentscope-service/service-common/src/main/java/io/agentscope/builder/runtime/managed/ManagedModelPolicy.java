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
package io.agentscope.builder.runtime.managed;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.session.SessionModelPolicy;
import io.agentscope.core.session.SessionRecorder;
import java.util.Map;

/** Brain reserves model usage; Hands settles it from native facts in its event transaction. */
public final class ManagedModelPolicy implements SessionModelPolicy {
    private final ManagedBudgetStore budgets;
    private final ManagedTurnExecution execution;

    public ManagedModelPolicy(ManagedBudgetStore budgets, ManagedTurnExecution execution) {
        this.budgets = budgets;
        this.execution = execution;
    }

    @Override
    public void beforeCall(String call, String model, RuntimeContext context) {
        try {
            budgets.reserve(execution, call, model);
        } catch (ManagedStateException denied) {
            if (!"budget_exceeded".equals(denied.code())) throw denied;
            var recorder = SessionRecorder.from(context);
            if (recorder == null)
                throw new IllegalStateException(
                        "Managed model budget requires its native recorder");
            recorder.append(
                    "budget/exceeded",
                    Map.of(
                            "limit",
                            denied.getMessage(),
                            "model_call_id",
                            call,
                            "turn_id",
                            execution.turnId(),
                            "run_id",
                            recorder.runId()),
                    false);
            recorder.flushNow();
            // A permanent admission decision ends the Turn, rather than retrying the MQ delivery.
            throw new LimitExceeded(denied.getMessage());
        }
    }

    @Override
    public void afterCall(
            String call, String model, String status, ChatUsage usage, RuntimeContext context) {
        // Native model/end is committed and priced by Hands, including replay after a crash.
    }

    public static final class LimitExceeded extends RuntimeException {
        public LimitExceeded(String limit) {
            super("Session budget prevented model execution: " + limit);
        }
    }
}

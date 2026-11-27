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

import io.agentscope.durable.DurableTypes.ExecutionRequest;
import java.util.List;

/** A transport that durably admits one action's runnable tools together. */
public interface BatchDispatchExecutor extends Executor {
    /**
     * Stage requests from one action. Existing identities must be reused across retries.
     * Observations arrive independently through the backend.
     */
    void dispatchBatch(List<ExecutionRequest> requests);
}

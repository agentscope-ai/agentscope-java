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
package io.agentscope.core.model;

/**
 * Capability interface marks exceptions signaling that a model stream ended
 * abnormally <b>after</b> the completeness of the response could not be
 * confirmed (e.g. an SSE stream that terminated without the {@code [DONE]}
 * sentinel or a {@code finish_reason} chunk).
 *
 * <p>Such an exception carries special semantics for retry layers:
 *
 * <ul>
 *   <li>Blind flux-level re-subscription is unsafe when content was already
 *       emitted downstream, because the retried stream restarts from scratch
 *       and would duplicate the partial content. Generic retry wrappers (see
 *       {@link ModelUtils#applyTimeoutAndRetry}) therefore skip these errors
 *       by default.</li>
 *   <li>The agent reasoning layer may still retry safely if it resets its
 *       accumulators and notifies downstream consumers before re-subscribing
 *       (see ReActAgent's model-call retry).</li>
 * </ul>
 */
public interface PartialStreamContent {

    /**
     * Whether any response chunk was already emitted to downstream consumers
     * before the stream ended abnormally.
     *
     * @return true if partial content reached subscribers
     */
    boolean isContentEmitted();
}

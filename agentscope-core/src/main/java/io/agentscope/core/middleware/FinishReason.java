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
package io.agentscope.core.middleware;

/**
 * Which of the final-answer exits produced this output; how to treat it is up to the
 * {@link MiddlewareBase#onFinalAnswer} implementation. Interrupt-like exits —
 * HITL suspension ({@code suspended}) and {@code ACTING_STOP_REQUESTED} — are not final
 * answers and never pass the gate.
 *
 * @author shanhongyu
 */
public enum FinishReason {
    /** Normal finish: the model produced a final answer without pending tool calls. */
    FINAL_ANSWER,

    /**
     * Iterations exhausted: the forced summary emitted by {@code summarizing()} after
     * maxIters was hit. Retrying from here rarely converges — accepting a fallback
     * replacement is the recommended shape.
     */
    MAX_ITERATIONS,

    /**
     * Tool-direct output: every tool call in the round was {@code returnDirect} and
     * succeeded, so the tool results are the final answer (naming aligned with {@code
     * GenerateReason.TOOL_RETURN_DIRECT}). The content usually comes from the
     * developer's own tool code, so audit-style implementations normally accept it.
     */
    RETURN_DIRECT
}

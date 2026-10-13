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
package io.agentscope.core.observation;

/** An observation could not be acknowledged. Never convert this into a retryable tool result. */
public final class ActionObservationException extends RuntimeException {
    public ActionObservationException(Throwable cause) {
        super(
                "Unable to persist action observation; do not replay the action automatically",
                cause);
    }

    public static boolean causedBy(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ActionObservationException) return true;
            if (current.getCause() == current) break;
        }
        return false;
    }
}

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

import io.agentscope.durable.DurableTypes.ThreadKey;
import java.util.List;
import java.util.Objects;

/** A durable request to advance a particular generation of the Thread's current Turn. */
public record TurnDispatch(
        String version,
        ThreadKey thread,
        String turnId,
        long generation,
        long revision,
        Reason reason) {
    public static final String VERSION = "2";

    public enum Reason {
        SUBMIT,
        NEXT_HEAD,
        STEER,
        RESUME,
        RESPONSE,
        SIGNAL,
        CONTROL,
        TOOL_RESULT,
        RECOVERY
    }

    public TurnDispatch {
        if (!VERSION.equals(version) || generation < 1 || revision < 0)
            throw new IllegalArgumentException(
                    "Invalid Turn dispatch version, generation or revision");
        Objects.requireNonNull(thread);
        Objects.requireNonNull(reason);
        DurableJson.text(turnId, "turnId");
    }

    public String id() {
        return DurableJson.hash(List.of(thread.storageKey(), turnId, generation));
    }
}

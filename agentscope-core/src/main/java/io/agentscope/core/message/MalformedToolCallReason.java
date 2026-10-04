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
package io.agentscope.core.message;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Why a tool call assembled from the model output cannot be executed as-is.
 *
 * <p>Reasons are stored on {@link ToolUseBlock#getMetadata()} under {@link
 * ToolUseBlock#METADATA_MALFORMED_REASONS} as a list of enum names so they survive state
 * serialization; use {@link #of(ToolUseBlock)} to read them back.
 */
public enum MalformedToolCallReason {
    /** The tool call carries no {@code function.name}. */
    MISSING_NAME,

    /** The raw arguments are not a valid JSON object. */
    INVALID_ARGUMENTS;

    /**
     * Reads the malformed reasons recorded on a tool call.
     *
     * @param block the tool call, may be {@code null}
     * @return the recorded reasons, empty if the call is not marked as malformed
     */
    public static Set<MalformedToolCallReason> of(ToolUseBlock block) {
        Set<MalformedToolCallReason> reasons = EnumSet.noneOf(MalformedToolCallReason.class);
        if (block == null
                || !(block.getMetadata().get(ToolUseBlock.METADATA_MALFORMED_REASONS)
                        instanceof Collection<?> names)) {
            return reasons;
        }
        for (Object value : names) {
            for (MalformedToolCallReason reason : values()) {
                if (reason.name().equals(String.valueOf(value))) {
                    reasons.add(reason);
                }
            }
        }
        return reasons;
    }
}

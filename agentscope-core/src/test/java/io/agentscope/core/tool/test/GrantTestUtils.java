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
package io.agentscope.core.tool.test;

import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;

/**
 * Grant-layer test helper: seeds an explicit {@link CapabilitySurface#ALLOW_ALL} for
 * direct-execution tests that bypass the agent {@code call()} entry (a missing surface is
 * treated as unrestricted there; the explicit seed keeps the grant intent visible).
 *
 * @author shanhongyu
 */
public final class GrantTestUtils {

    /** Utility class: not instantiable. */
    private GrantTestUtils() {}

    /**
     * A fresh RuntimeContext carrying {@link CapabilitySurface#ALLOW_ALL}.
     *
     * @return a new request-scoped context with the allow-all surface
     */
    public static RuntimeContext grantRc() {
        RuntimeContext rc = RuntimeContext.empty();
        rc.put(CapabilitySurface.class, CapabilitySurface.ALLOW_ALL);
        return rc;
    }

    /**
     * Seeds {@link CapabilitySurface#ALLOW_ALL} onto the given context.
     *
     * @param rc the context to seed (no-op when {@code null})
     * @return the same context, for in-line use
     */
    public static RuntimeContext withGrant(RuntimeContext rc) {
        if (rc != null) {
            rc.put(CapabilitySurface.class, CapabilitySurface.ALLOW_ALL);
        }
        return rc;
    }
}

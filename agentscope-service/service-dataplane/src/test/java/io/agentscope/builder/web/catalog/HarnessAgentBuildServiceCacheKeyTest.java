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
package io.agentscope.builder.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import org.junit.jupiter.api.Test;

class HarnessAgentBuildServiceCacheKeyTest {

    private static final SessionAgentBuildSpec SPEC =
            new SessionAgentBuildSpec(1, "env-1", null, null, null, null);

    private static ManagedSessionDto session(String sessionId, String externalKey) {
        return new ManagedSessionDto(
                sessionId,
                "owner-1",
                "bbb",
                "owner-1",
                1,
                "latest",
                null,
                "env-1",
                externalKey,
                null,
                null,
                null,
                "idle",
                null,
                0L,
                0L,
                null);
    }

    @Test
    void plainSessionsOfSameAgentShareOneInstance() {
        assertThat(HarnessAgentBuildService.cacheKey(session("s1", null), SPEC))
                .isEqualTo(HarnessAgentBuildService.cacheKey(session("s2", null), SPEC));
    }

    @Test
    void externalKeysDoNotCreateRuntimeSpecificAgentVariants() {
        String worker =
                HarnessAgentBuildService.cacheKey(
                        session("s1", "agent-task|f92cf745-82f5-45f3-a273-8ad96a87aa5a"), SPEC);
        String lead =
                HarnessAgentBuildService.cacheKey(
                        session("s2", "agent-task|85254b55-1a6d-4e5c-9499-d913561930c2"), SPEC);
        String plain = HarnessAgentBuildService.cacheKey(session("s3", null), SPEC);

        assertThat(worker).isEqualTo(lead).isEqualTo(plain);
    }
}

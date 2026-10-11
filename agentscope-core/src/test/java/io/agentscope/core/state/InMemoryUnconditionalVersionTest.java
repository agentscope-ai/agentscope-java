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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryUnconditionalVersionTest {
    record T(String v) implements State {}

    @Test
    @DisplayName("unconditional save returns its own version even when a writer interleaves")
    void unconditionalSaveReturnsOwnVersion() {
        InMemoryAgentStateStore store =
                new InMemoryAgentStateStore() {
                    @Override
                    public void save(String u, String s, String k, State v) {
                        super.save(u, s, k, v);
                        // Inject a concurrent writer exactly when the value under test is
                        // written through save(): on main the unconditional saveIfVersion
                        // delegates to save() and reads the version outside the lock, so this
                        // injection lands in that window. The fixed path never routes through
                        // save(), which is what keeps this hook silent after the fix.
                        if (v instanceof T t && "mine".equals(t.v())) {
                            super.save(u, s, k, new T("other"));
                        }
                    }
                };
        store.save("u", "s", "k", new T("initial")); // version 1
        long returned =
                store.saveIfVersion("u", "s", "k", new T("mine"), AgentStateStore.UNVERSIONED);
        // This caller's write is version 2; on main the injected writer makes it return 3.
        assertEquals(2L, returned);
        // A concurrent writer commits after the unconditional save returned: the store moves
        // on to version 3, but the version returned above stays the caller's own write (2).
        store.save("u", "s", "k", new T("other"));
        var latest = store.getVersioned("u", "s", "k", T.class);
        assertEquals(3L, latest.version());
        assertEquals("other", latest.value().v());
    }
}

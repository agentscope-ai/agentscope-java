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
package io.agentscope.harness.agent.filesystem.remote.store;

import java.util.List;
import java.util.Map;

/**
 * Optional capability for stores providing atomic item creation and versioned replacement.
 *
 * <p>All clients sharing a backend must observe the same positive item versions. Expected version
 * zero creates only an absent item; a positive version replaces only that exact version. A failed
 * comparison writes nothing. Successful writes advance the version. Storage failures propagate;
 * implementations must not emulate CAS with an unprotected read followed by an unconditional put.
 * This capability does not provide transactions across items or conditional deletion.
 */
public interface VersionedBaseStore extends BaseStore {

    /**
     * Atomically creates or replaces one item according to the version contract of this capability.
     *
     * @param namespace hierarchical namespace path
     * @param key item key
     * @param value replacement value
     * @param expectedVersion zero for creation, otherwise the observed positive version
     * @return true on commit, false on version mismatch without writing
     */
    @Override
    boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion);
}

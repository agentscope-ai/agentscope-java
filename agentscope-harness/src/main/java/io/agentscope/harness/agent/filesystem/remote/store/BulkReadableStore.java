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

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Optional {@link BaseStore} capability for reading several keys in one backend operation.
 *
 * <p>Missing keys are absent from the result and duplicate keys are read once. Implementations
 * should preserve the same value and version semantics as {@link #get}.
 */
public interface BulkReadableStore extends BaseStore {
    /**
     * Get several items by key in one backend operation.
     *
     * @param namespace hierarchical namespace path
     * @param keys item keys within the namespace
     * @return items that exist, keyed by their requested key
     */
    Map<String, StoreItem> getAll(List<String> namespace, Collection<String> keys);
}

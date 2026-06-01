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
package io.agentscope.harness.agent.store;

import java.util.Map;

/**
 * A single item retrieved from a {@link BaseStore}.
 *
 * @param key the item's key within its namespace
 * @param value the item's data as a string-keyed map
 * @param version monotonically increasing version counter; starts at 1 and increments on every
 *     successful {@link BaseStore#put} or {@link BaseStore#putIfVersion}. A value of
 *     {@code 0} means the version is unknown (e.g. items returned by legacy implementations
 *     that predate versioning).
 */
public record StoreItem(String key, Map<String, Object> value, long version) {

    /** Back-compat constructor for code that does not yet supply a version. */
    public StoreItem(String key, Map<String, Object> value) {
        this(key, value, 0L);
    }
}

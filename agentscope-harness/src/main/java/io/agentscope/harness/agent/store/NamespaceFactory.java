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

import java.util.List;

/**
 * Factory that produces a namespace tuple for {@link BaseStore} operations at call time.
 *
 * <p>Unlike a static namespace, a {@code NamespaceFactory} is invoked on <em>every</em> store
 * operation (read, write, ls, etc.), allowing the namespace to vary based on runtime context such
 * as the current session id, user id, or agent id.
 *
 * <p>Example:
 *
 * <pre>{@code
 * NamespaceFactory factory = () -> List.of("sessions", getCurrentSessionId(), "filesystem");
 * RemoteFilesystem fs = new RemoteFilesystem(store, factory);
 * }</pre>
 */
@FunctionalInterface
public interface NamespaceFactory {

    /**
     * Returns the namespace tuple for the current operation context.
     *
     * @return non-null, non-empty list of namespace segments
     */
    List<String> getNamespace();
}

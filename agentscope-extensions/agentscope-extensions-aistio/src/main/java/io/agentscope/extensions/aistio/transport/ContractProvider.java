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
package io.agentscope.extensions.aistio.transport;

import java.util.List;
import java.util.Map;

/**
 * Backing data for the {@code /agentscope/*} HTTP contract, implemented by the bridge.
 *
 * <p>Every method returns JSON-serializable maps and lists. Throwing {@link NotFoundException} maps
 * to 404 and {@link UnsupportedOperationException} to 501; anything else becomes a 500.
 */
public interface ContractProvider {

    Map<String, Object> info();

    List<Map<String, Object>> sessions();

    Map<String, Object> sessionState(String sessionId);

    Map<String, Object> context(String sessionId);

    Map<String, Object> messages(String sessionId, int offset, int limit);

    List<Map<String, Object>> subagents();

    List<Map<String, Object>> workspaces();

    void compress(String sessionId);

    void terminate(String sessionId);

    /** Signals that the requested session or resource does not exist on this instance. */
    class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}

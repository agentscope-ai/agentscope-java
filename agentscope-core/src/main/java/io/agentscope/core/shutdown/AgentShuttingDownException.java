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
package io.agentscope.core.shutdown;

/**
 * Exception thrown when request is rejected or interrupted during shutdown.
 */
public class AgentShuttingDownException extends RuntimeException {

    public static final String DEFAULT_MESSAGE =
            "Operation interrupted due to system shutting down, please retry";

    public AgentShuttingDownException() {
        super(DEFAULT_MESSAGE);
    }

    public AgentShuttingDownException(String message) {
        super(message);
    }
}

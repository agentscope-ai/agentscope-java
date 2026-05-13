/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

/**
 * WebSocket connection close information.
 *
 * <p>Contains the close code and reason when a WebSocket connection is closed.
 *
 * @param code WebSocket close code
 * @param reason Close reason message
 */
public record CloseInfo(int code, String reason) {

    /** Normal closure (1000). */
    public static final int NORMAL_CLOSURE = 1000;

    /** Going away (1001). */
    public static final int GOING_AWAY = 1001;

    /** Protocol error (1002). */
    public static final int PROTOCOL_ERROR = 1002;

    /** Abnormal closure (1006). */
    public static final int ABNORMAL_CLOSURE = 1006;

    /**
     * Check if this is a normal closure.
     *
     * @return true if the close code is 1000 (normal closure)
     */
    public boolean isNormal() {
        return code == NORMAL_CLOSURE;
    }

    /**
     * Create a CloseInfo for normal closure.
     *
     * @param reason Close reason
     * @return CloseInfo instance
     */
    public static CloseInfo normal(String reason) {
        return new CloseInfo(NORMAL_CLOSURE, reason);
    }

    /**
     * Create a CloseInfo for abnormal closure.
     *
     * @param reason Close reason
     * @return CloseInfo instance
     */
    public static CloseInfo abnormal(String reason) {
        return new CloseInfo(ABNORMAL_CLOSURE, reason);
    }
}

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.session;

/**
 * Information about a session.
 * <p>
 * Contains metadata about stored sessions including size, modification time,
 * and the number of state components saved in the session.
 */
public class SessionInfo {
    private final String sessionId;
    private final long size;
    private final long lastModified;
    private final int componentCount;

    /**
     * Create a new SessionInfo instance.
     *
     * @param sessionId      Unique identifier for the session
     * @param size           Size of the session storage in bytes
     * @param lastModified   Last modification timestamp in milliseconds since epoch
     * @param componentCount Number of state components stored in the session
     */
    public SessionInfo(String sessionId, long size, long lastModified, int componentCount) {
        this.sessionId = sessionId;
        this.size = size;
        this.lastModified = lastModified;
        this.componentCount = componentCount;
    }

    /**
     * Get the unique identifier for this session.
     *
     * @return The session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Get the size of the session storage.
     *
     * @return Size in bytes
     */
    public long getSize() {
        return size;
    }

    /**
     * Get the last modification timestamp.
     *
     * @return Timestamp in milliseconds since epoch
     */
    public long getLastModified() {
        return lastModified;
    }

    /**
     * Get the number of state components stored in this session.
     *
     * @return Number of components
     */
    public int getComponentCount() {
        return componentCount;
    }

    /**
     * Returns a string representation of the session information.
     *
     * @return Formatted string containing session ID, size, last modified time, and component count
     */
    @Override
    public String toString() {
        return String.format(
                "SessionInfo{id='%s', size=%d, lastModified=%d, components=%d}",
                sessionId, size, lastModified, componentCount);
    }
}

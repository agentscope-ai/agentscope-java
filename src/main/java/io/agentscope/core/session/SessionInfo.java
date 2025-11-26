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

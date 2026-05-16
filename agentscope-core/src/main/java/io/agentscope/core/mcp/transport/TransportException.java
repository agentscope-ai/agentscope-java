package io.agentscope.core.mcp.transport;

/**
 * Exception thrown when transport communication fails.
 */
public class TransportException extends Exception {

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransportException(Throwable cause) {
        super(cause);
    }
}

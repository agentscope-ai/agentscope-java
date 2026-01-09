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
package io.agentscope.core.live;

/**
 * Exception thrown when LiveAgent reconnection fails.
 *
 * <p>This exception is thrown when the LiveAgent exceeds the maximum number of reconnection
 * attempts or encounters an unrecoverable error.
 */
public class ReconnectFailedException extends RuntimeException {

    private final int attemptCount;
    private final int maxAttempts;

    /**
     * Creates a reconnect failed exception with a message.
     *
     * @param message the error message
     */
    public ReconnectFailedException(String message) {
        super(message);
        this.attemptCount = 0;
        this.maxAttempts = 0;
    }

    /**
     * Creates a reconnect failed exception with a message and cause.
     *
     * @param message the error message
     * @param cause the cause of the failure
     */
    public ReconnectFailedException(String message, Throwable cause) {
        super(message, cause);
        this.attemptCount = 0;
        this.maxAttempts = 0;
    }

    /**
     * Creates a reconnect failed exception with attempt information.
     *
     * @param message the error message
     * @param attemptCount the number of attempts made
     * @param maxAttempts the maximum number of attempts allowed
     */
    public ReconnectFailedException(String message, int attemptCount, int maxAttempts) {
        super(message);
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Creates a reconnect failed exception with attempt information and cause.
     *
     * @param message the error message
     * @param cause the cause of the failure
     * @param attemptCount the number of attempts made
     * @param maxAttempts the maximum number of attempts allowed
     */
    public ReconnectFailedException(
            String message, Throwable cause, int attemptCount, int maxAttempts) {
        super(message, cause);
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Gets the number of reconnection attempts made.
     *
     * @return the attempt count
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Gets the maximum number of reconnection attempts allowed.
     *
     * @return the maximum attempts
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Checks if the maximum number of attempts was reached.
     *
     * @return true if max attempts was reached
     */
    public boolean isMaxAttemptsReached() {
        return attemptCount >= maxAttempts && maxAttempts > 0;
    }
}

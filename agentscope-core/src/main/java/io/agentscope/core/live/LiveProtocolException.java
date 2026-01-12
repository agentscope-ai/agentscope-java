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
package io.agentscope.core.live;

import java.nio.charset.StandardCharsets;

/**
 * Live protocol parsing exception.
 *
 * <p>Thrown when message parsing fails in the Live session layer. This exception is propagated
 * through Reactor's error channel.
 *
 * <p>Contains raw data preview for debugging purposes.
 */
public class LiveProtocolException extends RuntimeException {

    private static final int MAX_PREVIEW_LENGTH = 200;

    private final byte[] rawData;
    private final String dataPreview;

    /**
     * Create a LiveProtocolException.
     *
     * @param message Error message
     * @param cause Root cause
     * @param rawData Raw data that failed to parse
     */
    public LiveProtocolException(String message, Throwable cause, byte[] rawData) {
        super(message, cause);
        this.rawData = rawData;
        this.dataPreview = createPreview(rawData);
    }

    /**
     * Create a LiveProtocolException without cause.
     *
     * @param message Error message
     * @param rawData Raw data that failed to parse
     */
    public LiveProtocolException(String message, byte[] rawData) {
        this(message, null, rawData);
    }

    /**
     * Get the raw data that failed to parse.
     *
     * @return Raw data bytes
     */
    public byte[] getRawData() {
        return rawData;
    }

    /**
     * Get a preview of the raw data (truncated for logging).
     *
     * @return Data preview string
     */
    public String getDataPreview() {
        return dataPreview;
    }

    @Override
    public String getMessage() {
        return String.format("%s [preview=%s]", super.getMessage(), dataPreview);
    }

    private static String createPreview(byte[] data) {
        if (data == null || data.length == 0) {
            return "<empty>";
        }
        String str = new String(data, StandardCharsets.UTF_8);
        if (str.length() > MAX_PREVIEW_LENGTH) {
            return str.substring(0, MAX_PREVIEW_LENGTH) + "...";
        }
        return str;
    }
}

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
package io.agentscope.core.live.formatter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Abstract base class for binary protocol formatters.
 *
 * <p>Applicable to Doubao (binary protocol). Provides binary frame encoding/decoding utility
 * methods. Works directly with byte[] - no String conversion needed.
 */
public abstract class AbstractBinaryLiveFormatter implements LiveFormatter<byte[]> {

    /**
     * Decoded frame containing event ID and payload.
     *
     * @param eventId the event identifier
     * @param payload the frame payload bytes
     */
    protected record DecodedFrame(int eventId, byte[] payload) {}

    /**
     * Encode binary frame.
     *
     * <p>Doubao frame format:
     *
     * <pre>
     * | 4 bytes header | 4 bytes event_id | payload |
     * </pre>
     *
     * @param eventId the event identifier
     * @param payload the payload bytes
     * @return encoded frame bytes
     */
    protected byte[] encodeFrame(int eventId, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + (payload != null ? payload.length : 0));
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Header (4 bytes) - simplified version
        buffer.putInt(0x11000000 | (payload != null ? payload.length : 0));

        // Event ID (4 bytes)
        buffer.putInt(eventId);

        // Payload
        if (payload != null) {
            buffer.put(payload);
        }

        return buffer.array();
    }

    /**
     * Decode binary frame.
     *
     * @param data the raw frame bytes
     * @return decoded frame, or null if data is invalid
     */
    protected DecodedFrame decodeFrame(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Skip header
        buffer.getInt();

        // Event ID
        int eventId = buffer.getInt();

        // Payload
        byte[] payload = null;
        if (buffer.hasRemaining()) {
            payload = new byte[buffer.remaining()];
            buffer.get(payload);
        }

        return new DecodedFrame(eventId, payload);
    }

    /**
     * Extract string from payload (UTF-8).
     *
     * @param payload the payload bytes
     * @return decoded string, or null if payload is null
     */
    protected String payloadToString(byte[] payload) {
        if (payload == null) {
            return null;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * Convert string to payload (UTF-8).
     *
     * @param str the string to convert
     * @return UTF-8 encoded bytes, or null if str is null
     */
    protected byte[] stringToPayload(String str) {
        if (str == null) {
            return null;
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }
}

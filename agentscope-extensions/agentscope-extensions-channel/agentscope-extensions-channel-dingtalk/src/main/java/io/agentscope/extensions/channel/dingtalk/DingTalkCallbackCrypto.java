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
package io.agentscope.extensions.channel.dingtalk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Implements DingTalk's HTTP-callback request verification and optional body decryption for robot
 * message reception. See the DingTalk docs on <a
 * href="https://open.dingtalk.com/document/robots/verify-valid-requests-for-intra-enterprise-robot-group-chat">verifying
 * robot requests</a> and <a
 * href="https://open.dingtalk.com/document/development/callback-event-message-body-encryption-and-decryption">callback
 * body encryption</a>.
 *
 * <p>Inputs:
 *
 * <ul>
 *   <li>{@code appSecret} — the enterprise-internal app secret. Used as the HMAC key for request
 *       signature verification: each callback carries {@code timestamp} and {@code sign} headers,
 *       where {@code sign = Base64(HmacSHA256(appSecret, timestamp + "\n" + appSecret))} and the
 *       timestamp is a millisecond epoch DingTalk requires to be no older than one hour;
 *       timestamps ahead of local time are tolerated only within a clock-skew allowance.
 *   <li>{@code aesKey} — optional 43-character base64 (no padding). When the robot is configured
 *       with encryption, the callback body is a {@code {"encrypt": ...}} envelope instead of plain
 *       JSON, and this key (decoded with an appended {@code "="} to 32 bytes, its first 16 bytes
 *       also used as the CBC IV) decrypts it. May be {@code null} for robots configured without
 *       encryption.
 * </ul>
 *
 * <p>Instances are immutable and thread-safe; {@link #verify} and {@link #decrypt} may be invoked
 * concurrently from multiple request threads.
 */
public final class DingTalkCallbackCrypto {

    /** Maximum age of the request timestamp, per DingTalk docs. */
    private static final long MAX_TIMESTAMP_AGE_MS = 3_600_000L;

    /**
     * Maximum tolerance for timestamps ahead of local time. A future-dated timestamp can only
     * arise from clock skew, so this bounds replay of a captured header pair in the future
     * direction; the past direction keeps the full documented one-hour freshness window.
     */
    private static final long MAX_FUTURE_TIMESTAMP_MS = 300_000L;

    private final String appSecret;
    private final byte[] aesKey; // 32 bytes; null when encryption is disabled
    private final IvParameterSpec iv; // null when encryption is disabled

    public DingTalkCallbackCrypto(String appSecret, String aesKey) {
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("appSecret is required");
        }
        if (aesKey != null && aesKey.length() != 43) {
            throw new IllegalArgumentException(
                    "aesKey must be 43 characters (got " + aesKey.length() + ")");
        }
        this.appSecret = appSecret;
        if (aesKey == null) {
            this.aesKey = null;
            this.iv = null;
        } else {
            byte[] key = Base64.getDecoder().decode(aesKey + "=");
            if (key.length != 32) {
                throw new IllegalArgumentException(
                        "Decoded AES key must be 32 bytes (got " + key.length + ")");
            }
            this.aesKey = key;
            this.iv = new IvParameterSpec(Arrays.copyOf(key, 16));
        }
    }

    /**
     * Verifies the {@code timestamp}/{@code sign} request headers of a callback: the timestamp must
     * be a millisecond epoch no older than {@link #MAX_TIMESTAMP_AGE_MS} and no further ahead of
     * local time than {@link #MAX_FUTURE_TIMESTAMP_MS}, and {@code sign} must equal {@code
     * Base64(HmacSHA256(appSecret, timestamp + "\n" + appSecret))}.
     */
    public boolean verify(String timestamp, String sign) {
        if (timestamp == null || sign == null) {
            return false;
        }
        long ts;
        String normalized = timestamp.trim();
        try {
            ts = Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            return false;
        }
        long age = System.currentTimeMillis() - ts;
        if (age > MAX_TIMESTAMP_AGE_MS || -age > MAX_FUTURE_TIMESTAMP_MS) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest =
                    mac.doFinal((normalized + "\n" + appSecret).getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    sign.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decrypts the {@code encrypt} body field and returns the inner JSON payload as a UTF-8 string.
     *
     * <p>DingTalk plaintext layout after AES-CBC decrypt + PKCS#7 unpad:
     *
     * <pre>
     * | 16 bytes random | 4 bytes msg_len (big-endian) | msg (msg_len bytes) | optional trailing bytes |
     * </pre>
     *
     * <p>Trailing bytes beyond {@code msg_len} (present in DingTalk's event-callback variant of this
     * envelope, which appends a receive id) are not part of the message and are ignored.
     */
    public String decrypt(String encryptBase64) {
        if (aesKey == null) {
            throw new IllegalStateException("aesKey is not configured for this channel");
        }
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(encryptBase64);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), iv);
            byte[] plain = cipher.doFinal(cipherBytes);
            byte[] unpad = pkcs7Unpad(plain);
            if (unpad.length < 20) {
                throw new IllegalStateException("Decrypted payload too short");
            }
            int msgLen =
                    ((unpad[16] & 0xff) << 24)
                            | ((unpad[17] & 0xff) << 16)
                            | ((unpad[18] & 0xff) << 8)
                            | (unpad[19] & 0xff);
            if (msgLen < 0 || 20 + msgLen > unpad.length) {
                throw new IllegalStateException("Invalid msg_len in decrypted payload: " + msgLen);
            }
            return new String(unpad, 20, msgLen, StandardCharsets.UTF_8);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("DingTalk decrypt failed: " + e.getMessage(), e);
        }
    }

    /** Returns whether this channel is configured with an {@code aesKey} (encrypted callbacks). */
    public boolean hasAesKey() {
        return aesKey != null;
    }

    private static byte[] pkcs7Unpad(byte[] in) {
        if (in.length == 0) {
            throw new IllegalStateException("Decrypted payload is empty");
        }
        // This envelope family (WeCom EncodingAESKey / DingTalk callback crypto) pads to a
        // 32-byte block, so valid pad values span 1..32 rather than the 16-byte AES block size.
        int pad = in[in.length - 1] & 0xff;
        if (pad < 1 || pad > 32 || pad > in.length) {
            throw new IllegalStateException("Invalid PKCS#7 padding length: " + pad);
        }
        for (int i = in.length - pad; i < in.length; i++) {
            if ((in[i] & 0xff) != pad) {
                throw new IllegalStateException("Invalid PKCS#7 padding bytes");
            }
        }
        return Arrays.copyOf(in, in.length - pad);
    }
}

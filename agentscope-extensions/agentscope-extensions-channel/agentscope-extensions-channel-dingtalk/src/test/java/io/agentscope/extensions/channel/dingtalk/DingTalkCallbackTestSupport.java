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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Shared fixtures for callback tests: HMAC signature computation and AES envelope construction. */
final class DingTalkCallbackTestSupport {

    static final String SECRET = "test-app-secret";

    /** 43-character base64 (no padding) encoding of the 32-byte test AES key. */
    static final String AES_KEY =
            Base64.getEncoder()
                    .encodeToString(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                    .substring(0, 43);

    private DingTalkCallbackTestSupport() {}

    /** Returns the current millisecond epoch as a request timestamp string. */
    static String timestamp() {
        return Long.toString(System.currentTimeMillis());
    }

    /**
     * Computes the expected request signature for {@link #SECRET}:
     * {@code Base64(HmacSHA256(secret, timestamp + "\n" + secret))}.
     */
    static String sign(String timestamp) throws Exception {
        return sign(SECRET, timestamp);
    }

    static String sign(String secret, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    /**
     * AES-CBC encrypts {@code json} into the DingTalk callback envelope: 16 random bytes | 4-byte
     * big-endian length | msg [| trailer], PKCS#7-padded to a 32-byte multiple, base64-encoded.
     */
    static String encrypt(String json, String trailer) throws Exception {
        byte[] key = Base64.getDecoder().decode(AES_KEY + "=");
        byte[] msg = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[16]);
        bos.write((msg.length >>> 24) & 0xff);
        bos.write((msg.length >>> 16) & 0xff);
        bos.write((msg.length >>> 8) & 0xff);
        bos.write(msg.length & 0xff);
        bos.write(msg);
        if (trailer != null) {
            bos.write(trailer.getBytes(StandardCharsets.UTF_8));
        }
        byte[] raw = bos.toByteArray();
        int pad = 32 - (raw.length % 32);
        byte[] padded = Arrays.copyOf(raw, raw.length + pad);
        Arrays.fill(padded, raw.length, padded.length, (byte) pad);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOf(key, 16)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }
}

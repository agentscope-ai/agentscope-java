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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Shared fixtures for callback tests: HMAC signature computation, AES envelope construction, and a
 * stand-in DingTalk API for tests that let the outbound path run for real.
 */
final class DingTalkCallbackTestSupport {

    static final String SECRET = "test-app-secret";

    /** 43-character base64 (no padding) encoding of the 32-byte test AES key. */
    static final String AES_KEY =
            Base64.getEncoder()
                    .encodeToString(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                    .substring(0, 43);

    private DingTalkCallbackTestSupport() {}

    /** Generates a 43-character AES key: base64 of 32 random bytes, padding stripped. */
    static String newAesKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key).substring(0, 43);
    }

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
        return encrypt(AES_KEY, json, trailer);
    }

    /** {@link #encrypt(String, String)} for a caller-supplied 43-character AES key. */
    static String encrypt(String aesKey, String json, String trailer) throws Exception {
        byte[] key = Base64.getDecoder().decode(aesKey + "=");
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

    /** Reads the {@code appKey} a recorded token request authenticated as. */
    static String appKeyOf(RecordedRequest request) {
        try {
            return new ObjectMapper()
                    .readTree(request.getBody().readUtf8())
                    .path("appKey")
                    .asText(null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Minimal DingTalk API: the token endpoint echoes a deterministic token per app key, message
     * send accepts.
     */
    static final class ApiDispatcher extends Dispatcher {

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            if (path.startsWith("/v1.0/oauth2/accessToken")) {
                return json(
                        "{\"accessToken\":\"tok-" + appKeyOf(request) + "\",\"expireIn\":7200}");
            }
            if (path.startsWith("/v1.0/robot/")) {
                return json("{}");
            }
            return new MockResponse().setResponseCode(404);
        }

        private static MockResponse json(String body) {
            return new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body);
        }
    }
}

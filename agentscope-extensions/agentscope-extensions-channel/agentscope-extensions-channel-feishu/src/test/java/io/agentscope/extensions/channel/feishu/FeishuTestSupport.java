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
package io.agentscope.extensions.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Reproduces the platform side of the Feishu callback protocol — signing and encrypting — so tests
 * can drive {@link FeishuCallbackController} with well-formed requests.
 */
final class FeishuTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private FeishuTestSupport() {}

    /** Generates a random Encrypt Key of the shape the developer console issues. */
    static String newEncryptKey() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder key = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            key.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return key.toString();
    }

    /** The {@code X-Lark-Signature} header: hex(SHA-256(timestamp + nonce + encryptKey + body)). */
    static String sign(String encryptKey, String timestamp, String nonce, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(timestamp.getBytes(StandardCharsets.UTF_8));
            md.update(nonce.getBytes(StandardCharsets.UTF_8));
            md.update(encryptKey.getBytes(StandardCharsets.UTF_8));
            md.update(body.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Encrypts {@code json} the way Feishu does: 16-byte IV prefix + AES-256-CBC(PKCS#7), base64. */
    static String encrypt(String encryptKey, String json) {
        try {
            byte[] key =
                    MessageDigest.getInstance("SHA-256")
                            .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] plain = json.getBytes(StandardCharsets.UTF_8);
            int pad = 16 - (plain.length % 16);
            byte[] padded = Arrays.copyOf(plain, plain.length + pad);
            Arrays.fill(padded, plain.length, padded.length, (byte) pad);
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] cipherText = cipher.doFinal(padded);
            byte[] out = Arrays.copyOf(iv, iv.length + cipherText.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Feishu test encryption failed", e);
        }
    }

    /** Wraps ciphertext in the Feishu envelope: {@code {"encrypt":"<base64>"}}. */
    static String envelope(String encrypt) {
        return "{\"encrypt\":\"" + encrypt + "\"}";
    }

    /** A minimal Schema 2.0 text-message event as Feishu posts it, in plaintext form. */
    static String textEvent(
            String verificationToken,
            String tenantKey,
            String chatId,
            String openId,
            String text,
            String eventId) {
        return "{\"schema\":\"2.0\",\"header\":{\"event_id\":\""
                + eventId
                + "\",\"event_type\":\"im.message.receive_v1\",\"token\":\""
                + verificationToken
                + "\",\"tenant_key\":\""
                + tenantKey
                + "\"},\"event\":{\"sender\":{\"sender_id\":{\"open_id\":\""
                + openId
                + "\"},\"sender_type\":\"user\"},\"message\":{\"message_id\":\"om_"
                + eventId
                + "\",\"chat_id\":\""
                + chatId
                + "\",\"chat_type\":\"p2p\",\"message_type\":\"text\",\"content\":\"{\\\"text\\\":\\\""
                + text
                + "\\\"}\"}}}";
    }

    /** A URL-verification challenge body. */
    static String urlVerification(String verificationToken, String challenge) {
        return "{\"type\":\"url_verification\",\"token\":\""
                + verificationToken
                + "\",\"challenge\":\""
                + challenge
                + "\"}";
    }

    /** The {@code app_id} a token request asked for. */
    static String appIdOf(RecordedRequest request) {
        try {
            return new ObjectMapper()
                    .readTree(request.getBody().readUtf8())
                    .path("app_id")
                    .asText(null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Minimal Feishu API: the token endpoint answers with a token named after the requested {@code
     * app_id}, and message sends are accepted. The token name is what lets a test tell which
     * credential generation an outbound request authenticated as.
     */
    static final class ApiDispatcher extends Dispatcher {

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            if (path.startsWith("/open-apis/auth/v3/tenant_access_token/internal")) {
                return json(
                        "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"tok-"
                                + appIdOf(request)
                                + "\",\"expire\":7200}");
            }
            if (path.startsWith("/open-apis/im/v1/messages")) {
                return json("{\"code\":0,\"msg\":\"ok\",\"data\":{\"message_id\":\"om_back\"}}");
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

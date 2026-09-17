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
package io.agentscope.extensions.channel.wecom;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
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
 * Reproduces the platform side of the WeCom callback protocol — signing and encrypting — so tests
 * can drive {@link WeComCallbackController} with well-formed requests.
 */
final class WeComTestSupport {

    private WeComTestSupport() {}

    /** Generates a 43-character EncodingAESKey: base64 of 32 random bytes, padding stripped. */
    static String newAesKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key).substring(0, 43);
    }

    /** The {@code msg_signature} WeCom sends: SHA-1 over the sorted token/timestamp/nonce/encrypt. */
    static String sign(String token, String timestamp, String nonce, String encrypt) {
        String[] parts = {token, timestamp, nonce, encrypt};
        Arrays.sort(parts);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-1")
                            .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** Encrypts {@code xml} the way WeCom does, producing the {@code Encrypt} value. */
    static String encrypt(String encodingAesKey, String receiveId, String xml) {
        try {
            byte[] key = Base64.getDecoder().decode(encodingAesKey + "=");
            byte[] plain = plaintext(xml, receiveId);
            int pad = 32 - (plain.length % 32);
            byte[] padded = Arrays.copyOf(plain, plain.length + pad);
            Arrays.fill(padded, plain.length, padded.length, (byte) pad);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOf(key, 16)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new IllegalStateException("WeCom test encryption failed", e);
        }
    }

    /** Wraps an {@code Encrypt} value in the callback envelope WeCom posts. */
    static String envelope(String encrypt) {
        return "<xml><ToUserName>corp</ToUserName><Encrypt>"
                + encrypt
                + "</Encrypt><AgentID>1000002</AgentID></xml>";
    }

    /** A minimal plaintext text-message payload. */
    static String textMessage(String fromUser, String content, String msgId) {
        return "<xml><ToUserName>corp</ToUserName><FromUserName>"
                + fromUser
                + "</FromUserName><CreateTime>1700000000</CreateTime><MsgType>text</MsgType><Content>"
                + content
                + "</Content><MsgId>"
                + msgId
                + "</MsgId><AgentID>1000002</AgentID></xml>";
    }

    /** Reads a query parameter off a request recorded by a {@link MockWebServer}. */
    static String query(RecordedRequest request, String name) {
        String path = request.getPath();
        if (path == null) {
            return null;
        }
        int start = path.indexOf('?');
        if (start < 0) {
            return null;
        }
        for (String pair : path.substring(start + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Minimal WeCom API: {@code gettoken} echoes a deterministic token, message sends accept. */
    static final class ApiDispatcher extends Dispatcher {

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            if (path.startsWith("/cgi-bin/gettoken")) {
                return json(
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\"tok-"
                                + query(request, "corpid")
                                + "\",\"expires_in\":7200}");
            }
            if (path.startsWith("/cgi-bin/message/send")) {
                return json("{\"errcode\":0,\"errmsg\":\"ok\"}");
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

    /** The plaintext layout WeCom encrypts: 16 random bytes, 4-byte length, payload, receive id. */
    private static byte[] plaintext(String xml, String receiveId) {
        byte[] message = xml.getBytes(StandardCharsets.UTF_8);
        byte[] receive = receiveId.getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(random, 0, random.length);
        out.write((message.length >>> 24) & 0xff);
        out.write((message.length >>> 16) & 0xff);
        out.write((message.length >>> 8) & 0xff);
        out.write(message.length & 0xff);
        out.write(message, 0, message.length);
        out.write(receive, 0, receive.length);
        return out.toByteArray();
    }
}

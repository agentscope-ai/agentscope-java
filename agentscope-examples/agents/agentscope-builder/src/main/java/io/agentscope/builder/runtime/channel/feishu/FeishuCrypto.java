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
package io.agentscope.builder.runtime.channel.feishu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Implements Feishu callback encryption + signature verification.
 *
 * <p>Feishu encrypts the callback body when an Encrypt Key is configured in the developer console.
 * The wire format is JSON {@code {"encrypt":"<base64-ciphertext>"}}. The AES key is derived as
 * {@code SHA-256(encryptKey)} (32 bytes). The ciphertext layout is:
 *
 * <pre>
 * | 16 bytes IV | AES-CBC + PKCS#7 padded body |
 * </pre>
 *
 * <p>The signature header {@code X-Lark-Signature} is computed as
 * {@code SHA-256(timestamp + nonce + encryptKey + rawBody)} where {@code rawBody} is the raw HTTP
 * request body (the JSON-stringified envelope, not the decrypted plaintext).
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-/encrypt-key-encryption-configuration-case">Feishu Encrypt Key spec</a>
 */
public final class FeishuCrypto {

    private final String encryptKey;
    private final byte[] aesKey; // 32 bytes (SHA-256 of encryptKey)

    public FeishuCrypto(String encryptKey) {
        if (encryptKey == null || encryptKey.isBlank()) {
            throw new IllegalArgumentException("encryptKey is required");
        }
        this.encryptKey = encryptKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            this.aesKey = md.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES key: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies the SHA-256 signature: {@code hex(SHA-256(timestamp + nonce + encryptKey + body))}.
     * Returns false when any argument is null or the digest mismatches.
     */
    public boolean verifySignature(String signature, String timestamp, String nonce, String body) {
        if (signature == null || timestamp == null || nonce == null || body == null) {
            return false;
        }
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
            return constantTimeEquals(hex.toString(), signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decrypts the {@code encrypt} string from a Feishu callback. Returns the JSON plaintext as a
     * UTF-8 string.
     *
     * <p>Layout after base64-decoding:
     *
     * <pre>
     * | 16 bytes IV | AES-256-CBC + PKCS#7 padded plaintext |
     * </pre>
     */
    public String decrypt(String encryptBase64) {
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(encryptBase64);
            if (cipherBytes.length < 16 + 16) {
                throw new IllegalStateException("Encrypted payload too short");
            }
            byte[] iv = Arrays.copyOfRange(cipherBytes, 0, 16);
            byte[] body = Arrays.copyOfRange(cipherBytes, 16, cipherBytes.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(body);
            byte[] unpadded = pkcs7Unpad(plain);
            return new String(unpadded, StandardCharsets.UTF_8);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Feishu decrypt failed: " + e.getMessage(), e);
        }
    }

    private static byte[] pkcs7Unpad(byte[] in) {
        if (in.length == 0) {
            return in;
        }
        int pad = in[in.length - 1] & 0xff;
        if (pad < 1 || pad > 32 || pad > in.length) {
            return in;
        }
        return Arrays.copyOf(in, in.length - pad);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}

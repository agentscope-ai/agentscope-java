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
package io.agentscope.claw2.runtime.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class FeishuCryptoTest {

    private static final String ENCRYPT_KEY = "agentscope-feishu-encrypt-key-1234";

    @Test
    void decryptsWhatWeEncrypt() throws Exception {
        FeishuCrypto crypto = new FeishuCrypto(ENCRYPT_KEY);
        String plaintext = "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"e1\"}}";
        String encrypted = encryptForTest(plaintext, ENCRYPT_KEY);
        String roundTrip = crypto.decrypt(encrypted);
        assertEquals(plaintext, roundTrip);
    }

    @Test
    void verifySignatureMatchesSha256OfConcatenation() {
        FeishuCrypto crypto = new FeishuCrypto(ENCRYPT_KEY);
        String timestamp = "1696132800";
        String nonce = "abcd1234";
        String body = "{\"encrypt\":\"abc\"}";
        String expected = sha256Hex(timestamp + nonce + ENCRYPT_KEY + body);
        assertTrue(crypto.verifySignature(expected, timestamp, nonce, body));
        assertFalse(
                crypto.verifySignature(
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        timestamp,
                        nonce,
                        body));
        assertFalse(crypto.verifySignature(null, timestamp, nonce, body));
        assertFalse(crypto.verifySignature(expected, null, nonce, body));
    }

    @Test
    void rejectsEmptyEncryptKey() {
        assertThrows(IllegalArgumentException.class, () -> new FeishuCrypto(""));
        assertThrows(IllegalArgumentException.class, () -> new FeishuCrypto(null));
    }

    @Test
    void decryptShortPayloadFails() {
        FeishuCrypto crypto = new FeishuCrypto(ENCRYPT_KEY);
        // base64 of < 32 bytes — must throw rather than silently return garbage.
        String tooShort = Base64.getEncoder().encodeToString(new byte[8]);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tooShort));
    }

    // ------------------------------------------------------------------
    //  Helpers — mirror Feishu wire format: |16 IV|AES-256-CBC + PKCS7|
    // ------------------------------------------------------------------
    private static String encryptForTest(String plaintext, String encryptKey) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] aesKey = sha256.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        byte[] padded = pkcs7Pad(plaintext.getBytes(StandardCharsets.UTF_8), 16);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
        byte[] cipherBytes = cipher.doFinal(padded);
        byte[] full = new byte[16 + cipherBytes.length];
        System.arraycopy(iv, 0, full, 0, 16);
        System.arraycopy(cipherBytes, 0, full, 16, cipherBytes.length);
        return Base64.getEncoder().encodeToString(full);
    }

    private static byte[] pkcs7Pad(byte[] data, int blockSize) {
        int pad = blockSize - (data.length % blockSize);
        if (pad == 0) pad = blockSize;
        byte[] out = Arrays.copyOf(data, data.length + pad);
        for (int i = data.length; i < out.length; i++) {
            out[i] = (byte) pad;
        }
        return out;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

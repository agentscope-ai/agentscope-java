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
package io.agentscope.claw2.runtime.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WeComCryptoTest {

    private static final String TOKEN = "agentscope_token";
    private static final String RECEIVE_ID = "wxabc123corp";
    // 43-char base64 (no padding) → decoded with "=" appended yields 32 bytes.
    private static final String ENCODING_AES_KEY =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"; // 43 chars

    @Test
    void decryptsWhatWeEncrypt() throws Exception {
        WeComCrypto crypto = new WeComCrypto(TOKEN, ENCODING_AES_KEY, RECEIVE_ID);
        String xml = "<xml><Content>hello, agentscope</Content></xml>";
        String encrypted = encryptForTest(xml, ENCODING_AES_KEY, RECEIVE_ID);
        String roundTrip = crypto.decrypt(encrypted);
        assertEquals(xml, roundTrip);
    }

    @Test
    void wrongReceiveIdRejected() throws Exception {
        WeComCrypto crypto = new WeComCrypto(TOKEN, ENCODING_AES_KEY, "wrongCorp");
        String encrypted = encryptForTest("<xml/>", ENCODING_AES_KEY, RECEIVE_ID);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(encrypted));
    }

    @Test
    void verifySignatureMatchesSortedConcatSha1() {
        WeComCrypto crypto = new WeComCrypto(TOKEN, ENCODING_AES_KEY, RECEIVE_ID);
        String timestamp = "1696132800";
        String nonce = "abcd1234";
        String encrypt = "encrypted-payload-xyz";
        String expected = sortedSha1(TOKEN, timestamp, nonce, encrypt);
        assertTrue(crypto.verifySignature(expected, timestamp, nonce, encrypt));
        assertFalse(
                crypto.verifySignature(
                        "0000000000000000000000000000000000000000", timestamp, nonce, encrypt));
        assertFalse(crypto.verifySignature(null, timestamp, nonce, encrypt));
    }

    @Test
    void rejectsInvalidAesKeyLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WeComCrypto(TOKEN, "too-short", RECEIVE_ID));
    }

    // ------------------------------------------------------------------
    //  Helpers — encrypt the same layout that WeCom sends.
    //  Plaintext: | 16 rand | 4 BE msg_len | msg | receive_id | + PKCS#7 pad
    // ------------------------------------------------------------------
    private static String encryptForTest(String msg, String encodingAesKey, String receiveId)
            throws Exception {
        byte[] aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
        byte[] receiveBytes = receiveId.getBytes(StandardCharsets.UTF_8);
        byte[] random16 = new byte[16];
        new SecureRandom().nextBytes(random16);

        ByteBuffer buf = ByteBuffer.allocate(16 + 4 + msgBytes.length + receiveBytes.length);
        buf.put(random16);
        buf.putInt(msgBytes.length); // big-endian
        buf.put(msgBytes);
        buf.put(receiveBytes);
        byte[] payload = buf.array();
        byte[] padded = pkcs7Pad(payload, 32);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(Arrays.copyOf(aesKey, 16)));
        byte[] cipherBytes = cipher.doFinal(padded);
        return Base64.getEncoder().encodeToString(cipherBytes);
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

    private static String sortedSha1(String... parts) {
        String[] copy = parts.clone();
        Arrays.sort(copy);
        StringBuilder sb = new StringBuilder();
        for (String p : copy) sb.append(p);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

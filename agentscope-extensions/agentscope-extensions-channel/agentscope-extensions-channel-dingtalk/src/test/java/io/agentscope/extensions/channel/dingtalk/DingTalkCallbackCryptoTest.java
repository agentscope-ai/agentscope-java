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

import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.AES_KEY;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.SECRET;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.encrypt;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Tests for {@link DingTalkCallbackCrypto}: header signature verification and AES decryption. */
class DingTalkCallbackCryptoTest {

    @Test
    void verifiesValidSignature() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertTrue(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void verifiesPaddedTimestampHeader() throws Exception {
        // The header is trimmed once and the normalized value feeds both the freshness window
        // and the HMAC input, so a whitespace-padded header still verifies.
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertTrue(crypto.verify("  " + timestamp + "  ", sign(timestamp)));
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertFalse(crypto.verify(timestamp, sign("another-secret", timestamp)));
    }

    @Test
    void rejectsSignatureComputedWithDifferentTimestamp() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis());
        assertFalse(
                crypto.verify(
                        timestamp, sign(Long.toString(System.currentTimeMillis() - 60_000L))));
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis() - 2 * 3_600_000L);
        assertFalse(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void rejectsFutureTimestamp() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis() + 2 * 3_600_000L);
        assertFalse(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void rejectsFutureTimestampBeyondClockSkew() throws Exception {
        // A future-dated timestamp is only legitimate within clock-skew tolerance, not the full
        // one-hour freshness window, so a captured header pair is not replayable far into the
        // future.
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        String timestamp = Long.toString(System.currentTimeMillis() + 10 * 60_000L);
        assertFalse(crypto.verify(timestamp, sign(timestamp)));
    }

    @Test
    void rejectsNonNumericTimestamp() {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        assertFalse(crypto.verify("not-a-number", "any-sign"));
        assertFalse(crypto.verify(null, "any-sign"));
        assertFalse(crypto.verify(Long.toString(System.currentTimeMillis()), null));
    }

    @Test
    void decryptsEnvelope() throws Exception {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        String json = "{\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}}";
        assertEquals(json, crypto.decrypt(encrypt(json, null)));
    }

    @Test
    void ignoresTrailingBytesBeyondMsgLength() throws Exception {
        // DingTalk's event-callback variant of this envelope appends a receive id after the
        // message; robot callbacks must tolerate both shapes.
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        String json = "{\"msgtype\":\"text\"}";
        assertEquals(json, crypto.decrypt(encrypt(json, "receive-id")));
    }

    @Test
    void decryptRejectsTamperedFraming() throws Exception {
        // Neither CBC nor the header signature protects the body — the signature covers only the
        // timestamp header — so tampering is caught structurally, not cryptographically:
        // corrupting the first ciphertext block garbles the 4-byte msg_len header and must be
        // rejected.
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        byte[] cipherBytes = Base64.getDecoder().decode(encrypt("{\"msgtype\":\"text\"}", null));
        cipherBytes[0] ^= 0x55;
        String tampered = Base64.getEncoder().encodeToString(cipherBytes);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void decryptRejectsTamperedPadding() throws Exception {
        // Corrupting the final ciphertext block garbles the padding region; PKCS#7 validity is
        // checked strictly, so damaged padding is rejected instead of silently passed through.
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, AES_KEY);
        byte[] cipherBytes = Base64.getDecoder().decode(encrypt("{\"msgtype\":\"text\"}", null));
        cipherBytes[cipherBytes.length - 1] ^= 0x55;
        String tampered = Base64.getEncoder().encodeToString(cipherBytes);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void decryptWithoutAesKeyFails() {
        DingTalkCallbackCrypto crypto = new DingTalkCallbackCrypto(SECRET, null);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("anything"));
    }

    @Test
    void constructorRejectsWrongAesKeyLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DingTalkCallbackCrypto(SECRET, "too-short"));
        assertThrows(
                IllegalArgumentException.class, () -> new DingTalkCallbackCrypto(null, AES_KEY));
    }
}

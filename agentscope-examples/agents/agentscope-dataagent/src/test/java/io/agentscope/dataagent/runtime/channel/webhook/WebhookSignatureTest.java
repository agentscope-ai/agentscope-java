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
package io.agentscope.dataagent.runtime.channel.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies the HMAC-SHA256 helpers used by both inbound verification (server-side) and outbound
 * callback signing (client-side). The known vector below is RFC 4231 case 1, which any conforming
 * SHA-256 HMAC implementation must reproduce — pinning it here catches accidental algorithm or
 * encoding changes.
 */
class WebhookSignatureTest {

    /** RFC 4231 §4.2 — key=0x0b*20, data="Hi There" → known SHA-256 HMAC. */
    @Test
    void hmacMatchesRfc4231Vector1() {
        String secret =
                new String(
                        new byte[] {
                            0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
                            0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b
                        },
                        StandardCharsets.ISO_8859_1);
        String hex = WebhookSignature.hmacHex(secret, "Hi There".getBytes(StandardCharsets.UTF_8));
        assertThat(hex)
                .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    }

    @Test
    void hmacIsDeterministic() {
        byte[] body =
                "{\"externalUserId\":\"alice@corp\",\"message\":\"hello\"}"
                        .getBytes(StandardCharsets.UTF_8);
        String first = WebhookSignature.hmacHex("super-secret", body);
        String second = WebhookSignature.hmacHex("super-secret", body);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentSecretsProduceDifferentDigests() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        String a = WebhookSignature.hmacHex("secret-A", body);
        String b = WebhookSignature.hmacHex("secret-B", body);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void emptyBodyIsHandled() {
        String hex = WebhookSignature.hmacHex("k", new byte[0]);
        assertThat(hex).hasSize(64).matches("[0-9a-f]+");
        // Null body must be treated identically to an empty body so callers never NPE.
        assertThat(WebhookSignature.hmacHex("k", null)).isEqualTo(hex);
    }

    @Test
    void constantTimeEqualsAcceptsMatches() {
        String a = WebhookSignature.hmacHex("k", "x".getBytes(StandardCharsets.UTF_8));
        String b = WebhookSignature.hmacHex("k", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(WebhookSignature.constantTimeEquals(a, b)).isTrue();
    }

    @Test
    void constantTimeEqualsRejectsMismatches() {
        assertThat(WebhookSignature.constantTimeEquals("abcd", "abce")).isFalse();
        assertThat(WebhookSignature.constantTimeEquals("abcd", "abcde")).isFalse();
        assertThat(WebhookSignature.constantTimeEquals(null, "abcd")).isFalse();
        assertThat(WebhookSignature.constantTimeEquals("abcd", null)).isFalse();
        assertThat(WebhookSignature.constantTimeEquals(null, null)).isFalse();
    }

    /**
     * End-to-end: signature over a request body must verify when the receiver re-computes with the
     * same secret. This is the exact loop {@code WebhookCallbackController} runs on each inbound
     * POST.
     */
    @Test
    void signAndVerifyRoundTrips() {
        String secret = "shared-secret";
        byte[] body =
                "{\"externalUserId\":\"alice\",\"message\":\"ping\"}"
                        .getBytes(StandardCharsets.UTF_8);

        String sentSig = WebhookSignature.hmacHex(secret, body);
        String recomputed = WebhookSignature.hmacHex(secret, body);

        assertThat(WebhookSignature.constantTimeEquals(sentSig, recomputed)).isTrue();
    }

    /** Tampering with the body must invalidate the signature. */
    @Test
    void tamperedBodyFailsVerification() {
        String secret = "shared-secret";
        byte[] original = "{\"message\":\"transfer 100\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"message\":\"transfer 999\"}".getBytes(StandardCharsets.UTF_8);

        String sigOnOriginal = WebhookSignature.hmacHex(secret, original);
        String sigOnTampered = WebhookSignature.hmacHex(secret, tampered);

        assertThat(WebhookSignature.constantTimeEquals(sigOnOriginal, sigOnTampered)).isFalse();
    }
}

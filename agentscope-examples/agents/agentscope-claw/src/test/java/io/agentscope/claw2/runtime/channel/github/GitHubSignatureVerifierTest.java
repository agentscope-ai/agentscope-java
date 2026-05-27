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
package io.agentscope.claw2.runtime.channel.github;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class GitHubSignatureVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";

    @Test
    void verifiesCorrectHmacSha256() {
        // Fixture body and expected signature mirror GitHub's docs example.
        String body = "Hello, World!";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sig = "sha256=" + hmacHex(SECRET, bytes);
        GitHubSignatureVerifier verifier = new GitHubSignatureVerifier(SECRET);
        assertTrue(verifier.verify(sig, bytes));
    }

    @Test
    void rejectsWrongSignature() {
        GitHubSignatureVerifier verifier = new GitHubSignatureVerifier(SECRET);
        byte[] body = "anything".getBytes(StandardCharsets.UTF_8);
        assertFalse(
                verifier.verify(
                        "sha256=0000000000000000000000000000000000000000000000000000000000000000",
                        body));
    }

    @Test
    void rejectsMissingPrefix() {
        GitHubSignatureVerifier verifier = new GitHubSignatureVerifier(SECRET);
        byte[] body = "anything".getBytes(StandardCharsets.UTF_8);
        String hexOnly = hmacHex(SECRET, body);
        // Without the sha256= prefix, verification must fail (header format is strict).
        assertFalse(verifier.verify(hexOnly, body));
    }

    @Test
    void rejectsNullHeader() {
        GitHubSignatureVerifier verifier = new GitHubSignatureVerifier(SECRET);
        assertFalse(verifier.verify(null, "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new GitHubSignatureVerifier(""));
        assertThrows(IllegalArgumentException.class, () -> new GitHubSignatureVerifier(null));
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(body);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

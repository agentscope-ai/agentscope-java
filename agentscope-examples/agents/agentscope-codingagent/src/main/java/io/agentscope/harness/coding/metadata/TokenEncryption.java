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
package io.agentscope.harness.coding.metadata;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Token encryption/decryption using Google Tink AEAD.
 *
 * <p>Mirrors open-swe's {@code agent/encryption.py} (Fernet → Tink AEAD). Used to store GitHub
 * tokens encrypted at rest in {@code SqliteBaseStore}.
 *
 * <h2>Key management</h2>
 *
 * The keyset JSON is loaded from:
 *
 * <ol>
 *   <li>{@code TOKEN_ENC_KEYSET_PATH} env var (path to JSON file)
 *   <li>{@code TOKEN_ENC_KEYSET} env var (inline base64-encoded JSON)
 * </ol>
 *
 * Generate a new keyset with:
 *
 * <pre>
 * tinkey create-keyset --key-template AES256_GCM --out keyset.json
 * </pre>
 */
public class TokenEncryption {

    private final Aead aead;

    public TokenEncryption() throws GeneralSecurityException {
        AeadConfig.register();
        this.aead = buildAead();
    }

    /**
     * Encrypts a plaintext token.
     *
     * @param plaintext token to encrypt
     * @return base64url-encoded ciphertext
     */
    public String encrypt(String plaintext) throws GeneralSecurityException {
        byte[] ciphertext = aead.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), null);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
    }

    /**
     * Decrypts a ciphertext token.
     *
     * @param ciphertext base64url-encoded ciphertext (from {@link #encrypt})
     * @return plaintext token
     */
    public String decrypt(String ciphertext) throws GeneralSecurityException {
        byte[] decoded = Base64.getUrlDecoder().decode(ciphertext);
        return new String(aead.decrypt(decoded, null), StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------

    private static Aead buildAead() throws GeneralSecurityException {
        String keysetPath = System.getenv("TOKEN_ENC_KEYSET_PATH");
        if (keysetPath != null && !keysetPath.isBlank()) {
            try {
                String json = Files.readString(Paths.get(keysetPath.trim()));
                return keysetFromJson(json).getPrimitive(Aead.class);
            } catch (Exception e) {
                throw new GeneralSecurityException(
                        "Failed to load Tink keyset from TOKEN_ENC_KEYSET_PATH: " + keysetPath, e);
            }
        }

        String keysetBase64 = System.getenv("TOKEN_ENC_KEYSET");
        if (keysetBase64 != null && !keysetBase64.isBlank()) {
            try {
                String json =
                        new String(
                                Base64.getDecoder().decode(keysetBase64.trim()),
                                StandardCharsets.UTF_8);
                return keysetFromJson(json).getPrimitive(Aead.class);
            } catch (Exception e) {
                throw new GeneralSecurityException(
                        "Failed to load Tink keyset from TOKEN_ENC_KEYSET env var", e);
            }
        }

        throw new GeneralSecurityException(
                "No token encryption keyset configured. Set TOKEN_ENC_KEYSET_PATH or"
                        + " TOKEN_ENC_KEYSET environment variable.");
    }

    private static KeysetHandle keysetFromJson(String json) throws GeneralSecurityException {
        return TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get());
    }
}

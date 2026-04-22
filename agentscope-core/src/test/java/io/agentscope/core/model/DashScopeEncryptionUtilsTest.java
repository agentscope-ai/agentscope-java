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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DashScopeEncryptionUtils.
 *
 * <p>Tests verify encryption/decryption functionality including AES-GCM encryption
 * and RSA key encryption.
 */
@Tag("unit")
@DisplayName("DashScopeEncryptionUtils Unit Tests")
class DashScopeEncryptionUtilsTest {

    @Test
    @DisplayName("Should generate AES secret key")
    void testGenerateAesSecretKey() {
        SecretKey key = DashScopeEncryptionUtils.generateAesSecretKey();

        assertNotNull(key);
        assertEquals("AES", key.getAlgorithm());
        assertEquals(32, key.getEncoded().length); // 256 bits = 32 bytes
    }

    @Test
    @DisplayName("Should generate unique IV")
    void testGenerateIv() {
        byte[] iv1 = DashScopeEncryptionUtils.generateIv();
        byte[] iv2 = DashScopeEncryptionUtils.generateIv();

        assertNotNull(iv1);
        assertEquals(12, iv1.length); // GCM_IV_LENGTH = 12

        assertNotNull(iv2);
        assertEquals(12, iv2.length);

        // IVs should be different (very high probability)
        boolean different = false;
        for (int i = 0; i < iv1.length; i++) {
            if (iv1[i] != iv2[i]) {
                different = true;
                break;
            }
        }
        assertTrue(different, "Generated IVs should be different");
    }

    @Test
    @DisplayName("Should encrypt and decrypt data with AES-GCM")
    void testEncryptAndDecryptWithAes() {
        SecretKey key = DashScopeEncryptionUtils.generateAesSecretKey();
        byte[] iv = DashScopeEncryptionUtils.generateIv();
        String plaintext = "Hello, this is test data!";

        String encrypted = DashScopeEncryptionUtils.encryptWithAes(key, iv, plaintext);
        assertNotNull(encrypted);
        assertTrue(encrypted.length() > 0);
        assertTrue(
                !encrypted.equals(plaintext), "Encrypted data should be different from plaintext");

        String decrypted = DashScopeEncryptionUtils.decryptWithAes(key, iv, encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("Should encrypt and decrypt empty string")
    void testEncryptAndDecryptEmptyString() {
        SecretKey key = DashScopeEncryptionUtils.generateAesSecretKey();
        byte[] iv = DashScopeEncryptionUtils.generateIv();
        String plaintext = "";

        String encrypted = DashScopeEncryptionUtils.encryptWithAes(key, iv, plaintext);
        String decrypted = DashScopeEncryptionUtils.decryptWithAes(key, iv, encrypted);

        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("Should encrypt and decrypt JSON data")
    void testEncryptAndDecryptJson() {
        SecretKey key = DashScopeEncryptionUtils.generateAesSecretKey();
        byte[] iv = DashScopeEncryptionUtils.generateIv();
        String plaintext = "{\"messages\":[{\"role\":\"user\",\"content\":\"test\"}]}";

        String encrypted = DashScopeEncryptionUtils.encryptWithAes(key, iv, plaintext);
        String decrypted = DashScopeEncryptionUtils.decryptWithAes(key, iv, encrypted);

        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("Should fail to decrypt with wrong key")
    void testDecryptWithWrongKey() {
        SecretKey key1 = DashScopeEncryptionUtils.generateAesSecretKey();
        SecretKey key2 = DashScopeEncryptionUtils.generateAesSecretKey();
        byte[] iv = DashScopeEncryptionUtils.generateIv();
        String plaintext = "test data";

        String encrypted = DashScopeEncryptionUtils.encryptWithAes(key1, iv, plaintext);

        assertThrows(
                DashScopeEncryptionUtils.EncryptionException.class,
                () -> DashScopeEncryptionUtils.decryptWithAes(key2, iv, encrypted));
    }

    @Test
    @DisplayName("Should fail to decrypt with wrong IV")
    void testDecryptWithWrongIv() {
        SecretKey key = DashScopeEncryptionUtils.generateAesSecretKey();
        byte[] iv1 = DashScopeEncryptionUtils.generateIv();
        byte[] iv2 = DashScopeEncryptionUtils.generateIv();
        String plaintext = "test data";

        String encrypted = DashScopeEncryptionUtils.encryptWithAes(key, iv1, plaintext);

        assertThrows(
                DashScopeEncryptionUtils.EncryptionException.class,
                () -> DashScopeEncryptionUtils.decryptWithAes(key, iv2, encrypted));
    }

    @Test
    @DisplayName("Should fail to decrypt invalid Base64 data")
    void testDecryptInvalidBase64() {
        SecretKey key = DashScopeEncryptionUtils.generateAesSecretKey();
        byte[] iv = DashScopeEncryptionUtils.generateIv();

        assertThrows(
                DashScopeEncryptionUtils.EncryptionException.class,
                () -> DashScopeEncryptionUtils.decryptWithAes(key, iv, "invalid-base64!@#"));
    }

    @Test
    @DisplayName("Should encrypt AES key with RSA public key")
    void testEncryptAesKeyWithRsa() throws Exception {
        // Generate RSA key pair for testing
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();

        // Encode public key to Base64 (as expected by the method)
        byte[] publicKeyBytes = publicKey.getEncoded();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyBytes);

        // Generate AES key
        SecretKey aesKey = DashScopeEncryptionUtils.generateAesSecretKey();

        // Encrypt AES key with RSA
        String encryptedAesKey =
                DashScopeEncryptionUtils.encryptAesKeyWithRsa(aesKey, publicKeyBase64);

        assertNotNull(encryptedAesKey);
        assertTrue(encryptedAesKey.length() > 0);

        // Verify we can decrypt it with the private key
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedAesKey);
        Cipher rsaCipher = Cipher.getInstance("RSA");
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] decryptedBytes = rsaCipher.doFinal(encryptedBytes);
        String decryptedAesKeyBase64 = new String(decryptedBytes);
        byte[] decryptedAesKeyBytes = Base64.getDecoder().decode(decryptedAesKeyBase64);

        assertEquals(aesKey.getEncoded().length, decryptedAesKeyBytes.length);
        for (int i = 0; i < aesKey.getEncoded().length; i++) {
            assertEquals(aesKey.getEncoded()[i], decryptedAesKeyBytes[i]);
        }
    }

    @Test
    @DisplayName("Should fail with invalid RSA public key")
    void testEncryptAesKeyWithInvalidRsaKey() {
        SecretKey aesKey = DashScopeEncryptionUtils.generateAesSecretKey();
        String invalidPublicKey = "invalid-base64-key";

        assertThrows(
                DashScopeEncryptionUtils.EncryptionException.class,
                () -> DashScopeEncryptionUtils.encryptAesKeyWithRsa(aesKey, invalidPublicKey));
    }

    @Test
    @DisplayName("Should throw EncryptionException with message")
    void testEncryptionExceptionWithMessage() {
        DashScopeEncryptionUtils.EncryptionException exception =
                new DashScopeEncryptionUtils.EncryptionException("Test error message");

        assertEquals("Test error message", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw EncryptionException with message and cause")
    void testEncryptionExceptionWithCause() {
        Throwable cause = new RuntimeException("Original error");
        DashScopeEncryptionUtils.EncryptionException exception =
                new DashScopeEncryptionUtils.EncryptionException("Wrapped error", cause);

        assertEquals("Wrapped error", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
}

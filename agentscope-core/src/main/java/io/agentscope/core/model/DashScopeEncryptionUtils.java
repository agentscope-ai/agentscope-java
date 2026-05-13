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

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption utilities for secure DashScope API access.
 *
 * <p>This class provides encryption functionality for DashScope API calls using:
 * <ul>
 *   <li>AES-GCM encryption for request/response data</li>
 *   <li>RSA public key encryption for AES key exchange</li>
 * </ul>
 *
 * <p>This implementation follows Aliyun's encryption protocol requirements for secure
 * cloud model inference, supporting TLS encryption and token-based authentication.
 */
public final class DashScopeEncryptionUtils {

    /** AES-GCM Tag length in bits (128 bits = 16 bytes). */
    private static final int GCM_TAG_LENGTH = 128;

    /** AES-GCM IV length in bytes (12 bytes). */
    private static final int GCM_IV_LENGTH = 12;

    /** AES key size in bits (256 bits). */
    private static final int AES_KEY_SIZE = 256;

    private DashScopeEncryptionUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Generate a new AES secret key for encryption.
     *
     * @return a new AES-256 secret key
     * @throws EncryptionException if key generation fails
     */
    public static SecretKey generateAesSecretKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            SecureRandom secureRandom = new SecureRandom();
            keyGen.init(AES_KEY_SIZE, secureRandom);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new EncryptionException("Failed to generate AES secret key", e);
        }
    }

    /**
     * Generate a unique initialization vector (IV) for AES-GCM encryption.
     *
     * @return a random 12-byte IV
     */
    public static byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * Encrypt data using AES-GCM.
     *
     * @param secretKey the AES secret key
     * @param iv the initialization vector
     * @param plaintext the plaintext data to encrypt
     * @return Base64-encoded encrypted data
     * @throws EncryptionException if encryption fails
     */
    public static String encryptWithAes(SecretKey secretKey, byte[] iv, String plaintext) {
        try {
            byte[] content = plaintext.getBytes(StandardCharsets.UTF_8);

            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            aesCipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(secretKey.getEncoded(), "AES"),
                    gcmParameterSpec);

            byte[] encryptedBytes = aesCipher.doFinal(content);
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt data with AES-GCM", e);
        }
    }

    /**
     * Decrypt data using AES-GCM.
     *
     * @param secretKey the AES secret key
     * @param iv the initialization vector
     * @param encryptedData Base64-encoded encrypted data
     * @return the decrypted plaintext
     * @throws EncryptionException if decryption fails
     */
    public static String decryptWithAes(SecretKey secretKey, byte[] iv, String encryptedData) {
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedData);

            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            aesCipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(secretKey.getEncoded(), "AES"),
                    gcmParameterSpec);

            byte[] decryptedBytes = aesCipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt data with AES-GCM", e);
        }
    }

    /**
     * Encrypt an AES secret key using RSA public key.
     *
     * @param aesSecretKey the AES secret key to encrypt
     * @param publicKeyBase64 Base64-encoded RSA public key
     * @return Base64-encoded encrypted AES key
     * @throws EncryptionException if encryption fails
     */
    public static String encryptAesKeyWithRsa(SecretKey aesSecretKey, String publicKeyBase64) {
        try {
            byte[] aesKeyBytes = aesSecretKey.getEncoded();
            String base64AesKey = Base64.getEncoder().encodeToString(aesKeyBytes);

            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pubKey = kf.generatePublic(spec);

            Cipher rsaCipher = Cipher.getInstance("RSA");
            rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey);
            byte[] encryptedBytes =
                    rsaCipher.doFinal(base64AesKey.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt AES key with RSA", e);
        }
    }

    /**
     * Exception thrown when encryption/decryption operations fail.
     */
    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message) {
            super(message);
        }

        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

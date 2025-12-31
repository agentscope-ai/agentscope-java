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
package io.agentscope.core.rag.reader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for common reader operations.
 *
 * <p>This class provides shared functionality for document readers, including:
 * <ul>
 *   <li>Document ID generation using hash algorithms
 *   <li>Byte array to hex string conversion
 * </ul>
 */
public final class ReaderUtils {

    private ReaderUtils() {
        // Prevent instantiation
    }

    /**
     * Generates a deterministic document ID using the specified hash algorithm.
     *
     * <p>This method is useful for creating consistent document IDs based on file paths
     * or other identifying information.
     *
     * @param input the input string to hash (typically a file path)
     * @param algorithm the hash algorithm to use (e.g., "MD5", "SHA-256")
     * @return the hash of the input as a hexadecimal string
     * @throws RuntimeException if the specified algorithm is not available
     */
    public static String generateDocId(String input, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(algorithm + " algorithm not available", e);
        }
    }

    /**
     * Generates a deterministic document ID using MD5 hash.
     *
     * <p>This is a convenience method that uses MD5 as the hash algorithm.
     *
     * @param input the input string to hash (typically a file path)
     * @return MD5 hash of the input as a hexadecimal string
     */
    public static String generateDocIdMD5(String input) {
        return generateDocId(input, "MD5");
    }

    /**
     * Generates a deterministic document ID using SHA-256 hash.
     *
     * <p>This is a convenience method that uses SHA-256 as the hash algorithm.
     *
     * @param input the input string to hash (typically a file path)
     * @return SHA-256 hash of the input as a hexadecimal string
     */
    public static String generateDocIdSHA256(String input) {
        return generateDocId(input, "SHA-256");
    }

    /**
     * Converts byte array to hexadecimal string.
     *
     * @param bytes the byte array to convert
     * @return hexadecimal string representation (lowercase)
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

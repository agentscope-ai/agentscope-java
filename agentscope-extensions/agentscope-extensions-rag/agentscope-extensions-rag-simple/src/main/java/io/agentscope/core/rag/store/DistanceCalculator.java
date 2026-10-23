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
package io.agentscope.core.rag.store;

/**
 * Utility class for calculating distances and similarities between vectors.
 *
 * <p>This class provides static methods for computing various distance metrics
 * commonly used in vector similarity search, including cosine similarity and
 * Euclidean distance.
 */
class DistanceCalculator {

    private DistanceCalculator() {
        // Utility class, prevent instantiation
    }

    /**
     * Calculates the cosine similarity between two vectors.
     *
     * <p>Cosine similarity measures the cosine of the angle between two vectors,
     * ranging from -1 to 1. A value of 1 indicates identical vectors, 0 indicates
     * orthogonal vectors, and -1 indicates opposite vectors.
     *
     * <p>Formula: cos(θ) = (A · B) / (||A|| * ||B||)
     *
     * @param vector1 the first vector
     * @param vector2 the second vector
     * @return the cosine similarity value between -1 and 1
     * @throws IllegalArgumentException if vectors are null, empty, or have different lengths
     */
    public static double cosineSimilarity(final double[] vector1, final double[] vector2) {
        validateVectors(vector1, vector2);

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            norm1 += vector1[i] * vector1[i];
            norm2 += vector2[i] * vector2[i];
        }

        double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);
        if (denominator == 0.0) {
            // At least one vector is a zero vector.
            // Cosine similarity is undefined for zero vectors, returning 0.0 as convention.
            return 0.0;
        }

        return dotProduct / denominator;
    }

    /**
     * Calculates the Euclidean distance between two vectors.
     *
     * <p>Euclidean distance measures the straight-line distance between two points
     * in n-dimensional space. Smaller values indicate more similar vectors.
     *
     * <p>Formula: d = sqrt(Σ(ai - bi)²)
     *
     * @param vector1 the first vector
     * @param vector2 the second vector
     * @return the Euclidean distance (always non-negative)
     * @throws IllegalArgumentException if vectors are null, empty, or have different lengths
     */
    public static double euclideanDistance(final double[] vector1, final double[] vector2) {
        return Math.sqrt(squaredEuclideanDistance(vector1, vector2));
    }

    /**
     * Calculates the squared Euclidean distance between two vectors.
     *
     * <p>This is a faster version that avoids the square root operation.
     * Useful when only comparing distances (not needing the actual distance value).
     *
     * @param vector1 the first vector
     * @param vector2 the second vector
     * @return the squared Euclidean distance (always non-negative)
     * @throws IllegalArgumentException if vectors are null, empty, or have different lengths
     */
    public static double squaredEuclideanDistance(final double[] vector1, final double[] vector2) {
        validateVectors(vector1, vector2);

        double sumSquaredDiff = 0.0;
        for (int i = 0; i < vector1.length; i++) {
            double diff = vector1[i] - vector2[i];
            sumSquaredDiff += diff * diff;
        }

        return sumSquaredDiff;
    }

    /**
     * Validates that two vectors are not null, not empty, and have the same length.
     *
     * @param vector1 the first vector
     * @param vector2 the second vector
     * @throws IllegalArgumentException if validation fails
     */
    private static void validateVectors(final double[] vector1, final double[] vector2) {
        if (vector1 == null) {
            throw new IllegalArgumentException("First vector cannot be null");
        }
        if (vector2 == null) {
            throw new IllegalArgumentException("Second vector cannot be null");
        }
        if (vector1.length == 0) {
            throw new IllegalArgumentException("First vector cannot be empty");
        }
        if (vector2.length == 0) {
            throw new IllegalArgumentException("Second vector cannot be empty");
        }
        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException(
                    String.format(
                            "Vectors must have the same length: %d != %d",
                            vector1.length, vector2.length));
        }
    }
}

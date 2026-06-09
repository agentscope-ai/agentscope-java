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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DistanceCalculator.
 */
@Tag("unit")
@DisplayName("DistanceCalculator Unit Tests")
class DistanceCalculatorTest {

    @Test
    @DisplayName("Should calculate cosine similarity for identical vectors")
    void testCosineSimilarityIdentical() {
        double[] v1 = {1.0, 0.0, 0.0};
        double[] v2 = {1.0, 0.0, 0.0};

        double similarity = DistanceCalculator.cosineSimilarity(v1, v2);

        assertEquals(1.0, similarity, 1e-9);
    }

    @Test
    @DisplayName("Should calculate cosine similarity for orthogonal vectors")
    void testCosineSimilarityOrthogonal() {
        double[] v1 = {1.0, 0.0, 0.0};
        double[] v2 = {0.0, 1.0, 0.0};

        double similarity = DistanceCalculator.cosineSimilarity(v1, v2);

        assertEquals(0.0, similarity, 1e-9);
    }

    @Test
    @DisplayName("Should calculate cosine similarity for opposite vectors")
    void testCosineSimilarityOpposite() {
        double[] v1 = {1.0, 0.0, 0.0};
        double[] v2 = {-1.0, 0.0, 0.0};

        double similarity = DistanceCalculator.cosineSimilarity(v1, v2);

        assertEquals(-1.0, similarity, 1e-9);
    }

    @Test
    @DisplayName("Should calculate cosine similarity for general vectors")
    void testCosineSimilarityGeneral() {
        double[] v1 = {1.0, 2.0, 3.0};
        double[] v2 = {4.0, 5.0, 6.0};

        double similarity = DistanceCalculator.cosineSimilarity(v1, v2);

        // Expected: (1*4 + 2*5 + 3*6) / (sqrt(1+4+9) * sqrt(16+25+36))
        // = 32 / (sqrt(14) * sqrt(77)) ≈ 0.9746
        assertTrue(similarity > 0.97 && similarity < 0.98);
    }

    @Test
    @DisplayName("Should handle zero vectors in cosine similarity")
    void testCosineSimilarityZeroVectors() {
        double[] v1 = {0.0, 0.0, 0.0};
        double[] v2 = {1.0, 2.0, 3.0};

        double similarity = DistanceCalculator.cosineSimilarity(v1, v2);

        assertEquals(0.0, similarity, 1e-9);
    }

    @Test
    @DisplayName("Should throw exception for null vectors in cosine similarity")
    void testCosineSimilarityNullVectors() {
        double[] v1 = {1.0, 2.0, 3.0};

        assertThrows(
                IllegalArgumentException.class,
                () -> DistanceCalculator.cosineSimilarity(null, v1));
        assertThrows(
                IllegalArgumentException.class,
                () -> DistanceCalculator.cosineSimilarity(v1, null));
    }

    @Test
    @DisplayName("Should throw exception for empty vectors in cosine similarity")
    void testCosineSimilarityEmptyVectors() {
        double[] v1 = {};
        double[] v2 = {1.0, 2.0, 3.0};

        assertThrows(
                IllegalArgumentException.class, () -> DistanceCalculator.cosineSimilarity(v1, v2));
    }

    @Test
    @DisplayName("Should throw exception for different length vectors in cosine similarity")
    void testCosineSimilarityDifferentLengths() {
        double[] v1 = {1.0, 2.0};
        double[] v2 = {1.0, 2.0, 3.0};

        assertThrows(
                IllegalArgumentException.class, () -> DistanceCalculator.cosineSimilarity(v1, v2));
    }

    @Test
    @DisplayName("Should calculate Euclidean distance for identical vectors")
    void testEuclideanDistanceIdentical() {
        double[] v1 = {1.0, 2.0, 3.0};
        double[] v2 = {1.0, 2.0, 3.0};

        double distance = DistanceCalculator.euclideanDistance(v1, v2);

        assertEquals(0.0, distance, 1e-9);
    }

    @Test
    @DisplayName("Should calculate Euclidean distance for different vectors")
    void testEuclideanDistanceDifferent() {
        double[] v1 = {0.0, 0.0, 0.0};
        double[] v2 = {3.0, 4.0, 0.0};

        double distance = DistanceCalculator.euclideanDistance(v1, v2);

        // sqrt(3^2 + 4^2 + 0^2) = 5.0
        assertEquals(5.0, distance, 1e-9);
    }

    @Test
    @DisplayName("Should calculate Euclidean distance for general vectors")
    void testEuclideanDistanceGeneral() {
        double[] v1 = {1.0, 2.0, 3.0};
        double[] v2 = {4.0, 5.0, 6.0};

        double distance = DistanceCalculator.euclideanDistance(v1, v2);

        // sqrt((1-4)^2 + (2-5)^2 + (3-6)^2) = sqrt(9 + 9 + 9) = sqrt(27) ≈ 5.196
        assertEquals(Math.sqrt(27), distance, 1e-9);
    }

    @Test
    @DisplayName("Should throw exception for null vectors in Euclidean distance")
    void testEuclideanDistanceNullVectors() {
        double[] v1 = {1.0, 2.0, 3.0};

        assertThrows(
                IllegalArgumentException.class,
                () -> DistanceCalculator.euclideanDistance(null, v1));
        assertThrows(
                IllegalArgumentException.class,
                () -> DistanceCalculator.euclideanDistance(v1, null));
    }

    @Test
    @DisplayName("Should throw exception for different length vectors in Euclidean distance")
    void testEuclideanDistanceDifferentLengths() {
        double[] v1 = {1.0, 2.0};
        double[] v2 = {1.0, 2.0, 3.0};

        assertThrows(
                IllegalArgumentException.class, () -> DistanceCalculator.euclideanDistance(v1, v2));
    }

    @Test
    @DisplayName("Should calculate squared Euclidean distance")
    void testSquaredEuclideanDistance() {
        double[] v1 = {0.0, 0.0, 0.0};
        double[] v2 = {3.0, 4.0, 0.0};

        double squaredDistance = DistanceCalculator.squaredEuclideanDistance(v1, v2);

        // 3^2 + 4^2 + 0^2 = 25.0
        assertEquals(25.0, squaredDistance, 1e-9);
    }

    @Test
    @DisplayName("Should verify squared Euclidean distance equals Euclidean distance squared")
    void testSquaredEuclideanDistanceRelation() {
        double[] v1 = {1.0, 2.0, 3.0};
        double[] v2 = {4.0, 5.0, 6.0};

        double euclidean = DistanceCalculator.euclideanDistance(v1, v2);
        double squared = DistanceCalculator.squaredEuclideanDistance(v1, v2);

        assertEquals(euclidean * euclidean, squared, 1e-9);
    }

    @Test
    @DisplayName("Should throw exception for null vectors in squared Euclidean distance")
    void testSquaredEuclideanDistanceNullVectors() {
        double[] v1 = {1.0, 2.0, 3.0};

        assertThrows(
                IllegalArgumentException.class,
                () -> DistanceCalculator.squaredEuclideanDistance(null, v1));
        assertThrows(
                IllegalArgumentException.class,
                () -> DistanceCalculator.squaredEuclideanDistance(v1, null));
    }
}

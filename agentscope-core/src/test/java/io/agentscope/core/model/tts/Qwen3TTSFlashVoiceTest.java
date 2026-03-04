/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Qwen3TTSFlashVoice enum.
 */
class Qwen3TTSFlashVoiceTest {

    @Test
    @DisplayName("should have 17 voice constants defined")
    void shouldHave17Voices() {
        assertEquals(17, Qwen3TTSFlashVoice.values().length);
    }

    @Test
    @DisplayName("should have correct voiceId for CHERRY")
    void shouldHaveCorrectVoiceIdForCherry() {
        assertEquals("Cherry", Qwen3TTSFlashVoice.CHERRY.getVoiceId());
        assertEquals("芊悦", Qwen3TTSFlashVoice.CHERRY.getDisplayName());
        assertEquals(Qwen3TTSFlashVoice.Gender.FEMALE, Qwen3TTSFlashVoice.CHERRY.getGender());
        assertNotNull(Qwen3TTSFlashVoice.CHERRY.getDescription());
    }

    @Test
    @DisplayName("should have correct voiceId for ETHAN")
    void shouldHaveCorrectVoiceIdForEthan() {
        assertEquals("Ethan", Qwen3TTSFlashVoice.ETHAN.getVoiceId());
        assertEquals("晨煦", Qwen3TTSFlashVoice.ETHAN.getDisplayName());
        assertEquals(Qwen3TTSFlashVoice.Gender.MALE, Qwen3TTSFlashVoice.ETHAN.getGender());
        assertNotNull(Qwen3TTSFlashVoice.ETHAN.getDescription());
    }

    @Test
    @DisplayName("should have correct gender for ELIAS")
    void shouldHaveCorrectGenderForElias() {
        assertEquals("Elias", Qwen3TTSFlashVoice.ELIAS.getVoiceId());
        assertEquals(Qwen3TTSFlashVoice.Gender.MALE, Qwen3TTSFlashVoice.ELIAS.getGender());
    }

    @Test
    @DisplayName("should find voice by voiceId case-insensitively")
    void shouldFindVoiceByVoiceId() {
        assertEquals(Qwen3TTSFlashVoice.CHERRY, Qwen3TTSFlashVoice.fromVoiceId("Cherry"));
        assertEquals(Qwen3TTSFlashVoice.CHERRY, Qwen3TTSFlashVoice.fromVoiceId("cherry"));
        assertEquals(Qwen3TTSFlashVoice.CHERRY, Qwen3TTSFlashVoice.fromVoiceId("CHERRY"));

        assertEquals(Qwen3TTSFlashVoice.ETHAN, Qwen3TTSFlashVoice.fromVoiceId("Ethan"));
        assertEquals(Qwen3TTSFlashVoice.LI, Qwen3TTSFlashVoice.fromVoiceId("li"));
        assertEquals(Qwen3TTSFlashVoice.KIKI, Qwen3TTSFlashVoice.fromVoiceId("Kiki"));
    }

    @Test
    @DisplayName("should return null for non-existent voiceId")
    void shouldReturnNullForNonExistentVoiceId() {
        assertNull(Qwen3TTSFlashVoice.fromVoiceId("NonExistent"));
        assertNull(Qwen3TTSFlashVoice.fromVoiceId("Unknown"));
    }

    @Test
    @DisplayName("should return null for null or empty voiceId")
    void shouldReturnNullForNullOrEmptyVoiceId() {
        assertNull(Qwen3TTSFlashVoice.fromVoiceId(null));
        assertNull(Qwen3TTSFlashVoice.fromVoiceId(""));
    }

    @Test
    @DisplayName("should return random voice using ThreadLocalRandom")
    void shouldReturnRandomVoice() {
        Qwen3TTSFlashVoice voice1 = Qwen3TTSFlashVoice.random();
        assertNotNull(voice1);

        // Call multiple times to verify randomness (not guaranteed to be different but should
        // work)
        Set<Qwen3TTSFlashVoice> voices = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            voices.add(Qwen3TTSFlashVoice.random());
        }
        // With 17 voices and 50 calls, we should get at least 2 different voices
        assertTrue(voices.size() >= 2);
    }

    @Test
    @DisplayName("should return random voice using provided Random instance")
    void shouldReturnRandomVoiceWithProvidedRandom() {
        Random random = new Random(12345); // Fixed seed for reproducibility
        Qwen3TTSFlashVoice voice1 = Qwen3TTSFlashVoice.random(random);
        assertNotNull(voice1);

        // Reset random with same seed to get same result
        random = new Random(12345);
        Qwen3TTSFlashVoice voice2 = Qwen3TTSFlashVoice.random(random);
        assertEquals(voice1, voice2);
    }

    @Test
    @DisplayName("should have all voices with non-null properties")
    void shouldHaveAllVoicesWithNonNullProperties() {
        for (Qwen3TTSFlashVoice voice : Qwen3TTSFlashVoice.values()) {
            assertNotNull(voice.getVoiceId(), "voiceId should not be null for " + voice);
            assertNotNull(voice.getDisplayName(), "displayName should not be null for " + voice);
            assertNotNull(voice.getGender(), "gender should not be null for " + voice);
            assertNotNull(voice.getDescription(), "description should not be null for " + voice);
        }
    }

    @Test
    @DisplayName("should have unique voiceIds for all voices")
    void shouldHaveUniqueVoiceIds() {
        Set<String> voiceIds = new HashSet<>();
        for (Qwen3TTSFlashVoice voice : Qwen3TTSFlashVoice.values()) {
            assertTrue(
                    voiceIds.add(voice.getVoiceId()),
                    "Duplicate voiceId found: " + voice.getVoiceId());
        }
        assertEquals(17, voiceIds.size());
    }

    @Test
    @DisplayName("Gender enum should have MALE and FEMALE")
    void genderEnumShouldHaveMaleAndFemale() {
        assertEquals(2, Qwen3TTSFlashVoice.Gender.values().length);
        assertEquals(Qwen3TTSFlashVoice.Gender.MALE, Qwen3TTSFlashVoice.Gender.valueOf("MALE"));
        assertEquals(Qwen3TTSFlashVoice.Gender.FEMALE, Qwen3TTSFlashVoice.Gender.valueOf("FEMALE"));
    }

    @Test
    @DisplayName("should have correct distribution of male and female voices")
    void shouldHaveCorrectGenderDistribution() {
        int maleCount = 0;
        int femaleCount = 0;
        for (Qwen3TTSFlashVoice voice : Qwen3TTSFlashVoice.values()) {
            if (voice.getGender() == Qwen3TTSFlashVoice.Gender.MALE) {
                maleCount++;
            } else if (voice.getGender() == Qwen3TTSFlashVoice.Gender.FEMALE) {
                femaleCount++;
            }
        }
        assertEquals(17, maleCount + femaleCount, "Total male + female should equal total voices");
        assertTrue(maleCount > 0, "Should have at least one male voice");
        assertTrue(femaleCount > 0, "Should have at least one female voice");
    }
}

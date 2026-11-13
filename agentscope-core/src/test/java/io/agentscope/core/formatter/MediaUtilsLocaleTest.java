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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Extension matching must not depend on the default locale.
 *
 * <p>Under a Turkish default locale, {@code String.toLowerCase()} maps {@code 'I'} to the dotless
 * {@code 'ı'}, so {@code "GIF".toLowerCase()} is not {@code "gif"}. File extensions are ASCII
 * identifiers, not natural-language text, so they must be compared case-insensitively with
 * {@link Locale#ROOT}.
 */
class MediaUtilsLocaleTest {

    private Locale previous;

    @BeforeEach
    void useTurkishLocale() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(previous);
    }

    @Test
    @DisplayName("determineMediaType resolves upper-case extensions under tr-TR")
    void determineMediaTypeResolvesUpperCaseExtensions() {
        assertEquals("image/gif", MediaUtils.determineMediaType("photo.GIF"));
        assertEquals("image/heic", MediaUtils.determineMediaType("photo.HEIC"));
        assertEquals("video/x-msvideo", MediaUtils.determineMediaType("clip.AVI"));
        assertEquals("application/pdf", MediaUtils.determineMediaType("doc.PDF"));
        assertEquals("image/gif", MediaUtils.determineMediaType("https://example.com/photo.GIF"));
    }

    @Test
    @DisplayName("validateImageExtension accepts upper-case extensions under tr-TR")
    void validateImageExtensionAcceptsUpperCaseExtensions() {
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("photo.GIF"));
        assertDoesNotThrow(() -> MediaUtils.validateImageExtension("photo.HEIC"));
    }

    @Test
    @DisplayName("validateAudioExtension and validateVideoExtension accept upper-case extensions")
    void validateAudioAndVideoExtensionsAcceptUpperCaseExtensions() {
        assertDoesNotThrow(() -> MediaUtils.validateAudioExtension("clip.MP3"));
        assertDoesNotThrow(() -> MediaUtils.validateVideoExtension("clip.MP4"));
    }

    @Test
    @DisplayName("determineAudioFormat resolves upper-case extensions under tr-TR")
    void determineAudioFormatResolvesUpperCaseExtensions() {
        assertEquals("wav", MediaUtils.determineAudioFormat("clip.WAV"));
        assertEquals("mp3", MediaUtils.determineAudioFormat("clip.MP3"));
    }
}

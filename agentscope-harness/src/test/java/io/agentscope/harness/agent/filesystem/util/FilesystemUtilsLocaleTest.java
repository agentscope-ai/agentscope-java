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
package io.agentscope.harness.agent.filesystem.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binary/text classification decides whether the file tools base64-encode a file or hand its
 * bytes to the model as text, so it must not depend on the JVM default locale.
 *
 * <p>Under a Turkish default locale {@code "GIF".toLowerCase()} is {@code "gıf"} (dotless i), so an
 * upper-case binary extension was classified as text and the tool returned mojibake instead of
 * base64.
 */
class FilesystemUtilsLocaleTest {

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
    @DisplayName("getFileType still classifies upper-case binary extensions as binary under tr-TR")
    void getFileTypeClassifiesUpperCaseBinaryExtensions() {
        assertEquals("binary", FilesystemUtils.getFileType("photo.GIF"));
        assertEquals("binary", FilesystemUtils.getFileType("photo.PNG"));
        assertEquals("binary", FilesystemUtils.getFileType("archive.ZIP"));
        assertEquals("binary", FilesystemUtils.getFileType("report.PDF"));
    }

    @Test
    @DisplayName("getFileType still classifies text extensions as text under tr-TR")
    void getFileTypeKeepsTextExtensions() {
        assertEquals("text", FilesystemUtils.getFileType("notes.MD"));
        assertEquals("text", FilesystemUtils.getFileType("Main.JAVA"));
        assertEquals("text", FilesystemUtils.getFileType("no-extension"));
    }
}

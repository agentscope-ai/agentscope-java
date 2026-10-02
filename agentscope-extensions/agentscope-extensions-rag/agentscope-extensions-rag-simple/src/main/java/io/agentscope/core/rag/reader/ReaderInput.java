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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Input wrapper for document readers.
 *
 * <p>This class provides a unified interface for different types of input sources
 * (text strings, file paths, URLs, etc.) that can be read by document readers.
 */
public class ReaderInput {

    private final String content;
    private final InputType type;

    private ReaderInput(String content, InputType type) {
        this.content = content;
        this.type = type;
    }

    /**
     * Creates a ReaderInput from a text string.
     *
     * @param text the text content
     * @return a ReaderInput instance
     */
    public static ReaderInput fromString(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text content cannot be null");
        }
        return new ReaderInput(text, InputType.STRING);
    }

    /**
     * Creates a ReaderInput from a file path.
     *
     * @param filePath the path to the file
     * @return a ReaderInput instance
     * @throws IOException if the file cannot be read
     */
    public static ReaderInput fromFile(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path cannot be null");
        }
        return fromFile(Path.of(filePath));
    }

    /**
     * Creates a ReaderInput from a file path.
     *
     * @param filePath the path to the file
     * @return a ReaderInput instance
     * @throws IOException if the file cannot be read
     */
    public static ReaderInput fromFile(Path filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path cannot be null");
        }
        if (!Files.exists(filePath)) {
            throw new IOException("File does not exist: " + filePath);
        }
        String content = Files.readString(filePath);
        return new ReaderInput(content, InputType.FILE);
    }

    /**
     * Creates a ReaderInput from a File object.
     *
     * @param file the file object
     * @return a ReaderInput instance
     * @throws IOException if the file cannot be read
     */
    public static ReaderInput fromFile(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        return fromFile(file.toPath());
    }

    /**
     * Creates a ReaderInput from a File path string.
     *
     * @param path the file path string
     * @return a ReaderInput instance
     * @throws IllegalArgumentException if the path is invalid
     */
    public static ReaderInput fromPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("File path cannot be null");
        }
        return fromPath(Path.of(path));
    }

    /**
     * Creates a ReaderInput from a File path.
     *
     * @param path the file path
     * @return a ReaderInput instance
     * @throws IllegalArgumentException if the path is invalid
     */
    public static ReaderInput fromPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("File path cannot be null");
        }

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File does not exist: " + path);
        }

        return new ReaderInput(path.toAbsolutePath().toString(), InputType.FILE);
    }

    /**
     * Gets the input content as a string.
     *
     * @return the content string
     */
    public String asString() {
        return content;
    }

    /**
     * Gets the input type.
     *
     * @return the input type
     */
    public InputType getType() {
        return type;
    }

    /**
     * Input type enumeration.
     */
    public enum InputType {
        STRING,
        FILE,
        URL
    }
}

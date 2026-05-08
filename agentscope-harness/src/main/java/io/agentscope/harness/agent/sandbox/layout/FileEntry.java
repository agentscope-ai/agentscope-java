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
package io.agentscope.harness.agent.sandbox.layout;

/**
 * Layout entry that creates a file with inline text content.
 */
public class FileEntry extends WorkspaceEntry {

    private String content = "";
    private String encoding = "UTF-8";

    /** Creates an empty file entry. */
    public FileEntry() {}

    /**
     * Creates a file entry with the given content.
     *
     * @param content the file content as a string
     */
    public FileEntry(String content) {
        this.content = content;
    }

    /**
     * Creates a file entry with the given content and encoding.
     *
     * @param content the file content as a string
     * @param encoding the character encoding to use when writing the file
     */
    public FileEntry(String content, String encoding) {
        this.content = content;
        this.encoding = encoding;
    }

    /**
     * Returns the file content.
     *
     * @return file content string
     */
    public String getContent() {
        return content;
    }

    /**
     * Sets the file content.
     *
     * @param content the file content string
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Returns the character encoding used to write this file.
     *
     * @return encoding name (e.g. "UTF-8")
     */
    public String getEncoding() {
        return encoding;
    }

    /**
     * Sets the character encoding used to write this file.
     *
     * @param encoding encoding name (e.g. "UTF-8")
     */
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
}

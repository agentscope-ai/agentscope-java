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

/**
 * Enumeration of table formatting options for Word document readers.
 *
 * <p>This enum defines how tables should be converted to text format
 * when extracting content from Word documents.
 */
public enum TableFormat {
    /**
     * Convert tables to Markdown format.
     *
     * <p>Example:
     * <pre>
     * | Header1 | Header2 |
     * |---------|---------|
     * | Cell1   | Cell2   |
     * </pre>
     *
     * <p>Note: If table cells contain newlines (\n), the Markdown format
     * may not render correctly. In such cases, use JSON format instead.
     */
    MARKDOWN,

    /**
     * Convert tables to JSON format.
     *
     * <p>Extracts the table as a JSON string representing a list of rows,
     * where each row is a list of cell values. This format handles cells
     * with newlines correctly.
     *
     * <p>Example:
     * <pre>
     * &lt;system-info&gt;A table loaded as a JSON array:&lt;/system-info&gt;
     * ["Header1", "Header2"]
     * ["Cell1", "Cell2"]
     * </pre>
     */
    JSON
}

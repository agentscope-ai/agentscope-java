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

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link DocumentBlock} as a one-line reference for text-only sinks.
 *
 * <p>Memory flushing and conversation compaction both feed history to a text model. The document
 * body cannot travel with that prompt, but the fact that a file was part of the conversation must
 * not disappear. This renderer keeps the name, the MIME type, and the location the bytes can be
 * reached at. Base64 payloads are described by size instead of being inlined: one attachment
 * would otherwise consume the entire prompt budget.
 */
public final class DocumentReferenceRenderer {

    private DocumentReferenceRenderer() {}

    /**
     * Renders a message as text with one reference appended per document.
     *
     * <p>Sinks that store or prompt on plain text — long-term memory backends, task results,
     * summaries — would otherwise reduce an attachment-only turn to an empty string and drop it
     * entirely.
     *
     * @param msg message to render
     * @return the message text followed by its document references; never null
     */
    public static String renderMessageText(Msg msg) {
        return renderMessageText(msg, true);
    }

    /**
     * Renders a message as text, optionally including documents carried by its tool results.
     *
     * <p>Sinks that already render tool results on their own - a transcript export, for instance -
     * pass {@code false} to avoid reporting the same document twice.
     *
     * @param msg message to render
     * @param includeToolResults whether documents inside a {@code ToolResultBlock} are included
     * @return the message text followed by its document references; never null
     */
    public static String renderMessageText(Msg msg, boolean includeToolResults) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        List<DocumentBlock> documents = new ArrayList<>(msg.getContentBlocks(DocumentBlock.class));
        // A tool result carries its own output blocks. They are part of the turn and used to be
        // invisible to every text sink, so include them here rather than in each caller.
        if (includeToolResults) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock result) {
                    for (ContentBlock output : result.getOutput()) {
                        if (output instanceof DocumentBlock document) {
                            documents.add(document);
                        }
                    }
                }
            }
        }
        if (documents.isEmpty()) {
            return text != null ? text : "";
        }
        StringBuilder sb = new StringBuilder(text != null ? text : "");
        for (DocumentBlock document : documents) {
            if (sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1))) {
                sb.append('\n');
            }
            sb.append(render(document));
        }
        return sb.toString();
    }

    /**
     * Renders a single document reference, for example {@code
     * [document: report.pdf, application/pdf, url: https://example.com/report.pdf]}.
     *
     * @param doc the document to render
     * @return a single-line reference; never blank
     */
    public static String render(DocumentBlock doc) {
        StringBuilder sb = new StringBuilder("[document: ");
        String label = singleLine(doc.getName());
        if (label == null || label.isBlank()) {
            label = singleLine(doc.getId());
        }
        if (label == null || label.isBlank()) {
            label = "unnamed";
        }
        sb.append(label);
        Source source = doc.getSource();
        String mimeType = doc.getMimeType();
        if (mimeType == null || mimeType.isBlank()) {
            // The source often knows its own type; prefer it over losing the type entirely.
            if (source instanceof Base64Source base64) {
                mimeType = base64.getMediaType();
            } else if (source instanceof URLSource url) {
                mimeType = url.getMimeType();
            }
        }
        if (mimeType != null && !mimeType.isBlank()) {
            sb.append(", ").append(singleLine(mimeType));
        }
        if (source instanceof FileIdSource fileIdSource) {
            sb.append(", provider file id: ")
                    .append(singleLine(fileIdSource.getFileId()))
                    .append(" (")
                    .append(singleLine(fileIdSource.getProvider()))
                    .append(")");
        } else if (source instanceof URLSource urlSource) {
            sb.append(", url: ").append(singleLine(urlSource.getUrl()));
        } else if (source instanceof Base64Source base64Source) {
            sb.append(", inline base64");
            long bytes =
                    doc.getSizeBytes() != null ? doc.getSizeBytes() : decodedSize(base64Source);
            sb.append(" (~").append(bytes).append(" bytes)");
        }
        return sb.append("]").toString();
    }

    /**
     * Flattens a reference field onto one line.
     *
     * <p>The renderer promises a one-line reference: a name copied from an untrusted header or a
     * multipart filename can carry CR/LF, which would break the line the caller trusted.
     *
     * @param value the raw field value, may be null
     * @return the value with CR/LF collapsed to spaces, or null
     */
    private static String singleLine(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\r\\n|\\r|\\n", " ");
    }

    /**
     * Estimates the decoded size of a Base64 payload, subtracting the padding characters.
     *
     * @param source base64 source
     * @return decoded size in bytes
     */
    private static long decodedSize(Base64Source source) {
        String data = source.getData();
        int padding = 0;
        for (int i = data.length() - 1; i >= 0 && data.charAt(i) == '='; i--) {
            padding++;
        }
        return (data.length() * 3L) / 4L - padding;
    }
}

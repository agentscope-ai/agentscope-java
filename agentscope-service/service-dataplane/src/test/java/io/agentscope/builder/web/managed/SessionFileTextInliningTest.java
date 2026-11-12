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
package io.agentscope.builder.web.managed;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests how a session file is turned into model input.
 *
 * <p>A text file used to be handed to the model as an opaque data block, which the OpenAI formatter
 * renders as {@code [Media - unsupported MIME type: text/plain]} — the agent never saw the content.
 * These tests pin the media types that are inlined as text, the wrapper that marks the content as an
 * attachment, the size cut that keeps a huge file from swallowing the context, and the bytes that
 * must stay opaque no matter what the media type claims.
 */
class SessionFileTextInliningTest {

    @Test
    void textFilesReachTheModelAsTextNotAsAPlaceholder() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String payload = "Q3 revenue was 12M.\nSecond line.\n";
        String id =
                upload(
                        files,
                        "k",
                        "notes.txt",
                        "text/plain",
                        payload.getBytes(StandardCharsets.UTF_8));

        assertThat(textOf(onlyMessage(files, id)))
                .isEqualTo(
                        "<file name=\"notes.txt\" media_type=\"text/plain\">\n"
                                + payload
                                + "\n</file>");
    }

    @Test
    void structuredTextSubtypesAreInlinedAsWell() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String json = "{\"answer\":42}";
        String markdown = "# Title\n\nBody";
        String vendorJson = "{\"data\":[]}";
        String jsonId =
                upload(
                        files,
                        "k1",
                        "a.json",
                        "application/json",
                        json.getBytes(StandardCharsets.UTF_8));
        String mdId =
                upload(
                        files,
                        "k2",
                        "b.md",
                        "text/markdown",
                        markdown.getBytes(StandardCharsets.UTF_8));
        String vendorId =
                upload(
                        files,
                        "k3",
                        "c.json",
                        "application/vnd.api+json",
                        vendorJson.getBytes(StandardCharsets.UTF_8));

        assertThat(bodyOf(onlyMessage(files, jsonId))).isEqualTo(json);
        assertThat(bodyOf(onlyMessage(files, mdId))).isEqualTo(markdown);
        assertThat(bodyOf(onlyMessage(files, vendorId))).isEqualTo(vendorJson);
    }

    @Test
    void theInlinedTextIsMarkedAsAnAttachmentWithItsName() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String id =
                upload(
                        files,
                        "k",
                        "re-port \"2\" & <draft>.txt",
                        "text/plain",
                        "body".getBytes(StandardCharsets.UTF_8));

        assertThat(textOf(onlyMessage(files, id)))
                .startsWith("<file name=\"re-port &quot;2&quot; &amp; &lt;draft&gt;.txt\"");
    }

    @Test
    void aLargeTextFileIsCutAndTheCutIsStatedInTheText() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        byte[] big = new byte[300_000];
        java.util.Arrays.fill(big, (byte) 'a');
        String id = upload(files, "k", "big.txt", "text/plain", big);

        String text = textOf(onlyMessage(files, id));

        assertThat(text).contains("\n\n[truncated: 262144 of 300000 bytes]\n</file>");
        assertThat(text)
                .hasSize(
                        262_144
                                + "<file name=\"big.txt\" media_type=\"text/plain\">\n".length()
                                + "\n\n[truncated: 262144 of 300000 bytes]\n</file>".length());
    }

    @Test
    void binaryFilesStayOpaque() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String id = upload(files, "k", "report.pdf", "application/pdf", new byte[] {1, 2, 3});

        assertThat(onlyMessage(files, id).getContent().get(0)).isInstanceOf(DataBlock.class);
    }

    @Test
    void aTextMediaTypeOverBytesContainingNulStaysOpaque() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String id = upload(files, "k", "mislabeled.txt", "text/plain", new byte[] {'a', 0, 'b'});

        assertThat(onlyMessage(files, id).getContent().get(0)).isInstanceOf(DataBlock.class);
    }

    @Test
    void aTextMediaTypeOverBytesThatAreNotValidTextStaysOpaque() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        // Binary without a NUL byte, mislabelled as text/plain: decoding must reject it instead of
        // replacing the malformed bytes with U+FFFD and inlining the result.
        byte[] binary = {1, 2, 'B', 'I', 'N', (byte) 0x80, (byte) 0x81, (byte) 0xFE, (byte) 0xFF};
        String id = upload(files, "k", "binary.txt", "text/plain", binary);

        assertThat(onlyMessage(files, id).getContent().get(0)).isInstanceOf(DataBlock.class);
    }

    @Test
    void aTextMediaTypeOverControlCharactersStaysOpaque() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        // Valid UTF-8, so strict decoding alone would accept it; the control characters are what
        // make it binary for this purpose.
        byte[] control = {1, 2, 'a', 'b', 'c'};
        String id = upload(files, "k", "control.txt", "text/plain", control);

        assertThat(onlyMessage(files, id).getContent().get(0)).isInstanceOf(DataBlock.class);
    }

    @Test
    void aDeclaredCharsetIsHonoured() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String id =
                upload(
                        files,
                        "k",
                        "latin1.txt",
                        "text/plain;charset=ISO-8859-1",
                        new byte[] {'c', 'a', 'f', (byte) 0xE9});

        assertThat(bodyOf(onlyMessage(files, id))).isEqualTo("café");
    }

    @Test
    void utf16ContentIsDecodedWithItsDeclaredCharset() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String payload = "材料 PR4101_UTF16_OK";
        String id =
                upload(
                        files,
                        "k",
                        "wide.txt",
                        "text/plain;charset=UTF-16",
                        payload.getBytes(StandardCharsets.UTF_16));

        assertThat(bodyOf(onlyMessage(files, id))).isEqualTo(payload);
    }

    private static String upload(
            SessionFileService files, String key, String name, String mediaType, byte[] bytes) {
        return (String) files.upload("s", key, name, mediaType, bytes).get("file_id");
    }

    private static Msg onlyMessage(SessionFileService files, String... fileIds) {
        var blocks = new ArrayList<Map<String, Object>>();
        for (String id : fileIds) {
            blocks.add(Map.of("type", "file", "file_id", id));
        }
        var input =
                new AgentSessionInput(null, List.of(new AgentSessionInput.Message("user", blocks)));
        List<Msg> messages =
                AgentSessionInput.messages(files.resolveInput("s", input.normalized()), "c");
        assertThat(messages).hasSize(1);
        return messages.get(0);
    }

    private static String textOf(Msg msg) {
        assertThat(msg.getContent()).hasSize(1);
        assertThat(msg.getContent().get(0)).isInstanceOf(TextBlock.class);
        return ((TextBlock) msg.getContent().get(0)).getText();
    }

    /** The content inside the {@code <file>} wrapper, i.e. what the file actually contains. */
    private static String bodyOf(Msg msg) {
        String text = textOf(msg);
        int start = text.indexOf(">\n") + 2;
        int end = text.lastIndexOf("\n</file>");
        return text.substring(start, end);
    }
}

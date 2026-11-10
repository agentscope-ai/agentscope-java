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
 * These tests pin the media types that are inlined as text, the size cut that keeps a huge file from
 * swallowing the context, and the cases that must stay opaque.
 */
class SessionFileTextInliningTest {

    private static final String TRUNCATION_MARKER = "[truncated: ";

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

        Msg msg = onlyMessage(files, id);

        assertThat(msg.getContent()).hasSize(1);
        assertThat(msg.getContent().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) msg.getContent().get(0)).getText()).isEqualTo(payload);
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

        assertThat(onlyMessage(files, jsonId).getContent().get(0))
                .isInstanceOfSatisfying(
                        TextBlock.class, block -> assertThat(block.getText()).isEqualTo(json));
        assertThat(onlyMessage(files, mdId).getContent().get(0))
                .isInstanceOfSatisfying(
                        TextBlock.class, block -> assertThat(block.getText()).isEqualTo(markdown));
        assertThat(onlyMessage(files, vendorId).getContent().get(0))
                .isInstanceOfSatisfying(
                        TextBlock.class,
                        block -> assertThat(block.getText()).isEqualTo(vendorJson));
    }

    @Test
    void aLargeTextFileIsCutAndTheCutIsStatedInTheText() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        byte[] big = new byte[300_000];
        java.util.Arrays.fill(big, (byte) 'a');
        String id = upload(files, "k", "big.txt", "text/plain", big);

        String text = textOf(onlyMessage(files, id));

        assertThat(text).endsWith("\n\n[truncated: 262144 of 300000 bytes]");
        assertThat(text).hasSize(262_144 + "\n\n[truncated: 262144 of 300000 bytes]".length());
    }

    @Test
    void binaryFilesStayOpaque() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String id = upload(files, "k", "report.pdf", "application/pdf", new byte[] {1, 2, 3});

        assertThat(onlyMessage(files, id).getContent().get(0)).isInstanceOf(DataBlock.class);
    }

    @Test
    void aTextMediaTypeOverBinaryBytesStaysOpaque() {
        var files = new SessionFileService(new InMemoryStore(), 1_000_000);
        String id = upload(files, "k", "mislabeled.txt", "text/plain", new byte[] {'a', 0, 'b'});

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

        assertThat(textOf(onlyMessage(files, id))).isEqualTo("café");
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
}

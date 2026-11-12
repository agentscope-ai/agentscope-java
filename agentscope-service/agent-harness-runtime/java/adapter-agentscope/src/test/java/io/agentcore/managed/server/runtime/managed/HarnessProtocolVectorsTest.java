/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Java identity implementation against the same vectors used by the Python and Node checks. */
class HarnessProtocolVectorsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode vectors() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate =
                    directory.resolve(
                            "managed-agents/agent-harness-runtime/protocol/examples/vectors.json");
            if (Files.isRegularFile(candidate))
                return mapper.readTree(Files.readAllBytes(candidate));
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate the shared protocol baseline");
    }

    @Test
    void actualHandsWireFixtureDecodesThroughTheProductionCodec() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null
                && !Files.isRegularFile(
                        root.resolve(
                                "managed-agents/agent-harness-runtime/protocol/examples/hands-wire.json")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate wire fixture");
        var fixture =
                mapper.readTree(
                        Files.readAllBytes(
                                root.resolve(
                                        "managed-agents/agent-harness-runtime/protocol/examples/hands-wire.json")));
        var schema = new ProtocolSchemas();
        schema.validate("hands-wire/Envelope", fixture.required("envelope"));
        schema.validate("hands-wire/Command", fixture.required("command"));
        var command = mapper.convertValue(fixture.required("command"), ManagedToolCommand.class);
        var message =
                new ManagedMessageBus.Message(
                        command.commandId(),
                        command.messageGroup(),
                        mapper.writeValueAsBytes(fixture.required("envelope")));
        assertEquals(
                command,
                new ManagedBatchCodec(mapper, new FileRuntimeObjectStore("runtime", directory))
                        .decodeToolCommand(message));
    }

    @Test
    void identityFramingAndHashesMatchOtherLanguagesIncludingSupplementaryUnicode()
            throws Exception {
        for (var vector : vectors().path("identity")) {
            var parts = new ArrayList<String>();
            vector.path("parts").forEach(part -> parts.add(part.asText()));
            String hash = ManagedScope.fingerprint(parts.toArray(String[]::new));
            assertEquals(vector.path("sha256").asText(), hash);
            assertEquals(
                    vector.path("base64url").asText(),
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(HexFormat.of().parseHex(hash)));
            var framed = new StringBuilder();
            parts.forEach(part -> framed.append(part.length()).append(':').append(part));
            assertEquals(
                    vector.path("framedUtf8Hex").asText(),
                    HexFormat.of().formatHex(framed.toString().getBytes(StandardCharsets.UTF_8)));
        }
        var scope = new ManagedScope("租户😀", "demo", "会话");
        assertEquals("SEYyDOkmvepcPbTnG6jKOL5DJwpqE_RuXvBFg9OaZ1U", scope.messageGroup());
    }

    @Test
    void canonicalUtf8FixturesHaveTheSameSha256InJava() throws Exception {
        // Exercise the actual wire canonicalizer against shared cross-language vectors.
        int count = 0;
        for (var vector : vectors().path("canonical")) {
            byte[] bytes = vector.path("canonical").asText().getBytes(StandardCharsets.UTF_8);
            assertEquals(
                    vector.path("sha256").asText(),
                    RuntimeObjectStore.sha256(bytes),
                    vector.path("name").asText());
            assertEquals(
                    vector.path("sha256").asText(), CanonicalJson.hash(vector.required("value")));
            assertTrue(mapper.readTree(bytes).isObject());
            count++;
        }
        assertEquals(4, count);
    }
}

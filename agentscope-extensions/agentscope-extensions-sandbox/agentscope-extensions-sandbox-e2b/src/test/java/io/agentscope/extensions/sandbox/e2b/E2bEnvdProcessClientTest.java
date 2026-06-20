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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.MediaType;
import org.junit.jupiter.api.Test;

class E2bEnvdProcessClientTest {

    @Test
    void jsonCodecUsesConnectJsonAndJsonPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+json"), client.connectMediaType());
        assertEquals(0x00, envelope[0] & 0xFF);
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        String json = new String(payload, StandardCharsets.UTF_8);
        assertEquals(
                "{\"process\":{\"cmd\":\"/bin/bash\",\"args\":[\"-l\",\"-c\","
                        + "\"echo hello\"],\"cwd\":\"/workspace\"},\"stdin\":false}",
                json);
    }

    @Test
    void protoCodecKeepsBinaryPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");
        DynamicMessage expected = client.buildStartRequest("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+proto"), client.connectMediaType());
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        assertArrayEquals(expected.toByteArray(), payload);
    }

    @Test
    void jsonCodecParsesStartResponseFrame() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        DynamicMessage response = startResponse(client, "hello\n", "warn\n", 3);
        String json =
                "{\"event\":{\"data\":{\"stdout\":\""
                        + Base64.getEncoder()
                                .encodeToString("hello\n".getBytes(StandardCharsets.UTF_8))
                        + "\",\"stderr\":\""
                        + Base64.getEncoder()
                                .encodeToString("warn\n".getBytes(StandardCharsets.UTF_8))
                        + "\"},\"end\":{\"exitCode\":3}}}";

        DynamicMessage parsed =
                client.parseStartResponseFrame(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(response, parsed);
    }

    private static DynamicMessage startResponse(
            E2bEnvdProcessClient client, String stdout, String stderr, int exitCode) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor dataDesc = processEventDesc.findNestedTypeByName("DataEvent");
        Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");

        DynamicMessage data =
                DynamicMessage.newBuilder(dataDesc)
                        .setField(
                                dataDesc.findFieldByName("stdout"), ByteString.copyFromUtf8(stdout))
                        .setField(
                                dataDesc.findFieldByName("stderr"), ByteString.copyFromUtf8(stderr))
                        .build();
        DynamicMessage end =
                DynamicMessage.newBuilder(endDesc)
                        .setField(endDesc.findFieldByName("exit_code"), exitCode)
                        .build();
        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("data"), data)
                        .setField(processEventDesc.findFieldByName("end"), end)
                        .build();
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build();
    }

    private static E2bSandboxClientOptions options(E2bCodec codec) {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setCodec(codec);
        return options;
    }
}

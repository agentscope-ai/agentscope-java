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
package io.agentscope.harness.agent.transcript;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectStoreTranscriptStoreTest {

    @Test
    void appendSegmentPropagatesReturnedUploadFailure() {
        AbstractFilesystem filesystem =
                (AbstractFilesystem)
                        Proxy.newProxyInstance(
                                AbstractFilesystem.class.getClassLoader(),
                                new Class<?>[] {AbstractFilesystem.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("uploadFiles")) {
                                        return List.of(
                                                FileUploadResponse.fail(
                                                        "segment", "connection closed"));
                                    }
                                    return null;
                                });

        ObjectStoreTranscriptStore store = new ObjectStoreTranscriptStore(filesystem);

        assertThrows(
                IllegalStateException.class,
                () ->
                        store.appendSegment(
                                new TranscriptRef("tenant", "agent", "session"),
                                0,
                                0,
                                "writer",
                                new byte[] {1}));
    }
}

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
package io.agentscope.dataagent.web.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedSandboxFilesystemTest {

    @Test
    void downloadDecodesPosixBase64WithEmbeddedLineWraps() throws Exception {
        byte[] expected = new byte[128];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        String wrapped = Base64.getMimeEncoder(76, new byte[] {'\n'}).encodeToString(expected);
        Sandbox sandbox = mock(Sandbox.class);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("user-1").build();
        when(sandbox.exec(eq(runtimeContext), anyString(), isNull()))
                .thenReturn(new ExecResult(0, wrapped, "", false));

        FileDownloadResponse result =
                new SharedSandboxFilesystem(sandbox)
                        .downloadFiles(runtimeContext, List.of("/workspace/blob.bin"))
                        .get(0);

        assertNull(result.error());
        assertArrayEquals(expected, result.content());
    }
}

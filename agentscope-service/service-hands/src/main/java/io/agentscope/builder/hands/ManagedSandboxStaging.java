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
package io.agentscope.builder.hands;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Creates only missing pinned definition files; never resets a user Workspace. */
public final class ManagedSandboxStaging {
    private ManagedSandboxStaging() {}

    public static void stage(Sandbox sandbox, Map<String, String> definitions, ObjectMapper mapper)
            throws Exception {
        if (definitions == null || definitions.isEmpty()) return;
        String encoded = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(definitions));
        String program =
                "import"
                    + " base64,json,pathlib,sys;root=pathlib.Path.cwd().resolve();files=json.loads(base64.b64decode('"
                        + encoded
                        + "'));\n"
                        + "marker=root/'.managed-tools'/'definition-ready';\n"
                        + "if marker.exists(): sys.exit(0)\n"
                        + "for name,text in files.items():\n"
                        + " p=(root/name).resolve(); assert p.is_relative_to(root);"
                        + " p.parent.mkdir(parents=True,exist_ok=True);\n"
                        + " if not p.exists(): p.write_text(text)\n"
                        + "marker.parent.mkdir(parents=True,exist_ok=True);marker.write_text('ready')";
        String code = Base64.getEncoder().encodeToString(program.getBytes(StandardCharsets.UTF_8));
        if (!sandbox.exec(
                        null,
                        "python3 -c 'import base64;exec(base64.b64decode(\"" + code + "\"))'",
                        30)
                .ok()) throw new IllegalStateException("Sandbox definition staging failed");
    }
}

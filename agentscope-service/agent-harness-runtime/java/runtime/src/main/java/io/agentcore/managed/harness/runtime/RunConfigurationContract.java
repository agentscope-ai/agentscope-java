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
package io.agentcore.managed.harness.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Immutable configuration identity shared by the service and HTTP Brain workers. */
public final class RunConfigurationContract {
    public static final String FORMAT = "managed-effective-agent-v1";
    private static final ProtocolSchemas SCHEMAS = new ProtocolSchemas();

    private RunConfigurationContract() {}

    public static ObjectNode sealModelBinding(ObjectNode response) {
        var sealed = response.deepCopy();
        ((ObjectNode) sealed.required("identity")).put("configurationDigest", digest(sealed));
        validateModelBinding(sealed);
        return sealed;
    }

    public static void validateModelBinding(JsonNode response) {
        SCHEMAS.validate("runtime/ModelBindingResponse", response);
        if (!response.required("identity")
                .required("configurationDigest")
                .asText()
                .equals(digest(response)))
            throw new IllegalArgumentException("Invalid model binding identity");
        if (response.required("configuration").has("model"))
            ModelConfigurationContract.validate(
                    response.required("configuration").required("model"),
                    response.required("identity").required("harnessType").asText());
    }

    public static ObjectNode seal(ObjectNode response) {
        var sealed = response.deepCopy();
        ((ObjectNode) sealed.required("identity")).put("configurationDigest", digest(sealed));
        validate(sealed);
        return sealed;
    }

    public static void validate(JsonNode response) {
        SCHEMAS.validate("runtime/ConfigurationResponse", response);
        ModelConfigurationContract.validate(
                response.required("configuration").required("model"),
                response.required("identity").required("harnessType").asText());
        if (!response.required("identity")
                        .required("configurationDigest")
                        .asText()
                        .equals(digest(response))
                || !response.required("scope")
                        .equals(
                                response.required("configuration")
                                        .required("toolConfiguration")
                                        .required("scope")))
            throw new IllegalArgumentException("Invalid effective configuration identity");
    }

    private static String digest(JsonNode response) {
        ObjectNode content = response.deepCopy();
        ((ObjectNode) content.required("identity")).remove("configurationDigest");
        return CanonicalJson.hash(content);
    }
}

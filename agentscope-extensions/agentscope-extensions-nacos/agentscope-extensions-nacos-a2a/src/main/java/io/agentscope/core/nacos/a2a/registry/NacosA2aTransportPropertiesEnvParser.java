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

package io.agentscope.core.nacos.a2a.registry;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.agentscope.core.nacos.a2a.registry.constants.Constants;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parser for {@link NacosA2aRegistryTransportProperties} from Environment.
 *
 * <p>
 * The ENV of A2A transport properties is prefixed with {@link Constants#PROPERTIES_ENV_PREFIX}, and is appended with
 * {TRANSPORT} and {ATTRIBUTE}. Such as `NACOS_A2A_AGENT_JSONRPC_HOST=127.0.0.1`.
 *
 * <p>This parser will parse the environment variables which are prefixed with {@link Constants#PROPERTIES_ENV_PREFIX} and
 * convert them into {@link NacosA2aRegistryTransportProperties}.
 * These variables will be split by `_` excluding the prefix and the first part is the transport name, the second part is the
 * attribute name. If there are duplicate keys in the environment variables, the last one will be used.
 *
 * @see Constants.TransportPropertiesAttribute
 */
public class NacosA2aTransportPropertiesEnvParser {

    /**
     * Retrieves transport properties from environment variables and converts them to a map of
     * {@link NacosA2aRegistryTransportProperties}.
     *
     * <p>
     * This method scans all environment variables that start with the predefined prefix, parses them according to the
     * expected format, and converts them into NacosA2aRegistryTransportProperties objects. The environment variables
     * should follow the format PREFIX_TRANSPORT_ATTRIBUTE=value.
     * </p>
     *
     * @return a map where keys are transport names and values are corresponding
     *     NacosA2aRegistryTransportProperties objects
     */
    public Map<String, NacosA2aRegistryTransportProperties> getTransportProperties() {
        return doGetTransportProperties(System.getenv());
    }

    private Map<String, NacosA2aRegistryTransportProperties> doGetTransportProperties(
            Map<String, String> environment) {
        Set<String> transportPropertiesKeys =
                environment.keySet().stream()
                        .filter(key -> key.startsWith(Constants.PROPERTIES_ENV_PREFIX))
                        .collect(Collectors.toSet());
        Map<String, Map<Constants.TransportPropertiesAttribute, Object>> transportProperties =
                parse(transportPropertiesKeys, environment);
        Map<String, NacosA2aRegistryTransportProperties> result =
                new HashMap<>(transportProperties.size());
        transportProperties.forEach(
                (transport, properties) ->
                        result.put(transport, convertToProperties(transport, properties)));
        return result;
    }

    private Map<String, Map<Constants.TransportPropertiesAttribute, Object>> parse(
            Set<String> transportPropertiesKeys, Map<String, String> environment) {
        Map<String, Map<Constants.TransportPropertiesAttribute, Object>> result = new HashMap<>();
        transportPropertiesKeys.forEach(
                envKey -> {
                    String subKey = envKey.substring(Constants.PROPERTIES_ENV_PREFIX.length());
                    String[] transportAttrs = subKey.split("_");
                    if (transportAttrs.length != 2) {
                        return;
                    }
                    String transport = transportAttrs[0].trim().toUpperCase();
                    String attr = transportAttrs[1].trim().toUpperCase();
                    String value = environment.get(envKey);
                    Constants.TransportPropertiesAttribute attribute =
                            Constants.TransportPropertiesAttribute.getByEnvKey(attr);
                    if (null == attribute) {
                        return;
                    }
                    result.compute(
                            transport,
                            (key, oldValue) -> {
                                if (null == oldValue) {
                                    oldValue = new HashMap<>();
                                }
                                oldValue.put(attribute, value);
                                return oldValue;
                            });
                });
        return result;
    }

    private NacosA2aRegistryTransportProperties convertToProperties(
            String transport, Map<Constants.TransportPropertiesAttribute, Object> properties) {
        NacosA2aRegistryTransportProperties.Builder builder =
                NacosA2aRegistryTransportProperties.builder().transport(transport);
        properties.forEach(
                (key, value) -> {
                    switch (key) {
                        case HOST -> builder.host(value.toString());
                        case PORT -> builder.port(parseInt(transport, value.toString()));
                        case PATH -> builder.path(value.toString());
                        case PROTOCOL -> builder.protocol(value.toString());
                        case QUERY -> builder.query(value.toString());
                        case SUPPORT_TLS ->
                                builder.supportTls(Boolean.parseBoolean(value.toString()));
                    }
                });
        return builder.build();
    }

    private int parseInt(String transport, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new NacosRuntimeException(
                    NacosException.INVALID_PARAM,
                    String.format("Invalid `port` value for transport `%s`: %s", transport, value));
        }
    }
}

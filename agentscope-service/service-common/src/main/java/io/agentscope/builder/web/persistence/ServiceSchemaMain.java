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
package io.agentscope.builder.web.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Migration-only entry point run through the existing service jar's PropertiesLauncher. */
public final class ServiceSchemaMain {
    private ServiceSchemaMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("component", "url", "username", "password", "schema");
        for (String argument : args) {
            int equals = argument.indexOf('=');
            if (!argument.startsWith("--")
                    || equals < 3
                    || !allowed.contains(argument.substring(2, equals))) {
                throw new IllegalArgumentException(
                        "Migration arguments require --component, --url, --username, --password, or"
                                + " --schema followed by =value");
            }
            String key = argument.substring(2, equals);
            if (options.putIfAbsent(key, argument.substring(equals + 1)) != null) {
                throw new IllegalArgumentException("Duplicate migration option: " + key);
            }
        }
        String componentName = options.get("component");
        String component = migrationComponent(componentName);
        String prefix =
                switch (component) {
                    case "portal" -> "BUILDER_PORTAL_DB_";
                    case "managed" -> "BUILDER_MANAGED_DB_";
                    default -> "BUILDER_DB_";
                };
        String url = option(options, "url", prefix + "URL", "");
        if (url.isBlank()) {
            throw new IllegalArgumentException("Migration database URL is required");
        }
        String username = option(options, "username", prefix + "USER", "");
        String password = option(options, "password", prefix + "PASSWORD", "");
        String schema =
                options.getOrDefault(
                        "schema",
                        switch (component) {
                            case "portal" -> "portal";
                            case "managed" -> "ma";
                            default -> "dp";
                        });
        DriverManager.setLoginTimeout(30);
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            ServiceSchema.migrate(connection, component, schema);
        }
        System.out.println("Service schema migration complete: " + componentName);
    }

    // Ledger component IDs are persisted and deliberately independent of deployment names.
    static String migrationComponent(String component) {
        if (component == null) {
            throw new IllegalArgumentException("Migration component is required");
        }
        return switch (component) {
            case "portal" -> "portal";
            case "dataplane" -> "dataplane";
            case "managed" -> "managed";
            default ->
                    throw new IllegalArgumentException(
                            "--component must be portal, dataplane, or managed");
        };
    }

    private static String option(
            Map<String, String> options, String name, String environmentName, String fallback) {
        if (options.containsKey(name)) {
            return options.get(name);
        }
        return System.getenv().getOrDefault(environmentName, fallback);
    }
}

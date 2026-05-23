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
package io.agentscope.harness.claw.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Materialises the bundled {@code classpath:/workspace-template/**} resources into a target
 * directory on first run. Existing files are <em>never</em> overwritten — this is a
 * non-destructive bootstrap helper so users can edit configs and skills freely.
 *
 * <p>Uses Spring's {@link PathMatchingResourcePatternResolver} so it works identically in flat
 * classpath layouts (IDE, {@code mvn exec}) and Spring Boot fat JARs ({@code BOOT-INF/classes/}).
 */
public final class WorkspaceTemplate {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTemplate.class);

    /** Classpath root that holds the template tree shipped with this app. */
    public static final String TEMPLATE_ROOT = "workspace-template";

    private static final String CLASSPATH_PATTERN = "classpath*:/" + TEMPLATE_ROOT + "/**/*";

    private WorkspaceTemplate() {}

    /**
     * Copies {@code classpath:/workspace-template/**} into {@code targetDir}. Existing files are
     * preserved as-is; only missing files are written.
     *
     * @return number of files newly written.
     */
    public static int materialise(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(WorkspaceTemplate.class.getClassLoader());

        Resource[] resources;
        try {
            resources = resolver.getResources(CLASSPATH_PATTERN);
        } catch (IOException ioe) {
            log.warn("No workspace template resources found on classpath: {}", ioe.getMessage());
            return 0;
        }
        if (resources.length == 0) {
            log.warn(
                    "No classpath resources matched {} — skipping materialisation",
                    CLASSPATH_PATTERN);
            return 0;
        }

        String marker = "/" + TEMPLATE_ROOT + "/";
        int written = 0;
        for (Resource r : resources) {
            if (!r.isReadable()) continue;
            String url = r.getURL().toString();
            int idx = url.indexOf(marker);
            if (idx < 0) continue;
            String rel = url.substring(idx + marker.length());
            if (rel.isEmpty() || rel.endsWith("/")) continue;

            Path dest = targetDir.resolve(rel);
            if (Files.exists(dest)) continue;

            Files.createDirectories(dest.getParent());
            try (InputStream in = r.getInputStream()) {
                Files.copy(in, dest);
                written++;
            }
        }
        log.info(
                "Workspace template materialisation complete: {} new files written under {}",
                written,
                targetDir);
        return written;
    }
}

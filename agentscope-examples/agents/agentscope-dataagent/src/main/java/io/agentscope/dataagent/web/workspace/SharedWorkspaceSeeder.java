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

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Materialises the bundled {@code classpath:shared/**} tree (default skills, sub-agents, memory
 * snippets shipped with the DataAgent build) onto disk at {@code ${cwd}/shared/} the first time the
 * application starts.
 *
 * <p>Write semantics are <b>write-if-missing</b>: an admin-approved contribution that has already
 * been materialised by {@link io.agentscope.dataagent.web.marketplace.MarketContributionService} is
 * never clobbered by a restart, and the on-disk copy can be hand-edited by operators between
 * deployments. To force a refresh of a shipped file, delete it on disk and bounce the process.
 *
 * <p>Wiring: the seed target ({@code ${cwd}/shared/}) is the same path {@link UserSandboxRegistry}
 * mounts into every fresh container as a read-only workspace projection, and the same path
 * {@code LocalApprovalMarketplace} reads when listing/fetching contributed skills. Seeding here
 * therefore makes every tenant see the bundled {@code sql-analysis} / {@code chart-rendering}
 * skills and {@code data-explorer} / {@code report-writer} sub-agents out of the box without any
 * per-tenant copy step.
 */
@Component
public class SharedWorkspaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(SharedWorkspaceSeeder.class);
    private static final String CLASSPATH_PREFIX = "shared/";

    private final DataAgentBootstrap bootstrap;
    private final ResourcePatternResolver resolver;

    public SharedWorkspaceSeeder(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    void seed() {
        Path target = bootstrap.cwd().resolve("shared").normalize();
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            log.warn("SharedWorkspaceSeeder: failed to create {}: {}", target, e.getMessage());
            return;
        }

        int copied = 0;
        int skipped = 0;
        try {
            List<LoadedFile> files = loadClasspathFiles();
            for (LoadedFile lf : files) {
                Path dest = target.resolve(lf.relPath());
                if (Files.exists(dest)) {
                    skipped++;
                    continue;
                }
                Path parent = dest.getParent();
                if (parent != null) Files.createDirectories(parent);
                writeAtomic(dest, lf.content());
                copied++;
            }
        } catch (IOException e) {
            log.warn("SharedWorkspaceSeeder: scan/copy failed: {}", e.getMessage());
        }
        log.info(
                "SharedWorkspaceSeeder: target={}, copied={}, kept-existing={}",
                target,
                copied,
                skipped);
    }

    /**
     * Eagerly enumerates every regular file under {@code classpath*:shared/**}, regardless of
     * whether the classpath element is an exploded directory (development) or a JAR (production
     * fat-jar). Returns paths relative to {@code shared/}.
     */
    private List<LoadedFile> loadClasspathFiles() throws IOException {
        Resource[] resources = resolver.getResources("classpath*:" + CLASSPATH_PREFIX + "**/*");
        java.util.List<LoadedFile> out = new java.util.ArrayList<>();
        for (Resource r : resources) {
            if (!r.isReadable()) continue;
            URI uri;
            try {
                uri = r.getURI();
            } catch (IOException e) {
                continue;
            }
            String scheme = uri.getScheme();
            String rel = extractRelative(uri.toString());
            if (rel == null || rel.isEmpty() || rel.endsWith("/")) continue;

            if ("file".equals(scheme)) {
                Path p = Paths.get(uri);
                if (Files.isDirectory(p)) continue;
                out.add(new LoadedFile(rel, Files.readAllBytes(p)));
            } else if ("jar".equals(scheme)) {
                String s = uri.toString();
                int bang = s.indexOf("!/");
                if (bang < 0) continue;
                URI jarFile = URI.create(s.substring(0, bang + 2));
                String inJarPath = s.substring(bang + 2);
                try (FileSystem fs = openOrCreateJarFs(jarFile)) {
                    Path p = fs.getPath(inJarPath);
                    if (!Files.isRegularFile(p)) continue;
                    out.add(new LoadedFile(rel, Files.readAllBytes(p)));
                }
            } else {
                try (InputStream in = r.getInputStream()) {
                    out.add(new LoadedFile(rel, in.readAllBytes()));
                }
            }
        }

        // Some classpath roots (notably Spring Boot devtools layouts) don't surface intermediate
        // directories via the "**/*" glob; the per-file walk above is the authoritative source.
        // Filter out anything that did not start with the shared/ prefix as a safety net.
        out.removeIf(lf -> lf.relPath().isEmpty());
        return out;
    }

    /**
     * Extracts the path component after {@code /shared/} from a classpath resource URI. Returns
     * {@code null} when the URI does not contain the expected prefix.
     */
    private static String extractRelative(String uriString) {
        int idx = uriString.indexOf("/" + CLASSPATH_PREFIX);
        if (idx < 0) {
            // Might be `shared/...` without leading slash (rare with classpath* roots).
            idx = uriString.indexOf(CLASSPATH_PREFIX);
            if (idx < 0) return null;
            return uriString.substring(idx + CLASSPATH_PREFIX.length());
        }
        return uriString.substring(idx + CLASSPATH_PREFIX.length() + 1);
    }

    private static FileSystem openOrCreateJarFs(URI jarFile) throws IOException {
        try {
            return FileSystems.getFileSystem(jarFile);
        } catch (Exception ignore) {
            return FileSystems.newFileSystem(jarFile, Map.of());
        }
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record LoadedFile(String relPath, byte[] content) {}
}

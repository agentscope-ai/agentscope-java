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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes file operations to different {@link AbstractFilesystem} stores by path prefix.
 *
 * <p>Paths are matched against route prefixes (longest first). Unmatched paths fall through to the
 * default store.
 *
 * <p>Composite deliberately implements only {@link AbstractFilesystem} — it is the unified,
 * non-sandbox view that blends a local workspace with remote-store-backed paths. Shell execution
 * is intentionally not supported in this mode: routing shell commands across stores is
 * ambiguous, and the primary use case (distributed memory with per-user/session isolation) does
 * not need it. If you need shell execution, use a sandbox-backed filesystem
 * ({@link AbstractSandboxFilesystem}) or {@link LocalFilesystemWithShell} directly instead.
 *
 * <p>Example:
 *
 * <pre>{@code
 * CompositeFilesystem fs = new CompositeFilesystem(
 *     localFs,
 *     Map.of("/memories/", storeFs, "/cache/", inMemoryFs)
 * );
 * fs.read(RuntimeContext.empty(), "/memories/notes.md", 0, 100);  // → storeFs.read(...)
 * fs.read(RuntimeContext.empty(), "/src/Main.java", 0, 100);      // → localFs.read(...)
 * }</pre>
 */
public class CompositeFilesystem implements AbstractFilesystem {

    private final AbstractFilesystem defaultBackend;
    private final List<RouteEntry> sortedRoutes;

    /**
     * Creates a composite filesystem with a default backend and prefix-based routes.
     *
     * @param defaultBackend backend for paths that don't match any route
     * @param routes map of path prefixes to stores; prefixes must start with {@code "/"}
     *     and should end with {@code "/"} (e.g. {@code "/memories/"})
     */
    public CompositeFilesystem(
            AbstractFilesystem defaultBackend, Map<String, AbstractFilesystem> routes) {
        if (defaultBackend == null) {
            throw new IllegalArgumentException("defaultBackend must not be null");
        }
        this.defaultBackend = defaultBackend;

        List<RouteEntry> entries = new ArrayList<>();
        if (routes != null) {
            for (Map.Entry<String, AbstractFilesystem> e : routes.entrySet()) {
                entries.add(new RouteEntry(e.getKey(), e.getValue()));
            }
        }
        entries.sort(Comparator.comparingInt((RouteEntry e) -> e.prefix().length()).reversed());
        this.sortedRoutes = List.copyOf(entries);
    }

    // ==================== Routing ====================

    private record RouteEntry(String prefix, AbstractFilesystem backend) {}

    private record RouteResult(
            AbstractFilesystem backend, String backendPath, String routePrefix) {}

    private RouteResult routeForPath(String path) {
        // Canonicalize both sides by stripping any leading slash before matching so callers can
        // pass either "/skills/foo" (the {@link AbstractFilesystem} contract) or "skills/foo"
        // (the convention used by {@code WorkspaceManager.writeUtf8WorkspaceRelative}) and route
        // to the same store. The original {@code entry.prefix()} is preserved in the
        // {@link RouteResult} so path remapping output stays in the prefix's stored form.
        String matchPath = stripLeadingSlash(path);
        for (RouteEntry entry : sortedRoutes) {
            String canonicalPrefix = stripLeadingSlash(entry.prefix());
            String prefixNoSlash =
                    canonicalPrefix.endsWith("/")
                            ? canonicalPrefix.substring(0, canonicalPrefix.length() - 1)
                            : canonicalPrefix;
            if (matchPath.equals(prefixNoSlash)) {
                if (canonicalPrefix.endsWith("/")) {
                    return new RouteResult(entry.backend(), "/", entry.prefix());
                }
                String backendPath = path.startsWith("/") ? path : "/" + path;
                return new RouteResult(entry.backend(), backendPath, entry.prefix());
            }
            String normalizedPrefix =
                    canonicalPrefix.endsWith("/") ? canonicalPrefix : canonicalPrefix + "/";
            if (matchPath.startsWith(normalizedPrefix)) {
                String suffix = matchPath.substring(normalizedPrefix.length());
                String backendPath = suffix.isEmpty() ? "/" : "/" + suffix;
                return new RouteResult(entry.backend(), backendPath, entry.prefix());
            }
        }
        return new RouteResult(defaultBackend, path, null);
    }

    private static String stripLeadingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return s.substring(i);
    }

    // ==================== Path remapping helpers ====================

    private static String prependRoute(String routePrefix, String backendPath) {
        // Exact-file route: the backend holds one logical key, so the externally visible path
        // is the route prefix itself — appending the backend's "/<file>" would yield
        // "AGENTS.md/AGENTS.md".
        if (!routePrefix.endsWith("/")) {
            return routePrefix;
        }
        String base = routePrefix.substring(0, routePrefix.length() - 1);
        return base + backendPath;
    }

    private static FileInfo remapFileInfo(FileInfo fi, String routePrefix) {
        return new FileInfo(
                prependRoute(routePrefix, fi.path()), fi.isDirectory(), fi.size(), fi.modifiedAt());
    }

    private static GrepMatch remapGrepMatch(GrepMatch m, String routePrefix) {
        return new GrepMatch(prependRoute(routePrefix, m.path()), m.line(), m.text());
    }

    private static String stripRouteFromPattern(String pattern, String routePrefix) {
        String barePattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        String barePrefix = routePrefix.replaceAll("^/|/$", "") + "/";
        if (barePattern.startsWith(barePrefix)) {
            return barePattern.substring(barePrefix.length());
        }
        return pattern;
    }

    // ==================== AbstractFilesystem ====================

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        RouteResult route = routeForPath(path);

        if (route.routePrefix() != null) {
            LsResult result = route.backend().ls(runtimeContext, route.backendPath());
            if (!result.isSuccess()) {
                return result;
            }
            List<FileInfo> remapped = new ArrayList<>();
            for (FileInfo fi : result.entries()) {
                remapped.add(remapFileInfo(fi, route.routePrefix()));
            }
            return LsResult.success(remapped);
        }

        if ("/".equals(path) || ".".equals(path)) {
            List<FileInfo> results = new ArrayList<>();
            LsResult defaultResult = defaultBackend.ls(runtimeContext, "/");
            if (defaultResult.isSuccess() && defaultResult.entries() != null) {
                results.addAll(defaultResult.entries());
            }
            for (RouteEntry entry : sortedRoutes) {
                if (entry.prefix().endsWith("/")) {
                    results.add(FileInfo.ofDir(entry.prefix(), ""));
                    continue;
                }
                // Exact-file route: surface as a file entry iff the backend has it.
                String exactPath = "/" + entry.prefix();
                if (entry.backend().exists(runtimeContext, exactPath)) {
                    results.add(FileInfo.ofFile(entry.prefix(), 0L, ""));
                }
            }
            results.sort(Comparator.comparing(FileInfo::path));
            return LsResult.success(results);
        }

        return defaultBackend.ls(runtimeContext, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        RouteResult route = routeForPath(filePath);
        return route.backend().read(runtimeContext, route.backendPath(), offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        RouteResult route = routeForPath(filePath);
        WriteResult result = route.backend().write(runtimeContext, route.backendPath(), content);
        if (result.isSuccess() && route.routePrefix() != null) {
            return WriteResult.ok(filePath);
        }
        return result;
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        RouteResult route = routeForPath(filePath);
        EditResult result =
                route.backend()
                        .edit(
                                runtimeContext,
                                route.backendPath(),
                                oldString,
                                newString,
                                replaceAll);
        if (result.isSuccess() && route.routePrefix() != null) {
            return EditResult.ok(filePath, result.occurrences());
        }
        return result;
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        if (path != null) {
            RouteResult route = routeForPath(path);
            if (route.routePrefix() != null) {
                GrepResult result =
                        route.backend().grep(runtimeContext, pattern, route.backendPath(), glob);
                if (!result.isSuccess()) {
                    return result;
                }
                List<GrepMatch> remapped = new ArrayList<>();
                for (GrepMatch m : result.matches()) {
                    remapped.add(remapGrepMatch(m, route.routePrefix()));
                }
                return GrepResult.success(remapped);
            }
        }

        if (path == null || "/".equals(path) || ".".equals(path)) {
            List<GrepMatch> allMatches = new ArrayList<>();
            GrepResult defaultResult = defaultBackend.grep(runtimeContext, pattern, path, glob);
            if (!defaultResult.isSuccess()) {
                return defaultResult;
            }
            if (defaultResult.matches() != null) {
                allMatches.addAll(defaultResult.matches());
            }
            for (RouteEntry entry : sortedRoutes) {
                if (!entry.prefix().endsWith("/")) {
                    // Exact-file route: scope grep to the single addressable file so we don't
                    // scan unrelated keys in the same backend namespace.
                    String exactPath = "/" + entry.prefix();
                    if (!entry.backend().exists(runtimeContext, exactPath)) {
                        continue;
                    }
                    GrepResult routeResult =
                            entry.backend().grep(runtimeContext, pattern, exactPath, glob);
                    if (!routeResult.isSuccess()) {
                        return routeResult;
                    }
                    if (routeResult.matches() != null) {
                        for (GrepMatch m : routeResult.matches()) {
                            allMatches.add(remapGrepMatch(m, entry.prefix()));
                        }
                    }
                    continue;
                }
                GrepResult routeResult = entry.backend().grep(runtimeContext, pattern, "/", glob);
                if (!routeResult.isSuccess()) {
                    return routeResult;
                }
                if (routeResult.matches() != null) {
                    for (GrepMatch m : routeResult.matches()) {
                        allMatches.add(remapGrepMatch(m, entry.prefix()));
                    }
                }
            }
            return GrepResult.success(allMatches);
        }

        return defaultBackend.grep(runtimeContext, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        RouteResult route = routeForPath(path);

        if (route.routePrefix() != null) {
            GlobResult result = route.backend().glob(runtimeContext, pattern, route.backendPath());
            if (!result.isSuccess()) {
                return result;
            }
            List<FileInfo> remapped = new ArrayList<>();
            for (FileInfo fi : result.matches()) {
                remapped.add(remapFileInfo(fi, route.routePrefix()));
            }
            return GlobResult.success(remapped);
        }

        // Non-root path that didn't match any route: delegate to default backend only.
        // Route scanning only makes sense for root-level recursive globs.
        if (path != null && !"/".equals(path) && !".".equals(path)) {
            return defaultBackend.glob(runtimeContext, pattern, path);
        }

        List<FileInfo> results = new ArrayList<>();
        GlobResult defaultResult = defaultBackend.glob(runtimeContext, pattern, path);
        if (defaultResult.isSuccess() && defaultResult.matches() != null) {
            results.addAll(defaultResult.matches());
        }
        String effectivePattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        PathMatcher fileMatcher =
                FileSystems.getDefault().getPathMatcher("glob:" + effectivePattern);
        // Java NIO's PathMatcher requires at least one separator for patterns like `**/*`, so
        // root-level files (e.g. AGENTS.md, MEMORY.md, tools.json — registered as exact-file
        // routes with no separator in their prefix) never satisfy the recursive matcher alone.
        // Mirror what {@link LocalFilesystem#glob} does: build a "direct" matcher by stripping
        // the leading `**/` so depth-1 entries also match. A pattern that already lacks the
        // recursive prefix falls through unchanged.
        String directExpr;
        if (effectivePattern.startsWith("**/")) {
            directExpr = effectivePattern.substring(3);
        } else if (effectivePattern.equals("**")) {
            directExpr = "*";
        } else {
            directExpr = effectivePattern;
        }
        PathMatcher directFileMatcher =
                FileSystems.getDefault().getPathMatcher("glob:" + directExpr);
        for (RouteEntry entry : sortedRoutes) {
            if (!entry.prefix().endsWith("/")) {
                // Exact-file route: check existence AND match against the glob pattern so
                // a glob("*.json", "/") does not surface AGENTS.md or other non-matching files.
                Path entryPath = Path.of(entry.prefix());
                if (!fileMatcher.matches(entryPath) && !directFileMatcher.matches(entryPath)) {
                    continue;
                }
                String exactPath = "/" + entry.prefix();
                if (entry.backend().exists(runtimeContext, exactPath)) {
                    results.add(FileInfo.ofFile(entry.prefix(), 0L, ""));
                }
                continue;
            }
            String routePattern = stripRouteFromPattern(pattern, entry.prefix());
            GlobResult routeResult = entry.backend().glob(runtimeContext, routePattern, "/");
            if (routeResult.isSuccess() && routeResult.matches() != null) {
                for (FileInfo fi : routeResult.matches()) {
                    results.add(remapFileInfo(fi, entry.prefix()));
                }
            }
        }
        results.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(results);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        FileUploadResponse[] results = new FileUploadResponse[files.size()];
        Map<AbstractFilesystem, List<IndexedFile>> batches = new HashMap<>();

        for (int i = 0; i < files.size(); i++) {
            Map.Entry<String, byte[]> file = files.get(i);
            RouteResult route = routeForPath(file.getKey());
            batches.computeIfAbsent(route.backend(), k -> new ArrayList<>())
                    .add(new IndexedFile(i, file.getKey(), route.backendPath(), file.getValue()));
        }

        for (Map.Entry<AbstractFilesystem, List<IndexedFile>> batch : batches.entrySet()) {
            List<Map.Entry<String, byte[]>> batchFiles = new ArrayList<>();
            for (IndexedFile f : batch.getValue()) {
                batchFiles.add(Map.entry(f.backendPath(), f.content()));
            }
            List<FileUploadResponse> responses =
                    batch.getKey().uploadFiles(runtimeContext, batchFiles);
            List<IndexedFile> indexed = batch.getValue();
            for (int i = 0; i < responses.size() && i < indexed.size(); i++) {
                FileUploadResponse backendResp = responses.get(i);
                String originalPath = indexed.get(i).originalPath();
                results[indexed.get(i).originalIndex()] =
                        backendResp.isSuccess()
                                ? FileUploadResponse.success(originalPath)
                                : FileUploadResponse.fail(originalPath, backendResp.error());
            }
        }

        return List.of(results);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        FileDownloadResponse[] results = new FileDownloadResponse[paths.size()];
        Map<AbstractFilesystem, List<int[]>> batches = new HashMap<>();
        Map<AbstractFilesystem, List<String>> batchPaths = new HashMap<>();

        for (int i = 0; i < paths.size(); i++) {
            RouteResult route = routeForPath(paths.get(i));
            batches.computeIfAbsent(route.backend(), k -> new ArrayList<>()).add(new int[] {i});
            batchPaths
                    .computeIfAbsent(route.backend(), k -> new ArrayList<>())
                    .add(route.backendPath());
        }

        for (Map.Entry<AbstractFilesystem, List<String>> batch : batchPaths.entrySet()) {
            List<FileDownloadResponse> responses =
                    batch.getKey().downloadFiles(runtimeContext, batch.getValue());
            List<int[]> indices = batches.get(batch.getKey());
            for (int i = 0; i < responses.size() && i < indices.size(); i++) {
                FileDownloadResponse resp = responses.get(i);
                int origIdx = indices.get(i)[0];
                results[origIdx] =
                        resp.error() != null
                                ? FileDownloadResponse.fail(paths.get(origIdx), resp.error())
                                : FileDownloadResponse.success(paths.get(origIdx), resp.content());
            }
        }

        return List.of(results);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        RouteResult route = routeForPath(path);
        WriteResult result = route.backend().delete(runtimeContext, route.backendPath());
        if (result.isSuccess() && route.routePrefix() != null) {
            return WriteResult.ok(path);
        }
        return result;
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        RouteResult srcRoute = routeForPath(fromPath);
        RouteResult dstRoute = routeForPath(toPath);

        if (srcRoute.backend() == dstRoute.backend()) {
            WriteResult result =
                    srcRoute.backend()
                            .move(runtimeContext, srcRoute.backendPath(), dstRoute.backendPath());
            if (result.isSuccess()) {
                return WriteResult.ok(toPath);
            }
            return result;
        }

        // Cross-backend move: read → write → delete
        var readResult = srcRoute.backend().read(runtimeContext, srcRoute.backendPath(), 0, 0);
        if (!readResult.isSuccess() || readResult.fileData() == null) {
            return WriteResult.fail("Cannot read source for cross-backend move: " + fromPath);
        }
        String content = readResult.fileData().content();
        if (content == null) {
            content = "";
        }
        WriteResult writeResult =
                dstRoute.backend().write(runtimeContext, dstRoute.backendPath(), content);
        if (!writeResult.isSuccess()) {
            return WriteResult.fail(
                    "Cross-backend move write failed for '" + toPath + "': " + writeResult.error());
        }
        srcRoute.backend().delete(runtimeContext, srcRoute.backendPath());
        return WriteResult.ok(toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        RouteResult route = routeForPath(path);
        return route.backend().exists(runtimeContext, route.backendPath());
    }

    /** Returns the default store. */
    public AbstractFilesystem getDefaultBackend() {
        return defaultBackend;
    }

    private record IndexedFile(
            int originalIndex, String originalPath, String backendPath, byte[] content) {}
}

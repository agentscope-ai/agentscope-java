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
package io.agentscope.builder.web.scaffold;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Materializes the default builder agent's workspace folder by copying classpath resources from
 * {@code scaffold/default/} into the target directory with write-if-missing semantics.
 *
 * <p>Layout produced (all under the supplied {@code workspace} root):
 *
 * <ul>
 *   <li>{@code AGENTS.md} — generated from {@code scaffold/default/AGENTS.md.template} with the
 *       supplied {@code displayName} / {@code sysPrompt} substituted into the {@code {{NAME}}} and
 *       {@code {{SYSPROMPT}}} placeholders.
 *   <li>{@code tools.json}, {@code skills/example-skill/SKILL.md}, {@code subagents/README.md} —
 *       copied verbatim from the corresponding resource under {@code scaffold/default/}.
 *   <li>{@code skills/}, {@code subagents/}, {@code memory/} — empty directories (so the UI's file
 *       tree always shows the standard slots even before a user adds anything).
 *   <li>{@code memory/.gitkeep} — empty marker file so the {@code memory/} folder survives a git
 *       clone of the workspace.
 * </ul>
 *
 * <p>Everything beyond the AGENTS.md placeholders lives as on-disk resources, not Java string
 * literals — operators who want to customise the default starter content edit the resource files
 * under {@code src/main/resources/scaffold/default/} rather than touching Java code. Existing
 * files in the destination workspace are never overwritten, so calling this on a populated
 * workspace is a no-op for whatever is already there.
 *
 * <p>For user-selected starter packs (e.g. a "research-assistant" or "customer-support" template)
 * see {@link io.agentscope.builder.web.template.TemplateRegistry} instead; that is the opt-in
 * pathway exposed in the UI and is separate from this auto-scaffolder.
 */
public final class WorkspaceScaffolder {

    private static final String RESOURCE_ROOT = "scaffold/default";

    /**
     * Files copied verbatim from {@code scaffold/default/<rel>} into {@code workspace/<rel>}.
     * Keep this list narrow — anything you add here ships in every fresh workspace, so prefer the
     * opt-in {@link io.agentscope.builder.web.template.TemplateRegistry} for richer starter packs.
     */
    private static final List<String> VERBATIM_RESOURCES =
            List.of("tools.json", "skills/example-skill/SKILL.md", "subagents/README.md");

    private WorkspaceScaffolder() {}

    /**
     * Materializes the workspace folder for an agent. Safe to call repeatedly: only files that do
     * not already exist are created.
     *
     * @param workspace target workspace directory (will be created if missing)
     * @param displayName human-readable agent name substituted into the AGENTS.md heading
     * @param sysPrompt optional system-prompt body substituted into AGENTS.md (may be {@code null})
     */
    public static void scaffold(Path workspace, String displayName, String sysPrompt)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        writeIfMissing(workspace.resolve("AGENTS.md"), renderAgentsMd(displayName, sysPrompt));
        for (String rel : VERBATIM_RESOURCES) {
            writeIfMissing(workspace.resolve(rel), readResource(RESOURCE_ROOT + "/" + rel));
        }
        // Empty file; carries no content so we don't bother adding it to the resource tree —
        // a {@code .gitkeep} only needs to exist for {@code git} to track the otherwise-empty
        // {@code memory/} directory.
        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    /**
     * Loads the AGENTS.md template resource and substitutes the supplied agent identity into the
     * {@code {{NAME}}} / {@code {{SYSPROMPT}}} placeholders. Falls back to {@code "agent"} and
     * {@code "You are a helpful assistant."} when either input is blank, so the generated file is
     * always well-formed even for callers that don't supply customisation.
     */
    private static String renderAgentsMd(String displayName, String sysPrompt) throws IOException {
        String name = (displayName == null || displayName.isBlank()) ? "agent" : displayName;
        String prompt =
                (sysPrompt == null || sysPrompt.isBlank())
                        ? "You are a helpful assistant."
                        : sysPrompt.trim();
        String template = readResource(RESOURCE_ROOT + "/AGENTS.md.template");
        return template.replace("{{NAME}}", name).replace("{{SYSPROMPT}}", prompt);
    }

    /**
     * Loads a UTF-8 classpath resource as a string. Throws {@link IOException} if the resource is
     * missing so a packaging mistake (renamed or excluded resource) surfaces loudly at startup
     * instead of silently producing an empty workspace.
     */
    private static String readResource(String classpathPath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = WorkspaceScaffolder.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException(
                        "Missing scaffold resource on classpath: '"
                                + classpathPath
                                + "' (expected under src/main/resources/"
                                + classpathPath
                                + ")");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}

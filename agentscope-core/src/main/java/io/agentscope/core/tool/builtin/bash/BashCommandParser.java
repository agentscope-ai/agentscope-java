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
package io.agentscope.core.tool.builtin.bash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterBash;

/**
 * Parses Bash command strings via tree-sitter and extracts structural information used by the
 * {@code Bash} tool's permission checks.
 *
 * <p>The parser is stateful only insofar as it owns a {@link TSParser} bound to the Bash grammar;
 * instances are safe to reuse from a single thread. Methods that produce {@link TSTree} objects
 * release them before returning, so callers do not need to manage native resources.
 *
 * <p>Methods are deliberately defensive: malformed input (unbalanced quotes, partial heredocs)
 * returns a best-effort answer rather than throwing.
 */
public final class BashCommandParser {

    private static final Pattern WORD_BOUNDARY_SPLIT = Pattern.compile("\\s+");

    private final TSParser parser;

    public BashCommandParser() {
        this.parser = new TSParser();
        this.parser.setLanguage(new TreeSitterBash());
    }

    /** True when every command in a compound is read-only by the heuristics in this class. */
    public boolean isReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (String single : splitCompoundCommand(command)) {
            if (!isSingleCommandReadOnly(single)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSingleCommandReadOnly(String singleCommand) {
        String trimmed = singleCommand.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        // Output redirects (>, >>) write to disk and must not bypass the dangerous-path
        // check that runs later in the Bash permission pipeline.
        if (hasOutputRedirect(trimmed)) {
            return false;
        }
        for (String pipelinePart : splitPipeline(trimmed)) {
            String firstToken = firstToken(pipelinePart.trim());
            if (firstToken == null) {
                return false;
            }
            if (firstToken.equals("git")) {
                String subcommand = secondToken(pipelinePart.trim());
                if (subcommand == null
                        || !BashConstants.GIT_READ_ONLY_COMMANDS.contains(subcommand)) {
                    return false;
                }
                continue;
            }
            if (!BashConstants.SAFE_COMMANDS.contains(firstToken)) {
                return false;
            }
            if (firstToken.equals("sed")) {
                if (pipelinePart.contains(" -i") || pipelinePart.contains(" --in-place")) {
                    return false;
                }
            }
            if (firstToken.equals("find") && pipelinePart.contains("-exec")) {
                return false;
            }
        }
        return true;
    }

    /** Extracts {@code (command_name, file_path)} pairs from {@code command} positional arguments. */
    public List<FilePathRef> extractFilePaths(String command) {
        List<FilePathRef> refs = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return refs;
        }
        TSTree tree = parseTree(command);
        try {
            visit(
                    tree.getRootNode(),
                    command,
                    node -> {
                        String type = node.getType();
                        if ("command".equals(type)) {
                            String name = firstCommandName(node, command);
                            if (name == null) {
                                return;
                            }
                            for (int i = 0; i < node.getChildCount(); i++) {
                                TSNode child = node.getChild(i);
                                String childType = child.getType();
                                if (!"word".equals(childType)
                                        && !"raw_string".equals(childType)
                                        && !"string".equals(childType)) {
                                    continue;
                                }
                                String text = textOf(child, command);
                                if (text.equals(name)) {
                                    continue;
                                }
                                if (text.startsWith("-")) {
                                    continue;
                                }
                                refs.add(new FilePathRef(name, stripQuotes(text)));
                            }
                        }
                    });
            // Redirected files also count as file paths.
            for (String redirect : extractRedirections(command)) {
                refs.add(new FilePathRef("redirect", redirect));
            }
        } finally {
            // TSTree resources are released by cleaner; nothing explicit to do.
        }
        return refs;
    }

    /** Lists redirection targets: {@code >}, {@code >>}, and {@code <} destinations. */
    public List<String> extractRedirections(String command) {
        List<String> out = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return out;
        }
        TSTree tree = parseTree(command);
        visit(
                tree.getRootNode(),
                command,
                node -> {
                    String type = node.getType();
                    if (!"file_redirect".equals(type) && !"heredoc_redirect".equals(type)) {
                        return;
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        TSNode child = node.getChild(i);
                        String childType = child.getType();
                        if ("word".equals(childType)
                                || "raw_string".equals(childType)
                                || "string".equals(childType)) {
                            out.add(stripQuotes(textOf(child, command)));
                        }
                    }
                });
        return out;
    }

    /**
     * Returns up to {@code maxPrefixes} command prefixes (broadest first), e.g.
     * {@code "git commit -m x"} yields {@code ["git commit -m", "git commit", "git"]}.
     */
    public List<String> extractCommandPrefixes(String command, int maxPrefixes) {
        if (maxPrefixes <= 0 || command == null || command.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String single : splitCompoundCommand(command)) {
            for (String piece : splitPipeline(single)) {
                String trimmed = piece.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                List<String> tokens = tokenize(trimmed);
                int upTo = Math.min(tokens.size(), maxPrefixes);
                for (int i = upTo; i >= 1; i--) {
                    seen.add(String.join(" ", tokens.subList(0, i)));
                }
            }
        }
        return List.copyOf(seen);
    }

    /** Splits on top-level {@code ;}, {@code &&}, {@code ||} (but not {@code |}). */
    public List<String> splitCompoundCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (!inDouble && c == '\'' && (i == 0 || command.charAt(i - 1) != '\\')) {
                inSingle = !inSingle;
            } else if (!inSingle && c == '"' && (i == 0 || command.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            }
            if (!inSingle && !inDouble) {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth = Math.max(0, depth - 1);
                }
                if (depth == 0 && c == ';') {
                    out.add(buf.toString());
                    buf.setLength(0);
                    continue;
                }
                if (depth == 0
                        && i + 1 < command.length()
                        && (c == '&' && command.charAt(i + 1) == '&'
                                || c == '|' && command.charAt(i + 1) == '|')) {
                    out.add(buf.toString());
                    buf.setLength(0);
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    /**
     * Returns the dangerous pattern that matched the command text, or {@code null} if no pattern
     * matched. Single-word patterns are matched on word boundaries; multi-word patterns by
     * substring.
     */
    public String checkDangerousCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String pattern : BashConstants.DANGEROUS_COMMANDS) {
            String patternLower = pattern.toLowerCase(Locale.ROOT);
            if (patternLower.contains("|")) {
                // Pipe patterns like "curl | sh" must tolerate arbitrary intermediate text
                // (URLs, flags) between the two sides of the pipe.
                String[] sides = patternLower.split("\\|", 2);
                String left = sides[0].trim();
                String right = sides[1].trim();
                String regex =
                        "(^|[^A-Za-z0-9_])"
                                + Pattern.quote(left)
                                + "[^|]*\\|\\s*"
                                + Pattern.quote(right)
                                + "($|[^A-Za-z0-9_])";
                if (Pattern.compile(regex).matcher(lower).find()) {
                    return pattern;
                }
            } else if (pattern.contains(" ")) {
                if (lower.contains(patternLower)) {
                    return pattern;
                }
            } else {
                Pattern p =
                        Pattern.compile(
                                "(^|[^A-Za-z0-9_])"
                                        + Pattern.quote(patternLower)
                                        + "($|[^A-Za-z0-9_])");
                if (p.matcher(lower).find()) {
                    return pattern;
                }
            }
        }
        return null;
    }

    /**
     * Rejects sed in-place edits targeting any of {@code dangerousFiles}. Returns an error message
     * or {@code null}.
     */
    public String checkSedConstraints(String command, List<String> dangerousFiles) {
        if (command == null || command.isBlank()) {
            return null;
        }
        TSTree tree = parseTree(command);
        boolean[] rejected = {false};
        String[] reason = {null};
        visit(
                tree.getRootNode(),
                command,
                node -> {
                    if (rejected[0]) {
                        return;
                    }
                    if (!"command".equals(node.getType())) {
                        return;
                    }
                    String name = firstCommandName(node, command);
                    if (!"sed".equals(name)) {
                        return;
                    }
                    boolean inPlace = false;
                    List<String> targets = new ArrayList<>();
                    for (int i = 0; i < node.getChildCount(); i++) {
                        TSNode child = node.getChild(i);
                        String childType = child.getType();
                        if (!"word".equals(childType)
                                && !"raw_string".equals(childType)
                                && !"string".equals(childType)) {
                            continue;
                        }
                        String text = stripQuotes(textOf(child, command));
                        if (text.equals(name)) {
                            continue;
                        }
                        if (text.equals("-i")
                                || text.equals("--in-place")
                                || text.startsWith("-i")) {
                            inPlace = true;
                        } else if (!text.startsWith("-")) {
                            targets.add(text);
                        }
                    }
                    if (!inPlace) {
                        return;
                    }
                    for (String target : targets) {
                        String fileName = simpleFileName(target);
                        for (String dangerous : dangerousFiles) {
                            if (fileName.equalsIgnoreCase(dangerous)
                                    || target.toLowerCase(Locale.ROOT)
                                            .endsWith(dangerous.toLowerCase(Locale.ROOT))) {
                                rejected[0] = true;
                                reason[0] = "sed in-place edit of sensitive file: " + target;
                                return;
                            }
                        }
                    }
                });
        return reason[0];
    }

    /**
     * Walks the AST and returns an explanation string when a dangerous node type is encountered.
     * Returns {@code null} when the command contains no such constructs.
     */
    public String checkInjectionRisk(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        TSTree tree = parseTree(command);
        String[] found = {null};
        visit(
                tree.getRootNode(),
                command,
                node -> {
                    if (found[0] != null) {
                        return;
                    }
                    String type = node.getType();
                    if (!BashConstants.DANGEROUS_NODE_TYPES.contains(type)) {
                        return;
                    }
                    if ("command_substitution".equals(type)
                            || "process_substitution".equals(type)) {
                        found[0] = "command uses dynamic substitution (" + type + ")";
                    } else {
                        found[0] = "command contains control-flow construct (" + type + ")";
                    }
                });
        return found[0];
    }

    /** A {@code command_name → argument} pair extracted from the AST. */
    public record FilePathRef(String commandName, String filePath) {}

    private boolean hasOutputRedirect(String command) {
        TSTree tree = parseTree(command);
        boolean[] found = {false};
        visit(
                tree.getRootNode(),
                command,
                node -> {
                    if (found[0]) {
                        return;
                    }
                    if (!"file_redirect".equals(node.getType())
                            && !"heredoc_redirect".equals(node.getType())) {
                        return;
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        TSNode child = node.getChild(i);
                        String text = textOf(child, command);
                        if (text.equals(">") || text.equals(">>") || text.matches("\\d+>>?")) {
                            found[0] = true;
                            return;
                        }
                    }
                });
        return found[0];
    }

    private TSTree parseTree(String command) {
        return parser.parseString(null, command);
    }

    private static void visit(
            TSNode node, String src, java.util.function.Consumer<TSNode> visitor) {
        visitor.accept(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            visit(node.getChild(i), src, visitor);
        }
    }

    private static String textOf(TSNode node, String src) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        byte[] bytes = src.getBytes(StandardCharsets.UTF_8);
        if (start < 0 || end > bytes.length || start > end) {
            return "";
        }
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    private static String firstCommandName(TSNode commandNode, String src) {
        for (int i = 0; i < commandNode.getChildCount(); i++) {
            TSNode child = commandNode.getChild(i);
            if ("command_name".equals(child.getType())) {
                return textOf(child, src).trim();
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String simpleFileName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static List<String> splitPipeline(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (!inDouble && c == '\'' && (i == 0 || command.charAt(i - 1) != '\\')) {
                inSingle = !inSingle;
            } else if (!inSingle && c == '"' && (i == 0 || command.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
            }
            if (!inSingle && !inDouble && c == '|') {
                if (i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    // || logical-or handled at compound-split layer
                    buf.append(c);
                    buf.append(command.charAt(i + 1));
                    i++;
                    continue;
                }
                out.add(buf.toString());
                buf.setLength(0);
                continue;
            }
            buf.append(c);
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    private static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (!inDouble && c == '\'' && (i == 0 || command.charAt(i - 1) != '\\')) {
                inSingle = !inSingle;
                buf.append(c);
            } else if (!inSingle && c == '"' && (i == 0 || command.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
                buf.append(c);
            } else if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (!buf.isEmpty()) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
            } else {
                buf.append(c);
            }
        }
        if (!buf.isEmpty()) {
            tokens.add(buf.toString());
        }
        return tokens;
    }

    private static String firstToken(String command) {
        String[] parts = WORD_BOUNDARY_SPLIT.split(command, 2);
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        // Skip leading variable assignments like FOO=bar
        String first = parts[0];
        if (first.contains("=") && !first.startsWith("=")) {
            return parts.length > 1 ? firstToken(parts[1]) : null;
        }
        return first;
    }

    private static String secondToken(String command) {
        String[] parts = WORD_BOUNDARY_SPLIT.split(command, 3);
        return parts.length >= 2 ? parts[1] : null;
    }
}

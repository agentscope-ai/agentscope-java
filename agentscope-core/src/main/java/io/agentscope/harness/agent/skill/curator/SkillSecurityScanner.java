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
package io.agentscope.harness.agent.skill.curator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static-analysis scanner for SKILL.md and skill support files. Ported from hermes-agent's
 * {@code tools/skills_guard.py} regex library plus the trust-level × verdict install policy.
 *
 * <p>Detection categories (one or more regexes per category):
 * <ul>
 *   <li>{@link Category#EXFILTRATION} — data exfiltration via curl/wget POST</li>
 *   <li>{@link Category#INJECTION} — prompt-injection markers in markdown</li>
 *   <li>{@link Category#DESTRUCTIVE} — {@code rm -rf /}, {@code mkfs}, {@code dd of=/dev}</li>
 *   <li>{@link Category#PERSISTENCE} — crontab / systemd / shell-rc tampering</li>
 *   <li>{@link Category#NETWORK} — listen sockets / reverse shells</li>
 *   <li>{@link Category#OBFUSCATION} — base64 → bash, eval $(curl …), etc.</li>
 * </ul>
 *
 * <p>The scan returns a {@link Verdict} (SAFE / CAUTION / DANGEROUS) plus the list of findings.
 * Callers (e.g. {@code SkillManageTool}, {@code HarnessAgent.promoteSkill}) consult
 * {@link #shouldAllow(TrustLevel, Verdict)} to map verdict + provenance to an install decision.
 *
 * <p>This is intentionally a Java-side approximation, not a security boundary. Skills always
 * run inside whatever sandbox the host configured (see {@code ShellExecuteTool}); the scanner
 * is here to catch the obvious mistakes during authoring, NOT to replace the sandbox.
 */
public final class SkillSecurityScanner {

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum Category {
        EXFILTRATION,
        INJECTION,
        DESTRUCTIVE,
        PERSISTENCE,
        NETWORK,
        OBFUSCATION
    }

    public enum Verdict {
        SAFE,
        CAUTION,
        DANGEROUS
    }

    /** Where the skill came from. */
    public enum TrustLevel {
        BUILTIN,
        TRUSTED,
        COMMUNITY,
        AGENT_CREATED
    }

    public record Finding(
            String patternId,
            Severity severity,
            Category category,
            String file,
            int line,
            String matchText,
            String description) {}

    public record ScanResult(Verdict verdict, List<Finding> findings, String reportText) {}

    /** Single regex pattern entry. */
    private record Rule(
            String id,
            Severity severity,
            Category category,
            Pattern pattern,
            String description) {}

    // ---------------------------------------------------------------------
    //  Built-in rule library (ported from hermes tools/skills_guard.py)
    // ---------------------------------------------------------------------

    private static final List<Rule> RULES = buildRules();

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        // EXFILTRATION ----------------------------------------------------
        rules.add(
                new Rule(
                        "exfil-curl-post",
                        Severity.HIGH,
                        Category.EXFILTRATION,
                        Pattern.compile("curl\\s+[^\\n]*\\s(-d|--data|-F|--data-binary)\\s"),
                        "curl POST upload — possible data exfiltration"));
        rules.add(
                new Rule(
                        "exfil-wget-post",
                        Severity.HIGH,
                        Category.EXFILTRATION,
                        Pattern.compile("wget\\s+(?:[^\\n]*\\s)?--post-data\\b"),
                        "wget --post-data — possible data exfiltration"));
        rules.add(
                new Rule(
                        "exfil-nc-pipe",
                        Severity.HIGH,
                        Category.EXFILTRATION,
                        Pattern.compile("\\b(cat|tar|gzip)\\s+[^\\n]*\\|\\s*nc\\s"),
                        "piping local data into netcat — exfiltration"));

        // INJECTION -------------------------------------------------------
        rules.add(
                new Rule(
                        "inj-ignore-prev",
                        Severity.MEDIUM,
                        Category.INJECTION,
                        Pattern.compile(
                                "(?i)ignore\\s+(all\\s+)?(your\\s+)?previous\\s+instructions"),
                        "prompt-injection marker: 'ignore previous instructions'"));
        rules.add(
                new Rule(
                        "inj-system-tag",
                        Severity.MEDIUM,
                        Category.INJECTION,
                        Pattern.compile("(?i)<\\s*(system|admin)\\s*>"),
                        "prompt-injection marker: <system> / <admin> tag inside body"));
        rules.add(
                new Rule(
                        "inj-jailbreak",
                        Severity.MEDIUM,
                        Category.INJECTION,
                        Pattern.compile("(?i)\\b(DAN|jailbreak|developer\\s+mode)\\b"),
                        "prompt-injection marker: jailbreak vocabulary"));

        // DESTRUCTIVE -----------------------------------------------------
        rules.add(
                new Rule(
                        "dest-rm-rf-root",
                        Severity.CRITICAL,
                        Category.DESTRUCTIVE,
                        Pattern.compile("rm\\s+-rf?\\s+(--no-preserve-root\\s+)?/(\\s|$)"),
                        "rm -rf / — destroys filesystem"));
        rules.add(
                new Rule(
                        "dest-mkfs",
                        Severity.CRITICAL,
                        Category.DESTRUCTIVE,
                        Pattern.compile("\\bmkfs(\\.[a-z0-9]+)?\\s+/dev/"),
                        "mkfs on a block device — destroys data"));
        rules.add(
                new Rule(
                        "dest-dd-dev",
                        Severity.CRITICAL,
                        Category.DESTRUCTIVE,
                        Pattern.compile("\\bdd\\s+[^\\n]*of=/dev/(sd|nvme|hd|xvd|disk)"),
                        "dd writing to a raw disk device"));
        rules.add(
                new Rule(
                        "dest-redirect-dev",
                        Severity.HIGH,
                        Category.DESTRUCTIVE,
                        Pattern.compile(">\\s*/dev/(sd|nvme|hd|xvd)[a-z0-9]*"),
                        "shell redirect to a raw disk device"));

        // PERSISTENCE -----------------------------------------------------
        rules.add(
                new Rule(
                        "pers-crontab-install",
                        Severity.HIGH,
                        Category.PERSISTENCE,
                        Pattern.compile("\\b(crontab\\s+-)|(echo\\s+[^\\n]*\\s+>>\\s+/etc/cron)"),
                        "installs crontab entry — persistence"));
        rules.add(
                new Rule(
                        "pers-systemd-install",
                        Severity.HIGH,
                        Category.PERSISTENCE,
                        Pattern.compile(
                                "(systemctl\\s+enable\\s+|cp\\s+[^\\n]*\\.service\\s+/etc/systemd"
                                        + "/system|/etc/systemd/system/[^\\s]+\\.service)"),
                        "installs systemd unit — persistence"));
        rules.add(
                new Rule(
                        "pers-rc-tamper",
                        Severity.MEDIUM,
                        Category.PERSISTENCE,
                        Pattern.compile(
                                "echo\\s+[^\\n]*>>\\s+~?/?(\\.bashrc|\\.zshrc|\\.profile)"),
                        "writes shell-rc — persistence"));

        // NETWORK ---------------------------------------------------------
        rules.add(
                new Rule(
                        "net-reverse-shell-bash",
                        Severity.CRITICAL,
                        Category.NETWORK,
                        Pattern.compile("bash\\s+-i\\s+>&\\s*/dev/tcp/"),
                        "bash reverse shell"));
        rules.add(
                new Rule(
                        "net-nc-listen",
                        Severity.HIGH,
                        Category.NETWORK,
                        Pattern.compile("\\bnc\\s+(?:[^\\n]*\\s)?-(l|lvp|nlvp)\\b"),
                        "netcat listener"));
        rules.add(
                new Rule(
                        "net-nc-exec",
                        Severity.CRITICAL,
                        Category.NETWORK,
                        Pattern.compile("\\bnc\\s+(?:[^\\n]*\\s)?-e\\b"),
                        "netcat -e (command execution)"));

        // OBFUSCATION -----------------------------------------------------
        rules.add(
                new Rule(
                        "obf-base64-pipe-shell",
                        Severity.HIGH,
                        Category.OBFUSCATION,
                        Pattern.compile("base64\\s+(-d|--decode)\\b[^\\n]*\\|\\s*(bash|sh|zsh)"),
                        "base64 -d | shell — obfuscated execution"));
        rules.add(
                new Rule(
                        "obf-eval-curl",
                        Severity.CRITICAL,
                        Category.OBFUSCATION,
                        Pattern.compile("\\beval\\s+[^\\n]*\\$\\(\\s*(curl|wget)\\b"),
                        "eval $(curl ...) — remote-code execution"));
        rules.add(
                new Rule(
                        "obf-curl-pipe-shell",
                        Severity.HIGH,
                        Category.OBFUSCATION,
                        Pattern.compile("(curl|wget)\\s+[^\\n]*\\|\\s*(bash|sh|zsh)"),
                        "curl/wget piped to shell — remote-code execution"));

        return List.copyOf(rules);
    }

    // ---------------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------------

    /**
     * Scan a complete skill: SKILL.md + every support file. {@code resources} is a map of
     * relative path → content (matching {@code AgentSkill.getResources()}). Returns
     * the most severe verdict across all files.
     */
    public static ScanResult scan(String skillName, String skillMd, Map<String, String> resources) {
        List<Finding> all = new ArrayList<>();
        if (skillMd != null) {
            all.addAll(scanText("SKILL.md", skillMd));
        }
        if (resources != null) {
            for (Map.Entry<String, String> e : resources.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                all.addAll(scanText(e.getKey(), e.getValue()));
            }
        }
        Verdict verdict = verdictFor(all);
        return new ScanResult(verdict, all, formatReport(skillName, verdict, all));
    }

    /** Scan a single file (used after {@code write_file} / {@code patch}). */
    public static ScanResult scanSingleFile(String relPath, String content) {
        List<Finding> findings = scanText(relPath, content);
        Verdict verdict = verdictFor(findings);
        return new ScanResult(verdict, findings, formatReport(relPath, verdict, findings));
    }

    /**
     * Map (trust × verdict) to an install decision. Mirrors hermes
     * {@code tools/skills_guard.py::INSTALL_POLICY}.
     *
     * @return {@code true} if writing the skill should proceed; {@code false} if it must be
     *     blocked / rolled back.
     */
    public static boolean shouldAllow(TrustLevel trust, Verdict verdict) {
        return switch (trust) {
            case BUILTIN -> true; // built-ins are trusted unconditionally
            case TRUSTED -> verdict != Verdict.DANGEROUS;
            case COMMUNITY -> verdict == Verdict.SAFE;
            case AGENT_CREATED ->
                    // SAFE / CAUTION allowed; DANGEROUS blocked. Hermes' "ASK" mode collapses
                    // to BLOCK in the Java port — the agent will have to revise its skill body
                    // rather than be prompted; matching enterprise default.
                    verdict != Verdict.DANGEROUS;
        };
    }

    // ---------------------------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------------------------

    private static List<Finding> scanText(String fileLabel, String content) {
        List<Finding> findings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return findings;
        }
        // Per-rule scan; report all matches with line numbers.
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(content);
            while (m.find()) {
                int line = lineNumberAt(content, m.start());
                String text = m.group();
                if (text.length() > 200) {
                    text = text.substring(0, 200) + "…";
                }
                findings.add(
                        new Finding(
                                rule.id(),
                                rule.severity(),
                                rule.category(),
                                fileLabel,
                                line,
                                text,
                                rule.description()));
            }
        }
        return findings;
    }

    private static int lineNumberAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Verdict verdictFor(List<Finding> findings) {
        if (findings.isEmpty()) {
            return Verdict.SAFE;
        }
        for (Finding f : findings) {
            if (f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH) {
                return Verdict.DANGEROUS;
            }
        }
        return Verdict.CAUTION;
    }

    private static String formatReport(String title, Verdict verdict, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("Security scan: ").append(title).append(" — ").append(verdict).append("\n");
        if (findings.isEmpty()) {
            sb.append("  (no findings)\n");
            return sb.toString();
        }
        for (Finding f : findings) {
            sb.append("  [")
                    .append(f.severity())
                    .append("/")
                    .append(f.category())
                    .append("] ")
                    .append(f.patternId())
                    .append(" @ ")
                    .append(f.file())
                    .append(":")
                    .append(f.line())
                    .append(" — ")
                    .append(f.description())
                    .append("\n      match: ")
                    .append(f.matchText())
                    .append("\n");
        }
        return sb.toString();
    }

    private SkillSecurityScanner() {}
}

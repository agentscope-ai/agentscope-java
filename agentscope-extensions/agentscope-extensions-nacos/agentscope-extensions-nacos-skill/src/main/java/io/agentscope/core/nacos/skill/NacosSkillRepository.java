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
package io.agentscope.core.nacos.skill;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.utils.StringUtils;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AgentSkillRepository} backed by Nacos AI skill packages.
 *
 * <p><strong>Loading:</strong> Downloads the skill ZIP through {@link AiService}, then builds
 * {@link AgentSkill} from it (including {@code SKILL.md}). Indented YAML frontmatter in Nacos
 * exports is normalized so AgentScope's flat {@code key: value} parser can read it. API choice:
 * {@link AiService#downloadSkillZipByVersion(String, String)} if a version is set; else {@link
 * AiService#downloadSkillZipByLabel(String, String)} if only a label is set; else {@link
 * AiService#downloadSkillZip(String)}. When both version and label resolve, version wins and the
 * label is not used for download.
 *
 * <p><strong>Capabilities:</strong> Read operations work: {@link #getSkill(String)}, {@link
 * #skillExists(String)}, {@link #getRepositoryInfo()}, {@link #getSource()}, {@link
 * #isWriteable()} (always {@code false}). Listing and writes are unsupported: {@link
 * #getAllSkillNames()} and {@link #getAllSkills()} return empty results; {@link #save(List,
 * boolean)} and {@link #delete(String)} do nothing; {@link #setWriteable(boolean)} is ignored. These
 * paths log a warning.
 *
 * <p><strong>Version and label resolution:</strong> Independently for version and for label, the
 * first non-blank value wins, in order: (1) {@link Properties} from {@link
 * #NacosSkillRepository(AiService, String, Properties)}, (2) JVM system property {@link
 * #SKILL_VERSION_PATH} or {@link #SKILL_LABEL_PATH}, (3) environment variable {@link
 * #ENV_SKILL_VERSION_PATH} or {@link #ENV_SKILL_LABEL_PATH}.
 */
public class NacosSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRepository.class);

    private static final String SKILL_MD = "SKILL.md";

    /** Same key pattern as {@code MarkdownSkillParser}'s simple YAML parser (flat key: value). */
    private static final Pattern YAML_KV =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_-]*)\\s*:\\s*(.*)$");

    /** Skill package entry: exactly one path segment then {@value #SKILL_MD}. */
    private static final Pattern ROOT_SKILL_MD = Pattern.compile("^([^/]+)/" + SKILL_MD + "$");

    private static final String REPO_TYPE = "nacos";
    private static final String LOCATION_PREFIX = "namespace:";

    /** Key for skill version in {@link Properties}, JVM {@link System#getProperty(String)}, etc. */
    public static final String SKILL_VERSION_PATH = "agentscope.nacos.skill.version";

    /** Key for skill label in {@link Properties}, JVM {@link System#getProperty(String)}, etc. */
    public static final String SKILL_LABEL_PATH = "agentscope.nacos.skill.label";

    /** Environment variable name for skill version (fallback after properties and JVM). */
    public static final String ENV_SKILL_VERSION_PATH = "AGENTSCOPE_NACOS_SKILL_VERSION";

    /** Environment variable name for skill label (fallback after properties and JVM). */
    public static final String ENV_SKILL_LABEL_PATH = "AGENTSCOPE_NACOS_SKILL_LABEL";

    private final AiService aiService;
    private final String namespaceId;
    private final String source;
    private final String location;
    private final String skillVersion;
    private final String skillLabel;

    /**
     * Same as {@link #NacosSkillRepository(AiService, String, Properties)} with {@code properties
     * == null}.
     */
    public NacosSkillRepository(AiService aiService, String namespaceId) {
        this(aiService, namespaceId, null);
    }

    /**
     * Creates a repository for the given Nacos namespace; resolves skill version and label once
     * (see class Javadoc for precedence).
     *
     * @param aiService   the Nacos AI service (must not be null)
     * @param namespaceId the Nacos namespace id (blank treated as {@code public})
     * @param properties  optional application properties (e.g. from {@code application.properties});
     *                    may be {@code null}
     */
    public NacosSkillRepository(AiService aiService, String namespaceId, Properties properties) {
        if (aiService == null) {
            throw new IllegalArgumentException("AiService cannot be null");
        }
        this.aiService = aiService;

        this.namespaceId = StringUtils.isBlank(namespaceId) ? "public" : namespaceId.trim();
        this.source = REPO_TYPE + ":" + this.namespaceId;
        this.location = LOCATION_PREFIX + this.namespaceId;
        this.skillVersion =
                firstNonBlank(
                        getProperties(properties, SKILL_VERSION_PATH),
                        System.getProperty(SKILL_VERSION_PATH),
                        System.getenv(ENV_SKILL_VERSION_PATH));
        this.skillLabel =
                firstNonBlank(
                        getProperties(properties, SKILL_LABEL_PATH),
                        System.getProperty(SKILL_LABEL_PATH),
                        System.getenv(ENV_SKILL_LABEL_PATH));
        log.info(
                "NacosSkillRepository initialized for namespace: {}, skillVersion: {},"
                        + " skillLabel: {}",
                this.namespaceId,
                StringUtils.isBlank(skillVersion) ? "(none)" : skillVersion,
                StringUtils.isBlank(skillLabel) ? "(none)" : skillLabel);
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        try {
            byte[] zipBytes = downloadSkillZipBytes(name.trim());
            if (zipBytes == null || zipBytes.length == 0) {
                throw new IllegalArgumentException("Skill not found: " + name);
            }
            byte[] adaptedZip = adaptNacosSkillZipForYamlFrontmatter(zipBytes);
            return SkillUtil.createFromZip(adaptedZip, getSource());
        } catch (NacosException e) {
            if (e.getErrCode() == NacosException.NOT_FOUND) {
                throw new IllegalArgumentException("Skill not found: " + name, e);
            }
            throw new RuntimeException("Failed to load skill from Nacos: " + name, e);
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        try {
            byte[] zipBytes = downloadSkillZipBytes(skillName.trim());
            return zipBytes != null && zipBytes.length > 0;
        } catch (NacosException e) {
            if (e.getErrCode() == NacosException.NOT_FOUND) {
                return false;
            }
            log.warn("Error checking skill existence for {}: {}", skillName, e.getMessage());
            return false;
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(REPO_TYPE, location, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public void setWriteable(boolean writeable) {
        log.warn("NacosSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    // ---------- Read-only no-op operations (list and write) ----------

    @Override
    public List<String> getAllSkillNames() {
        log.warn("NacosSkillRepository is read-only, getAllSkillNames returns empty list");
        return Collections.emptyList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        log.warn("NacosSkillRepository is read-only, getAllSkills returns empty list");
        return Collections.emptyList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        log.warn("NacosSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("NacosSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public void close() {
        // AiService lifecycle is managed by the caller; nothing to release here
    }

    /** Returns {@link Properties#getProperty(String)} or {@code null} if {@code properties} is null. */
    private static String getProperties(Properties properties, String key) {
        if (properties == null || key == null) {
            return null;
        }
        return properties.getProperty(key);
    }

    /** First argument that is non-null and non-blank after {@link String#trim()}; otherwise {@code null}. */
    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (StringUtils.isNotBlank(s)) {
                return s.trim();
            }
        }
        return null;
    }

    /** Dispatches to AiService using version, label, or unnamed ZIP as resolved at construction. */
    private byte[] downloadSkillZipBytes(String skillName) throws NacosException {
        if (StringUtils.isNotBlank(skillVersion)) {
            return aiService.downloadSkillZipByVersion(skillName, skillVersion);
        }
        if (StringUtils.isNotBlank(skillLabel)) {
            return aiService.downloadSkillZipByLabel(skillName, skillLabel);
        }
        return aiService.downloadSkillZip(skillName);
    }

    /**
     * Nacos-exported {@value #SKILL_MD} often uses indented continuation lines in YAML frontmatter
     * (no {@code key:} prefix). AgentScope's frontmatter parser only accepts flat {@code key:
     * value} lines; fold those continuations into the previous key and rewrite the zip so {@link
     * SkillUtil#createFromZip} succeeds.
     */
    private static byte[] adaptNacosSkillZipForYamlFrontmatter(byte[] zipBytes) {
        String skillPath = null;
        String skillMd = null;
        Map<String, byte[]> entries = new LinkedHashMap<>();

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeZipEntryName(entry.getName());
                byte[] data = readZipEntryBytes(zin);
                entries.put(name, data);
                if (ROOT_SKILL_MD.matcher(name).matches()) {
                    if (skillPath != null && !skillPath.equals(name)) {
                        return zipBytes;
                    }
                    skillPath = name;
                    skillMd = new String(data, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to inspect skill zip for frontmatter adaptation: {}", e.getMessage());
            return zipBytes;
        }

        if (skillPath == null || skillMd == null) {
            return zipBytes;
        }

        String adaptedMd = adaptSkillMdFrontmatter(skillMd);
        if (adaptedMd.equals(skillMd)) {
            return zipBytes;
        }

        entries.put(skillPath, adaptedMd.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        } catch (IOException e) {
            log.warn("Failed to repack skill zip after frontmatter adaptation: {}", e.getMessage());
            return zipBytes;
        }
        return bos.toByteArray();
    }

    /** Normalizes frontmatter only; leaves body unchanged. */
    private static String adaptSkillMdFrontmatter(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }
        String text = markdown;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!text.startsWith("---")) {
            return markdown;
        }

        List<String> lines = splitLinesPreserveTrailing(text);
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return markdown;
        }

        int yamlEnd = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("---")) {
                yamlEnd = i;
                break;
            }
        }
        if (yamlEnd < 0) {
            return markdown;
        }

        List<String> yamlLines = lines.subList(1, yamlEnd);
        String normalizedYaml = normalizeFoldedFlatYaml(yamlLines);
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append(normalizedYaml);
        out.append("---");
        for (int i = yamlEnd + 1; i < lines.size(); i++) {
            out.append('\n').append(lines.get(i));
        }
        return out.toString();
    }

    private static List<String> splitLinesPreserveTrailing(String text) {
        String[] parts = text.split("\\R", -1);
        List<String> list = new ArrayList<>(parts.length);
        Collections.addAll(list, parts);
        return list;
    }

    /**
     * Fold lines that are not {@code key: value} into the previous key's value (Nacos / loose YAML
     * style descriptions).
     */
    private static String normalizeFoldedFlatYaml(List<String> yamlLines) {
        StringBuilder emit = new StringBuilder();
        String pendingKey = null;
        String pendingVal = null;

        for (String raw : yamlLines) {
            String t = raw.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#")) {
                continue;
            }
            Matcher m = YAML_KV.matcher(t);
            if (m.matches()) {
                flushYamlKvLine(emit, pendingKey, pendingVal);
                pendingKey = m.group(1);
                pendingVal = m.group(2).trim();
            } else if (pendingKey != null) {
                if (pendingVal == null || pendingVal.isEmpty()) {
                    pendingVal = t;
                } else {
                    pendingVal = pendingVal + " " + t;
                }
            }
        }
        flushYamlKvLine(emit, pendingKey, pendingVal);
        return emit.toString();
    }

    private static void flushYamlKvLine(StringBuilder sb, String key, String value) {
        if (key == null) {
            return;
        }
        sb.append(key).append(": ");
        if (value == null || value.isEmpty()) {
            sb.append('\n');
            return;
        }
        if (yamlValueNeedsQuoting(value)) {
            sb.append('"');
            appendYamlDoubleQuotedEscaped(sb, value);
            sb.append('"');
        } else {
            sb.append(value);
        }
        sb.append('\n');
    }

    /** Aligns with {@code MarkdownSkillParser.SimpleYamlParser#needsQuoting} behavior. */
    private static boolean yamlValueNeedsQuoting(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if (value.contains(":")
                || value.contains("#")
                || value.contains("\n")
                || value.contains("\r")
                || value.contains("\t")) {
            return true;
        }
        if (Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1))) {
            return true;
        }
        char first = value.charAt(0);
        return first == '"'
                || first == '\''
                || first == '['
                || first == ']'
                || first == '{'
                || first == '}'
                || first == '>'
                || first == '|'
                || first == '*'
                || first == '&'
                || first == '!'
                || first == '%'
                || first == '@'
                || first == '`';
    }

    private static void appendYamlDoubleQuotedEscaped(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
    }

    private static String normalizeZipEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            throw new IllegalArgumentException("Zip entry name cannot be null or empty.");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Zip entry name must be a relative path.");
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "Zip entry name must not contain parent directory segments.");
            }
        }
        return normalized;
    }

    private static byte[] readZipEntryBytes(ZipInputStream zipInputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            while ((read = zipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }
}

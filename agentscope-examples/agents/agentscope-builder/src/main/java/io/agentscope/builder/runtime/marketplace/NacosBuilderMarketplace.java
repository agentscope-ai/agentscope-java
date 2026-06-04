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
package io.agentscope.builder.runtime.marketplace;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.ai.model.skills.SkillMeta;
import com.alibaba.nacos.api.ai.model.skills.SkillResource;
import com.alibaba.nacos.api.ai.model.skills.SkillSummary;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.model.Page;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerFactory;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerService;
import com.alibaba.nacos.maintainer.client.ai.SkillMaintainerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Nacos-backed marketplace. Uses the maintainer client (not the regular AiService client,
 * which only exposes downloads) to drive the paged {@code listSkills} API. Skill content is
 * fetched by resolving the real version from the skill metadata's {@code latest} label, then
 * calling {@code getSkillVersionDetail(..., resolvedVersion)}.
 *
 * <p>Pagination: builder is a multi-tenant platform — but we still expect skill counts on the order of 100s per user, not millions. We page through
 * the upstream result in batches of {@link #PAGE_SIZE} until the server reports we have all
 * pages, capping at {@link #MAX_PAGES} to keep a misbehaving server from hanging the UI.
 */
public class NacosBuilderMarketplace implements BuilderMarketplace {

    private static final Logger logger = LoggerFactory.getLogger(NacosBuilderMarketplace.class);

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final String LATEST_LABEL = "latest";
    private static final String ONLINE_STATUS = "online";

    private final String id;
    private final String serverAddr;
    private final String namespaceId;
    private final String username;
    private final String accessKey;
    private final AiMaintainerService service;

    public NacosBuilderMarketplace(
            String id,
            String serverAddr,
            String namespaceId,
            String username,
            String password,
            String accessKey,
            String secretKey) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalArgumentException("serverAddr must not be blank");
        }
        this.id = id;
        this.serverAddr = serverAddr.trim();
        this.namespaceId = (namespaceId == null || namespaceId.isBlank()) ? "public" : namespaceId;
        this.username = blankToNull(username);
        this.accessKey = blankToNull(accessKey);

        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, this.serverAddr);
        props.setProperty(PropertyKeyConst.NAMESPACE, this.namespaceId);
        if (this.username != null) {
            props.setProperty(PropertyKeyConst.USERNAME, this.username);
            if (password != null) {
                props.setProperty(PropertyKeyConst.PASSWORD, password);
            }
        }
        if (this.accessKey != null) {
            props.setProperty(PropertyKeyConst.ACCESS_KEY, this.accessKey);
            if (secretKey != null) {
                props.setProperty(PropertyKeyConst.SECRET_KEY, secretKey);
            }
        }
        try {
            this.service = AiMaintainerFactory.createAiMaintainerService(props);
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "Failed to create Nacos AiMaintainerService for marketplace "
                            + id
                            + " ("
                            + this.serverAddr
                            + ")",
                    e);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return "nacos";
    }

    @Override
    public String displayLocation() {
        return serverAddr + " / ns=" + namespaceId;
    }

    @Override
    public List<MarketSkillSummary> list() {
        SkillMaintainerService skillService = service.skill();
        List<MarketSkillSummary> all = new ArrayList<>();
        int pageNo = 1;
        try {
            while (pageNo <= MAX_PAGES) {
                Page<SkillSummary> page =
                        skillService.listSkills(namespaceId, null, null, pageNo, PAGE_SIZE);
                if (page == null || page.getPageItems() == null) {
                    break;
                }
                for (SkillSummary s : page.getPageItems()) {
                    String version =
                            s.getEditingVersion() != null
                                    ? s.getEditingVersion()
                                    : s.getReviewingVersion();
                    all.add(new MarketSkillSummary(s.getName(), s.getDescription(), version));
                }
                if (pageNo >= page.getPagesAvailable() || page.getPageItems().size() < PAGE_SIZE) {
                    break;
                }
                pageNo++;
            }
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "Nacos listSkills failed for " + serverAddr + "/" + namespaceId, e);
        }
        return all;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            String skillName = name.trim();
            SkillMaintainerService skillService = service.skill();
            SkillMeta skillMeta = skillService.getSkillMeta(namespaceId, skillName);
            String resolvedVersion = resolveLatestSkillVersion(namespaceId, skillName, skillMeta);

            Skill skill =
                    skillService.getSkillVersionDetail(namespaceId, skillName, resolvedVersion);
            if (skill == null || skill.getSkillMd() == null || skill.getSkillMd().isEmpty()) {
                return null;
            }
            Map<String, String> resources = new LinkedHashMap<>();
            Map<String, SkillResource> upstream = skill.getResource();
            if (upstream != null) {
                upstream.forEach(
                        (key, value) -> {
                            if (value == null || value.getContent() == null) {
                                return;
                            }
                            // Prefer the resource's stated name (its workspace-relative path) if
                            // present, fall back to the map key so we never lose the file.
                            String path =
                                    (value.getName() != null && !value.getName().isBlank())
                                            ? value.getName()
                                            : key;
                            resources.put(path, value.getContent());
                        });
            }
            return new MarketSkillContent(
                    skill.getName(), skill.getDescription(), skill.getSkillMd(), resources);
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "Nacos getSkillVersionDetail failed for "
                            + serverAddr
                            + "/"
                            + namespaceId
                            + "/"
                            + name,
                    e);
        }
    }

    @Override
    public void close() {
        // AiMaintainerService does not expose a close(); the underlying HTTP client is reaped
        // when this instance is GC'd. Nothing else to release.
        logger.debug("Closing nacos marketplace {} ({})", id, serverAddr);
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String resolveLatestSkillVersion(
            String namespaceId, String skillName, SkillMeta skillMeta) {
        String version =
                skillMeta != null && skillMeta.getLabels() != null
                        ? skillMeta.getLabels().get(LATEST_LABEL)
                        : null;
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    "Nacos skill metadata is missing a '"
                            + LATEST_LABEL
                            + "' label for "
                            + namespaceId
                            + "/"
                            + skillName
                            + "; cannot resolve latest version");
        }

        if (skillMeta.getVersions() == null || skillMeta.getVersions().isEmpty()) {
            throw new IllegalStateException(
                    "Nacos skill latest label points to unknown version '"
                            + version
                            + "' for "
                            + namespaceId
                            + "/"
                            + skillName
                            + "; metadata versions are empty");
        }

        for (SkillMeta.SkillVersionSummary summary : skillMeta.getVersions()) {
            if (summary == null || !version.equals(summary.getVersion())) {
                continue;
            }
            String status = summary.getStatus();
            if (!ONLINE_STATUS.equalsIgnoreCase(status)) {
                throw new IllegalStateException(
                        "Nacos skill latest version '"
                                + version
                                + "' for "
                                + namespaceId
                                + "/"
                                + skillName
                                + " is not online"
                                + " (status="
                                + status
                                + ")");
            }
            return version;
        }

        throw new IllegalStateException(
                "Nacos skill latest label points to unknown version '"
                        + version
                        + "' for "
                        + namespaceId
                        + "/"
                        + skillName);
    }
}

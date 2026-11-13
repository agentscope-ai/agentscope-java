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
package io.agentscope.dataagent.runtime.marketplace;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a Nacos skill marketplace for browsing and downloading published skill content.
 *
 * <p>Listing uses the maintainer client's paginated Skill API, limited to {@link #MAX_PAGES}
 * pages of {@link #PAGE_SIZE} entries. Downloads resolve the {@code latest} metadata label to an
 * exact published version and include both SKILL.md and supporting text resources. A skill without
 * that label has no downloadable content, even if it has a draft version.
 *
 * <p>Credentials are passed to the maintainer client and are excluded from the display location.
 */
public class NacosDataAgentMarketplace implements DataAgentMarketplace {

    private static final Logger logger = LoggerFactory.getLogger(NacosDataAgentMarketplace.class);
    public static final String TYPE = "nacos";

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final String LATEST_LABEL = "latest";

    private final String id;
    private final String serverAddr;
    private final String namespaceId;
    private final String username;
    private final String accessKey;
    private final AiMaintainerService service;

    /**
     * Creates a marketplace backed by a Nacos maintainer client.
     *
     * @param id stable marketplace identifier
     * @param serverAddr Nacos server address
     * @param namespaceId namespace identifier; null or blank selects {@code public}
     * @param username optional authentication username
     * @param password password used when a username is supplied
     * @param accessKey optional access key for a compatible authentication plugin
     * @param secretKey secret key used when an access key is supplied
     * @throws IllegalArgumentException if the identifier or server address is null or blank
     * @throws IllegalStateException if the Nacos maintainer client cannot be created
     */
    public NacosDataAgentMarketplace(
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

    /** {@inheritDoc} */
    @Override
    public String id() {
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public String displayLocation() {
        return serverAddr + " / ns=" + namespaceId;
    }

    /**
     * Lists skill summaries, stopping at the server's last page or the local page limit.
     *
     * @return skill summaries in server order, with the editing or reviewing version when present
     * @throws IllegalStateException if listing fails, retaining the Nacos exception as its cause
     */
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

    /**
     * Downloads the skill version selected by its published {@code latest} label.
     *
     * @param name skill name; surrounding whitespace is ignored
     * @return the published content and resources, or {@code null} if the name is blank, the label
     *     is absent, the skill or version was deleted, or the selected version has no SKILL.md content
     * @throws IllegalStateException if metadata or content retrieval fails, retaining the Nacos cause
     */
    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            SkillMaintainerService skills = service.skill();
            SkillMeta metadata = skills.getSkillMeta(namespaceId, name.trim());
            String version =
                    metadata == null || metadata.getLabels() == null
                            ? null
                            : metadata.getLabels().get(LATEST_LABEL);
            if (version == null || version.isBlank()) return null;
            Skill skill = skills.getSkillVersionDetail(namespaceId, name.trim(), version);
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
            if (e.getErrCode() == NacosException.NOT_FOUND) return null;
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

    /**
     * Completes the marketplace lifecycle.
     *
     * <p>The Nacos maintainer service exposes no shutdown operation. This method logs closure
     * and can be called repeatedly.
     */
    @Override
    public void close() {
        logger.debug("Closing nacos marketplace {} ({})", id, serverAddr);
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/**
 * Verifies published skill selection, resource mapping, bounded pagination, and failure propagation.
 *
 * <p>The maintainer factory and service are mocked so these tests require no Nacos server or credentials.
 */
class NacosDataAgentMarketplaceTest {
    private final AiMaintainerService admin = mock(AiMaintainerService.class);
    private final SkillMaintainerService skills = mock(SkillMaintainerService.class);
    private final AtomicReference<Properties> clientProperties = new AtomicReference<>();
    private MockedStatic<AiMaintainerFactory> factory;

    @BeforeEach
    void setUp() {
        when(admin.skill()).thenReturn(skills);
        factory = mockStatic(AiMaintainerFactory.class);
        factory.when(() -> AiMaintainerFactory.createAiMaintainerService(any(Properties.class)))
                .thenAnswer(
                        call -> {
                            clientProperties.set(call.getArgument(0));
                            return admin;
                        });
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    private NacosDataAgentMarketplace marketplace() {
        return new NacosDataAgentMarketplace(
                "it", "localhost:8848", "public", null, null, null, null);
    }

    private void published(String version) throws NacosException {
        SkillMeta meta = new SkillMeta();
        meta.setLabels(Map.of("latest", version));
        when(skills.getSkillMeta("public", "demo")).thenReturn(meta);
    }

    private Skill content() {
        Skill skill = new Skill();
        skill.setName("demo");
        skill.setDescription("Published description");
        skill.setSkillMd("# Published skill");
        return skill;
    }

    @Test
    void fetchResolvesPublishedLatestLabelAndMapsResources() throws Exception {
        published("1.2.3");
        Skill skill = content();
        Map<String, SkillResource> resources = new HashMap<>();
        SkillResource named = new SkillResource();
        named.setName("notes.txt");
        named.setContent("notes");
        SkillResource fallback = new SkillResource();
        fallback.setName(" ");
        fallback.setContent("fallback");
        SkillResource unnamed = new SkillResource();
        unnamed.setContent("unnamed");
        resources.put("ignored-key", named);
        resources.put("fallback.txt", fallback);
        resources.put("unnamed.txt", unnamed);
        resources.put("null-resource", null);
        resources.put("no-content", new SkillResource());
        skill.setResource(resources);
        when(skills.getSkillVersionDetail("public", "demo", "1.2.3")).thenReturn(skill);
        var result = marketplace().fetch(" demo ");
        assertNotNull(result);
        assertEquals("demo", result.name());
        assertEquals("Published description", result.description());
        assertEquals("# Published skill", result.markdown());
        assertEquals(
                Map.of("notes.txt", "notes", "fallback.txt", "fallback", "unnamed.txt", "unnamed"),
                result.resources());
        verify(skills).getSkillVersionDetail("public", "demo", "1.2.3");
    }

    @Test
    void contentWithoutResourcesHasEmptyResourceMap() throws Exception {
        published("1.2.3");
        Skill skill = content();
        skill.setResource(null);
        when(skills.getSkillVersionDetail("public", "demo", "1.2.3")).thenReturn(skill);
        assertEquals(Map.of(), marketplace().fetch("demo").resources());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void absentPublishedLabelDoesNotFetchDraft(int state) throws Exception {
        SkillMeta meta = new SkillMeta();
        if (state == 0) meta = null;
        else if (state == 1) meta.setLabels(null);
        else meta.setLabels(state == 2 ? Map.of("draft", "2.0.0") : Map.of("latest", " "));
        when(skills.getSkillMeta("public", "demo")).thenReturn(meta);
        assertNull(marketplace().fetch("demo"));
        verify(skills, never()).getSkillVersionDetail(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void missingPublishedContentReturnsNull(int state) throws Exception {
        published("1.0.0");
        Skill skill = content();
        if (state == 0) skill = null;
        else skill.setSkillMd(state == 1 ? null : "");
        when(skills.getSkillVersionDetail("public", "demo", "1.0.0")).thenReturn(skill);
        assertNull(marketplace().fetch("demo"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankNameDoesNotCallServer(String name) {
        assertNull(marketplace().fetch(name));
        verifyNoInteractions(skills);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fetchPreservesMetadataAndContentFailures(boolean failContent) throws Exception {
        NacosException failure = new NacosException(403, "denied");
        if (failContent) {
            published("1.0.0");
            when(skills.getSkillVersionDetail("public", "demo", "1.0.0")).thenThrow(failure);
        } else when(skills.getSkillMeta("public", "demo")).thenThrow(failure);
        var marketplace = marketplace();
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> marketplace.fetch("demo"))
                        .getCause());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedSkillOrPublishedVersionHasNoDownloadableContent(boolean deletedVersion)
            throws Exception {
        NacosException missing = new NacosException(NacosException.NOT_FOUND, "deleted");
        if (deletedVersion) {
            published("1.0.0");
            when(skills.getSkillVersionDetail("public", "demo", "1.0.0")).thenThrow(missing);
        } else when(skills.getSkillMeta("public", "demo")).thenThrow(missing);
        assertNull(marketplace().fetch("demo"));
    }

    private Page<SkillSummary> page(int size, int pages) {
        Page<SkillSummary> result = new Page<>();
        List<SkillSummary> summaries = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            SkillSummary summary = new SkillSummary();
            summary.setName("skill-" + i);
            summary.setDescription("description");
            if (i == 0) summary.setEditingVersion("2.0.0");
            else summary.setReviewingVersion("1.0.0");
            summaries.add(summary);
        }
        result.setPageItems(summaries);
        result.setPagesAvailable(pages);
        return result;
    }

    @Test
    void listingCollectsPagesAndKeepsSummaryVersions() throws Exception {
        when(skills.listSkills("public", null, null, 1, 100)).thenReturn(page(100, 2));
        when(skills.listSkills("public", null, null, 2, 100)).thenReturn(page(1, 2));
        var result = marketplace().list();
        assertEquals(101, result.size());
        assertEquals(new MarketSkillSummary("skill-0", "description", "2.0.0"), result.get(0));
        assertEquals("1.0.0", result.get(1).version());
        verify(skills, never()).listSkills("public", null, null, 3, 100);
    }

    @Test
    void listingStopsAtPageLimit() throws Exception {
        when(skills.listSkills(eq("public"), any(), any(), anyInt(), eq(100)))
                .thenReturn(page(100, 1000));
        assertEquals(5000, marketplace().list().size());
        verify(skills).listSkills("public", null, null, 50, 100);
        verify(skills, never()).listSkills("public", null, null, 51, 100);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void listingStopsOnAbsentOrShortPage(int state) throws Exception {
        Page<SkillSummary> result = page(state == 3 ? 1 : 0, 100);
        if (state == 0) result = null;
        else if (state == 1) result.setPageItems(null);
        when(skills.listSkills("public", null, null, 1, 100)).thenReturn(result);
        assertEquals(state == 3 ? 1 : 0, marketplace().list().size());
        verify(skills, never()).listSkills("public", null, null, 2, 100);
    }

    @Test
    void listingPreservesFailure() throws Exception {
        NacosException failure = new NacosException(503, "unavailable");
        when(skills.listSkills("public", null, null, 1, 100)).thenThrow(failure);
        var marketplace = marketplace();
        assertSame(
                failure, assertThrows(IllegalStateException.class, marketplace::list).getCause());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankIdentifierAndServerBeforeCreatingClient(String blank) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NacosDataAgentMarketplace(blank, "host", null, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NacosDataAgentMarketplace("id", blank, null, null, null, null, null));
        factory.verifyNoInteractions();
    }

    @Test
    void configuresCredentialsAndExcludesThemFromDisplayLocation() {
        var marketplace =
                new NacosDataAgentMarketplace(
                        "id",
                        " host:8848 ",
                        "team",
                        " user ",
                        "test-password",
                        " key ",
                        "test-secret");
        assertEquals("id", marketplace.id());
        assertEquals("nacos", marketplace.type());
        assertEquals("host:8848 / ns=team", marketplace.displayLocation());
        Properties props = clientProperties.get();
        assertEquals("user", props.getProperty(PropertyKeyConst.USERNAME));
        assertEquals("test-password", props.getProperty(PropertyKeyConst.PASSWORD));
        assertEquals("key", props.getProperty(PropertyKeyConst.ACCESS_KEY));
        assertEquals("test-secret", props.getProperty(PropertyKeyConst.SECRET_KEY));
        marketplace.close();
        marketplace.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankOptionalSettingsUsePublicNamespaceAndOmitCredentials(String blank) {
        new NacosDataAgentMarketplace("id", "host", blank, blank, null, blank, null);
        assertEquals("public", clientProperties.get().getProperty(PropertyKeyConst.NAMESPACE));
        assertNull(clientProperties.get().getProperty(PropertyKeyConst.USERNAME));
        assertNull(clientProperties.get().getProperty(PropertyKeyConst.ACCESS_KEY));
    }

    @Test
    void usernameAndAccessKeyDoNotRequireSecretsAtConstruction() {
        new NacosDataAgentMarketplace("id", "host", null, "user", null, "key", null);
        assertNull(clientProperties.get().getProperty(PropertyKeyConst.PASSWORD));
        assertNull(clientProperties.get().getProperty(PropertyKeyConst.SECRET_KEY));
    }

    @Test
    void clientCreationFailureKeepsOriginalCause() {
        NacosException failure = new NacosException(500, "initialization failed");
        factory.when(() -> AiMaintainerFactory.createAiMaintainerService(any(Properties.class)))
                .thenThrow(failure);
        assertSame(
                failure, assertThrows(IllegalStateException.class, this::marketplace).getCause());
    }
}

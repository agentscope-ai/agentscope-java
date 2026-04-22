/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.nacos.a2a.discovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentCardListener;
import com.alibaba.nacos.api.ai.listener.NacosAgentCardEvent;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import io.a2a.spec.AgentCard;
import io.agentscope.core.nacos.a2a.utils.AgentCardConverterUtil;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link NacosAgentCardResolver}.
 */
@ExtendWith(MockitoExtension.class)
class NacosAgentCardResolverTest {

    @Mock private AiService aiService;

    private NacosAgentCardResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new NacosAgentCardResolver(aiService);
    }

    @Test
    @DisplayName("Should create resolver with Properties successfully")
    void testCreateResolverWithProperties() throws NacosException {
        // Given
        Properties properties = new Properties();
        properties.put("serverAddr", "localhost:8848");

        // When & Then - We can't easily test this without mocking AiFactory
        // So we just test that it doesn't throw an exception in normal cases
        assertDoesNotThrow(
                () -> {
                    NacosAgentCardResolver resolver = new NacosAgentCardResolver(properties);
                    assertNotNull(resolver);
                });
    }

    @Test
    @DisplayName("Should return AgentCard when agent card exists in cache")
    void testGetAgentCardWhenInCache() throws Exception {
        // Given
        String agentName = "test-agent";
        AgentCard cachedAgentCard = mock(AgentCard.class);

        // Use reflection to put value in cache
        Field cacheField = NacosAgentCardResolver.class.getDeclaredField("agentCardCaches");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AgentCard> agentCardCaches = (Map<String, AgentCard>) cacheField.get(resolver);
        agentCardCaches.put(agentName, cachedAgentCard);

        // When
        AgentCard result = resolver.getAgentCard(agentName);

        // Then
        assertEquals(cachedAgentCard, result);
        verify(aiService, never())
                .subscribeAgentCard(anyString(), any()); // Should not call aiService if cached
    }

    @Test
    @DisplayName("Should fetch AgentCard from service when not in cache")
    void testGetAgentCardWhenNotInCache() throws NacosException {
        // Given
        String agentName = "test-agent";
        AgentCardDetailInfo nacosAgentCard = mock(AgentCardDetailInfo.class);
        AgentCard expectedAgentCard = mock(AgentCard.class);

        try (MockedStatic<AgentCardConverterUtil> mockedConverter =
                mockStatic(AgentCardConverterUtil.class)) {
            mockedConverter
                    .when(() -> AgentCardConverterUtil.convertToA2aAgentCard(eq(nacosAgentCard)))
                    .thenReturn(expectedAgentCard);

            when(aiService.subscribeAgentCard(eq(agentName), any())).thenReturn(nacosAgentCard);

            // When
            AgentCard result = resolver.getAgentCard(agentName);

            // Then
            assertNotNull(result);
            assertEquals(expectedAgentCard, result);
            verify(aiService).subscribeAgentCard(eq(agentName), any());
            mockedConverter.verify(
                    () -> AgentCardConverterUtil.convertToA2aAgentCard(eq(nacosAgentCard)),
                    times(1));
        }
    }

    @Test
    @DisplayName("Should handle NacosException and rethrow as runtime exception")
    void testGetAgentCardHandlesNacosException() throws NacosException {
        // Given
        String agentName = "test-agent";
        NacosException nacosException = new NacosException(500, "Test error");

        when(aiService.subscribeAgentCard(eq(agentName), any())).thenThrow(nacosException);

        // When & Then
        assertThrows(RuntimeException.class, () -> resolver.getAgentCard(agentName));
    }

    @Test
    @DisplayName("Should cache AgentCard after fetching from service")
    void testGetAgentCardCachesResult() throws Exception, NacosException {
        // Given
        String agentName = "test-agent";
        AgentCardDetailInfo nacosAgentCard = mock(AgentCardDetailInfo.class);
        AgentCard expectedAgentCard = mock(AgentCard.class);

        try (MockedStatic<AgentCardConverterUtil> mockedConverter =
                mockStatic(AgentCardConverterUtil.class)) {
            mockedConverter
                    .when(() -> AgentCardConverterUtil.convertToA2aAgentCard(eq(nacosAgentCard)))
                    .thenReturn(expectedAgentCard);

            when(aiService.subscribeAgentCard(eq(agentName), any())).thenReturn(nacosAgentCard);

            // First call - should fetch from service
            AgentCard firstResult = resolver.getAgentCard(agentName);

            // Second call - should use cached value
            AgentCard secondResult = resolver.getAgentCard(agentName);

            // Then
            assertEquals(firstResult, secondResult);
            verify(aiService, times(1))
                    .subscribeAgentCard(eq(agentName), any()); // Called only once
            mockedConverter.verify(
                    () -> AgentCardConverterUtil.convertToA2aAgentCard(eq(nacosAgentCard)),
                    times(1));
        }
    }

    @Test
    @DisplayName("Should handle multiple concurrent agent card requests")
    void testGetAgentCardConcurrentRequests() throws NacosException {
        // Given
        String agentName1 = "test-agent-1";
        String agentName2 = "test-agent-2";
        AgentCardDetailInfo nacosAgentCard1 = mock(AgentCardDetailInfo.class);
        AgentCardDetailInfo nacosAgentCard2 = mock(AgentCardDetailInfo.class);
        AgentCard expectedAgentCard1 = mock(AgentCard.class);
        AgentCard expectedAgentCard2 = mock(AgentCard.class);

        try (MockedStatic<AgentCardConverterUtil> mockedConverter =
                mockStatic(AgentCardConverterUtil.class)) {
            mockedConverter
                    .when(() -> AgentCardConverterUtil.convertToA2aAgentCard(eq(nacosAgentCard1)))
                    .thenReturn(expectedAgentCard1);
            mockedConverter
                    .when(() -> AgentCardConverterUtil.convertToA2aAgentCard(eq(nacosAgentCard2)))
                    .thenReturn(expectedAgentCard2);

            when(aiService.subscribeAgentCard(eq(agentName1), any())).thenReturn(nacosAgentCard1);
            when(aiService.subscribeAgentCard(eq(agentName2), any())).thenReturn(nacosAgentCard2);

            // When
            AgentCard result1 = resolver.getAgentCard(agentName1);
            AgentCard result2 = resolver.getAgentCard(agentName2);

            // Then
            assertNotNull(result1);
            assertNotNull(result2);
            assertNotEquals(result1, result2); // Different agents should have different cards
            verify(aiService).subscribeAgentCard(eq(agentName1), any());
            verify(aiService).subscribeAgentCard(eq(agentName2), any());
        }
    }

    @Test
    @DisplayName("Should handle null AgentCardDetailInfo from service")
    void testGetAgentCardHandlesNullResult() throws NacosException {
        // Given
        String agentName = "test-agent";
        when(aiService.subscribeAgentCard(eq(agentName), any())).thenReturn(null);

        try (MockedStatic<AgentCardConverterUtil> mockedConverter =
                mockStatic(AgentCardConverterUtil.class)) {
            mockedConverter
                    .when(
                            () ->
                                    AgentCardConverterUtil.convertToA2aAgentCard(
                                            eq((AgentCardDetailInfo) null)))
                    .thenReturn(null);

            // When
            AgentCard result = resolver.getAgentCard(agentName);

            // Then
            // The behavior depends on AgentCardConverterUtil.convertToA2aAgentCard's handling of
            // null
            // This will likely return null, which is acceptable
            assertNull(result);
            mockedConverter.verify(
                    () ->
                            AgentCardConverterUtil.convertToA2aAgentCard(
                                    eq((AgentCardDetailInfo) null)),
                    times(1));
        }
    }

    @Test
    @DisplayName("Should handle new AgentCard when callback called from nacos client")
    void testGetAgentCardHandlesNewAgentCard() throws NacosException {
        String agentName = "test-agent";
        AtomicReference<AbstractNacosAgentCardListener> listenerRef = new AtomicReference<>();
        when(aiService.subscribeAgentCard(eq(agentName), any()))
                .thenAnswer(
                        (Answer<AgentCardDetailInfo>)
                                invocationOnMock -> {
                                    AbstractNacosAgentCardListener listener =
                                            invocationOnMock.getArgument(1);
                                    listenerRef.set(listener);
                                    return null;
                                });
        try (MockedStatic<AgentCardConverterUtil> mockedConverter =
                mockStatic(AgentCardConverterUtil.class)) {
            // First time mock return null
            mockedConverter
                    .when(
                            () ->
                                    AgentCardConverterUtil.convertToA2aAgentCard(
                                            eq((AgentCardDetailInfo) null)))
                    .thenReturn(null);
            AgentCard result = resolver.getAgentCard(agentName);
            assertNull(result);

            // mock callback for agent changed
            AgentCardDetailInfo detailInfo = mock(AgentCardDetailInfo.class);
            when(detailInfo.getName()).thenReturn(agentName);
            AgentCard expectedAgentCard = mock(AgentCard.class);
            mockedConverter
                    .when(() -> AgentCardConverterUtil.convertToA2aAgentCard(detailInfo))
                    .thenReturn(expectedAgentCard);
            listenerRef.get().onEvent(new NacosAgentCardEvent(detailInfo));

            // Second time can get agent card
            result = resolver.getAgentCard(agentName);
            assertSame(expectedAgentCard, result);
        }
    }
}

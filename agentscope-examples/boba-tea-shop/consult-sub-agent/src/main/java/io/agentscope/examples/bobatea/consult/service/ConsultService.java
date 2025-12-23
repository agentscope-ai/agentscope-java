/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.examples.bobatea.consult.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import io.agentscope.examples.bobatea.consult.entity.Product;
import io.agentscope.examples.bobatea.consult.mapper.ProductMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Consultation Knowledge Base Service Class
 * Provides retrieval services for bubble tea shop products and store information
 */
@Service
public class ConsultService {

    private static final Logger logger = LoggerFactory.getLogger(ConsultService.class);

    @Value("${spring.ai.dashscope.document-retrieval.index-id}")
    private String indexID;

    @Value("${spring.ai.dashscope.document-retrieval.enable-reranking}")
    private boolean enableReranking;

    @Value("${spring.ai.dashscope.document-retrieval.rerank-top-n}")
    private int rerankTopN;

    @Value("${spring.ai.dashscope.document-retrieval.rerank-min-score}")
    private float rerankMinScore;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private DashScopeApi dashscopeApi;

    @Autowired private ProductMapper productMapper;

    public ConsultService() {}

    /**
     * Initialize document retriever
     * Use @PostConstruct to ensure execution after dependency injection is complete
     */
    @PostConstruct
    public void initRetriever() {
        this.dashscopeApi = DashScopeApi.builder().apiKey(apiKey).build();
    }

    /**
     * Search knowledge base by query content
     */
    public String searchKnowledge(String query) {
        logger.info("=== ConsultService.searchKnowledge entry ===");
        logger.info("Request parameter - query: {}", query);

        try {
            DashScopeDocumentRetrieverOptions options =
                    DashScopeDocumentRetrieverOptions.builder()
                            .withEnableReranking(enableReranking)
                            .withRerankTopN(rerankTopN)
                            .withRerankMinScore(rerankMinScore)
                            .build();
            List<Document> documents = dashscopeApi.retriever(indexID, query, options);

            logger.info("Retrieved document count: {}", documents.size());

            if (documents.isEmpty()) {
                String result = "No relevant information found, query content: " + query;
                logger.info("=== ConsultService.searchKnowledge exit ===");
                logger.info("Return result: {}", result);
                return result;
            }

            // Combine all document text content, using \n\n as separator
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                String text = document.getText();

                if (!text.trim().isEmpty()) {
                    result.append(text);

                    // If not the last document, add separator
                    if (i < documents.size() - 1) {
                        result.append("\n\n");
                    }
                }
            }

            String finalResult = result.toString();
            logger.info("=== ConsultService.searchKnowledge exit ===");
            logger.info("Return result length: {} characters", finalResult.length());
            logger.info(
                    "Return result preview: {}",
                    finalResult.length() > 200
                            ? finalResult.substring(0, 200) + "..."
                            : finalResult);

            return finalResult;
        } catch (Exception e) {
            logger.error("Knowledge base retrieval exception", e);
            String errorResult = "Knowledge base retrieval failed: " + e.getMessage() + ", query content: " + query;
            logger.info("=== ConsultService.searchKnowledge exit ===");
            logger.info("Return error result: {}", errorResult);
            return errorResult;
        }
    }

    /**
     * Get all available product list
     */
    public List<Product> getAllProducts() {
        logger.info("=== ConsultService.getAllProducts entry ===");

        try {
            List<Product> products = productMapper.selectAllAvailable();

            logger.info("=== ConsultService.getAllProducts exit ===");
            logger.info("Return result - total products: {}", products.size());

            return products;
        } catch (Exception e) {
            logger.error("Get product list exception", e);
            return new ArrayList<>();
        }
    }

    /**
     * Get product details by product name
     */
    public Product getProductByName(String productName) {
        logger.info("=== ConsultService.getProductByName entry ===");
        logger.info("Request parameter - productName: {}", productName);

        try {
            Product product = productMapper.selectByNameAndStatus(productName, 1);

            logger.info("=== ConsultService.getProductByName exit ===");
            logger.info("Return result - product: {}", product != null ? product.getName() : "null");

            return product;
        } catch (Exception e) {
            logger.error("Get product details exception", e);
            return null;
        }
    }

    /**
     * Fuzzy search product list by product name
     */
    public List<Product> searchProductsByName(String productName) {
        logger.info("=== ConsultService.searchProductsByName entry ===");
        logger.info("Request parameter - productName: {}", productName);

        try {
            List<Product> products = productMapper.selectByNameLike(productName);

            logger.info("=== ConsultService.searchProductsByName exit ===");
            logger.info("Return result - total products: {}", products.size());

            return products;
        } catch (Exception e) {
            logger.error("Search products exception", e);
            return new ArrayList<>();
        }
    }

    /**
     * Validate if product exists and is available
     */
    public boolean validateProduct(String productName) {
        logger.info("=== ConsultService.validateProduct entry ===");
        logger.info("Request parameter - productName: {}", productName);

        try {
            boolean exists = productMapper.existsByNameAndStatusTrue(productName) > 0;

            logger.info("=== ConsultService.validateProduct exit ===");
            logger.info("Return result - exists: {}", exists);

            return exists;
        } catch (Exception e) {
            logger.error("Validate product exception", e);
            return false;
        }
    }
}

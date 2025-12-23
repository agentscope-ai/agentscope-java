/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * 咨询知识库服务类 提供奶茶店产品和店铺信息的检索服务
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
     * 初始化文档检索器 使用@PostConstruct确保在依赖注入完成后执行
     */
    @PostConstruct
    public void initRetriever() {
        this.dashscopeApi = DashScopeApi.builder().apiKey(apiKey).build();
    }

    /**
     * 根据查询内容检索知识库
     */
    public String searchKnowledge(String query) {
        logger.info("=== ConsultService.searchKnowledge 入口 ===");
        logger.info("请求参数 - query: {}", query);

        try {
            DashScopeDocumentRetrieverOptions options =
                    DashScopeDocumentRetrieverOptions.builder()
                            .withEnableReranking(enableReranking)
                            .withRerankTopN(rerankTopN)
                            .withRerankMinScore(rerankMinScore)
                            .build();
            List<Document> documents = dashscopeApi.retriever(indexID, query, options);

            logger.info("检索到文档数量: {}", documents.size());

            if (documents.isEmpty()) {
                String result = "未找到相关资料，查询内容：" + query;
                logger.info("=== ConsultService.searchKnowledge 出口 ===");
                logger.info("返回结果: {}", result);
                return result;
            }

            // 整合所有文档的text内容，用\n\n作为分隔符
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                String text = document.getText();

                if (!text.trim().isEmpty()) {
                    result.append(text);

                    // 如果不是最后一个文档，添加分隔符
                    if (i < documents.size() - 1) {
                        result.append("\n\n");
                    }
                }
            }

            String finalResult = result.toString();
            logger.info("=== ConsultService.searchKnowledge 出口 ===");
            logger.info("返回结果长度: {} 字符", finalResult.length());
            logger.info(
                    "返回结果预览: {}",
                    finalResult.length() > 200
                            ? finalResult.substring(0, 200) + "..."
                            : finalResult);

            return finalResult;
        } catch (Exception e) {
            logger.error("知识库检索异常", e);
            String errorResult = "知识库检索失败: " + e.getMessage() + "，查询内容：" + query;
            logger.info("=== ConsultService.searchKnowledge 出口 ===");
            logger.info("返回错误结果: {}", errorResult);
            return errorResult;
        }
    }

    /**
     * 获取所有可用产品列表
     */
    public List<Product> getAllProducts() {
        logger.info("=== ConsultService.getAllProducts 入口 ===");

        try {
            List<Product> products = productMapper.selectAllAvailable();

            logger.info("=== ConsultService.getAllProducts 出口 ===");
            logger.info("返回结果 - 产品总数: {}", products.size());

            return products;
        } catch (Exception e) {
            logger.error("获取产品列表异常", e);
            return new ArrayList<>();
        }
    }

    /**
     * 根据产品名称获取产品详情
     */
    public Product getProductByName(String productName) {
        logger.info("=== ConsultService.getProductByName 入口 ===");
        logger.info("请求参数 - productName: {}", productName);

        try {
            Product product = productMapper.selectByNameAndStatus(productName, 1);

            logger.info("=== ConsultService.getProductByName 出口 ===");
            logger.info("返回结果 - product: {}", product != null ? product.getName() : "null");

            return product;
        } catch (Exception e) {
            logger.error("获取产品详情异常", e);
            return null;
        }
    }

    /**
     * 根据产品名称模糊搜索产品列表
     */
    public List<Product> searchProductsByName(String productName) {
        logger.info("=== ConsultService.searchProductsByName 入口 ===");
        logger.info("请求参数 - productName: {}", productName);

        try {
            List<Product> products = productMapper.selectByNameLike(productName);

            logger.info("=== ConsultService.searchProductsByName 出口 ===");
            logger.info("返回结果 - 产品总数: {}", products.size());

            return products;
        } catch (Exception e) {
            logger.error("搜索产品异常", e);
            return new ArrayList<>();
        }
    }

    /**
     * 验证产品是否存在且可用
     */
    public boolean validateProduct(String productName) {
        logger.info("=== ConsultService.validateProduct 入口 ===");
        logger.info("请求参数 - productName: {}", productName);

        try {
            boolean exists = productMapper.existsByNameAndStatusTrue(productName) > 0;

            logger.info("=== ConsultService.validateProduct 出口 ===");
            logger.info("返回结果 - exists: {}", exists);

            return exists;
        } catch (Exception e) {
            logger.error("验证产品异常", e);
            return false;
        }
    }
}

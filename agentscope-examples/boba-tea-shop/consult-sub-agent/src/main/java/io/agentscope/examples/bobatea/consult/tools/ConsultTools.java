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

package io.agentscope.examples.bobatea.consult.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.bobatea.consult.entity.Product;
import io.agentscope.examples.bobatea.consult.service.ConsultService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Consultation Knowledge Base MCP Tool Class
 * Provides knowledge base retrieval tools under MCP protocol
 */
@Service
public class ConsultTools {

    @Autowired private ConsultService consultService;

    /**
     * Knowledge base retrieval tool
     */
    @Tool(
            name = "consult-search-knowledge",
            description = "根据用户查询内容检索云边奶茶铺知识库，包括产品信息、店铺介绍等。支持模糊匹配，可以查询产品名称、描述、分类、茶底等信息。")
    public String searchKnowledge(
            @ToolParam(
                            name = "query",
                            description = "查询内容，可以是产品名称、产品描述关键词、店铺信息关键词等，例如：云边茉莉、经典奶茶、品牌介绍等")
                    String query) {
        try {
            String result = consultService.searchKnowledge(query);
            return result;
        } catch (Exception e) {
            return "知识库检索失败: " + e.getMessage();
        }
    }

    /**
     * Get all products list tool
     */
    @Tool(
            name = "consult-get-products",
            description = "获取云边奶茶铺所有可用产品的完整列表，包括产品名称、详细描述、当前价格和库存数量。帮助用户了解可选择的奶茶产品。")
    public String getProducts() {
        try {
            List<Product> products = consultService.getAllProducts();
            if (products.isEmpty()) {
                return "当前没有任何可用产品。";
            }

            StringBuilder result = new StringBuilder("云边奶茶铺可用产品列表:\n");
            for (Product product : products) {
                result.append(
                        String.format(
                                "- %s: %s, 价格: %.2f元, 库存: %d件\n",
                                product.getName(),
                                product.getDescription(),
                                product.getPrice(),
                                product.getStock()));
            }

            return result.toString();
        } catch (Exception e) {
            return "获取产品列表失败: " + e.getMessage();
        }
    }

    /**
     * Get product detailed information tool
     */
    @Tool(
            name = "consult-get-product-info",
            description = "获取指定产品的详细信息，包括产品描述、价格和当前库存状态。帮助用户了解产品的具体信息。")
    public String getProductInfo(
            @ToolParam(
                            name = "productName",
                            description =
                                    "产品名称，必须是云边奶茶铺的现有产品，如：云边茉莉、桂花云露、云雾观音、云山红韵、云桃乌龙、云边普洱、云桂龙井、云峰山茶")
                    String productName) {
        try {
            Product product = consultService.getProductByName(productName);
            if (product == null) {
                return "产品不存在或已下架: " + productName;
            }

            return String.format(
                    "产品信息:\n名称: %s\n描述: %s\n价格: %.2f元\n库存: %d件\n保质期: %d分钟\n制作时间: %d分钟",
                    product.getName(),
                    product.getDescription(),
                    product.getPrice(),
                    product.getStock(),
                    product.getShelfTime(),
                    product.getPreparationTime());
        } catch (Exception e) {
            return "获取产品信息失败: " + e.getMessage();
        }
    }

    /**
     * Fuzzy search products by product name tool
     */
    @Tool(
            name = "consult-search-products",
            description = "根据产品名称进行模糊搜索，返回匹配的产品列表。支持部分名称搜索，例如搜索'云'可以找到所有包含'云'字的产品。")
    public String searchProducts(
            @ToolParam(name = "productName", description = "产品名称关键词，支持模糊匹配，例如：云、茉莉、乌龙等")
                    String productName) {
        try {
            List<Product> products = consultService.searchProductsByName(productName);
            if (products.isEmpty()) {
                return "未找到匹配的产品: " + productName;
            }

            StringBuilder result = new StringBuilder("搜索结果 (" + products.size() + " 个产品):\n");
            for (Product product : products) {
                result.append(
                        String.format(
                                "- %s: %s, 价格: %.2f元, 库存: %d件\n",
                                product.getName(),
                                product.getDescription(),
                                product.getPrice(),
                                product.getStock()));
            }

            return result.toString();
        } catch (Exception e) {
            return "搜索产品失败: " + e.getMessage();
        }
    }
}

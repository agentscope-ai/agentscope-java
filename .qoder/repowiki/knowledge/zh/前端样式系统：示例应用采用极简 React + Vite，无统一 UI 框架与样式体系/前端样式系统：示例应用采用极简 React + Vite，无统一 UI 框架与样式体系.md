---
kind: frontend_style
name: 前端样式系统：示例应用采用极简 React + Vite，无统一 UI 框架与样式体系
slug: frontend_style
category: frontend_style
scope:
    - '**'
---

本仓库为 AgentScope Java 后端框架，整体以 Maven 多模块聚合工程为主，不包含统一的 Web 前端或 UI 组件库。仅在 `agentscope-examples/agents/` 下的三个示例应用（agentscope-builder、agentscope-dataagent、agentscope-paw）各自附带一个独立的轻量级前端子项目，用于演示与后端交互。

这些前端子项目的技术栈高度一致且极其精简：
- 构建工具：Vite 6 + @vitejs/plugin-react
- 运行时：React 18 + react-router-dom 6
- 语言：TypeScript
- 打包输出：通过 `vite.config.ts` 将产物直接构建到对应 Spring Boot 模块的 `src/main/resources/static`，由后端静态资源服务托管
- 开发代理：Vite dev server 将 `/api` 请求反向代理至 `http://localhost:8080`

在样式层面，这三个前端项目均未引入任何 CSS 框架或 UI 组件库（如 Tailwind、Ant Design、MUI、Chakra、styled-components、Emotion 等），也未发现 `.css` / `.scss` / `.less` 文件及 `tailwind.config.*` 配置文件。它们依赖浏览器默认样式，仅使用内联 style 或最小化的 CSS-in-JS 方式实现界面。

此外，仓库根目录的 `docs/` 下存在 Sphinx 文档站点的 `_static/custom.css` 等样式文件，但这是文档站点而非产品 UI，不属于框架的前端样式体系。

结论：该仓库不存在跨模块统一的前端样式系统、设计令牌或 UI 组件库；示例前端仅为最简演示用途，未形成可复用的样式规范。
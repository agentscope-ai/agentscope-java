---
kind: frontend_style
name: 前端样式体系：示例应用 React+Vite + 文档站 Furo 主题定制
category: frontend_style
scope:
    - '**'
source_files:
    - agentscope-examples/agents/agentscope-builder/frontend/vite.config.ts
    - agentscope-examples/agents/agentscope-dataagent/frontend/vite.config.ts
    - agentscope-examples/agents/agentscope-paw/frontend/vite.config.ts
    - docs/_static/custom.css
    - docs/_static/homepage.css
---

本仓库的前端样式体系分为两个相对独立的层次：示例应用的轻量 React 前端与基于 Sphinx/Furo 的文档站点。两者均遵循“最小依赖、纯 CSS 变量驱动”的风格策略，未引入大型 UI 组件库或原子化框架。

## 1. 示例应用前端（agentscope-builder / agentscope-dataagent / agentscope-paw）

- **技术栈**：React 18 + TypeScript + Vite 6，路由使用 react-router-dom；无 Tailwind、无 styled-components/emotion、无 AntD/MUI/Chakra/shadcn 等第三方 UI 库。
- **构建产物**：`vite.config.ts` 将静态资源直接输出到对应 Spring Boot 模块的 `src/main/resources/static`，由后端以静态文件方式提供。开发期通过 `/api` 代理到 `http://localhost:8080`。
- **目录结构**：每个示例 frontend 子项目采用统一划分——`src/api/`（按领域拆分的 fetch 封装）、`src/components/`（页面级复用组件如 AppShell、ChatPanel、WorkspaceEditor 等）、`src/pages/`（路由页面）。
- **样式策略**：未发现任何 `.css`/`.scss`/`.less` 文件，也未在 package.json 中声明样式相关依赖。推测样式主要通过内联 style 对象或 JSX className 配合全局 CSS 注入实现，整体风格保持极简，不依赖设计令牌系统。

## 2. 文档站点（docs/_static）

- **主题基础**：基于 Sphinx 的 Furo 主题，所有颜色、字体、间距等视觉变量均来自 Furo 的 CSS 自定义属性（如 `--color-brand-primary`、`--color-background-secondary`、`--font-stack`），天然支持 light/dark 模式切换。
- **覆盖层**：
  - `custom.css`（~1300 行）：重写语言切换器、版本选择器、顶部导航栏、侧边栏分组标题、博客文章页右侧目录隐藏等交互与布局细节。
  - `homepage.css`（~775 行）：为首页 Landing 页面定义独立视觉体系，包括 Hero 区、Mac 风格代码窗口、统计条、特性卡片网格、FAQ 手风琴等，全部通过 `body.agentscope-home` 命名空间隔离，避免污染文档正文。
- **响应式与可访问性**：大量使用 `clamp()`、`@media (max-width: ...)` 断点以及 `prefers-reduced-motion`、`prefers-color-scheme`、`prefers-contrast: high` 媒体查询，确保移动端友好与无障碍体验。
- **多语言/多版本**：通过 `body[data-current-lang]`、`body[data-current-tab]` 等 data 属性配合 CSS `:has()` 选择器动态控制 TOC 分组显示与文案，无需 JS 重绘。

## 3. 架构与约定

- **零设计令牌文件**：仓库不存在统一的 design-token JSON/YAML 或 CSS 变量集中定义文件；文档站依赖 Furo 内置变量，示例应用则未建立共享样式层。
- **命名空间隔离**：文档站通过 `body.agentscope-home`、`.agentscope-landing` 等类名限定作用域，避免与 Furo 默认样式冲突。
- **构建集成**：示例前端作为 Maven 子模块被打包进 Spring Boot JAR；文档站通过 Sphinx 构建时加载 `_config.yml` 中注册的 `custom.css`、`homepage.css`。

## 4. 开发者应遵循的规则

- 新增示例前端页面时，沿用 `src/api/` + `src/components/` + `src/pages/` 三目录结构，并通过 `vite.config.ts` 的 proxy 对接本地后端。
- 文档站样式修改优先覆盖 Furo 变量而非硬编码色值；首页专属样式放入 `homepage.css` 并始终带 `body.agentscope-home` 前缀。
- 避免在示例应用中引入新的 CSS 框架或 UI 库，以保持各示例包体积一致、构建链路简单。
- 涉及交互动画时，需同时提供 `prefers-reduced-motion: reduce` 降级方案。
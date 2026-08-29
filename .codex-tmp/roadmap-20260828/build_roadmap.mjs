import fs from "node:fs/promises";
import {
  FileBlob,
  Presentation,
  PresentationFile,
} from "@oai/artifact-tool";

const TMP_DIR = "/Users/ken/agentscope-2/agentscope-java/.codex-tmp/roadmap-20260828";
const STARTER_PPTX = `${TMP_DIR}/template-starter.pptx`;
const FINAL_PPTX = "/Users/ken/agentscope-2/agentscope-java/docs/AgentScope Java 2.0 Roadmap.pptx";
const FONT = "Alibaba PuHuiTi";

const C = {
  navy: "#09124F",
  blue: "#2969F2",
  cyan: "#16B9CE",
  teal: "#19BFA6",
  purple: "#7458E8",
  orange: "#FF7A1A",
  ink: "#23314D",
  muted: "#68768F",
  line: "#DCE4F2",
  paleBlue: "#EEF4FF",
  paleCyan: "#EAFBFD",
  palePurple: "#F2EEFF",
  paleOrange: "#FFF2E8",
  white: "#FFFFFF",
  bg: "#FAFCFF",
};

async function writeBlob(path, blob) {
  await fs.writeFile(path, new Uint8Array(await blob.arrayBuffer()));
}

function rect(slide, x, y, w, h, fill, radius = "rounded-xl", line = "none") {
  return slide.shapes.add({
    geometry: "roundRect",
    position: { left: x, top: y, width: w, height: h },
    fill,
    line: line === "none" ? { style: "solid", fill: "none", width: 0 } : line,
    borderRadius: radius,
  });
}

function textBox(slide, text, x, y, w, h, style = {}) {
  const shape = slide.shapes.add({
    geometry: "textbox",
    position: { left: x, top: y, width: w, height: h },
    fill: "none",
    line: { style: "solid", fill: "none", width: 0 },
  });
  shape.text = text;
  shape.text.style = {
    typeface: FONT,
    fontSize: 26,
    color: C.ink,
    alignment: "left",
    verticalAlignment: "middle",
    wrap: "square",
    autoFit: "shrinkText",
    insets: { top: 0, right: 0, bottom: 0, left: 0 },
    ...style,
  };
  return shape;
}

function circleLabel(slide, label, x, y, size, fill) {
  const shape = slide.shapes.add({
    geometry: "ellipse",
    position: { left: x, top: y, width: size, height: size },
    fill,
    line: { style: "solid", fill: "none", width: 0 },
  });
  shape.text = label;
  shape.text.style = {
    typeface: FONT,
    fontSize: 24,
    bold: true,
    color: C.white,
    alignment: "center",
    verticalAlignment: "middle",
    insets: { top: 0, right: 0, bottom: 0, left: 0 },
  };
  return shape;
}

function addRoadmapTrack(slide, cfg) {
  const { y, number, color, pale, title, thesis, items } = cfg;
  rect(slide, 92, y, 1966, 225, C.white, "rounded-2xl", {
    style: "solid",
    fill: C.line,
    width: 2,
  });
  rect(slide, 92, y, 20, 225, color, "rounded-xl");
  circleLabel(slide, number, 146, y + 62, 86, color);
  textBox(slide, title, 272, y + 35, 500, 58, {
    fontSize: 34,
    bold: true,
    color,
  });
  textBox(slide, thesis, 272, y + 96, 500, 82, {
    fontSize: 24,
    color: C.muted,
    verticalAlignment: "top",
  });

  const startX = 820;
  const gap = 18;
  const itemW = (1160 - gap * (items.length - 1)) / items.length;
  items.forEach((item, index) => {
    const x = startX + index * (itemW + gap);
    rect(slide, x, y + 35, itemW, 155, pale, "rounded-xl");
    textBox(slide, item.label, x + 24, y + 51, itemW - 48, 44, {
      fontSize: 28,
      bold: true,
      color: C.ink,
      alignment: "center",
    });
    textBox(slide, item.detail, x + 24, y + 99, itemW - 48, 67, {
      fontSize: 22,
      color: C.muted,
      alignment: "center",
      verticalAlignment: "top",
    });
  });
}

async function buildRoadmapOverviewPng() {
  const deck = Presentation.create({ slideSize: { width: 2150, height: 1104 } });
  const slide = deck.slides.add();
  slide.background.fill = C.bg;

  textBox(slide, "三条主线协同演进", 92, 24, 820, 50, {
    fontSize: 34,
    bold: true,
    color: C.navy,
  });
  textBox(slide, "能力成熟度", 1740, 28, 300, 40, {
    fontSize: 22,
    bold: true,
    color: C.muted,
    alignment: "right",
  });
  rect(slide, 1740, 75, 300, 8, C.line, "rounded-full");
  rect(slide, 1740, 75, 300, 8, {
    type: "gradient",
    gradientKind: "linear",
    angleDeg: 0,
    stops: [
      { offset: 0, color: C.cyan },
      { offset: 50000, color: C.blue },
      { offset: 100000, color: C.purple },
    ],
  }, "rounded-full");

  addRoadmapTrack(slide, {
    y: 110,
    number: "01",
    color: C.cyan,
    pale: C.paleCyan,
    title: "Harness 能力完善",
    thesis: "把长任务所需的目标、记忆与安全执行能力沉到运行时内核。",
    items: [
      { label: "Goal", detail: "Executor × Verifier\n目标校验与反馈闭环" },
      { label: "Memory", detail: "Agentic / Static\n统一记忆策略与存储" },
      { label: "Sandbox", detail: "E2B 等后端\n隔离、恢复、弹性执行" },
    ],
  });

  addRoadmapTrack(slide, {
    y: 360,
    number: "02",
    color: C.blue,
    pale: C.paleBlue,
    title: "生产化闭环",
    thesis: "让每一次 AgentLoop 都可观测、可评估，并纳入统一托管。",
    items: [
      { label: "AgentLoop 观测", detail: "Agent / Model / Tool\n全链路事件与追踪" },
      { label: "评估接入", detail: "离线回归 + 在线反馈\n数据驱动持续优化" },
      { label: "AgentCore 托管", detail: "构建 · 部署 · 运行\n治理 · 扩缩 · 恢复" },
    ],
  });

  addRoadmapTrack(slide, {
    y: 610,
    number: "03",
    color: C.purple,
    pale: C.palePurple,
    title: "规模化协作与服务",
    thesis: "从单体 Agent 扩展为跨节点团队，并开放统一服务入口。",
    items: [
      { label: "多 Agent 编排", detail: "Remote Subagent\nAgentTeams" },
      { label: "RAG Service", detail: "知识库服务化\n多租户与分布式" },
      { label: "Channel / Gateway", detail: "多渠道接入\n统一路由与会话" },
    ],
  });

  rect(slide, 92, 887, 1966, 145, C.navy, "rounded-2xl");
  textBox(slide, "目标形态", 142, 913, 230, 40, {
    fontSize: 24,
    bold: true,
    color: "#8FC8FF",
  });
  textBox(slide, "可恢复  ·  可观测  ·  可评估  ·  可托管  ·  可协作", 390, 905, 1550, 64, {
    fontSize: 36,
    bold: true,
    color: C.white,
    alignment: "center",
  });
  textBox(slide, "从框架能力走向企业级 Agent 生产平台", 390, 969, 1550, 34, {
    fontSize: 22,
    color: "#C7D5FF",
    alignment: "center",
  });

  const out = `${TMP_DIR}/roadmap-overview.png`;
  await writeBlob(out, await deck.export({ slide, format: "png", scale: 1 }));
  return out;
}

function lifecycleNode(slide, label, x, y, fill) {
  const node = rect(slide, x, y, 235, 92, fill, "rounded-xl");
  node.line = { style: "solid", fill: C.white, width: 2 };
  textBox(slide, label, x + 18, y + 18, 199, 56, {
    fontSize: 25,
    bold: true,
    color: C.white,
    alignment: "center",
  });
}

function sideCapability(slide, label, detail, x, y, w, color, pale) {
  rect(slide, x, y, w, 118, pale, "rounded-xl");
  rect(slide, x, y, 12, 118, color, "rounded-xl");
  textBox(slide, label, x + 30, y + 15, w - 48, 39, {
    fontSize: 27,
    bold: true,
    color,
  });
  textBox(slide, detail, x + 30, y + 59, w - 48, 42, {
    fontSize: 21,
    color: C.muted,
    verticalAlignment: "top",
  });
}

async function buildProductionLoopPng() {
  const deck = Presentation.create({ slideSize: { width: 2150, height: 1104 } });
  const slide = deck.slides.add();
  slide.background.fill = C.bg;

  rect(slide, 110, 48, 1930, 92, C.navy, "rounded-2xl");
  textBox(slide, "AgentLoop 观测与评估横切全链路", 155, 66, 760, 54, {
    fontSize: 32,
    bold: true,
    color: C.white,
  });
  textBox(slide, "Agent → Model → Tool → Subagent → Service", 995, 68, 960, 48, {
    fontSize: 25,
    color: "#C7D5FF",
    alignment: "right",
  });

  textBox(slide, "Harness Runtime Kernel", 110, 176, 440, 48, {
    fontSize: 28,
    bold: true,
    color: C.cyan,
  });
  sideCapability(slide, "Goal", "目标驱动执行与验证闭环", 110, 238, 430, C.cyan, C.paleCyan);
  sideCapability(slide, "Memory", "Agentic / Static / 可插拔存储", 110, 374, 430, C.teal, "#EBFAF6");
  sideCapability(slide, "Sandbox", "E2B 等隔离与恢复后端", 110, 510, 430, C.orange, C.paleOrange);

  slide.shapes.add({
    geometry: "ellipse",
    position: { left: 625, top: 245, width: 900, height: 630 },
    fill: "none",
    line: { style: "solid", fill: "#CBD8F3", width: 20 },
  });
  slide.shapes.add({
    geometry: "ellipse",
    position: { left: 668, top: 288, width: 814, height: 544 },
    fill: C.paleBlue,
    line: { style: "solid", fill: "none", width: 0 },
  });
  rect(slide, 840, 433, 470, 235, {
    type: "gradient",
    gradientKind: "linear",
    angleDeg: 35,
    stops: [
      { offset: 0, color: C.blue },
      { offset: 100000, color: C.purple },
    ],
  }, "rounded-2xl");
  textBox(slide, "AgentCore", 880, 466, 390, 54, {
    fontSize: 42,
    bold: true,
    color: C.white,
    alignment: "center",
  });
  textBox(slide, "全生命周期托管", 880, 524, 390, 48, {
    fontSize: 30,
    bold: true,
    color: C.white,
    alignment: "center",
  });
  textBox(slide, "统一状态 · 事件 · 版本 · 策略", 880, 586, 390, 34, {
    fontSize: 21,
    color: "#DCE7FF",
    alignment: "center",
  });

  lifecycleNode(slide, "定义 / 构建", 650, 206, C.cyan);
  lifecycleNode(slide, "测试 / 评估", 958, 174, C.blue);
  lifecycleNode(slide, "部署 / 发布", 1265, 206, C.purple);
  lifecycleNode(slide, "运行 / 扩缩", 1320, 720, C.orange);
  lifecycleNode(slide, "观测 / 告警", 958, 835, C.blue);
  lifecycleNode(slide, "复盘 / 优化", 595, 720, C.teal);

  textBox(slide, "协作与服务扩展", 1610, 176, 430, 48, {
    fontSize: 28,
    bold: true,
    color: C.purple,
    alignment: "right",
  });
  sideCapability(slide, "Remote Subagent", "跨进程 / 跨节点委派与事件回传", 1610, 238, 430, C.purple, C.palePurple);
  sideCapability(slide, "AgentTeams", "Team Lead + Task Board + Mailbox", 1610, 374, 430, C.blue, C.paleBlue);
  sideCapability(slide, "RAG Service", "知识与检索能力服务化", 1610, 510, 430, C.teal, "#EBFAF6");
  sideCapability(slide, "Channel / Gateway", "统一入口、路由与会话承接", 1610, 646, 430, C.orange, C.paleOrange);

  rect(slide, 110, 951, 1930, 92, C.white, "rounded-xl", {
    style: "solid",
    fill: C.line,
    width: 2,
  });
  textBox(slide, "统一契约", 155, 971, 230, 48, {
    fontSize: 25,
    bold: true,
    color: C.navy,
  });
  textBox(slide, "事件流 + 状态存储 + 控制面 API，把内核能力、生产治理与协作服务串成闭环", 390, 966, 1570, 54, {
    fontSize: 26,
    bold: true,
    color: C.ink,
    alignment: "center",
  });

  const out = `${TMP_DIR}/production-loop.png`;
  await writeBlob(out, await deck.export({ slide, format: "png", scale: 1 }));
  return out;
}

async function replaceImageInInheritedSlot(slide, image, path, alt) {
  const oldFrame = image.frame;
  const oldGeometry = image.geometry;
  const oldBorderRadius = image.borderRadius;
  const oldRotation = image.rotation;
  const oldFlipHorizontal = image.flipHorizontal;
  const oldFlipVertical = image.flipVertical;
  const oldLockAspectRatio = image.lockAspectRatio;
  const bytes = await fs.readFile(path);
  image.delete();
  const replacement = slide.images.add({
    blob: bytes,
    contentType: "image/png",
    alt,
    fit: "contain",
    position: oldFrame,
    geometry: oldGeometry ?? "rect",
    borderRadius: oldBorderRadius,
  });
  replacement.rotation = oldRotation;
  replacement.flipHorizontal = oldFlipHorizontal;
  replacement.flipVertical = oldFlipVertical;
  replacement.lockAspectRatio = oldLockAspectRatio;
  return replacement;
}

async function main() {
  await fs.mkdir(`${TMP_DIR}/final-render-2`, { recursive: true });
  // Artifact rendering is intentionally sequential: the renderer owns shared
  // process state and parallel exports can cross-contaminate slide previews.
  const roadmapPng = await buildRoadmapOverviewPng();
  const loopPng = await buildProductionLoopPng();

  const presentation = await PresentationFile.importPptx(
    await FileBlob.load(STARTER_PPTX),
  );

  const importedInspect = await presentation.inspect({
    kind: "slide,textbox,image,notes",
    maxChars: 100000,
  });
  const records = importedInspect.ndjson
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line));
  const record = (slideNumber, kind, predicate = () => true) => {
    const match = records.find(
      (item) => item.slide === slideNumber && item.kind === kind && predicate(item),
    );
    if (!match) throw new Error(`Missing ${kind} on slide ${slideNumber}`);
    return match;
  };

  const title25 = presentation.resolve(
    record(1, "textbox", (item) => item.name?.includes("大标题")).id,
  );
  const subtitle25 = presentation.resolve(
    record(1, "textbox", (item) => item.name === "文本框 3").id,
  );
  const image25 = presentation.resolve(record(1, "image").id);
  const slide25 = presentation.resolve(record(1, "slide").id);
  title25.text = "AgentScope Roadmap：三条能力主线";
  subtitle25.text = "Harness 夯实长任务内核，AgentCore 承接生产运营，多 Agent 与服务能力扩展规模化协作边界。";
  await replaceImageInInheritedSlot(slide25, image25, roadmapPng, "AgentScope 三条主线能力 Roadmap");

  slide25.speakerNotes.textFrame.setText([
    "讲解建议：先讲三条主线并非孤立项目，最终目标是把 Agent 从开发框架推进到可运营的平台能力。",
    "Goal 重点强调 executor / verifier 的目标校验与反馈闭环；Roadmap 不在此页承诺具体版本日期。",
    "[Sources]",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/building-blocks/pipeline/overview",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/building-blocks/pipeline/goal",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/deploy/rag",
    "- /Users/ken/agentscope-2/agentscope-java/docs/AgentScope Java 2.0 v1.pptx (slides 8, 13–15, 18, 20, 22, 24)",
    "[/Sources]",
  ]);
  slide25.speakerNotes.setVisible(true);

  const title26 = presentation.resolve(
    record(2, "textbox", (item) => item.name?.includes("大标题")).id,
  );
  const subtitle26 = presentation.resolve(
    record(2, "textbox", (item) => item.name === "文本框 3").id,
  );
  const image26 = presentation.resolve(record(2, "image").id);
  const slide26 = presentation.resolve(record(2, "slide").id);
  title26.text = "AgentScope Roadmap：统一 Agent 生产闭环";
  subtitle26.text = "统一事件、状态和控制面契约，让开发、运行、治理与协作在同一生命周期内闭环。";
  await replaceImageInInheritedSlot(slide26, image26, loopPng, "AgentCore 托管生命周期与 AgentScope 能力闭环");

  slide26.speakerNotes.textFrame.setText([
    "讲解建议：中间的 AgentCore 负责全生命周期托管；上方 AgentLoop 观测与评估横切所有阶段；左右两侧分别是 Harness 内核与规模化协作/服务扩展。",
    "强调统一契约是闭环关键：事件流负责可观测，状态存储负责恢复，控制面 API 负责运营与治理。",
    "[Sources]",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/building-blocks/pipeline/goal",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/deploy/rag",
    "- /Users/ken/agentscope-2/agentscope-java/docs/v2/en/docs/harness/subagent.md",
    "- /Users/ken/agentscope-2/agentscope-java/docs/v2/en/docs/harness/sandbox.md",
    "- /Users/ken/agentscope-2/agentscope-java/docs/AgentScope Java 2.0 v1.pptx (slides 14, 15, 18, 20, 22, 24)",
    "[/Sources]",
  ]);
  slide26.speakerNotes.setVisible(true);

  for (const [index, slide] of presentation.slides.items.entries()) {
    const stem = `slide-${String(index + 1).padStart(2, "0")}`;
    await writeBlob(
      `${TMP_DIR}/final-render-2/${stem}.png`,
      await presentation.export({ slide, format: "png", scale: 1 }),
    );
    const layout = await slide.export({ format: "layout" });
    await fs.writeFile(`${TMP_DIR}/final-render-2/${stem}.layout.json`, await layout.text());
  }

  const pptx = await PresentationFile.exportPptx(presentation);
  await pptx.save(FINAL_PPTX);

  const inspection = await presentation.inspect({
    kind: "slide,textbox,image,notes,layout",
    maxChars: 24000,
  });
  await fs.writeFile(`${TMP_DIR}/final-inspect.ndjson`, inspection.ndjson, "utf8");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

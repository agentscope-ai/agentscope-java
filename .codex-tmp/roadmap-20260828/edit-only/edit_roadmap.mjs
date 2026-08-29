import fs from "node:fs/promises";
import { FileBlob, PresentationFile } from "@oai/artifact-tool";

const TMP_DIR = "/Users/ken/agentscope-2/agentscope-java/.codex-tmp/roadmap-20260828";
const STARTER_PPTX = `${TMP_DIR}/template-starter.pptx`;
const FINAL_PPTX = "/Users/ken/agentscope-2/agentscope-java/docs/AgentScope Java 2.0 Roadmap.pptx";

async function writeBlob(path, blob) {
  await fs.writeFile(path, new Uint8Array(await blob.arrayBuffer()));
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
}

async function main() {
  await fs.mkdir(`${TMP_DIR}/final-render-3`, { recursive: true });
  const presentation = await PresentationFile.importPptx(
    await FileBlob.load(STARTER_PPTX),
  );

  const snapshot = await presentation.inspect({
    kind: "slide,textbox,image,notes",
    maxChars: 30000,
  });
  const records = snapshot.ndjson
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

  const slide1 = presentation.resolve(record(1, "slide").id);
  presentation.resolve(
    record(1, "textbox", (item) => item.name?.includes("大标题")).id,
  ).text = "AgentScope Roadmap：三条能力主线";
  presentation.resolve(
    record(1, "textbox", (item) => item.name === "文本框 3").id,
  ).text = "Harness 夯实长任务内核，AgentCore 承接生产运营，多 Agent 与服务能力扩展规模化协作边界。";
  await replaceImageInInheritedSlot(
    slide1,
    presentation.resolve(record(1, "image").id),
    `${TMP_DIR}/roadmap-overview.png`,
    "AgentScope 三条主线能力 Roadmap",
  );
  slide1.speakerNotes.textFrame.setText([
    "讲解建议：先讲三条主线并非孤立项目，最终目标是把 Agent 从开发框架推进到可运营的平台能力。",
    "Goal 重点强调 executor / verifier 的目标校验与反馈闭环；Roadmap 不在此页承诺具体版本日期。",
    "[Sources]",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/building-blocks/pipeline/overview",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/building-blocks/pipeline/goal",
    "- https://docs.agentscope.io/versions/2.0.8dev/en/deploy/rag",
    "- /Users/ken/agentscope-2/agentscope-java/docs/AgentScope Java 2.0 v1.pptx (slides 8, 13–15, 18, 20, 22, 24)",
    "[/Sources]",
  ]);
  slide1.speakerNotes.setVisible(true);

  const slide2 = presentation.resolve(record(2, "slide").id);
  presentation.resolve(
    record(2, "textbox", (item) => item.name?.includes("大标题")).id,
  ).text = "AgentScope Roadmap：统一 Agent 生产闭环";
  presentation.resolve(
    record(2, "textbox", (item) => item.name === "文本框 3").id,
  ).text = "统一事件、状态和控制面契约，让开发、运行、治理与协作在同一生命周期内闭环。";
  await replaceImageInInheritedSlot(
    slide2,
    presentation.resolve(record(2, "image").id),
    `${TMP_DIR}/production-loop.png`,
    "AgentCore 托管生命周期与 AgentScope 能力闭环",
  );
  slide2.speakerNotes.textFrame.setText([
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
  slide2.speakerNotes.setVisible(true);

  for (const [index, slide] of presentation.slides.items.entries()) {
    const stem = `slide-${String(index + 1).padStart(2, "0")}`;
    await writeBlob(
      `${TMP_DIR}/final-render-3/${stem}.png`,
      await presentation.export({ slide, format: "png", scale: 1 }),
    );
    const layout = await slide.export({ format: "layout" });
    await fs.writeFile(`${TMP_DIR}/final-render-3/${stem}.layout.json`, await layout.text());
  }

  const pptx = await PresentationFile.exportPptx(presentation);
  await pptx.save(FINAL_PPTX);

  const finalInspect = await presentation.inspect({
    kind: "slide,textbox,image,notes,layout",
    maxChars: 30000,
  });
  await fs.writeFile(`${TMP_DIR}/final-inspect.ndjson`, finalInspect.ndjson, "utf8");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

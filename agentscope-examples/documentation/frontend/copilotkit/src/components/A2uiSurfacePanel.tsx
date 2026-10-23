import { LayoutTemplate } from "lucide-react";

import { WORKBENCH_COMPONENT_NAMES } from "@/a2ui/workbenchCatalog";
import { PanelTitle } from "@/components/PanelTitle";
import type { A2uiSurfaceInfo } from "@/copilot/types";

/**
 * Provenance for the most recently generated A2UI surface.
 *
 * `generatedBy` is the interesting field: it names the model that composed the component tree, or
 * reads `fallback` when every model client was unavailable or produced something that failed
 * validation, in which case a deterministic surface was built from shared state instead.
 */
export function A2uiSurfacePanel({ a2ui }: { a2ui: A2uiSurfaceInfo }) {
  return (
    <section className="grid gap-4">
      <PanelTitle
        eyebrow="Generative UI"
        title="A2UI 界面"
        icon={<LayoutTemplate className="size-5" />}
      />

      {a2ui.surfaceId ? (
        <dl className="grid gap-2">
          <InfoRow label="Surface" value={a2ui.surfaceId} mono />
          <InfoRow label="Catalog" value={a2ui.catalogId ?? "-"} mono />
          <InfoRow label="生成方式" value={a2ui.generatedBy ?? "-"} />
          <InfoRow label="组件数量" value={String(a2ui.componentCount)} />
          <InfoRow label="生成意图" value={a2ui.intent ?? "-"} />
        </dl>
      ) : (
        <p className="rounded-2xl border border-dashed border-border px-4 py-6 text-center text-xs leading-5 text-muted-foreground">
          还没有生成界面。让 Agent 用一块可视化面板汇报指标与计划，组件树会现场生成并直接渲染在对话流中。
        </p>
      )}

      <div className="grid gap-2 rounded-2xl border border-border bg-card/60 p-4">
        <p className="text-xs font-semibold text-muted-foreground">浏览器已注册的自定义组件</p>
        <div className="flex flex-wrap gap-1.5">
          {WORKBENCH_COMPONENT_NAMES.map((name) => (
            <span
              key={name}
              className="rounded-full border border-champagne/25 bg-champagne/[0.07] px-2.5 py-0.5 font-mono text-[0.68rem] font-semibold text-champagne"
            >
              {name}
            </span>
          ))}
        </div>
        <p className="text-[0.68rem] leading-5 text-muted-foreground">
          这份清单随每次运行上报给 Agent，模型只会用其中的组件作图，因此不会生成前端画不出的界面。
        </p>
      </div>
    </section>
  );
}

function InfoRow({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="surface-row grid gap-1">
      <dt className="text-xs font-semibold text-muted-foreground">{label}</dt>
      <dd
        className={
          mono
            ? "m-0 truncate font-mono text-xs font-semibold text-foreground"
            : "m-0 text-sm font-semibold text-foreground"
        }
      >
        {value}
      </dd>
    </div>
  );
}

import { ListChecks, RefreshCcw, Sparkles } from "lucide-react";

import { type DemoState, initialDemoState } from "@/copilot/types";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export function StatePanel({
  state,
  updateState,
}: {
  state: DemoState;
  updateState: (patch: Partial<DemoState>) => void;
}) {
  const metrics = Object.entries(state.metrics);

  return (
    <Card className="border-border/80 bg-card/70 shadow-none backdrop-blur-xl">
      <CardHeader>
        <CardDescription className="eyebrow">Shared State</CardDescription>
        <CardTitle className="font-display flex items-center gap-2 text-xl tracking-tight">
          <ListChecks className="size-5 text-champagne" />
          状态管理
        </CardTitle>
      </CardHeader>
      <CardContent className="grid gap-4">
        <dl className="grid gap-2">
          <StateRow label="主题" value={state.topic} />
          <StateRow label="优先级" value={state.priority} />
          <StateRow label="状态" value={state.status} />
          <StateRow label="人工确认" value={state.approved ? "已批准" : "未批准"} />
          <StateRow label="更新时间" value={state.updatedAt} />
        </dl>
        {metrics.length > 0 ? (
          <div className="grid gap-2">
            <p className="text-xs font-semibold text-muted-foreground">
              最近一次工具查询到的指标
            </p>
            <div className="grid grid-cols-2 gap-2">
              {metrics.map(([key, value]) => (
                <div key={key} className="rounded-xl border border-border bg-secondary/30 px-3 py-2">
                  <p className="truncate text-[0.68rem] font-semibold text-muted-foreground">
                    {key}
                  </p>
                  <p className="font-display truncate text-sm font-semibold text-foreground">
                    {String(value)}
                  </p>
                </div>
              ))}
            </div>
          </div>
        ) : null}
        <div className="flex flex-wrap items-center gap-3 pt-1">
          <Button
            type="button"
            className="rounded-full"
            onClick={() => updateState({ priority: "高", status: "手动提升优先级" })}
          >
            <Sparkles className="size-4" />
            提升优先级
          </Button>
          <Button
            type="button"
            variant="outline"
            className="rounded-full"
            onClick={() => updateState(initialDemoState)}
          >
            <RefreshCcw className="size-4" />
            重置
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function StateRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="surface-row grid gap-1">
      <dt className="text-xs font-semibold text-muted-foreground">{label}</dt>
      <dd className="m-0 font-semibold text-foreground">{value}</dd>
    </div>
  );
}

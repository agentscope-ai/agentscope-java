import { useAttachments, useCopilotKit } from "@copilotkit/react-core/v2";
import { BookOpen, Paperclip, RefreshCcw, Send } from "lucide-react";

import type { DemoState } from "@/copilot/types";
import { useV2HookExamples } from "@/hooks/useV2HookExamples";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export function V2HooksPanel({
  state,
  updateState,
}: {
  state: DemoState;
  updateState: (patch: Partial<DemoState>) => void;
}) {
  const examples = useV2HookExamples({ state, updateState });
  const { copilotkit } = useCopilotKit();
  const attachments = useAttachments({
    config: {
      enabled: true,
      accept: "image/*,text/*,application/pdf",
      maxSize: 5 * 1024 * 1024,
      onUploadFailed: (error) => {
        console.warn("useAttachments upload failed", error);
      },
    },
  });

  const sendSuggestion = async (message: string) => {
    if (examples.agent.isRunning) return;
    examples.agent.addMessage({
      id: crypto.randomUUID(),
      role: "user",
      content: message,
    });
    await copilotkit.runAgent({ agent: examples.agent });
  };

  return (
    <Card className="border-border/80 bg-card/70 shadow-none backdrop-blur-xl">
      <CardHeader>
        <CardDescription className="eyebrow">V2 Hooks</CardDescription>
        <CardTitle className="font-display flex items-center gap-2 text-xl tracking-tight">
          <BookOpen className="size-5 text-champagne" />
          Hook 示例
        </CardTitle>
      </CardHeader>
      <CardContent className="grid gap-5">
        <section className="grid gap-2">
          <h3 className="text-sm font-semibold text-foreground">运行状态</h3>
          <div className="grid gap-2">
            {examples.statuses.map((item) => (
              <div key={item.label} className="surface-row grid gap-1">
                <span className="text-xs font-semibold text-muted-foreground">
                  {item.label}
                </span>
                <strong className="break-all text-sm font-medium text-foreground">
                  {item.value}
                </strong>
              </div>
            ))}
          </div>
        </section>

        <section className="grid gap-2">
          <h3 className="text-sm font-semibold text-foreground">
            useConfigureSuggestions / useSuggestions
          </h3>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={examples.suggestions.reloadSuggestions}
              disabled={examples.suggestions.isLoading}
            >
              <RefreshCcw className="size-4" />
              重新生成
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={examples.suggestions.clearSuggestions}
            >
              清空建议
            </Button>
          </div>
          <div className="grid gap-2">
            {examples.suggestions.suggestions.map((suggestion) => (
              <button
                key={`${suggestion.title}-${suggestion.message}`}
                type="button"
                onClick={() => void sendSuggestion(suggestion.message)}
                className="prompt-chip"
              >
                <span className="mb-1 flex items-center gap-2 font-semibold text-foreground">
                  <Send className="size-3.5 text-champagne" />
                  {suggestion.title}
                </span>
                <span className="text-muted-foreground">{suggestion.message}</span>
              </button>
            ))}
          </div>
        </section>

        <section className="grid gap-2">
          <h3 className="text-sm font-semibold text-foreground">useAttachments</h3>
          <div
            ref={attachments.containerRef}
            onDragOver={attachments.handleDragOver}
            onDragLeave={attachments.handleDragLeave}
            onDrop={attachments.handleDrop}
            className="rounded-2xl border border-dashed border-border bg-secondary/30 p-3"
          >
            <input
              ref={attachments.fileInputRef}
              type="file"
              multiple
              className="hidden"
              onChange={attachments.handleFileUpload}
            />
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                size="sm"
                variant="secondary"
                onClick={() => attachments.fileInputRef.current?.click()}
              >
                <Paperclip className="size-4" />
                选择文件
              </Button>
              <Badge variant={attachments.dragOver ? "success" : "secondary"}>
                {attachments.enabled ? "enabled" : "disabled"}
              </Badge>
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={attachments.consumeAttachments}
              >
                消费 ready 队列
              </Button>
            </div>
            <div className="mt-3 grid gap-2 text-sm text-muted-foreground">
              {attachments.attachments.length === 0 ? (
                <span>拖拽或选择文件后，这里会显示 hook 队列。</span>
              ) : (
                attachments.attachments.map((item) => (
                  <div
                    key={item.id}
                    className="flex items-center justify-between gap-2 rounded-xl bg-background/50 p-2"
                  >
                    <span className="truncate">{item.filename ?? item.id}</span>
                    <button
                      type="button"
                      onClick={() => attachments.removeAttachment(item.id)}
                      className="text-xs font-semibold text-destructive"
                    >
                      移除
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        </section>

        <section className="grid gap-2">
          <h3 className="text-sm font-semibold text-foreground">
            useMemories / learning hooks
          </h3>
          <div className="surface-row text-sm leading-6 text-muted-foreground">
            <p>Memory 数量：{examples.memories.memories.length}</p>
            <p>
              Memory 状态：
              {examples.memories.isAvailable ? "可用" : "当前 Runtime 未提供"}
            </p>
            {examples.memories.error ? <p>{examples.memories.error.message}</p> : null}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={() =>
                void examples.memories.refresh().catch((error) => {
                  console.warn("useMemories refresh failed", error);
                })
              }
            >
              刷新 memory
            </Button>
            <Button type="button" size="sm" onClick={() => void examples.addDemoMemory()}>
              添加 memory
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => void examples.recordAction()}
            >
              记录用户动作
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => void examples.recordCurrentThreadAction()}
            >
              记录当前线程动作
            </Button>
          </div>
          <div className="surface-row text-xs leading-5 text-muted-foreground">
            <strong className="text-foreground">{examples.annotationStatus.label}</strong>
            <p className="break-all">{examples.annotationStatus.value}</p>
          </div>
        </section>

        <section className="grid gap-2">
          <h3 className="text-sm font-semibold text-foreground">
            useRenderToolCall 预览
          </h3>
          <div className="rounded-2xl border border-border bg-background/40 p-3">
            {examples.renderToolPreview ?? (
              <span className="text-sm text-muted-foreground">等待 renderer 注册...</span>
            )}
          </div>
        </section>
      </CardContent>
    </Card>
  );
}

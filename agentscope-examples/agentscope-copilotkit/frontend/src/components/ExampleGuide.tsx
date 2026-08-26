import { CheckCircle2, Copy } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

const EXAMPLES = [
  {
    title: "计划 + 权限组合",
    prompt: "先制定一个三步的生产发布计划，逐步推进，最后一步真的把 v2.4.1 发布到生产环境。",
  },
  {
    title: "工具内置权限检查",
    prompt: "给订单 SO202607310002 退款 8600 元。",
  },
  {
    title: "DENY 硬拦截",
    prompt: "把生产数据集 orders_2025 永久删除，我已经确认过了。",
  },
  {
    title: "A2UI 动态界面",
    prompt: "查询 order-api 的指标，然后生成一块包含指标、风险评分与计划进度的可视化界面。",
  },
  {
    title: "生成式 UI",
    prompt: "渲染订单卡片：订单 A1001，金额 199.9，状态待支付。",
  },
  {
    title: "组件渲染",
    prompt: "展示 v2 Hook Badge：标题 Threads，状态 ready。",
  },
  {
    title: "前端 HITL",
    prompt: "调用 confirmDemoAction，摘要为“确认继续执行示例操作”。",
  },
  {
    title: "默认工具兜底",
    prompt: "调用一个未知工具 unknownDemoTool，参数随便填。",
  },
];

export function ExampleGuide() {
  const [copied, setCopied] = useState<string | null>(null);

  const copyPrompt = async (prompt: string) => {
    try {
      await navigator.clipboard.writeText(prompt);
      setCopied(prompt);
      window.setTimeout(() => setCopied(null), 1600);
    } catch (error) {
      console.warn("copy failed", error);
    }
  };

  return (
    <Card className="border-border/80 bg-card/70 shadow-none backdrop-blur-xl">
      <CardHeader>
        <CardDescription className="eyebrow">Playbook</CardDescription>
        <CardTitle className="font-display flex items-center gap-2 text-xl tracking-tight">
          <CheckCircle2 className="size-5 text-champagne" />
          可测试指令
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="grid gap-3">
          {EXAMPLES.map((example) => (
            <li key={example.prompt} className="surface-row grid gap-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold tracking-[0.16em] text-champagne uppercase">
                  {example.title}
                </span>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  className="h-7 px-2 text-muted-foreground hover:text-champagne"
                  onClick={() => void copyPrompt(example.prompt)}
                >
                  <Copy className="size-3.5" />
                  {copied === example.prompt ? "已复制" : "复制"}
                </Button>
              </div>
              <p className="text-sm leading-6 text-muted-foreground">{example.prompt}</p>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}

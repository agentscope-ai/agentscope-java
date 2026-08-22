import { CopilotChat } from "@copilotkit/react-core/v2";
import "@copilotkit/react-core/v2/styles.css";
import { Bot, ShieldCheck, Sparkles } from "lucide-react";

import { A2uiSurfacePanel } from "@/components/A2uiSurfacePanel";
import { CapabilityLauncher } from "@/components/CapabilityLauncher";
import { ExampleGuide } from "@/components/ExampleGuide";
import { PanelTitle } from "@/components/PanelTitle";
import { PermissionAuditPanel } from "@/components/PermissionAuditPanel";
import { PlanBoard } from "@/components/PlanBoard";
import { StatePanel } from "@/components/StatePanel";
import { StatusPill } from "@/components/StatusPill";
import { ThreadListPanel } from "@/components/ThreadListPanel";
import { V2HooksPanel } from "@/components/V2HooksPanel";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { CHAT_AGENT_ID } from "@/copilot/constants";
import { useDemoState } from "@/hooks/useDemoState";
import { useHitlInterrupt } from "@/hooks/useHitlInterrupt";
import {useV2HookExamples} from "@/hooks/useV2HookExamples";

export default function App() {
  return <CapabilityWorkbench />;
}

function CapabilityWorkbench() {
  const { state, updateState } = useDemoState();

  useHitlInterrupt(updateState);

  useV2HookExamples({ state, updateState });

  return (
    <main className="min-h-screen w-screen overflow-x-hidden px-5 py-6 max-[820px]:px-3 max-[820px]:py-4">
      <div className="mx-auto grid max-w-[1480px] gap-6">
        <section
          id="workbench"
          className="grid gap-4 rounded-[2rem] border border-border bg-background/30 p-4 backdrop-blur-sm max-[640px]:rounded-3xl max-[640px]:p-3"
        >
          <div className="flex items-end justify-between gap-4 max-[820px]:flex-col max-[820px]:items-start">
            <div>
              <p className="eyebrow">Interactive Workbench</p>
              <h2 className="font-display text-2xl font-semibold tracking-tight">
                实时能力控制台
              </h2>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
                左侧管理会话线程，中间进行流式对话，右侧观测共享状态与 CopilotKit v2 Hooks。
              </p>
            </div>
            <div className="grid min-w-64 gap-2 text-sm max-[820px]:w-full max-[820px]:grid-cols-2 max-[520px]:grid-cols-1">
              <StatusPill
                icon={<Sparkles className="size-4" />}
                label="演示主题"
                value={state.topic}
              />
              <StatusPill
                icon={<ShieldCheck className="size-4" />}
                label="审批状态"
                value={state.approved ? "已批准" : "待确认"}
              />
            </div>
          </div>

          <div className="grid min-h-[calc(100vh-8rem)] grid-cols-[280px_minmax(420px,1fr)_380px] gap-4 max-[1280px]:grid-cols-[260px_minmax(380px,1fr)] max-[960px]:grid-cols-1 max-[960px]:min-h-0">
            <ThreadListPanel />

            <section
              className="glass-panel grid min-h-0 grid-rows-[auto_auto_minmax(0,1fr)] overflow-hidden rounded-[1.75rem] p-4 max-[960px]:min-h-[72vh] max-[520px]:rounded-2xl"
              aria-label="CopilotKit 聊天框"
            >
              <div className="flex items-start justify-between gap-3 px-1 pb-4 max-[520px]:flex-col">
                <PanelTitle
                  eyebrow="CopilotChat"
                  title="AgentScope Copilot"
                  icon={<Bot className="size-5" />}
                />
                <Badge className="min-h-8 rounded-full border border-champagne/25 bg-champagne/10 px-3 font-semibold text-champagne hover:bg-champagne/15">
                  AG-UI / SSE
                </Badge>
              </div>
              <CapabilityLauncher />
              <div className="chat-frame min-h-0 overflow-hidden">
                <CopilotChat
                  agentId={CHAT_AGENT_ID}
                  attachments={{ enabled: true }}
                  labels={{
                    chatInputPlaceholder:
                      "试试：制定治理计划、发布到生产环境、生成一块指标看板...",
                    welcomeMessageText:
                      "你好。这里展示 AgentScope 2.0 的权限系统、计划模式、共享状态与 A2UI 动态界面生成。",
                  }}
                />
              </div>
            </section>

            <aside
              className="glass-panel min-h-0 overflow-hidden rounded-[1.75rem] p-3 max-[1280px]:col-span-full max-[520px]:rounded-2xl"
              aria-label="CopilotKit 能力示例"
            >
              <Tabs defaultValue="plan" className="h-full">
                <TabsList>
                  <TabsTrigger value="plan">计划</TabsTrigger>
                  <TabsTrigger value="permission">权限</TabsTrigger>
                  <TabsTrigger value="a2ui">A2UI</TabsTrigger>
                  <TabsTrigger value="state">状态</TabsTrigger>
                  <TabsTrigger value="hooks">Hooks</TabsTrigger>
                  <TabsTrigger value="guide">指令</TabsTrigger>
                </TabsList>
                <TabsContent value="plan" className="max-h-[calc(100vh-12rem)] overflow-auto pr-1">
                  <PlanBoard plan={state.plan} />
                </TabsContent>
                <TabsContent
                  value="permission"
                  className="max-h-[calc(100vh-12rem)] overflow-auto pr-1"
                >
                  <PermissionAuditPanel entries={state.permissionAudit} />
                </TabsContent>
                <TabsContent value="a2ui" className="max-h-[calc(100vh-12rem)] overflow-auto pr-1">
                  <A2uiSurfacePanel a2ui={state.a2ui} />
                </TabsContent>
                <TabsContent value="state" className="max-h-[calc(100vh-12rem)] overflow-auto pr-1">
                  <StatePanel state={state} updateState={updateState} />
                </TabsContent>
                <TabsContent value="hooks" className="max-h-[calc(100vh-12rem)] overflow-auto pr-1">
                  <V2HooksPanel state={state} updateState={updateState} />
                </TabsContent>
                <TabsContent value="guide" className="max-h-[calc(100vh-12rem)] overflow-auto pr-1">
                  <ExampleGuide />
                </TabsContent>
              </Tabs>
            </aside>
          </div>
        </section>
      </div>
    </main>
  );
}

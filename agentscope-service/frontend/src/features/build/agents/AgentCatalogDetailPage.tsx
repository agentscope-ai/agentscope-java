/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import RuntimeWorkspacesPanel from "@/components/RuntimeWorkspacesPanel";
import WorkspaceBindingPanel from "@/components/WorkspaceBindingPanel";
import ChannelAssociations from "@/components/ChannelAssociations";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Copy,
  MessageSquare,
  RefreshCw,
  Share2,
  Bot,
  ArrowRight,
} from "lucide-react";
import { useEffect, useState } from "react";
import {
  Link,
  Navigate,
  Outlet,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import {
  getAgent,
  getAgentDetailOverview,
  listCatalogAgentInstances,
  listCatalogBindings,
  rotateAgentRegistrationCredential,
  setCatalogBindingEnabled,
  type CatalogAgentInstance,
} from "@/api/agents";
import { getRoles } from "@/api/auth";
import { getUsername } from "@/lib/auth";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { EmptyState } from "@/components/EmptyState";
import { JsonViewer } from "@/components/JsonViewer";
import { Page } from "@/components/Page";
import { PublishEndpointCard } from "@/components/PublishEndpointCard";
import AgentSettingsForm from "@/components/AgentSettingsForm";
import ShareAgentDialog from "@/components/ShareAgentDialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { formatRelative } from "@/lib/format";
import { HostedAgentSettings } from "./HostedAgentSettings";
import { AgentGeneralSettings } from "./AgentGeneralSettings";
import {
  agentDetailTabs,
  agentServicePath,
  definitionSections,
  resolveAgentDetailTab,
  type AgentDetailTabId,
} from "./agentNavigation";
import { canEditAgentDefinition } from "./agentAccess";
import { useAgentActivity } from "./useAgentActivity";
import { ActivitySummary, AgentActivityPanel } from "./AgentActivityPanel";

function healthTone(
  health: string,
): "success" | "warning" | "danger" | "default" {
  if (health === "healthy" || health === "ready") return "success";
  if (health === "unhealthy" || health === "offline") return "danger";
  if (health === "unknown") return "warning";
  return "default";
}
function readinessTone(
  state?: string,
): "success" | "warning" | "danger" | "default" {
  if (state === "ready") return "success";
  if (state === "degraded" || state === "unbound") return "warning";
  if (state === "unavailable" || state === "inactive") return "danger";
  return "default";
}
function bindingConfiguration(value: unknown) {
  return value && typeof value === "object"
    ? (value as Record<string, unknown>)
    : {};
}
function bindingLabel(kind: string) {
  if (kind === "managed") return "Managed runtime";
  if (kind === "external-application") return "External application";
  if (kind === "hosted-runtime") return "Hosted runtime";
  return kind;
}
function bindingDescription(kind: string) {
  if (kind === "managed")
    return "AgentScope Service owns the definition, session lifecycle, and execution adapter.";
  if (kind === "external-application")
    return "An independently deployed application registers instances and receives ASDP commands.";
  if (kind === "hosted-runtime")
    return "Execution is launched on demand through an available detected agent runtime.";
  return "Runtime binding configuration.";
}
function InstanceCard({ instance }: { instance: CatalogAgentInstance }) {
  return (
    <div className="rounded-xl border border-border bg-white p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate font-medium">{instance.instanceKey}</div>
          <div className="mt-1 text-sm text-muted-foreground">
            {instance.framework || instance.backendKind || "runtime"}
            {instance.frameworkVersion ? ` ${instance.frameworkVersion}` : ""} ·
            generation {instance.generation}
          </div>
        </div>
        <Badge tone={healthTone(instance.health)}>{instance.health}</Badge>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 text-sm">
        <div>
          <span className="text-muted-foreground">Load</span>
          <div className="mt-0.5 font-medium">
            {instance.activeSessions}/
            {instance.capacity <= 0 ? "∞" : instance.capacity}
          </div>
        </div>
        <div>
          <span className="text-muted-foreground">Last seen</span>
          <div className="mt-0.5 font-medium">
            {formatRelative(instance.lastSeenAt)}
          </div>
        </div>
      </div>
    </div>
  );
}

export default function AgentCatalogDetailPage() {
  const { agentId = "" } = useParams();
  const scope = useControlPlaneScope();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const [registrationCredential, setRegistrationCredential] = useState("");
  const [shareOpen, setShareOpen] = useState(false);
  const [copyStatus, setCopyStatus] = useState("");
  const agent = useQuery({
    queryKey: ["catalog-agent", agentId],
    queryFn: () => getAgent(agentId),
    enabled: !!agentId,
  });
  const runtimeKind = agent.data?.runtimeKind;
  const base = agentServicePath(agentId);
  const definition = location.pathname.includes("/definition");
  const channels = location.pathname.endsWith("/connections/channels");
  const section = definition
    ? location.pathname.split("/").pop() || "behavior"
    : "";
  const tab = definition
    ? "definition"
    : channels
      ? "connections"
      : resolveAgentDetailTab(params.get("tab"), runtimeKind);
  const active = tab === "overview" || tab === "activity";
  const activity = useAgentActivity(agentId, active);
  const overview = useQuery({
    queryKey: ["catalog-agent-overview", agentId],
    queryFn: () => getAgentDetailOverview(agentId),
    enabled: !!agentId,
    refetchInterval: active ? 30_000 : false,
    refetchIntervalInBackground: false,
  });
  const bindings = useQuery({
    queryKey: ["catalog-agent-bindings", agentId],
    queryFn: () => listCatalogBindings(agentId),
    enabled: !!agentId,
  });
  const instances = useQuery({
    queryKey: ["catalog-agent-instances", agentId],
    queryFn: () => listCatalogAgentInstances(agentId),
    enabled: !!agentId && tab === "runtime",
    refetchInterval: tab === "runtime" ? 15_000 : false,
    refetchIntervalInBackground: false,
  });
  const refreshAgent = async () => {
    await queryClient.invalidateQueries({
      queryKey: ["catalog-agent", agentId],
    });
  };
  const refresh = async () => {
    await Promise.all(
      [
        "catalog-agent",
        "catalog-agent-overview",
        "catalog-agent-bindings",
        "catalog-agent-instances",
        "agent-activity-tasks",
        "agent-activity-sessions",
        "hosted-agent-settings",
      ].map((key) =>
        queryClient.invalidateQueries({ queryKey: [key, agentId] }),
      ),
    );
  };
  const toggle = useMutation({
    mutationFn: ({
      binding,
      enabled,
    }: {
      binding: NonNullable<typeof bindings.data>[number];
      enabled: boolean;
    }) => setCatalogBindingEnabled(agentId, binding, enabled),
    onSuccess: refresh,
  });
  const rotateCredential = useMutation({
    mutationFn: () => rotateAgentRegistrationCredential(agentId),
    onSuccess: setRegistrationCredential,
  });
  const runtimeKinds = [
    ...new Set((bindings.data || []).map((binding) => binding.kind)),
  ];
  const canEdit = scope.roles.some(role => ["admin", "developer"].includes(role));
  const setTab = (
    next: AgentDetailTabId,
    options: Record<string, string> = {},
  ) => {
    const query = new URLSearchParams();
    if (next !== "overview" && next !== "definition") query.set("tab", next);
    Object.entries(options).forEach(([key, value]) => query.set(key, value));
    navigate(
      scope.scopedPath(
        `${base}${next === "definition" ? "/definition/behavior" : ""}${query.size ? `?${query}` : ""}`,
      ),
    );
  };
  useEffect(() => {
    setRegistrationCredential("");
    setShareOpen(false);
    setCopyStatus("");
  }, [agentId]);
  // Refresh identity/version after moving between independently saved definition editors.
  useEffect(() => {
    void queryClient.invalidateQueries({
      queryKey: ["catalog-agent", agentId],
    });
  }, [location.pathname, agentId, queryClient]);

  if (agent.isPending)
    return (
      <Page>
        <p role="status" className="text-sm text-muted-foreground">
          Loading Agent…
        </p>
      </Page>
    );
  if (!agent.data)
    return (
      <Page>
        <EmptyState
          title="Agent unavailable"
          description={
            agent.error instanceof Error
              ? agent.error.message
              : "Unable to load this Agent."
          }
        />
        <Button onClick={() => void agent.refetch()}>Retry</Button>
      </Page>
    );
  if (
    params.get("tab") === "definition" &&
    !definition
  )
    return (
      <Navigate replace to={scope.scopedPath(`${base}/definition/behavior`)} />
    );
  if (
    params.get("connection") === "channels" &&
    runtimeKind === "managed" &&
    !channels
  )
    return (
      <Navigate replace to={scope.scopedPath(`${base}/connections/channels`)} />
    );
  const value = agent.data;
  const currentOverview = overview.data;
  const editableDefinition = canEdit;
  if (channels && runtimeKind !== "managed")
    return <Navigate replace to={scope.scopedPath(base)} />;
  const busy = activity.records.filter((item) =>
    ["running", "dispatched", "compressing"].includes(item.status),
  ).length;
  const lastWork = activity.records[0]?.timestamp;
  const context = {
    agentId,
    agent: value,
    refreshAgent,
    canEdit: editableDefinition,
  };

  return (
    <Page className="space-y-5">
      <header className="space-y-4">
        <Link
          to={scope.scopedPath("/agent-center/agents")}
          className="text-sm text-muted-foreground hover:text-foreground"
        >
          ← Agents
        </Link>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="rounded-xl border border-indigo-100 bg-indigo-50 p-3 text-indigo-600">
              <Bot className="h-6 w-6" />
            </span>
            <div className="min-w-0">
              <h1 className="text-2xl font-semibold tracking-tight">
                {value.name}
              </h1>
              <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <Badge>{bindingLabel(runtimeKind || "Agent")}</Badge>
                <Badge tone={readinessTone(currentOverview?.readiness.state)}>
                  {currentOverview?.readiness.state || "Observing"}
                </Badge>
                <span>{value.status}</span>
                <span>{value.agentKey}</span>
                <button
                  title="Copy Agent ID"
                  aria-label="Copy Agent ID"
                  className="inline-flex items-center gap-1 hover:text-primary"
                  onClick={async () => {
                    try {
                      await navigator.clipboard.writeText(value.id);
                      setCopyStatus("ID copied");
                    } catch {
                      setCopyStatus("Copy unavailable");
                    }
                  }}
                >
                  <Copy className="h-3.5 w-3.5" />
                  {copyStatus || "ID"}
                </button>
              </div>
            </div>
          </div>
          <div className="flex gap-2">
            <Button
              disabled={
                value.status === "archived" ||
                value.tierForCurrentUser === "CLONE"
              }
              onClick={() =>
                navigate(
                  scope.scopedPath(
                    `/work/chat?agent=${encodeURIComponent(value.id)}`,
                  ),
                )
              }
            >
              <MessageSquare className="h-4 w-4" />
              Chat
            </Button>
            {runtimeKind === "managed" && editableDefinition && (
              <Button variant="outline" onClick={() => setShareOpen(true)}>
                <Share2 className="h-4 w-4" />
                Share
              </Button>
            )}
            <Button
              variant="outline"
              aria-label="Refresh Agent"
              title="Refresh current data"
              onClick={() => void refresh()}
            >
              <RefreshCw className="h-4 w-4" />
              <span className="hidden sm:inline">Refresh</span>
            </Button>
          </div>
        </div>
        {value.description && (
          <p className="max-w-3xl text-sm text-muted-foreground">
            {value.description}
          </p>
        )}
      </header>
      <nav
        aria-label="Agent detail"
        className="flex gap-1 overflow-x-auto border-b border-border"
      >
        {agentDetailTabs(runtimeKind).map((item) => (
          <button
            key={item.id}
            aria-current={tab === item.id ? "page" : undefined}
            type="button"
            onClick={() => setTab(item.id)}
            className={`shrink-0 border-b-2 px-4 py-3 text-sm transition-colors ${tab === item.id ? "border-primary font-semibold text-primary" : "border-transparent text-muted-foreground hover:text-foreground"}`}
          >
            {item.label}
          </button>
        ))}
      </nav>
      {(toggle.isError || rotateCredential.isError) && (
        <p role="alert" className="text-sm text-red-600">
          {String(toggle.error || rotateCredential.error)}
        </p>
      )}
      {shareOpen && (
        <ShareAgentDialog agent={value} onClose={() => setShareOpen(false)} />
      )}

      {tab === "overview" && (
        <div className="space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <div className="flex items-center gap-2">
              <span
                className={`h-2 w-2 rounded-full ${busy ? "bg-indigo-500" : "bg-slate-300"}`}
              />
              <span className="font-medium">
                {activity.tasks.isSuccess && activity.sessions.isSuccess
                  ? busy
                    ? "Working"
                    : "No work marked in progress"
                  : "Observing activity"}
              </span>
            </div>
            <span className="text-muted-foreground">
              Last recorded work: {lastWork ? formatRelative(lastWork) : "—"}
            </span>
          </div>
          <ActivitySummary data={activity} />
          <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_300px]">
            <AgentActivityPanel
              compact
              agentId={agentId}
              data={activity}
              onOpenActivity={() => setTab("activity")}
            />
            <aside className="space-y-4">
              {runtimeKind === "managed" && (
                <Card>
                  <CardHeader>
                    <CardTitle>Agent definition</CardTitle>
                    <CardDescription>
                      Version {value.version ?? "—"}
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4 text-sm">
                    <div>
                      <span className="text-muted-foreground">Model</span>
                      <p className="mt-1 break-all font-medium">
                        {value.model || "Runtime default"}
                      </p>
                    </div>
                    <div>
                      <span className="text-muted-foreground">Workspace</span>
                      <p className="mt-1">
                        {value.workspaceId
                          ? "Linked workspace"
                          : "Agent workspace"}
                      </p>
                    </div>
                    <div className="flex gap-4">
                      <span>{value.skills?.length ?? 0} skills</span>
                      <span>{value.tools?.length ?? 0} toolsets</span>
                    </div>
                    <Button
                      variant="outline"
                      className="w-full"
                      onClick={() => setTab("definition")}
                    >
                      Open definition <ArrowRight className="h-4 w-4" />
                    </Button>
                  </CardContent>
                </Card>
              )}
              <Card>
                <CardHeader>
                  <CardTitle>Runtime</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3 text-sm">
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground">Availability</span>
                    <span>{currentOverview?.readiness.state || "Unknown"}</span>
                  </div>
                  <div className="flex justify-between gap-2">
                    <span className="text-muted-foreground">Execution</span>
                    <span>
                      {currentOverview?.readiness.mode === "on-demand"
                        ? "On demand"
                        : runtimeKind === "managed"
                          ? "Platform managed"
                          : `${currentOverview?.instances.healthy ?? "—"} healthy instances`}
                    </span>
                  </div>
                  {overview.isError && (
                    <p className="text-amber-700">
                      Runtime signals unavailable.
                    </p>
                  )}
                  <Button
                    size="sm"
                    variant="ghost"
                    className="w-full"
                    onClick={() => setTab("runtime")}
                  >
                    Runtime configuration <ArrowRight className="h-4 w-4" />
                  </Button>
                </CardContent>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle>Usage · 24h</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3 text-sm">
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Tokens</span>
                    <span>
                      {currentOverview?.usage.totalTokens == null
                        ? "Not reported"
                        : currentOverview.usage.totalTokens.toLocaleString()}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">
                      Reported errors
                    </span>
                    <span>
                      {currentOverview?.usage.errorCount ?? "Not reported"}
                    </span>
                  </div>
                </CardContent>
              </Card>
            </aside>
          </div>
        </div>
      )}
      {tab === "activity" && (
        <AgentActivityPanel
          key={agentId}
          agentId={agentId}
          data={activity}
          view={
            params.get("view") ||
            (params.get("tab") === "sessions" ? "sessions" : "work")
          }
          onViewChange={(view) => setTab("activity", { view })}
        />
      )}
      {tab === "definition" && (
        <div className="grid items-start gap-5 lg:grid-cols-[180px_minmax(0,1fr)]">
          <nav
            aria-label="Agent definition"
            className="flex gap-1 overflow-x-auto lg:flex-col"
          >
            {definitionSections.map((item) => (
              <Link
                key={item.id}
                aria-current={section === item.id ? "page" : undefined}
                to={scope.scopedPath(`${base}/definition/${item.id}`)}
                className={`shrink-0 rounded-lg px-4 py-2.5 text-sm ${section === item.id ? "bg-indigo-50 font-semibold text-primary" : "text-muted-foreground hover:bg-muted"}`}
              >
                {item.label}
              </Link>
            ))}
          </nav>
          <div className="min-w-0 space-y-4">
            {!editableDefinition && (
              <p className="rounded-lg border bg-muted/30 p-3 text-sm text-muted-foreground">
                Read-only definition. Editing requires access to this Agent.
              </p>
            )}
            {(section === "workspace" || value.version == null) && <WorkspaceBindingPanel agent={value} canEdit={editableDefinition} onSaved={refreshAgent} />}
            <div
              className={
                ["workspace", "skills", "tools", "subagents"].includes(section)
                  ? "h-[min(720px,75vh)] min-h-96 overflow-hidden rounded-xl border border-border bg-white"
                  : ""
              }
            >
              {value.version != null && <Outlet context={context} />}
            </div>
          </div>
        </div>
      )}
      {tab === "runtime" && (
        <section className="grid gap-5 border-t pt-5">
          <div>
            <h2 className="text-lg font-semibold">Runtime configuration</h2>
            <RuntimeWorkspacesPanel agent={value} />
            <p className="mt-1 text-sm text-muted-foreground">
              Execution environment, availability and runtime connections.
            </p>
          </div>
          {(bindings.data ?? []).map((binding) => {
            const rows = (instances.data ?? []).filter(
              (instance) => instance.bindingId === binding.id,
            );
            const configuration = bindingConfiguration(binding.configuration);
            return (
              <Card key={binding.id}>
                <CardHeader>
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <CardTitle>{bindingLabel(binding.kind)}</CardTitle>
                      <CardDescription className="mt-1">
                        {bindingDescription(binding.kind)}
                      </CardDescription>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge tone={binding.enabled ? "success" : "warning"}>
                        {binding.enabled ? "enabled" : "disabled"}
                      </Badge>
                      {canEdit && (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={toggle.isPending}
                          onClick={() =>
                            toggle.mutate({
                              binding,
                              enabled: !binding.enabled,
                            })
                          }
                        >
                          {binding.enabled ? "Disable" : "Enable"}
                        </Button>
                      )}
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="grid gap-5 lg:grid-cols-[0.8fr_1.2fr]">
                  <div>
                    <div className="mb-2 text-sm font-medium">
                      {binding.kind === "managed"
                        ? "Definition"
                        : binding.kind === "external-application"
                          ? "Registration & routing"
                          : "Runtime selection"}
                    </div>
                    {binding.kind === "managed" ? (
                      <div className="space-y-3 rounded-lg border p-4 text-sm">
                        <div>
                          <span className="text-muted-foreground">
                            Definition reference
                          </span>
                          <div className="mt-1 font-mono text-xs">
                            {String(
                              configuration.managedDefinitionRef ??
                                "Not configured",
                            )}
                          </div>
                        </div>
                        <div>
                          <span className="text-muted-foreground">Owner</span>
                          <div className="mt-1">
                            {String(configuration.ownerRef ?? "Not configured")}
                          </div>
                        </div>
                        {canEdit && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() =>
                              navigate(
                                scope.scopedPath(
                                  `/agent-center/agents/${agentId}/definition/behavior`,
                                ),
                              )
                            }
                          >
                            Edit behavior
                          </Button>
                        )}
                      </div>
                    ) : binding.kind === "external-application" ? (
                      <div className="space-y-3 rounded-lg border p-4 text-sm">
                        <div>
                          <span className="text-muted-foreground">
                            Registered instances
                          </span>
                          <div className="mt-1 font-medium">{rows.length}</div>
                        </div>
                        <div>
                          <span className="text-muted-foreground">
                            Instance selector
                          </span>
                          <JsonViewer
                            value={configuration.instanceSelector ?? {}}
                            className="mt-1 max-h-32"
                          />
                        </div>
                        <div className="text-xs text-muted-foreground">
                          Credentials authenticate registration; instance health
                          and availability are reported by the runtime.
                        </div>
                      </div>
                    ) : (
                      <div className="space-y-3 rounded-lg border p-4 text-sm">
                        <div className="font-medium">
                          Automatically selected
                        </div>
                        <div className="text-muted-foreground">
                          AgentScope uses the detected agent runtime selected
                          when this Agent was created.
                        </div>
                        <div className="text-xs text-muted-foreground">
                          Processes are launched per execution; availability and
                          capacity are checked automatically.
                        </div>
                      </div>
                    )}
                  </div>
                  <div>
                    <div className="mb-2 text-sm font-medium">
                      {binding.kind === "external-application"
                        ? "Registered instances"
                        : binding.kind === "hosted-runtime"
                          ? "Execution capacity"
                          : "Service lifecycle"}
                    </div>
                    <div className="grid gap-3">
                      {rows.map((instance) => (
                        <InstanceCard key={instance.id} instance={instance} />
                      ))}
                      {!rows.length && (
                        <div className="rounded-lg border border-dashed p-5 text-sm text-muted-foreground">
                          {binding.kind === "hosted-runtime"
                            ? "On-demand: an available agent runtime is selected when work starts."
                            : binding.kind === "managed"
                              ? "Sessions are created and managed automatically by AgentScope Service."
                              : "Not reporting: no external application process is currently connected."}
                        </div>
                      )}
                    </div>
                  </div>
                </CardContent>
              </Card>
            );
          })}
          {runtimeKind === "managed" && (
            <AgentSettingsForm
              section="runtime"
              agent={value}
              onSaved={refreshAgent}
            />
          )}
          {runtimeKinds.includes("hosted-runtime") && (
            <HostedAgentSettings agent={value} canEdit={canEdit} />
          )}
        </section>
      )}

      {tab === "connections" && (
        <div className="space-y-4">
          {runtimeKind === "managed" && (
            <div className="flex gap-2">
              <Button
                size="sm"
                variant={channels ? "ghost" : "secondary"}
                onClick={() => setTab("connections")}
              >
                Published APIs
              </Button>
              <Button
                size="sm"
                variant={channels ? "secondary" : "ghost"}
                onClick={() =>
                  navigate(scope.scopedPath(`${base}/connections/channels`))
                }
              >
                Channels
              </Button>
            </div>
          )}
          {(channels || runtimeKind !== "managed") && (
            <ChannelAssociations targetType="agent" targetRef={agentId} />
          )}
          {channels ? (
            <Outlet context={context} />
          ) : (
            <PublishEndpointCard
              targetType="agent"
              targetRef={value.id}
              targetName={value.name}
              ownerPath={`${base}?tab=connections`}
            />
          )}
        </div>
      )}
      {tab === "runtime" &&
        runtimeKinds.includes("external-application") &&
        canEdit && (
          <Card>
            <CardHeader>
              <CardTitle>Registration credential</CardTitle>
              <CardDescription>
                Authenticate external application instances when they register.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <Button
                variant="outline"
                disabled={rotateCredential.isPending}
                onClick={() => rotateCredential.mutate()}
              >
                Rotate credential
              </Button>
              {registrationCredential && (
                <div className="space-y-2">
                  <p className="text-sm text-amber-700">
                    Save this credential. The plaintext value is shown only
                    once.
                  </p>
                  <code className="block break-all rounded border p-3 text-xs">
                    {registrationCredential}
                  </code>
                  <Button
                    variant="outline"
                    onClick={() =>
                      void navigator.clipboard.writeText(registrationCredential)
                    }
                  >
                    Copy credential
                  </Button>
                </div>
              )}
            </CardContent>
          </Card>
        )}
      {tab === "runtime" && (bindings.isError || instances.isError) && (
        <p role="alert" className="text-sm text-amber-700">
          Runtime configuration could not be fully loaded. Use Refresh to retry.
        </p>
      )}
      {tab === "settings" &&
        (runtimeKind === "managed" ? (
          <AgentSettingsForm
            section="settings"
            agent={value}
            onSaved={refreshAgent}
          />
        ) : (
          <AgentGeneralSettings
            key={`${value.id}:${value.catalogVersion}`}
            agent={value}
            canEdit={canEdit}
            onSaved={refreshAgent}
          />
        ))}
    </Page>
  );
}

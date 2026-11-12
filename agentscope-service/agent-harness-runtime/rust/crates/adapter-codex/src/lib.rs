pub const CODEX_REVISION: &str = "e742db9f7ba98e63e502b310857d49d047888518";

mod model;
mod preview;
mod proxy;
mod store;

use anyhow::{Context, Result, bail, ensure};
use async_trait::async_trait;
use codex_core::compact::AutoCompactionDisabled;
use codex_core::config::{ConfigBuilder, ConfigOverrides, LoaderOverrides};
use codex_core::{
    CodexAppsToolsCache, CodexThread, RecoverTurnRequest, StartIfIdleSubmission,
    StartThreadOptions, ThreadManager, TurnInputRequest, build_models_manager,
    passthrough_image_store,
};
use codex_exec_server::EnvironmentManager;
use codex_extension_api::empty_extension_registry;
use codex_history::{InitialHistory, ResumedHistory};
use codex_home::CodexHomeUserInstructionsProvider;
use codex_login::AuthManager;
use codex_protocol::ThreadId;
use codex_protocol::dynamic_tools::{DynamicToolFunctionSpec, DynamicToolSpec};
use codex_protocol::items::TurnItem;
use codex_protocol::models::{ContentItem, ResponseItem};
use codex_protocol::protocol::{EventMsg, SessionSource};
use codex_protocol::user_input::UserInput;
use codex_rollout::RolloutItem;
use managed_brain_runtime::kernel::{
    Checkpoint, Kernel, KernelEvent, KernelSession, ModelGate, NativeCall, NativeOutput,
    PublicMessage,
};
use managed_brain_runtime::protocol::{RunConfiguration, framed_hash};
use serde_json::json;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;
use tempfile::TempDir;
use tokio::sync::OnceCell;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

pub struct CodexKernel {
    pub model_base_url: String,
    pub model_api_key: String,
    pub provider: String,
    credentials: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>,
}

impl CodexKernel {
    pub fn new(base_url: String, api_key: String, provider: String) -> Self {
        Self {
            model_base_url: base_url,
            model_api_key: api_key,
            provider,
            credentials: Arc::new(|name| std::env::var(name).ok()),
        }
    }

    /// Inject a controlled credential resolver without mutating process-global environment.
    pub fn with_credential_resolver(
        mut self,
        resolver: impl Fn(&str) -> Option<String> + Send + Sync + 'static,
    ) -> Self {
        self.credentials = Arc::new(resolver);
        self
    }
}

#[async_trait]
impl Kernel for CodexKernel {
    async fn open(
        &self,
        configuration: &RunConfiguration,
        checkpoint: Option<Checkpoint>,
        gate: Arc<dyn ModelGate>,
        cancel: CancellationToken,
    ) -> Result<Box<dyn KernelSession>> {
        let binding = Arc::new(model::ModelBinding::resolve(self, &configuration.model)?);
        if let Some(checkpoint) = &checkpoint {
            ensure!(
                checkpoint.kernel_revision == CODEX_REVISION,
                "Codex checkpoint revision mismatch"
            );
        }
        let home = TempDir::new()?;
        let scratch = home.path().join("workspace");
        tokio::fs::create_dir(&scratch).await?;
        let thread = Arc::new(OnceCell::new());
        let tools = configuration
            .tools
            .iter()
            .map(|t| {
                ensure!(
                    !t.name.is_empty()
                        && t.name
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-'),
                    "invalid dynamic tool name"
                );
                Ok((format!("hands_{}", t.name), t.parameters.clone()))
            })
            .collect::<Result<HashMap<_, _>>>()?;
        ensure!(tools.len() == configuration.tools.len(), "duplicate tools");
        let proxy =
            proxy::ModelProxy::start(binding.clone(), tools, thread.clone(), gate, cancel.clone())
                .await?;
        let mut overrides = vec![
            // Hands enforces managed tool policy; native shell/escalation tools are disabled.
            // Their CLI-only instructions would contradict the Agent's configured behavior.
            (
                "include_permissions_instructions".to_owned(),
                toml::Value::Boolean(false),
            ),
            (
                "include_environment_context".to_owned(),
                toml::Value::Boolean(false),
            ),
            (
                "include_environment_context_time".to_owned(),
                toml::Value::Boolean(false),
            ),
            (
                "model_provider".to_owned(),
                toml::Value::String("managed".into()),
            ),
            (
                "model_providers.managed.name".to_owned(),
                toml::Value::String("Managed Brain".into()),
            ),
            (
                "model_providers.managed.base_url".to_owned(),
                toml::Value::String(proxy.base_url.clone()),
            ),
            (
                "model_providers.managed.wire_api".to_owned(),
                toml::Value::String("responses".into()),
            ),
            (
                "model_providers.managed.requires_openai_auth".to_owned(),
                toml::Value::Boolean(false),
            ),
            (
                "model_providers.managed.request_max_retries".to_owned(),
                toml::Value::Integer(0),
            ),
            (
                "model_providers.managed.stream_max_retries".to_owned(),
                toml::Value::Integer(0),
            ),
            (
                "web_search".to_owned(),
                toml::Value::String("disabled".into()),
            ),
        ];
        for name in [
            "shell_tool",
            "unified_exec",
            "apply_patch_freeform",
            "multi_agent",
            "multi_agent_v2",
            "code_mode",
            "code_mode_host",
            "apps",
            "memories",
            "memory_tool",
            "js_repl",
            "image_generation",
            "guardian_v2",
            "response_websockets_v2",
        ] {
            overrides.push((format!("features.{name}"), toml::Value::Boolean(false)));
        }
        let loader = LoaderOverrides {
            ignore_user_config: true,
            ignore_project_config: true,
            ignore_user_and_project_exec_policy_rules: true,
            managed_config_path: Some(home.path().join("managed.toml")),
            system_config_path: Some(home.path().join("system.toml")),
            system_requirements_path: Some(home.path().join("requirements.toml")),
            macos_managed_config_requirements_base64: Some(String::new()),
            #[cfg(target_os = "macos")]
            managed_preferences_base64: Some(String::new()),
            ..Default::default()
        };
        let mut config = ConfigBuilder::default()
            .codex_home(home.path().to_path_buf())
            .without_project_context()
            .loader_overrides(loader)
            .cli_overrides(overrides)
            .harness_overrides(ConfigOverrides {
                model: Some(configuration.model.id.clone()),
                cwd: Some(scratch),
                // A managed Agent defines the base persona, replacing the CLI default.
                base_instructions: configuration.system.clone(),
                ..Default::default()
            })
            .build()
            .await?;
        let auth = AuthManager::shared_from_config(&config, false).await?;
        binding.configure_native(&mut config, auth.clone()).await?;
        let environments = Arc::new(EnvironmentManager::without_environments(
            config.http_client_factory(),
        ));
        let store = Arc::new(store::ScratchStore::default());
        // This store is process-local staging. Only Session Service commits are authoritative.
        // Every incarnation starts with a fresh store seeded from the committed native history.
        let manager = Arc::new(ThreadManager::new(
            &config,
            auth.clone(),
            build_models_manager(&config, auth),
            CodexAppsToolsCache::default(),
            SessionSource::Exec,
            environments,
            empty_extension_registry(),
            Arc::new(CodexHomeUserInstructionsProvider::new(
                config.codex_home.clone(),
            )),
            None,
            passthrough_image_store(),
            store,
            None,
            format!("managed-{}", Uuid::new_v4()),
            None,
            None,
        ));
        let mut options = StartThreadOptions::new(config);
        if std::env::var("MANAGED_AGENTS_EVALUATION_CONTEXT_MODE").as_deref() == Ok("short") {
            options.thread_extension_init.insert(AutoCompactionDisabled);
        }
        options.environments = Some(Vec::new());
        options.dynamic_tools = configuration
            .tools
            .iter()
            .map(|t| {
                DynamicToolSpec::Function(DynamicToolFunctionSpec {
                    name: format!("hands_{}", t.name),
                    description: t.description.clone(),
                    input_schema: t.parameters.clone(),
                    defer_loading: false,
                })
            })
            .collect();
        let prior_turn = checkpoint.as_ref().and_then(|c| c.turn_id.clone());
        let abandoned_calls = checkpoint
            .as_ref()
            .filter(|_| prior_turn.is_none())
            .map(|c| c.pending_calls.clone())
            .unwrap_or_default();
        if let Some(checkpoint) = checkpoint {
            let id = ThreadId::from_string(&checkpoint.thread_id)?;
            options.initial_history = InitialHistory::Resumed(ResumedHistory {
                conversation_id: id,
                history: Arc::new(serde_json::from_value(checkpoint.history)?),
                history_revision: None,
                rollout_path: None,
            });
        }
        let created = manager
            .start_thread(options)
            .await
            .context("start native thread")?;
        if !abandoned_calls.is_empty() {
            // A cancelled previous platform Turn can leave unresolved native calls.
            // Close them as history before the next user Turn; never dispatch them again.
            let items = abandoned_calls
                .into_iter()
                .map(|call| {
                    serde_json::from_value::<ResponseItem>(
                        json!({"type":"function_call_output","call_id":call.call_id,
                    "output":"Previous platform Turn ended before the tool result was applied."}),
                    )
                })
                .collect::<std::result::Result<Vec<_>, _>>()?;
            created.thread.inject_response_items(items).await?;
            created.thread.flush_rollout().await?;
        }
        thread
            .set(created.thread.clone())
            .map_err(|_| anyhow::anyhow!("thread already installed"))?;
        Ok(Box::new(Session {
            thread: created.thread,
            manager,
            turn_id: prior_turn,
            started: false,
            stopped: false,
            started_calls: HashSet::new(),
            proxy,
            cancel,
            _home: home,
        }))
    }
}

struct Session {
    thread: Arc<CodexThread>,
    manager: Arc<ThreadManager>,
    turn_id: Option<String>,
    started: bool,
    stopped: bool,
    started_calls: HashSet<String>,
    proxy: proxy::ModelProxy,
    cancel: CancellationToken,
    _home: TempDir,
}

pub(crate) async fn capture(thread: &CodexThread) -> Result<Checkpoint> {
    thread.ensure_rollout_materialized().await;
    thread.flush_rollout().await?;
    let history = thread
        .load_history(false)
        .await
        .context("capture native history")?;
    let mut calls = Vec::new();
    let mut outputs = HashSet::new();
    let mut seen = HashMap::new();
    let mut turn_id = None;
    let mut messages = Vec::new();
    for (index, item) in history.items.iter().enumerate() {
        match item {
            RolloutItem::ResponseItem(envelope) => match &envelope.item {
                ResponseItem::Message {
                    id, role, content, ..
                } if role == "assistant" => {
                    let text = content
                        .iter()
                        .filter_map(|part| match part {
                            ContentItem::OutputText { text, .. } => Some(text.clone()),
                            _ => None,
                        })
                        .collect::<Vec<_>>();
                    if !text.is_empty() {
                        let native_id = id
                            .as_ref()
                            .map(ToString::to_string)
                            .unwrap_or_else(|| format!("history_{index}"));
                        let message = PublicMessage {
                            id: format!(
                                "message_{}",
                                framed_hash(&[&history.thread_id.to_string(), &native_id])
                            ),
                            text,
                        };
                        if let Some(previous) = messages
                            .iter()
                            .find(|previous: &&PublicMessage| previous.id == message.id)
                        {
                            ensure!(previous == &message, "native message identity changed");
                        } else {
                            messages.push(message);
                        }
                    }
                }
                ResponseItem::FunctionCall {
                    call_id,
                    name,
                    arguments,
                    namespace,
                    ..
                } => {
                    ensure!(namespace.is_none(), "unexpected native namespace");
                    let name = name
                        .strip_prefix("hands_")
                        .ok_or_else(|| anyhow::anyhow!("unmanaged native tool in history"))?;
                    let call = NativeCall {
                        call_id: call_id.clone(),
                        name: name.to_owned(),
                        arguments: serde_json::from_str(arguments)?,
                    };
                    if let Some(prior) = seen.insert(call_id.clone(), call.clone()) {
                        ensure!(prior == call, "native call ID reused");
                    } else {
                        calls.push(call);
                    }
                }
                ResponseItem::FunctionCallOutput {
                    call_id: Some(id), ..
                } => {
                    outputs.insert(id.clone());
                }
                _ => {}
            },
            RolloutItem::EventMsg(EventMsg::TurnStarted(event)) => {
                if turn_id.as_ref() != Some(&event.turn_id) {
                    calls.clear();
                    outputs.clear();
                    seen.clear();
                    messages.clear();
                }
                turn_id = Some(event.turn_id.clone());
            }
            _ => {}
        }
    }
    calls.retain(|c| !outputs.contains(&c.call_id));
    let mut applied = outputs.into_iter().collect::<Vec<_>>();
    applied.sort();
    Ok(Checkpoint {
        kernel_revision: CODEX_REVISION.to_owned(),
        platform_turn_id: String::new(),
        thread_id: history.thread_id.to_string(),
        turn_id,
        history: serde_json::to_value(history.items)?,
        pending_calls: calls,
        applied_calls: applied,
        messages,
    })
}

#[async_trait]
impl KernelSession for Session {
    async fn start_or_recover(&mut self, prompt: &str) -> Result<()> {
        ensure!(!self.started && !self.stopped, "session cannot start");
        let submitted = if let Some(turn_id) = &self.turn_id {
            self.thread
                .recover_turn_if_idle(RecoverTurnRequest {
                    turn_id: turn_id.clone(),
                    thread_settings: Default::default(),
                    trace: None,
                    cyber_access_program: None,
                })
                .await?
        } else {
            self.thread
                .start_turn_if_idle(TurnInputRequest::user_input(vec![UserInput::Text {
                    text: prompt.to_owned(),
                    text_elements: Vec::new(),
                }]))
                .await?
        };
        let StartIfIdleSubmission::Started { turn_id, .. } = submitted else {
            bail!("Codex did not start the requested Turn")
        };
        if let Some(expected) = &self.turn_id {
            ensure!(expected == &turn_id, "Codex changed native Turn ID");
        }
        self.turn_id = Some(turn_id);
        self.started = true;
        Ok(())
    }

    async fn next(&mut self) -> Result<KernelEvent> {
        loop {
            let event = tokio::select! { event = self.thread.next_event() => event?, _ = self.cancel.cancelled() => bail!("execution cancelled") };
            match event.msg {
                EventMsg::ItemStarted(event) => {
                    if let TurnItem::DynamicToolCall(item) = event.item {
                        self.started_calls.insert(item.id);
                        let expected = self.proxy.calls.lock().await.clone();
                        if !expected.is_empty()
                            && expected.iter().all(|id| self.started_calls.contains(id))
                        {
                            return Ok(KernelEvent::Tools);
                        }
                    }
                }
                EventMsg::TurnComplete(event) => {
                    return Ok(KernelEvent::Complete(
                        json!([{"type":"text","text":event.last_agent_message.unwrap_or_default()}]),
                    ));
                }
                EventMsg::Error(_) => bail!("Codex model or runtime error"),
                EventMsg::TurnAborted(_) => bail!("Codex Turn aborted"),
                _ => {}
            }
        }
    }

    async fn snapshot(&self) -> Result<Checkpoint> {
        let mut checkpoint = capture(&self.thread).await?;
        if self.turn_id.is_none() {
            // History may contain a completed/cancelled preceding user Turn.
            checkpoint.pending_calls.clear();
            checkpoint.applied_calls.clear();
            checkpoint.messages.clear();
        }
        checkpoint.turn_id = self.turn_id.clone();
        Ok(checkpoint)
    }

    async fn inject(&mut self, outputs: Vec<NativeOutput>) -> Result<()> {
        ensure!(
            !self.started && !self.stopped,
            "tool results require an idle thread"
        );
        let checkpoint = self.snapshot().await?;
        let mut items = Vec::new();
        for output in outputs {
            ensure!(
                checkpoint
                    .pending_calls
                    .iter()
                    .any(|c| c.call_id == output.call_id),
                "output has no pending native call"
            );
            // Raw Responses output preserves the original call_id; no dynamic oneshot is needed.
            let content = if std::env::var("MANAGED_AGENTS_EVALUATION_TOOL_ENVELOPE").as_deref()
                == Ok("text-json-v1")
            {
                output.content
            } else {
                json!({"success":output.success,"content":output.content})
            };
            items.push(serde_json::from_value::<ResponseItem>(
                json!({"type":"function_call_output","call_id":output.call_id,
                "output":serde_json::to_string(&content)?}),
            )?);
        }
        self.thread.inject_response_items(items).await?;
        self.thread.flush_rollout().await?;
        Ok(())
    }

    async fn suspend(&mut self) -> Result<Checkpoint> {
        let checkpoint = self.snapshot().await?;
        self.thread.suspend_turn_and_shutdown().await?;
        self.shutdown().await?;
        // Never recapture here: cancellation cleanup can synthesize failed tool outputs.
        Ok(checkpoint)
    }

    async fn shutdown(&mut self) -> Result<()> {
        if !self.stopped {
            self.stopped = true;
            self.proxy.stop.cancel();
            let report = self
                .manager
                .shutdown_all_threads_bounded(Duration::from_secs(5))
                .await;
            ensure!(
                report.timed_out.is_empty() && report.submit_failed.is_empty(),
                "Codex thread shutdown incomplete"
            );
        }
        Ok(())
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.proxy.stop.cancel();
        if !self.stopped {
            let manager = self.manager.clone();
            tokio::spawn(async move {
                manager
                    .shutdown_all_threads_bounded(Duration::from_secs(5))
                    .await;
            });
        }
    }
}

use crate::preview::TextPreview;
use crate::protocol::RunConfiguration;
use anyhow::Result;
use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NativeCall {
    pub call_id: String,
    pub name: String,
    pub arguments: Value,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Checkpoint {
    pub kernel_revision: String,
    /// Set by the fenced runtime when committing. Distinguishes resume from a new user Turn.
    pub platform_turn_id: String,
    pub thread_id: String,
    pub turn_id: Option<String>,
    pub history: Value,
    pub pending_calls: Vec<NativeCall>,
    pub applied_calls: Vec<String>,
    /// Explicit public text extracted by the adapter from committed native messages.
    #[serde(default)]
    pub messages: Vec<PublicMessage>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct PublicMessage {
    pub id: String,
    pub text: Vec<String>,
}

/// Input includes cached tokens; cache fields are subsets, not additional input.
pub struct ModelUsage {
    pub input_tokens: u64,
    pub output_tokens: u64,
    pub cache_read_input_tokens: u64,
    pub cache_write_input_tokens: u64,
}

pub struct NativeOutput {
    pub call_id: String,
    pub content: Value,
    pub success: bool,
}

pub enum KernelEvent {
    Tools,
    Complete(Value),
}

/// The adapter MUST await this gate before every outbound inference request.
/// A model callback is never allowed to bypass the fenced commit lane.
#[async_trait]
pub trait ModelGate: Send + Sync {
    /// Independent best-effort lane; never authorizes model or tool execution.
    fn text_preview(&self, model_call: &str, message_id: &str) -> TextPreview;
    async fn before_request(
        &self,
        request: Value,
        checkpoint: Checkpoint,
        purpose: &str,
    ) -> Result<String>;
    async fn after_response(
        &self,
        id: String,
        response: Value,
        usage: Option<ModelUsage>,
        checkpoint: Checkpoint,
    ) -> Result<()>;
    /// Persist an invalid provider response and its known usage, then fail the
    /// Turn atomically. Rejected output must never reach the native tool lane.
    async fn reject_response(
        &self,
        id: String,
        response: Value,
        usage: Option<ModelUsage>,
        checkpoint: Checkpoint,
    ) -> Result<()>;
}

#[async_trait]
pub trait Kernel: Send + Sync {
    async fn open(
        &self,
        configuration: &RunConfiguration,
        checkpoint: Option<Checkpoint>,
        gate: Arc<dyn ModelGate>,
        cancel: CancellationToken,
    ) -> Result<Box<dyn KernelSession>>;
}

#[async_trait]
pub trait KernelSession: Send {
    async fn start_or_recover(&mut self, prompt: &str) -> Result<()>;
    async fn next(&mut self) -> Result<KernelEvent>;
    async fn snapshot(&self) -> Result<Checkpoint>;
    /// Injection is only legal while idle, before start_or_recover.
    async fn inject(&mut self, outputs: Vec<NativeOutput>) -> Result<()>;
    /// Return the pre-cancellation history and stop all writers before returning.
    /// Cleanup output after this checkpoint is local scratch, never recovery state.
    async fn suspend(&mut self) -> Result<Checkpoint>;
    async fn shutdown(&mut self) -> Result<()>;
}

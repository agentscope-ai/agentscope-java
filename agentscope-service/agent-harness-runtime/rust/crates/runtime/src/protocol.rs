use anyhow::{Result, ensure};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

pub const VERSION: &str = "0.1.0";
pub const MAX_OBJECT: usize = 64 * 1024 * 1024;
pub const STATE_FORMAT: &str = "codex-rs-e742db9f-v1";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "camelCase")]
pub struct Scope {
    pub tenant: String,
    pub namespace: String,
    pub session_id: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Owner {
    pub instance_id: String,
    pub incarnation_id: String,
}

// Deliberately no Debug: the execution token is a credential.
#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Fence {
    pub owner: Owner,
    pub epoch: String,
    pub execution_token: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Claim {
    pub scope: Scope,
    pub turn_id: String,
    pub fence: Fence,
    pub expires_at: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ObjectRef {
    pub scope: Scope,
    pub backend_id: String,
    pub key: String,
    pub sha256: String,
    pub size: String,
    pub media_type: String,
    pub format_version: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeState {
    pub harness_type: String,
    pub state_format_version: String,
    pub native_cursor: String,
    pub checkpoint_ref: ObjectRef,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ToolOperation {
    pub operation_id: String,
    pub task_id: String,
    pub execution_id: String,
    pub native_call_ids: Vec<String>,
    pub tool_name: String,
    pub arguments: Value,
    pub configuration_ref: ObjectRef,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolResult {
    pub result_id: String,
    pub task_id: String,
    pub execution_id: String,
    pub status: String,
    pub content: Value,
    pub artifacts: Vec<ObjectRef>,
    pub output: Value,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResultApplication {
    pub native_call_id: String,
    pub result_ids: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Recovery {
    pub brain_revision: String,
    pub native_state: Option<NativeState>,
    pub accepted_results: Vec<ToolResult>,
    pub tool_operations: Vec<ToolOperation>,
    pub result_applications: Vec<ResultApplication>,
    pub input_applications: Vec<Value>,
    pub pending_inputs: Vec<Value>,
    pub interaction_updates: Vec<Value>,
    pub model_updates: Vec<Value>,
    pub terminal: Option<Value>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolDescriptor {
    pub name: String,
    pub operation: String,
    pub description: String,
    pub parameters: Value,
    pub permission: Option<String>,
    pub argument_envelope: Option<Value>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Model {
    pub provider: String,
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub api: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub base_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub credential_env: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context_window: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output_limit: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_timeout_seconds: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cost: Option<ModelCost>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parameters: Option<ModelParameters>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ModelCost {
    pub input: f64,
    pub output: f64,
    pub cache_read: f64,
    pub cache_write: f64,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ModelParameters {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top_p: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parallel_tool_calls: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_choice: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning: Option<String>,
}

impl Model {
    pub fn validate(&self) -> anyhow::Result<()> {
        use anyhow::ensure;
        let id = |value: &str| !value.trim().is_empty() && value.chars().count() <= 1024;
        ensure!(id(&self.provider) && id(&self.id), "Invalid model identity");
        ensure!(
            self.api.as_deref().is_none_or(|api| matches!(
                api,
                "openai-completions" | "openai-responses" | "anthropic-messages"
            )),
            "Invalid model API"
        );
        ensure!(
            self.credential_env
                .as_deref()
                .is_none_or(valid_credential_reference),
            "Invalid model credential reference"
        );
        if let Some(base) = &self.base_url {
            let url =
                reqwest::Url::parse(base).map_err(|_| anyhow::anyhow!("Invalid model endpoint"))?;
            ensure!(
                base.len() <= 2048
                    && matches!(url.scheme(), "http" | "https")
                    && url.has_host()
                    && url.username().is_empty()
                    && url.password().is_none()
                    && url.query().is_none()
                    && url.fragment().is_none()
                    && self.credential_env.is_some(),
                "Invalid model endpoint or credential binding"
            );
        }
        let tokens = |value: u64| (1..=100_000_000).contains(&value);
        ensure!(
            self.context_window.is_none_or(tokens) && self.output_limit.is_none_or(tokens),
            "Invalid model token limits"
        );
        ensure!(
            !matches!((self.context_window, self.output_limit), (Some(context), Some(output)) if output > context),
            "Model outputLimit exceeds contextWindow"
        );
        if let Some(cost) = &self.cost {
            ensure!(
                [cost.input, cost.output, cost.cache_read, cost.cache_write]
                    .iter()
                    .all(|v| v.is_finite() && *v >= 0.0),
                "Invalid model cost metadata"
            );
        }
        ensure!(
            self.request_timeout_seconds
                .is_none_or(|v| (1..=3600).contains(&v)),
            "Invalid model timeout"
        );
        if let Some(parameters) = &self.parameters {
            ensure!(
                parameters
                    .top_p
                    .is_none_or(|v| v.is_finite() && (0.0..=1.0).contains(&v)),
                "Invalid model topP"
            );
            ensure!(
                parameters
                    .tool_choice
                    .as_deref()
                    .is_none_or(|v| matches!(v, "auto" | "none" | "required")),
                "Invalid model tool choice"
            );
            ensure!(
                self.api.as_deref() != Some("anthropic-messages")
                    || (parameters.parallel_tool_calls.is_none()
                        && parameters.tool_choice.is_none()),
                "Tool request controls require an OpenAI API"
            );
            ensure!(
                parameters
                    .temperature
                    .is_none_or(|v| v.is_finite() && (0.0..=2.0).contains(&v)),
                "Invalid model temperature"
            );
            ensure!(
                parameters.max_tokens.is_none_or(tokens),
                "Invalid model maxTokens"
            );
            ensure!(
                !matches!((parameters.max_tokens, self.output_limit.or(self.context_window)),
                (Some(requested), Some(limit)) if requested > limit),
                "Model maxTokens exceeds token limit"
            );
            ensure!(
                parameters
                    .reasoning
                    .as_deref()
                    .is_none_or(|v| matches!(v, "minimal" | "low" | "medium" | "high" | "xhigh")),
                "Invalid model reasoning effort"
            );
            ensure!(
                self.reasoning != Some(false) || parameters.reasoning.is_none(),
                "Model reasoning effort requires reasoning support"
            );
        }
        Ok(())
    }
}

pub fn valid_credential_reference(value: &str) -> bool {
    value
        .strip_prefix("MANAGED_MODEL_")
        .or_else(|| value.strip_prefix("PI_MODEL_"))
        .is_some_and(|suffix| {
            !suffix.is_empty()
                && suffix
                    .bytes()
                    .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
        })
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunConfiguration {
    pub prompt: String,
    pub system: Option<String>,
    pub max_model_calls: Option<u64>,
    pub inline_wait_ms: Option<u64>,
    pub cwd: String,
    pub model: Model,
    pub tool_configuration: ObjectRef,
    pub tools: Vec<ToolDescriptor>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Receipt {
    pub commit_id: String,
    pub content_hash: String,
    pub brain_revision: String,
    pub native_cursor: String,
    pub outcome: String,
}

pub fn sha256(bytes: impl AsRef<[u8]>) -> String {
    format!("{:x}", Sha256::digest(bytes.as_ref()))
}

pub fn canonical(value: &impl Serialize) -> Result<Vec<u8>> {
    Ok(serde_json_canonicalizer::to_vec(value)?)
}

pub fn fingerprint(parts: &[&str]) -> Vec<u8> {
    let framed = parts
        .iter()
        .map(|s| format!("{}:{s}", s.encode_utf16().count()))
        .collect::<String>();
    Sha256::digest(framed.as_bytes()).to_vec()
}

pub fn scope_key(scope: &Scope) -> String {
    URL_SAFE_NO_PAD.encode(fingerprint(&[
        &scope.tenant,
        &scope.namespace,
        &scope.session_id,
    ]))
}

pub fn framed_hash(parts: &[&str]) -> String {
    fingerprint(parts)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

pub fn task_id(scope: &Scope, turn: &str, call: &str) -> String {
    format!("tool_{}", framed_hash(&[&scope_key(scope), turn, call]))
}

pub fn counter(value: &str) -> Result<u64> {
    ensure!(
        !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()),
        "invalid decimal counter"
    );
    ensure!(
        value == "0" || !value.starts_with('0'),
        "noncanonical decimal counter"
    );
    let parsed: u64 = value.parse()?;
    ensure!(parsed <= i64::MAX as u64, "counter exceeds protocol range");
    Ok(parsed)
}

impl ToolResult {
    pub fn validate(&self, operation: &ToolOperation, scope: &Scope) -> Result<()> {
        ensure!(
            self.task_id == operation.task_id && self.execution_id == operation.execution_id,
            "tool result identity mismatch"
        );
        ensure!(
            self.result_id
                == format!(
                    "result_{}",
                    framed_hash(&[&self.task_id, &self.execution_id])
                ),
            "invalid result ID"
        );
        ensure!(
            ["succeeded", "failed", "cancelled"].contains(&self.status.as_str()),
            "invalid result status"
        );
        ensure!(
            self.content.get("inline").is_some() ^ self.content.get("ref").is_some(),
            "invalid result content"
        );
        if let Some(reference) = self.content.get("ref") {
            let reference: ObjectRef = serde_json::from_value(reference.clone())?;
            ensure!(&reference.scope == scope, "cross-scope tool result");
        }
        ensure!(
            self.artifacts.iter().all(|r| &r.scope == scope),
            "cross-scope artifact"
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_vectors_match_java_and_typescript() {
        let vectors: Value =
            serde_json::from_str(include_str!("../../../../protocol/examples/vectors.json"))
                .unwrap();
        for vector in vectors["canonical"].as_array().unwrap() {
            let bytes = canonical(&vector["value"]).unwrap();
            assert_eq!(
                String::from_utf8(bytes.clone()).unwrap(),
                vector["canonical"]
            );
            assert_eq!(sha256(bytes), vector["sha256"]);
        }
        for vector in vectors["identity"].as_array().unwrap() {
            let parts = vector["parts"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap())
                .collect::<Vec<_>>();
            assert_eq!(framed_hash(&parts), vector["sha256"]);
            assert_eq!(
                URL_SAFE_NO_PAD.encode(fingerprint(&parts)),
                vector["base64url"]
            );
        }
    }

    #[test]
    fn counters_do_not_round_or_accept_noncanonical_values() {
        assert_eq!(counter("9223372036854775807").unwrap(), i64::MAX as u64);
        for bad in ["", "01", "-1", "+1", "1.0", "9223372036854775808"] {
            assert!(counter(bad).is_err(), "{bad}");
        }
    }
}

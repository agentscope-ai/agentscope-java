use crate::CodexKernel;
use anyhow::{Result, ensure};
use codex_core::{build_models_manager, config::Config};
use codex_login::AuthManager;
use codex_protocol::openai_models::{
    ModelsResponse, ReasoningEffort, ReasoningEffortPreset, TruncationPolicyConfig,
};
use managed_brain_runtime::protocol::Model;
use reqwest::Url;
use serde_json::{Value, json};
use std::sync::Arc;

// Contains the resolved credential only while the native instance is alive. Never serialize it.
pub(crate) struct ModelBinding {
    pub url: Url,
    pub key: String,
    model: Model,
}

impl ModelBinding {
    pub fn resolve(kernel: &CodexKernel, model: &Model) -> Result<Self> {
        model.validate()?;
        ensure!(
            model
                .api
                .as_deref()
                .is_none_or(|api| api == "openai-responses"),
            "Codex requires model.api openai-responses"
        );
        let default_provider = model.provider == kernel.provider;
        ensure!(
            default_provider || model.base_url.is_some(),
            "Model provider requires an explicit endpoint binding"
        );
        ensure!(
            model.base_url.is_none()
                || (model.context_window.is_some() && model.output_limit.is_some()),
            "Custom Codex model requires contextWindow and outputLimit"
        );
        let base = model.base_url.as_deref().unwrap_or(&kernel.model_base_url);
        let url = Url::parse(&format!("{}/", base.trim_end_matches('/')))
            .map_err(|_| anyhow::anyhow!("Invalid model endpoint"))?;
        ensure!(
            matches!(url.scheme(), "http" | "https")
                && url.has_host()
                && url.username().is_empty()
                && url.password().is_none()
                && url.query().is_none()
                && url.fragment().is_none(),
            "Invalid model endpoint"
        );
        let key = if let Some(reference) = &model.credential_env {
            (kernel.credentials)(reference)
                .ok_or_else(|| anyhow::anyhow!("Model credential is unavailable"))?
        } else {
            ensure!(
                default_provider,
                "Model provider requires a credential binding"
            );
            kernel.model_api_key.clone()
        };
        ensure!(!key.trim().is_empty(), "Model credential is unavailable");
        Ok(Self {
            url: url.join("responses")?,
            key,
            model: model.clone(),
        })
    }

    /// Install a per-instance catalog so Core uses the sealed context and reasoning metadata.
    /// Merely changing the HTTP request would leave native truncation using different limits.
    pub async fn configure_native(
        &self,
        config: &mut Config,
        auth: Arc<AuthManager>,
    ) -> Result<()> {
        let manager = build_models_manager(config, auth);
        let mut info = manager
            .get_model_info(&self.model.id, &config.to_models_manager_config())
            .await;
        if info.used_fallback_model_metadata && self.model.base_url.is_some() {
            info.support_verbosity = false;
            info.supports_reasoning_summary_parameter = false;
        }
        if let Some(window) = self.model.context_window {
            config.model_context_window = Some(window as i64);
            info.context_window = Some(window as i64);
            info.max_context_window = Some(window as i64);
        }
        if self.model.reasoning == Some(false) {
            config.model_reasoning_effort = None;
            info.default_reasoning_level = None;
            info.supported_reasoning_levels.clear();
            info.supports_reasoning_summary_parameter = false;
        } else if let Some(effort) = self
            .model
            .parameters
            .as_ref()
            .and_then(|p| p.reasoning.as_ref())
        {
            let effort: ReasoningEffort = serde_json::from_value(json!(effort))?;
            config.model_reasoning_effort = Some(effort.clone());
            info.default_reasoning_level = Some(effort.clone());
            info.supported_reasoning_levels = vec![ReasoningEffortPreset {
                effort,
                description: "Managed model binding".into(),
            }];
        } else if self.model.reasoning == Some(true) && info.default_reasoning_level.is_none() {
            info.default_reasoning_level = Some(ReasoningEffort::Medium);
        }
        if std::env::var("MANAGED_AGENTS_EVALUATION_CONTEXT_MODE").as_deref() == Ok("short") {
            // The evaluation transport enforces the common result/context bounds.
            // Preserve accepted JSON through native injection and checkpoint replay;
            // a second native truncation would silently change the shared evidence.
            config.tool_output_token_limit = None;
            info.truncation_policy = TruncationPolicyConfig::bytes(64 * 1024 * 1024);
        }
        // The managed contract uses the standard Responses wire format.
        info.supports_reasoning_effort_updates = false;
        info.use_responses_lite = false;
        config.model_catalog = Some(ModelsResponse { models: vec![info] });
        Ok(())
    }

    pub fn request_timeout_seconds(&self) -> u64 {
        self.model.request_timeout_seconds.unwrap_or(180)
    }

    pub fn apply_request(&self, request: &mut Value) -> Result<()> {
        ensure!(
            request["model"] == self.model.id,
            "Native model differs from sealed binding"
        );
        let parameters = self.model.parameters.as_ref();
        if let Some(value) = parameters.and_then(|p| p.top_p) {
            request["top_p"] = json!(value);
        }
        if let Some(value) = parameters.and_then(|p| p.parallel_tool_calls) {
            request["parallel_tool_calls"] = json!(value);
        }
        if let Some(value) = parameters.and_then(|p| p.tool_choice.as_ref()) {
            request["tool_choice"] = json!(value);
        }
        if let Some(value) = parameters.and_then(|p| p.temperature) {
            request["temperature"] = json!(value);
        }
        if let Some(limit) = parameters
            .and_then(|p| p.max_tokens)
            .or(self.model.output_limit)
        {
            let limit = request["max_output_tokens"]
                .as_u64()
                .map_or(limit, |v| v.min(limit));
            request["max_output_tokens"] = json!(limit);
        }
        if self.model.reasoning == Some(false) {
            request.as_object_mut().unwrap().remove("reasoning");
            if let Some(include) = request["include"].as_array_mut() {
                include.retain(|v| v.as_str().is_none_or(|v| !v.starts_with("reasoning.")));
            }
        } else if let Some(effort) = parameters.and_then(|p| p.reasoning.as_ref()) {
            if !request["reasoning"].is_object() {
                request["reasoning"] = json!({});
            }
            request["reasoning"]["effort"] = json!(effort);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codex_core::config::{ConfigBuilder, LoaderOverrides};

    #[test]
    fn sealed_request_controls_override_native_defaults() -> Result<()> {
        let kernel = CodexKernel::new(
            "https://default.example/v1".into(),
            "fixture".into(),
            "openai".into(),
        );
        let binding = ModelBinding::resolve(
            &kernel,
            &serde_json::from_value(json!({
                "provider": "openai", "id": "fixture", "requestTimeoutSeconds": 17,
                "parameters": {"topP": 0.75, "parallelToolCalls": false, "toolChoice": "none"}
            }))?,
        )?;
        let mut request = json!({"model": "fixture", "top_p": 0.3,
            "parallel_tool_calls": true, "tool_choice": "auto"});
        binding.apply_request(&mut request)?;
        assert_eq!(request["top_p"], 0.75);
        assert_eq!(request["parallel_tool_calls"], false);
        assert_eq!(request["tool_choice"], "none");
        assert_eq!(binding.request_timeout_seconds(), 17);
        Ok(())
    }

    fn model() -> Model {
        serde_json::from_value(json!({"provider":"openai","id":"gpt-5.1-codex",
            "contextWindow":1000000,"outputLimit":4096,"reasoning":false}))
        .unwrap()
    }

    #[test]
    fn explicit_bindings_cannot_fall_back_to_unrelated_credentials() {
        let kernel = CodexKernel::new(
            "https://default.example/v1".into(),
            "default-secret".into(),
            "openai".into(),
        )
        .with_credential_resolver(|_| None);
        let value = serde_json::to_value(model()).unwrap();
        for (field, bad) in [
            ("credentialEnv", json!("MANAGED_MODEL_MISSING")),
            ("provider", json!("different-provider")),
            ("api", json!("openai-completions")),
            ("baseUrl", json!("https://other.example/v1")),
        ] {
            let mut candidate = value.clone();
            candidate[field] = bad;
            let candidate = serde_json::from_value(candidate).unwrap();
            match ModelBinding::resolve(&kernel, &candidate) {
                Ok(_) => panic!("invalid binding accepted"),
                Err(error) => assert!(!error.to_string().contains("secret")),
            }
        }
    }

    #[tokio::test]
    async fn native_model_catalog_consumes_context_and_reasoning_without_clamping_to_defaults()
    -> Result<()> {
        let home = tempfile::TempDir::new()?;
        let mut config = ConfigBuilder::default()
            .codex_home(home.path().to_path_buf())
            .without_project_context()
            .loader_overrides(LoaderOverrides {
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
            })
            .build()
            .await?;
        let auth = AuthManager::shared_from_config(&config, false).await?;
        let kernel = CodexKernel::new(
            "https://default.example/v1".into(),
            "fixture".into(),
            "openai".into(),
        );
        let binding = ModelBinding::resolve(&kernel, &model())?;
        binding.configure_native(&mut config, auth.clone()).await?;
        let native = build_models_manager(&config, auth)
            .get_model_info("gpt-5.1-codex", &config.to_models_manager_config())
            .await;
        assert_eq!(native.context_window, Some(1_000_000));
        assert_eq!(native.max_context_window, Some(1_000_000));
        assert_eq!(native.default_reasoning_level, None);
        assert!(native.supported_reasoning_levels.is_empty());
        assert!(!native.supports_reasoning_summary_parameter);
        Ok(())
    }
}

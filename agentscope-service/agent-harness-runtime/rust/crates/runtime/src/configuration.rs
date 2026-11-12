use crate::protocol::{RunConfiguration, Scope, VERSION, canonical, sha256};
use anyhow::{Result, ensure};
use serde::Deserialize;
use serde_json::Value;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Identity {
    format_version: String,
    harness_type: String,
    agent_id: String,
    agent_version: u32,
    source_digest: String,
    effective_digest: String,
    configuration_digest: String,
}

/// Verify the raw envelope before deserialization can discard fields or defaults can change it.
pub fn decode(
    mut response: Value,
    scope: &Scope,
    turn: &str,
    harness_type: &str,
) -> Result<RunConfiguration> {
    // Omit serde errors: source values can contain private model or resource configuration.
    let identity: Identity = serde_json::from_value(response["identity"].clone())
        .map_err(|_| anyhow::anyhow!("Invalid effective configuration identity"))?;
    let valid_hash = |hash: &str| {
        hash.len() == 64
            && hash
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    };
    ensure!(
        response["protocolVersion"] == VERSION
            && response["turnId"] == turn
            && response["scope"] == serde_json::to_value(scope)?
            && response["configuration"]["toolConfiguration"]["scope"] == response["scope"]
            && identity.format_version == "managed-effective-agent-v1"
            && matches!(
                identity.harness_type.as_str(),
                "pi" | "pi-durable" | "codex"
            )
            && identity.harness_type == harness_type
            && !identity.agent_id.trim().is_empty()
            && identity.agent_id.chars().count() <= 1024
            && response
                .as_object()
                .is_some_and(|object| object.keys().all(|key| matches!(
                    key.as_str(),
                    "protocolVersion" | "scope" | "turnId" | "identity" | "configuration"
                )))
            && identity.agent_version > 0
            && identity.agent_version <= i32::MAX as u32
            && valid_hash(&identity.source_digest)
            && valid_hash(&identity.effective_digest)
            && valid_hash(&identity.configuration_digest),
        "Invalid effective configuration identity"
    );
    response["identity"]
        .as_object_mut()
        .unwrap()
        .remove("configurationDigest");
    ensure!(
        sha256(canonical(&response)?) == identity.configuration_digest,
        "Invalid effective configuration digest"
    );
    for object in [
        &response["configuration"]["model"],
        &response["configuration"]["model"]["parameters"],
    ] {
        ensure!(
            !object
                .as_object()
                .is_some_and(|fields| fields.values().any(Value::is_null)),
            "Invalid effective configuration model fields"
        );
    }
    let configuration: RunConfiguration = serde_json::from_value(response["configuration"].take())
        .map_err(|_| anyhow::anyhow!("Invalid effective configuration fields"))?;
    configuration.model.validate()?;
    ensure!(
        harness_type != "codex"
            || configuration
                .model
                .api
                .as_deref()
                .is_none_or(|api| api == "openai-responses"),
        "Codex requires model.api openai-responses"
    );
    Ok(configuration)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn fixture() -> Value {
        let examples: Value = serde_json::from_str(include_str!(
            "../../../../protocol/examples/runtime-operations.json"
        ))
        .unwrap();
        examples["configurationResponse"].clone()
    }

    fn seal(value: &mut Value) {
        value["identity"]
            .as_object_mut()
            .unwrap()
            .remove("configurationDigest");
        value["identity"]["configurationDigest"] = json!(sha256(canonical(value).unwrap()));
    }

    #[test]
    fn verifies_shared_jcs_configuration_before_decoding() {
        let value = fixture();
        let scope: Scope = serde_json::from_value(value["scope"].clone()).unwrap();
        assert_eq!(
            decode(value.clone(), &scope, "turn1", "pi")
                .unwrap()
                .model
                .id,
            "gpt-4.1"
        );
        for harness in ["pi", "pi-durable", "codex"] {
            let mut value = value.clone();
            value["identity"]["harnessType"] = json!(harness);
            value["configuration"]["system"] = json!("中文 🧠");
            seal(&mut value);
            assert_eq!(
                decode(value, &scope, "turn1", harness)
                    .unwrap()
                    .system
                    .as_deref(),
                Some("中文 🧠")
            );
        }
    }

    #[test]
    fn rejects_modified_and_misrouted_configuration_without_exposing_content() {
        let original = fixture();
        let scope: Scope = serde_json::from_value(original["scope"].clone()).unwrap();
        for (path, changed, reseal) in [
            ("/configuration/system", json!("private prompt"), false),
            ("/identity/agentVersion", json!(4), false),
            ("/turnId", json!("different"), true),
            ("/scope/sessionId", json!("different"), true),
            (
                "/configuration/toolConfiguration/scope/sessionId",
                json!("different"),
                true,
            ),
            ("/identity/harnessType", json!("codex"), true),
            ("/identity/formatVersion", json!("unknown"), true),
            ("/identity/agentVersion", json!(0), true),
            ("/identity/agentId", json!(" "), true),
            ("/identity", Value::Null, false),
        ] {
            let mut value = original.clone();
            *value.pointer_mut(path).unwrap() = changed;
            if reseal {
                seal(&mut value);
            }
            let error = decode(value, &scope, "turn1", "pi")
                .unwrap_err()
                .to_string();
            assert!(error.starts_with("Invalid effective configuration"));
            assert!(!error.contains("private prompt"));
        }
    }

    #[test]
    fn preserves_model_binding_and_rejects_invalid_semantics_before_recovery() {
        let mut value = fixture();
        let scope: Scope = serde_json::from_value(value["scope"].clone()).unwrap();
        value["identity"]["harnessType"] = json!("codex");
        let model = json!({"provider":"managed","id":"custom","api":"openai-responses",
            "baseUrl":"https://models.example.invalid/v1","credentialEnv":"MANAGED_MODEL_ONE",
            "contextWindow":32000,"outputLimit":2048,"reasoning":true,
            "parameters":{"temperature":0.2,"maxTokens":1024,"reasoning":"low"},
            "cost":{"input":1.0,"output":2.0,"cacheRead":0.1,"cacheWrite":1.0}});
        value["configuration"]["model"] = model.clone();
        seal(&mut value);
        let decoded = decode(value.clone(), &scope, "turn1", "codex").unwrap();
        assert_eq!(serde_json::to_value(decoded.model).unwrap(), model);
        for (field, bad) in [
            ("api", json!("anthropic-messages")),
            ("credentialEnv", json!("MANAGED_AGENTS_INTERNAL_TOKEN")),
            ("baseUrl", json!("https://user:secret@example.invalid")),
            ("reasoning", json!(false)),
            ("reasoning", Value::Null),
            ("contextWindow", json!(1024)),
            ("requestTimeoutSeconds", json!(0)),
            ("requestTimeoutSeconds", json!(3601)),
            ("parameters", json!({"topP":1.1})),
            ("parameters", json!({"toolChoice":"unknown"})),
            ("parameters", json!({"parallelToolCalls":"false"})),
            ("parameters", json!({"temperature":3})),
            ("parameters", json!({"maxTokens":4096})),
            ("parameters", json!({"unknown":"private"})),
            (
                "cost",
                json!({"input":-1,"output":0,"cacheRead":0,"cacheWrite":0}),
            ),
        ] {
            let mut candidate = value.clone();
            candidate["configuration"]["model"][field] = bad;
            seal(&mut candidate);
            let error = decode(candidate, &scope, "turn1", "codex")
                .unwrap_err()
                .to_string();
            assert!(!error.contains("private") && !error.contains("secret"));
        }
    }
}

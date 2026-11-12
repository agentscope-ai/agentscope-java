use crate::protocol::{MAX_OBJECT, ObjectRef, Receipt, Scope, VERSION, canonical, counter, sha256};
use anyhow::{Result, ensure};
use async_trait::async_trait;
use base64::{Engine, engine::general_purpose::STANDARD};
use reqwest::{Client, Url, redirect::Policy};
use serde_json::{Value, json};
use std::time::Duration;

#[derive(Debug, thiserror::Error)]
pub enum ServiceError {
    #[error("Session Service response not confirmed")]
    Unconfirmed,
    #[error("Session Service rejected request: {code} (HTTP {status})")]
    Rejected { status: u16, code: String },
}

#[async_trait]
pub trait Transport: Send + Sync {
    async fn post(&self, operation: &str, request: &Value) -> Result<Value>;
}

pub struct HttpTransport {
    client: Client,
    base: Url,
    token: String,
}

impl HttpTransport {
    pub fn new(url: &str, token: String) -> Result<Self> {
        let base = Url::parse(&format!("{}/", url.trim_end_matches('/')))?;
        ensure!(
            ["http", "https"].contains(&base.scheme())
                && base.username().is_empty()
                && base.password().is_none()
                && base.query().is_none()
                && base.fragment().is_none(),
            "invalid Session Service URL"
        );
        ensure!(!token.is_empty(), "Session Service credential required");
        Ok(Self {
            base,
            token,
            client: Client::builder()
                .redirect(Policy::none())
                .timeout(Duration::from_secs(30))
                .build()?,
        })
    }
}

#[async_trait]
impl Transport for HttpTransport {
    async fn post(&self, operation: &str, request: &Value) -> Result<Value> {
        ensure!(
            operation
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b == b'/' || b == b'-')
                && !operation.starts_with('/'),
            "invalid operation"
        );
        let limit = if operation == "objects/read" || operation == "objects/write" {
            90 * 1024 * 1024
        } else if operation.starts_with("previews/") {
            64 * 1024
        } else {
            1024 * 1024
        };
        let bytes = canonical(request)?;
        ensure!(bytes.len() <= limit, "request exceeds protocol limit");
        let mut response = self
            .client
            .post(self.base.join(operation)?)
            .bearer_auth(&self.token)
            .header("content-type", "application/json")
            .body(bytes)
            .send()
            .await
            .map_err(|_| ServiceError::Unconfirmed)?;
        let status = response.status();
        let mut body = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| ServiceError::Unconfirmed)?
        {
            if body.len() + chunk.len() > limit {
                return Err(ServiceError::Unconfirmed.into());
            }
            body.extend_from_slice(&chunk);
        }
        let value: Value = serde_json::from_slice(&body).map_err(|_| ServiceError::Unconfirmed)?;
        if status.is_server_error() || [408, 429].contains(&status.as_u16()) {
            return Err(ServiceError::Unconfirmed.into());
        }
        if !status.is_success() {
            return Err(ServiceError::Rejected {
                status: status.as_u16(),
                code: value["code"].as_str().unwrap_or("service_error").to_owned(),
            }
            .into());
        }
        Ok(value)
    }
}

pub async fn commit(client: &dyn Transport, request: &Value) -> Result<Receipt> {
    let hash = sha256(canonical(request)?);
    let response = match client.post("commits", request).await {
        Ok(value) => value,
        Err(error)
            if matches!(
                error.downcast_ref::<ServiceError>(),
                Some(ServiceError::Unconfirmed)
            ) =>
        {
            let lookup = json!({"protocolVersion": VERSION, "scope": request["scope"], "turnId":request["turnId"], "commitId":request["commitId"]});
            match client.post("commits/receipt", &lookup).await {
                Ok(value) => value,
                Err(error) if matches!(error.downcast_ref::<ServiceError>(), Some(ServiceError::Rejected{code, ..}) if code == "receipt_not_found") => {
                    client.post("commits", request).await?
                }
                Err(error) => return Err(error),
            }
        }
        Err(error) => return Err(error),
    };
    let receipt: Receipt = serde_json::from_value(response)?;
    ensure!(
        receipt.outcome == "committed"
            && receipt.content_hash == hash
            && receipt.commit_id == request["commitId"],
        "commit receipt mismatch"
    );
    ensure!(
        counter(&receipt.brain_revision)?
            == counter(
                request["expectedBrainRevision"]
                    .as_str()
                    .ok_or_else(|| anyhow::anyhow!("missing revision"))?
            )? + 1,
        "commit revision mismatch"
    );
    ensure!(
        receipt.native_cursor == request["nativeState"]["nativeCursor"],
        "commit cursor mismatch"
    );
    Ok(receipt)
}

pub async fn put(
    client: &dyn Transport,
    scope: &Scope,
    bytes: &[u8],
    format: &str,
) -> Result<ObjectRef> {
    ensure!(
        !bytes.is_empty() && bytes.len() <= MAX_OBJECT,
        "invalid object size"
    );
    let hash = sha256(bytes);
    let result = client.post("objects/write", &json!({"protocolVersion":VERSION,"scope":scope,"objectId":hash,"sha256":hash,"size":bytes.len().to_string(),"mediaType":"application/json","formatVersion":format,"dataBase64":STANDARD.encode(bytes)})).await?;
    let reference: ObjectRef = serde_json::from_value(result["ref"].clone())?;
    ensure!(
        reference.scope == *scope
            && reference.sha256 == hash
            && counter(&reference.size)? == bytes.len() as u64
            && reference.format_version == format
            && reference.media_type == "application/json",
        "staged object identity mismatch"
    );
    Ok(reference)
}

pub async fn read(client: &dyn Transport, scope: &Scope, reference: &ObjectRef) -> Result<Vec<u8>> {
    ensure!(
        &reference.scope == scope && counter(&reference.size)? <= MAX_OBJECT as u64,
        "invalid object reference"
    );
    let result = client
        .post(
            "objects/read",
            &json!({"protocolVersion":VERSION,"ref":reference}),
        )
        .await?;
    ensure!(
        serde_json::from_value::<ObjectRef>(result["ref"].clone())? == *reference,
        "object reference mismatch"
    );
    let bytes = STANDARD.decode(
        result["dataBase64"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("missing object data"))?,
    )?;
    ensure!(
        bytes.len() as u64 == counter(&reference.size)? && sha256(&bytes) == reference.sha256,
        "object integrity failure"
    );
    Ok(bytes)
}

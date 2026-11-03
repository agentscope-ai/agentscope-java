---
title: "Files and artifacts"
description: "Give an Agent files as task input, then retrieve, download, and verify the actual deliverables."
zh_link: /v2/zh/service/files
---

When a task needs a report, image, or another file, the application can upload the material to its Session and reference the returned file ID in task input. After execution, it reads the actual artifact records and delivers accessible results to the user. This page follows that process from supplying materials to retrieving file output.

The Environment determines where the Agent's tools read and write working files. Uploaded Session files carry task materials, while an Artifact records a deliberately delivered result. A file appearing in the working directory does not automatically make it downloadable by an application. Use the file or artifact records returned by the service to retrieve its content.

## Upload a Session file

This example uses the Session and application credential from [Integrate applications with the Session API](/v2/en/service/service-api), with a local `report.pdf`. Upload returns an `id`; save it and reference it as `file_id` in structured input. Files are limited to 16 MiB and belong to the current Session. Another Session cannot reference them directly.

Check that the Session supports file storage and file input. Upload sends raw file bytes rather than a multipart form:

```bash
curl -sS --fail-with-body "$SESSION_URL/capabilities" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" | jq '.capabilities | {files, file_input}'
```

```bash
FILE_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/files" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Idempotency-Key: report-file-001" \
    -H "X-File-Name: report.pdf" \
    -H "Content-Type: application/pdf" \
    --data-binary @report.pdf
)
FILE_ID=$(jq -er '.id' <<< "$FILE_JSON")
```

Reference the file ID in task input:

```bash
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: review-report-001" \
    --data-binary @- <<JSON
{
  "input": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "Review this report, including its sources and open questions."
        },
        {
          "type": "file",
          "file_id": "$FILE_ID"
        }
      ]
    }
  ]
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
```

This input format is for Managed Agents. Teams and Workflows accept task input defined by the business; the service links referenced Session files as task attachments, which the executor must be able to process. Direct External or Hosted conversations do not automatically inherit Managed file-input support. Check `file_input` first.

An upload retry must preserve its key, filename, content type, and bytes. Use a new key when any of these changes. Percent-encode non-ASCII characters in `X-File-Name` using UTF-8. Upload stores the file; interpreting PDF, image, or audio content depends on the model and tools.

## Download and deliver

Use `GET /files` to list uploaded inputs. Downloading content still requires Session read access; the URL is not a public permanent link.

```bash
curl -sS --fail-with-body -G "$SESSION_URL/files" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --data-urlencode "limit=20" \
  --data-urlencode "offset=0"
```

The list returns `items` and `next_offset`. Use a non-null next offset in the next request until it becomes null.

```bash
curl -sS --fail-with-body "$SESSION_URL/files/$FILE_ID/content" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --output downloaded-report.pdf
```

A Managed Session can explicitly publish an uploaded file with `POST /artifacts`, a body of `{"file_id":"RETURNED_FILE_ID"}`, and an idempotency key. Artifacts may also reference accessible external HTTPS resources. Read the actual artifact from the snapshot or `/artifacts` and follow its file reference. Do not construct a download URL from a path guessed in an Agent reply.

Register an uploaded, verified result file as a Managed artifact. `RESULT_FILE_ID` must be an actual file ID in this Session. Publication records the deliverable; it does not read or generate a working-directory file.

```bash
RESULT_FILE_ID="UPLOADED_RESULT_FILE_ID"
ARTIFACT_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/artifacts" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: publish-report-001" \
    --data-binary @- <<JSON
{
  "file_id": "$RESULT_FILE_ID"
}
JSON
)
ARTIFACT_ID=$(jq -er '.artifact_id' <<< "$ARTIFACT_JSON")
```

List Session artifacts, then read the published artifact’s metadata:

```bash
curl -sS --fail-with-body "$SESSION_URL/artifacts" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
ARTIFACT_METADATA=$(
  curl -sS --fail-with-body "$SESSION_URL/artifacts/$ARTIFACT_ID" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
RESULT_FILE_ID=$(jq -er '.file_id' <<< "$ARTIFACT_METADATA")
```

```bash
curl -sS --fail-with-body "$SESSION_URL/files/$RESULT_FILE_ID/content" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --output result-report.pdf
```

<Accordion title="Register an external HTTPS artifact">

Use `uri` for an external reference, without `file_id`. Substitute a real HTTPS address the recipient can access. Publication neither copies the file into Service nor grants access.

```bash
curl -sS --fail-with-body "$SESSION_URL/artifacts" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: publish-external-report-001" \
  --data-binary @- <<'JSON'
{
  "name": "report.pdf",
  "uri": "https://your-app.example/reports/report.pdf",
  "media_type": "application/pdf"
}
JSON
```

</Accordion>

Team and Workflow executors report task artifacts. Read them from a Turn's `/artifacts`, then download through `/turns/{turnId}/artifacts/{artifactId}`. Verify content, version, and access permissions; a `completed` task alone does not establish a correct deliverable. See [Document verification](/v2/en/service/cases/document-verification) and [In-product delivery](/v2/en/service/cases/in-product-delivery).

<Accordion title="Download a Team or Workflow task artifact">

The following download route is for task artifacts reported by Teams and Workflows. Read the list first, then set `TURN_ARTIFACT_ID` to an actual artifact ID. It belongs to a different resource from the Managed artifact above.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/artifacts" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
TURN_ARTIFACT_ID="TASK_ARTIFACT_ID_FROM_RESPONSE"
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/artifacts/$TURN_ARTIFACT_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --output task-result.pdf
```

</Accordion>

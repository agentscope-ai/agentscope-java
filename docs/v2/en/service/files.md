---
title: "Files and artifacts"
description: "Give an Agent files as task input, then retrieve, download, and verify the actual deliverables."
zh_link: /v2/zh/service/files
---

When a task needs a report, image, or another file, the application can upload the material to its Session and reference the returned file ID in task input. After execution, it reads the actual artifact records and delivers accessible results to the user. This page follows that process from supplying materials to retrieving file output.

The Environment determines where the Agent's tools read and write working files. Uploaded Session files carry task materials, while an Artifact records a deliberately delivered result. A file appearing in the working directory does not automatically make it downloadable by an application. Use the file or artifact records returned by the service to retrieve its content.

## Upload a Session file

This example uses the Session and application credential from [Integrate applications with the Session API](/v2/en/service/service-api), with a local `report.pdf`. Upload returns an `id`; save it and reference it as `file_id` in structured input. Files are limited to 16 MiB and belong to the current Session. Another Session cannot reference them directly.

```bash
FILE_JSON=$(curl --fail-with-body -sS "$SESSION_URL/files" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Idempotency-Key: report-file-001' \
  -H 'X-File-Name: report.pdf' -H 'Content-Type: application/pdf' \
  --data-binary @report.pdf)
FILE_ID=$(printf '%s' "$FILE_JSON" | jq -er '.id')
curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: review-report-001' \
  -d "$(jq -n --arg id "$FILE_ID" \
    '{input:[{role:"user",content:[{type:"text",text:"Review this report"},{type:"file",file_id:$id}]}]}')"
```

This input format is for Managed Agents. Teams and Workflows accept task input defined by the business; the service links referenced Session files as task attachments, which the executor must be able to process. Direct External or Hosted conversations do not automatically inherit Managed file-input support. Check `file_input` first.

An upload retry must preserve its key, filename, content type, and bytes. Use a new key when any of these changes. Percent-encode non-ASCII characters in `X-File-Name` using UTF-8. Upload stores the file; interpreting PDF, image, or audio content depends on the model and tools.

## Download and deliver

Use `GET /files` to list uploaded inputs. Downloading content still requires Session read access; the URL is not a public permanent link.

```bash
curl --fail-with-body -sS "$SESSION_URL/files/$FILE_ID/content" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -o downloaded-report.pdf
```

A Managed Session can explicitly publish an uploaded file with `POST /artifacts`, a body of `{"file_id":"RETURNED_FILE_ID"}`, and an idempotency key. Artifacts may also reference accessible external HTTPS resources. Read the actual artifact from the snapshot or `/artifacts` and follow its file reference. Do not construct a download URL from a path guessed in an Agent reply.

Team and Workflow executors report task artifacts. Read them from a Turn's `/artifacts`, then download through `/turns/{turnId}/artifacts/{artifactId}`. Verify content, version, and access permissions; a `completed` task alone does not establish a correct deliverable. See [Document verification](/v2/en/service/cases/document-verification) and [In-product delivery](/v2/en/service/cases/in-product-delivery).

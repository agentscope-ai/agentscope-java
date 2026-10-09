---
title: "文件与产物"
description: "把文件作为任务输入交给 Agent，并读取、下载和检查实际交付的产物。"
en_link: /v2/en/service/files
---

当一项任务需要处理报告、图片或其他文件时，应用可以先把材料上传到 Session，再在任务输入中引用返回的文件 ID。任务完成后，应用读取实际产物记录，将能够访问的结果交付给用户。本页沿着这个过程说明如何传入材料和取得文件结果。

Agent 通过工具读写文件时，工作位置由 Environment 决定；上传到 Session 的文件用于传递材料，而 Artifact 记录明确交付的结果。因此，Agent 在工作目录里写出了一个文件，并不意味着业务应用已经能下载它。应用应根据服务返回的文件或产物记录获取内容。

## 上传会话文件

以下示例沿用[通过 Session API 接入应用](/v2/zh/service/service-api)中的 Session 和应用凭据，并假设本地已有 `report.pdf`。上传接口返回文件的 `id`；应用保存这个值，随后以 `file_id` 放入结构化消息。文件最多为 16 MiB，并归属于当前 Session，其他 Session 不能直接引用它。

先确认 Session 支持文件存储与文件输入；上传请求直接发送文件字节，不使用 multipart 表单：

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

再将文件 ID 放入这一轮任务的输入：

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
          "text": "核验这份报告，列出来源和需要确认的内容。"
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

这个输入格式适用于 Managed Agent。Team 和 Workflow 接收业务定义的任务输入，Service 会将其中的会话文件关联为任务附件；执行器仍需具备读取和处理附件的能力。External 或 Hosted 的直接对话不自动获得 Managed 的文件输入能力，接入前检查 `file_input`。

上传重试时使用相同的 key、文件名、内容类型和字节内容。如果其中任意一项改变，应使用新 key。`X-File-Name` 的非 ASCII 字符需要 UTF-8 百分号编码。上传只负责保存文件，模型是否能够理解 PDF、图片或音频，还取决于模型和工具配置。

## 下载与交付

调用 `GET /files` 可以列出会话输入文件；下载内容仍需 Session 的读取权限。下载地址不是可公开转发的永久链接。

```bash
curl -sS --fail-with-body -G "$SESSION_URL/files" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --data-urlencode "limit=20" \
  --data-urlencode "offset=0"
```

列表返回 `items` 和 `next_offset`。后者非空时，用它替换下一次请求的 `offset`，直至返回 null。

```bash
curl -sS --fail-with-body "$SESSION_URL/files/$FILE_ID/content" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --output downloaded-report.pdf
```

Managed Session 可以通过 `POST /artifacts` 将已上传的文件显式发布为产物，请求体为 `{"file_id":"RETURNED_FILE_ID"}`，并提供幂等键。产物也可以引用可访问的外部 HTTPS 资源。应用从快照或 `/artifacts` 中取得实际产物记录，再按照它的文件引用下载；不要根据 Agent 回复中猜测的路径拼接下载地址。

下面将已经上传并核验过的结果文件登记为 Managed 产物。`RESULT_FILE_ID` 必须是当前 Session 的真实文件 ID；这一步登记交付记录，不会从工作目录读取或生成文件。

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

列出会话产物，再读取刚发布的元数据：

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

<Accordion title="登记外部 HTTPS 产物">

仅登记外部资源时使用 `uri`，不要同时传 `file_id`。替换为业务接收方有权访问的真实 HTTPS 地址；登记不会把外部文件复制到 Service，也不会授予访问权限。

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

Team 和 Workflow 的任务产物由执行器回报，应用可以从 Turn 的 `/artifacts` 查看，再使用 `/turns/{turnId}/artifacts/{artifactId}` 下载。验收时应检查文件内容、版本和访问权限，不能仅凭任务返回 `completed` 判断交付物正确。[文档核验](/v2/zh/service/cases/document-verification)和[产品内文件交付](/v2/zh/service/cases/in-product-delivery)说明了业务端如何使用这些结果。

<Accordion title="下载 Team 或 Workflow 的任务产物">

以下下载路径用于 Team、Workflow 回报的任务产物。先查看列表，再把 `TURN_ARTIFACT_ID` 替换为实际产物 ID；它与上面的 Managed 产物 ID 属于不同资源。

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

---
title: "文件与产物"
description: "把文件作为任务输入交给 Agent，并读取、下载和检查实际交付的产物。"
en_link: /v2/en/service/files
---

当一项任务需要处理报告、图片或其他文件时，应用可以先把材料上传到 Session，再在任务输入中引用返回的文件 ID。任务完成后，应用读取实际产物记录，将能够访问的结果交付给用户。本页沿着这个过程说明如何传入材料和取得文件结果。

Agent 通过工具读写文件时，工作位置由 Environment 决定；上传到 Session 的文件用于传递材料，而 Artifact 记录明确交付的结果。因此，Agent 在工作目录里写出了一个文件，并不意味着业务应用已经能下载它。应用应根据服务返回的文件或产物记录获取内容。

## 上传会话文件

以下示例沿用[通过 Session API 接入应用](/v2/zh/service/service-api)中的 Session 和应用凭据，并假设本地已有 `report.pdf`。上传接口返回文件的 `id`；应用保存这个值，随后以 `file_id` 放入结构化消息。文件最多为 16 MiB，并归属于当前 Session，其他 Session 不能直接引用它。

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

这个输入格式适用于 Managed Agent。Team 和 Workflow 接收业务定义的任务输入，Service 会将其中的会话文件关联为任务附件；执行器仍需具备读取和处理附件的能力。External 或 Hosted 的直接对话不自动获得 Managed 的文件输入能力，接入前检查 `file_input`。

上传重试时使用相同的 key、文件名、内容类型和字节内容。如果其中任意一项改变，应使用新 key。`X-File-Name` 的非 ASCII 字符需要 UTF-8 百分号编码。上传只负责保存文件，模型是否能够理解 PDF、图片或音频，还取决于模型和工具配置。

## 下载与交付

调用 `GET /files` 可以列出会话输入文件；下载内容仍需 Session 的读取权限。下载地址不是可公开转发的永久链接。

```bash
curl --fail-with-body -sS "$SESSION_URL/files/$FILE_ID/content" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -o downloaded-report.pdf
```

Managed Session 可以通过 `POST /artifacts` 将已上传的文件显式发布为产物，请求体为 `{"file_id":"RETURNED_FILE_ID"}`，并提供幂等键。产物也可以引用可访问的外部 HTTPS 资源。应用从快照或 `/artifacts` 中取得实际产物记录，再按照它的文件引用下载；不要根据 Agent 回复中猜测的路径拼接下载地址。

Team 和 Workflow 的任务产物由执行器回报，应用可以从 Turn 的 `/artifacts` 查看，再使用 `/turns/{turnId}/artifacts/{artifactId}` 下载。验收时应检查文件内容、版本和访问权限，不能仅凭任务返回 `completed` 判断交付物正确。[文档核验](/v2/zh/service/cases/document-verification)和[产品内文件交付](/v2/zh/service/cases/in-product-delivery)说明了业务端如何使用这些结果。

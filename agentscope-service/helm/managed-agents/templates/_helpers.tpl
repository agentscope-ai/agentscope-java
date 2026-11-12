{{- define "service.name" -}}
{{- printf "%s-managed-agents" .Release.Name | trunc 45 | trimSuffix "-" -}}
{{- end -}}
{{- define "service.labels" -}}
app.kubernetes.io/name: managed-agents
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{/* Retain cloud defaults when an upgrade reuses values from an older Chart. */}}
{{- define "service.cloudDataSecret" -}}
{{- $data := .Values.data | default dict -}}
{{- if hasKey $data "existingSecret" -}}
{{- $data.existingSecret -}}
{{- else -}}
managed-agents-cloud-data
{{- end -}}
{{- end -}}
{{- define "service.cloudMigrationSecret" -}}
{{- if hasKey .Values.schema "dataExistingSecret" -}}
{{- .Values.schema.dataExistingSecret -}}
{{- else -}}
managed-agents-cloud-data-migration
{{- end -}}
{{- end -}}

{{- define "service.evaluationEnv" -}}
{{- $evaluation := .Values.evaluation | default dict -}}
{{- if $evaluation.contextMode -}}
{{- if not (has $evaluation.contextMode (list "short" "native")) -}}
{{- fail "evaluation.contextMode must be short or native" -}}
{{- end }}
- {name: MANAGED_AGENTS_EVALUATION_CONTEXT_MODE, value: {{ $evaluation.contextMode | quote }}}
{{- end -}}
{{- if $evaluation.toolEnvelope -}}
{{- if ne $evaluation.toolEnvelope "text-json-v1" -}}
{{- fail "evaluation.toolEnvelope must be text-json-v1" -}}
{{- end }}
- {name: MANAGED_AGENTS_EVALUATION_TOOL_ENVELOPE, value: {{ $evaluation.toolEnvelope | quote }}}
{{- end -}}
{{- if $evaluation.toolSurface -}}
{{- if ne $evaluation.toolSurface "mcp-only" -}}
{{- fail "evaluation.toolSurface must be mcp-only" -}}
{{- end }}
- {name: MANAGED_AGENTS_EVALUATION_TOOL_SURFACE, value: {{ $evaluation.toolSurface | quote }}}
{{- end -}}
{{- end -}}

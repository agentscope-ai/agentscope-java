{{- define "service.name" -}}
{{- printf "%s-agentscope" .Release.Name | trunc 45 | trimSuffix "-" -}}
{{- end -}}
{{- define "service.labels" -}}
app.kubernetes.io/name: agentscope-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* Optional overrides never introduce installation-specific defaults. */}}
{{- define "service.dataSecret" -}}
{{- (.Values.data | default dict).existingSecret | default "" -}}
{{- end -}}
{{- define "service.migrationDataSecret" -}}
{{- .Values.schema.dataExistingSecret | default "" -}}
{{- end -}}

{{- define "service.name" -}}
{{- printf "%s-agentscope" .Release.Name | trunc 45 | trimSuffix "-" -}}
{{- end -}}
{{- define "service.labels" -}}
app.kubernetes.io/name: agentscope-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

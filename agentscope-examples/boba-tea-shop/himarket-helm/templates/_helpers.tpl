{{- define "himarket.serviceAccountName" -}}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}

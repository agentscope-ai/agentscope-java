{{/*
Chart name
*/}}
{{- define "agentscope.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
app name
*/}}
{{- define "agentscope.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart name and version tag
*/}}
{{- define "agentscope.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
common tag
*/}}
{{- define "agentscope.labels" -}}
helm.sh/chart: {{ include "agentscope.chart" . }}
{{ include "agentscope.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
selector tag
*/}}
{{- define "agentscope.selectorLabels" -}}
app.kubernetes.io/name: {{ include "agentscope.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
common env
*/}}
{{- define "agentscope.commonEnv" -}}
- name: MODEL_PROVIDER
  value: {{ .Values.agentscope.model.provider | quote }}
- name: MODEL_API_KEY
  value: {{ .Values.agentscope.model.apiKey | quote }}
- name: MODEL_NAME
  value: {{ .Values.agentscope.model.modelName | quote }}
{{- if .Values.agentscope.model.baseUrl }}
- name: MODEL_BASE_URL
  value: {{ .Values.agentscope.model.baseUrl | quote }}
{{- end }}
- name: DASHSCOPE_ACCESS_KEY_ID
  value: {{ .Values.dashscope.accessKeyId | quote }}
- name: DASHSCOPE_ACCESS_KEY_SECRET
  value: {{ .Values.dashscope.accessKeySecret | quote }}
- name: DASHSCOPE_WORKSPACE_ID
  value: {{ .Values.dashscope.workspaceId | quote }}
- name: DASHSCOPE_INDEX_ID
  value: {{ .Values.dashscope.indexId | quote }}
- name: DB_HOST
  value: {{ .Values.mysql.host | quote }}
- name: DB_PORT
  value: "3306"
- name: DB_NAME
  value: {{ .Values.mysql.dbname | quote }}
- name: DB_USERNAME
  value: {{ .Values.mysql.username | quote }}
- name: DB_PASSWORD
  value: {{ .Values.mysql.password | quote }}
- name: MEM0_API_KEY
  value: {{ .Values.mem0.apiKey | quote }}
- name: NACOS_SERVER_ADDR
  value: {{ .Values.nacos.serverAddr | quote }}
- name: NACOS_NAMESPACE
  value: {{ .Values.nacos.namespace | quote }}
- name: NACOS_REGISTER_ENABLED
  value: {{ .Values.nacos.registerEnabled | quote }}
{{- end }}

{{/*
XXL-JOB env
*/}}
{{- define "agentscope.xxlJobEnv" -}}
{{- if .Values.xxlJob.enabled }}
- name: XXL_JOB_ENABLED
  value: "true"
- name: XXL_JOB_ADMIN
  value: {{ .Values.xxlJob.admin | quote }}
- name: XXL_JOB_ACCESS_TOKEN
  value: {{ .Values.xxlJob.accessToken | quote }}
- name: XXL_JOB_APPNAME
  value: {{ .Values.xxlJob.appname | quote }}
{{- end }}
{{- end }}

{{/*
image
*/}}
{{- define "agentscope.image" -}}
{{- $registry := .registry -}}
{{- $repository := .repository -}}
{{- $tag := .tag | default $.Values.image.tag -}}
{{- printf "%s/%s:%s" $registry $repository $tag -}}
{{- end }}

{{/*
Wait for Nacos init container
*/}}
{{- define "agentscope.waitForNacos" -}}
- name: wait-for-nacos
  image: {{ .Values.image.registry }}/busybox:1.36
  imagePullPolicy: {{ .Values.image.pullPolicy }}
  command:
    - sh
    - -c
    - |
      NACOS_ADDR="{{ .Values.nacos.serverAddr }}"
      NACOS_HOST=$(echo $NACOS_ADDR | cut -d: -f1)
      NACOS_PORT=$(echo $NACOS_ADDR | cut -d: -f2)
      echo "Waiting for Nacos at $NACOS_HOST:$NACOS_PORT..."
      until nc -z $NACOS_HOST $NACOS_PORT; do
        echo "Nacos is not ready, waiting..."
        sleep 2
      done
      echo "Nacos is ready!"
{{- end }}


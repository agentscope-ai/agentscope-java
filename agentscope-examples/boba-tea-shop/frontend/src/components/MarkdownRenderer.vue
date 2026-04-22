<!--
  ~ Copyright 2024-2026 the original author or authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
-->


<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

interface Props {
  content: string
  isStreaming?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isStreaming: false
})

const htmlContent = ref('')
let renderTimeout: number | null = null

// Configure marked
marked.setOptions({
  breaks: true,
  gfm: true
})

const processedContent = computed(() => {
  if (!props.content) return ''

  // Add streaming indicator
  let content = props.content
  if (props.isStreaming) {
    content += '<span class="streaming-cursor">▋</span>'
  }

  return content
})

const renderMarkdown = async () => {
  if (!processedContent.value) {
    htmlContent.value = ''
    return
  }

  // If streaming, use debounce mechanism
  if (props.isStreaming) {
    if (renderTimeout) {
      clearTimeout(renderTimeout)
    }
    renderTimeout = window.setTimeout(async () => {
      await performRender()
    }, 100) // 100ms debounce
  } else {
    await performRender()
  }
}

const performRender = async () => {
  try {
    // Always perform content preprocessing to ensure correct markdown format
    let content = processedContent.value

    // Perform basic formatting to ensure list items have proper line breaks
    content = content
      // First process Agent State info, ensure it's on its own line
      .replace(/^(Agent State: [^\n]+)/gm, '$1\n\n')
      // Process unordered list items (lines starting with -), ensure line break before
        .replace(/([^\n])\n(\s*-\s+)/g, '$1\n\n$2')
      // Process ordered list items (lines starting with number.), ensure line break before
        .replace(/(^|\n)(\s*\d+\.\s+)/g, '\n\n$2')
      // Ensure line break before list items (for existing format)
      .replace(/(\d+\.\s*\*\*[^*]+\*\*)/g, '\n\n$1')
      // Ensure line break after list items, using more precise matching
      .replace(/(\d+\.\s*\*\*[^*]+\*\*[^]*?)(?=\d+\.\s*\*\*|$)/g, (match) => {
        // If matched content doesn't end with line break, add one
        return match.trim() + '\n\n'
      })
      // Process consecutive list items, ensure proper spacing between them
      .replace(/(\n\s*-\s+[^\n]+)(\n\s*-\s+)/g, '$1\n$2')
      .replace(/(\n\s*\d+\.\s+[^\n]+)(\n\s*\d+\.\s+)/g, '$1\n$2')
      // Process paragraph line breaks, ensure proper spacing between paragraphs
      .replace(/([.!?])\s*([A-Z\u4e00-\u9fa5])/g, '$1\n\n$2')
      // Clean up extra blank lines
      .replace(/\n{3,}/g, '\n\n')
      // Remove leading line breaks
      .replace(/^\n+/, '')
      // Remove trailing line breaks
      .replace(/\n+$/, '')

    const rawHtml = await marked(content)
    htmlContent.value = DOMPurify.sanitize(rawHtml, {
      ALLOWED_TAGS: [
        'p', 'br', 'strong', 'em', 'u', 's', 'del', 'ins',
        'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
        'ul', 'ol', 'li', 'blockquote', 'pre', 'code',
        'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
        'div', 'span', 'hr'
      ],
      ALLOWED_ATTR: [
        'href', 'title', 'alt', 'src', 'class', 'id',
        'target', 'rel', 'style'
      ]
    })
  } catch (error) {
    console.error('Markdown rendering error:', error)
    htmlContent.value = props.content
  }
}

// Expose method for parent component to call
const forceRender = async () => {
  if (renderTimeout) {
    clearTimeout(renderTimeout)
    renderTimeout = null
  }
  await performRender()
}

// Expose methods
defineExpose({
  forceRender
})

onMounted(() => {
  renderMarkdown()
})

// Watch for content changes
import { watch } from 'vue'
watch(processedContent, renderMarkdown, { immediate: true })

// Watch for streaming state changes
watch(() => props.isStreaming, (newVal, oldVal) => {
  // When streaming ends, immediately render the final result
  if (oldVal === true && newVal === false) {
    if (renderTimeout) {
      clearTimeout(renderTimeout)
      renderTimeout = null
    }
    performRender()
  }
})
</script>

<template>
  <div
    class="markdown-content"
    v-html="htmlContent"
  />
</template>

<style scoped>
.markdown-content {
  line-height: 1.6;
  /* color: #333; */
  /* white-space: pre-wrap; */
  word-wrap: break-word;
}

.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4),
.markdown-content :deep(h5),
.markdown-content :deep(h6) {
  margin: 1em 0 0.5em 0;
  font-weight: 600;
  line-height: 1.25;
}

.markdown-content :deep(h1) {
  font-size: 1.5em;
  border-bottom: 1px solid #eaecef;
  padding-bottom: 0.3em;
}

.markdown-content :deep(h2) {
  font-size: 1.25em;
  border-bottom: 1px solid #eaecef;
  padding-bottom: 0.3em;
}

.markdown-content :deep(p) {
  margin: 0.5em 0;
  line-height: 1.6;
}

.markdown-content :deep(p:first-child) {
  margin-top: 0;
}

.markdown-content :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  margin: 0.5em 0;
  padding-left: 1.5em;
}

.markdown-content :deep(li) {
  margin: 0.5em 0;
  line-height: 1.6;
  position: relative;
}

.markdown-content :deep(li p) {
  margin: 0.25em 0;
}

.markdown-content :deep(li strong) {
  font-weight: 600;
  color: #1890ff;
}

.markdown-content :deep(li::marker) {
  color: #1890ff;
  font-weight: bold;
}

.markdown-content :deep(ul li) {
  list-style-type: disc;
}

.markdown-content :deep(ol li) {
  list-style-type: decimal;
}

.markdown-content :deep(li:not(:last-child)) {
  margin-bottom: 0.8em;
}

.markdown-content :deep(li) {
  margin-bottom: 0.6em;
  padding-left: 0.2em;
}

.markdown-content :deep(ul) {
  margin: 1em 0;
  padding-left: 1.8em;
}

.markdown-content :deep(ol) {
  margin: 1em 0;
  padding-left: 1.8em;
}

.markdown-content :deep(blockquote) {
  margin: 0.5em 0;
  padding: 0 1em;
  color: #6a737d;
  border-left: 0.25em solid #dfe2e5;
  background: #f6f8fa;
}

.markdown-content :deep(pre) {
  margin: 0.5em 0;
  padding: 1em;
  background: #f6f8fa;
  border-radius: 6px;
  overflow-x: auto;
}

.markdown-content :deep(code) {
  padding: 0.2em 0.4em;
  margin: 0;
  font-size: 85%;
  background: rgba(175, 184, 193, 0.2);
  border-radius: 3px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.markdown-content :deep(pre code) {
  padding: 0;
  background: transparent;
  border-radius: 0;
}

.markdown-content :deep(table) {
  border-collapse: collapse;
  margin: 0.5em 0;
  width: 100%;
}

.markdown-content :deep(th),
.markdown-content :deep(td) {
  border: 1px solid #dfe2e5;
  padding: 0.5em;
  text-align: left;
}

.markdown-content :deep(th) {
  background: #f6f8fa;
  font-weight: 600;
}

.markdown-content :deep(a) {
  color: #0366d6;
  text-decoration: none;
}

.markdown-content :deep(a:hover) {
  text-decoration: underline;
}

.markdown-content :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 6px;
}

.markdown-content :deep(hr) {
  height: 0.25em;
  margin: 1.5em 0;
  background: #e1e4e8;
  border: 0;
}

.streaming-cursor {
  animation: blink 1s infinite;
  color: #667eea;
  font-weight: bold;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}
</style>



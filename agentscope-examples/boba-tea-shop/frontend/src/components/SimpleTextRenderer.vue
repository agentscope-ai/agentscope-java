<!--
 * 简单的文本渲染组件，用于调试
-->
<script setup lang="ts">
interface Props {
  content: string
  isStreaming?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isStreaming: false
})

console.log('SimpleTextRenderer props:', { content: props.content, isStreaming: props.isStreaming })
</script>

<template>
  <div class="simple-text-content">
    <span v-if="props.content">{{ props.content }}</span>
    <span v-else class="empty-content">[空内容]</span>
    <span v-if="props.isStreaming" class="streaming-cursor">▋</span>
  </div>
</template>

<style scoped>
.simple-text-content {
  line-height: 1.6;
  color: #333;
}

.empty-content {
  color: #999;
  font-style: italic;
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

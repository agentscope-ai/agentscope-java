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

<!--
 * Simple text rendering component for debugging
-->
<script setup lang="ts">
interface Props {
  content: string
  isStreaming?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isStreaming: false
})

</script>

<template>
  <div class="simple-text-content">
    <span v-if="props.content">{{ props.content }}</span>
    <span v-else class="empty-content">[empty content]</span>
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

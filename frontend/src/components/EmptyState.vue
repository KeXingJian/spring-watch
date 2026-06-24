<script setup lang="ts">
defineProps<{
  icon?: string
  hint?: string
  inline?: boolean
}>()
</script>

<template>
  <div class="empty-state" :class="{ inline }">
    <div v-if="icon" class="icon" v-html="icon"></div>
    <div class="text-secondary"><slot>暂无数据</slot></div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

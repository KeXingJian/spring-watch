<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ content: string }>()

const html = computed(() => renderMarkdown(props.content ?? ''))
</script>

<template>
  <div class="md-body" v-html="html" />
</template>

<style scoped>
.md-body {
  font-size: 0.9rem;
  line-height: 1.62;
  word-break: break-word;
  color: oklch(var(--bc));
}
.md-body :deep(> *:first-child) { margin-top: 0; }
.md-body :deep(> *:last-child) { margin-bottom: 0; }

.md-body :deep(p) { margin: 0.45em 0; }
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3), .md-body :deep(h4) {
  font-weight: 700;
  margin: 0.9em 0 0.4em;
  line-height: 1.35;
}
.md-body :deep(h1) { font-size: 1.25rem; }
.md-body :deep(h2) { font-size: 1.12rem; border-bottom: 1px solid oklch(var(--b3)); padding-bottom: 4px; }
.md-body :deep(h3) { font-size: 1rem; }
.md-body :deep(h4) { font-size: 0.94rem; }

.md-body :deep(ul), .md-body :deep(ol) { margin: 0.4em 0; padding-left: 1.4em; }
.md-body :deep(li) { margin: 0.18em 0; }
.md-body :deep(li > p) { margin: 0.2em 0; }

.md-body :deep(a) { color: oklch(var(--in)); text-decoration: underline; }

.md-body :deep(blockquote) {
  margin: 0.5em 0;
  padding: 4px 12px;
  border-left: 3px solid oklch(var(--in) / 0.45);
  color: oklch(var(--bc) / 0.78);
  background: oklch(var(--b2) / 0.5);
  border-radius: 0 6px 6px 0;
}
.md-body :deep(blockquote p) { margin: 0.2em 0; }

.md-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.82em;
  background: oklch(var(--b2));
  border: 1px solid oklch(var(--b3));
  padding: 1px 5px;
  border-radius: 4px;
  word-break: break-all;
}

.md-body :deep(pre) {
  background: #0f172a;
  color: #e2e8f0;
  padding: 10px 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 0.55em 0;
  font-family: var(--font-mono);
  font-size: 0.78rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 360px;
}
.md-body :deep(pre code) {
  background: transparent;
  border: none;
  padding: 0;
  color: inherit;
  font-size: inherit;
}

.md-body :deep(table) {
  border-collapse: collapse;
  margin: 0.6em 0;
  width: 100%;
  font-size: 0.84rem;
}
.md-body :deep(th), .md-body :deep(td) {
  border: 1px solid oklch(var(--b3));
  padding: 5px 9px;
  text-align: left;
}
.md-body :deep(th) {
  background: oklch(var(--b2));
  font-weight: 600;
}
.md-body :deep(tr:nth-child(2n)) td { background: oklch(var(--b2) / 0.4); }

.md-body :deep(hr) {
  border: none;
  border-top: 1px solid oklch(var(--b3));
  margin: 0.8em 0;
}

.md-body :deep(strong) { font-weight: 700; }
</style>

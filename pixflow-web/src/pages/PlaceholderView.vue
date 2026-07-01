<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
const title = (route.meta.title as string | undefined) ?? '占位'
</script>

<template>
  <section class="placeholder">
    <h2>{{ title }}</h2>
    <p>该模块属于 Wave 6 范畴，V1 不在范围内。</p>
  </section>
</template>

<style scoped>
.placeholder { padding: 24px; color: var(--color-text-mute); }
h2 { margin: 0 0 8px; }
</style>
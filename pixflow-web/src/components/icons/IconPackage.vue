<script setup lang="ts">
import IconBase from './IconBase.vue'
/**
 * IconPackage — 压缩包/素材包节点
 * 用于左栏"上传"段节点类型标识（web.md §9 左侧栏）
 */
</script>
<template>
  <IconBase>
    <!-- 包裹外框 -->
    <path d="M21 8L12 3L3 8V16L12 21L21 16V8Z" />
    <!-- 顶部折线（开合处） -->
    <path d="M3 8L12 13L21 8" />
    <!-- 垂直中线（左侧缝） -->
    <path d="M12 13V21" />
    <!-- 捆扎绳 - 横向 -->
    <path d="M7.5 5.5L16.5 10.5" />
  </IconBase>
</template>
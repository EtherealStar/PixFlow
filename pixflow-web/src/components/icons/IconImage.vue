<script setup lang="ts">
import IconBase from './IconBase.vue'
/**
 * IconImage — 单图节点
 * 用于左栏"上传"段单图节点（也用于 HeroUpload 拖拽区）
 */
</script>
<template>
  <IconBase>
    <!-- 图框 -->
    <rect
      x="3"
      y="3"
      width="18"
      height="18"
      rx="2"
    />
    <!-- 圆形（太阳） -->
    <circle
      cx="9"
      cy="9"
      r="2"
    />
    <!-- 山形（地平线 + 山） -->
    <path d="M21 15L16 10L5 21" />
  </IconBase>
</template>
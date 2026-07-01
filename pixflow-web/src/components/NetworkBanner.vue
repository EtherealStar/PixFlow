<script setup lang="ts">
// 顶部条幅：网络已断开。
</script>

<template>
  <div class="network-banner">
    <span>⚠ 网络已断开，请检查本地连接...</span>
  </div>
</template>

<style scoped>
.network-banner {
  background: var(--color-warning);
  color: white;
  padding: 6px 16px;
  font-size: 13px;
  text-align: center;
}
</style>
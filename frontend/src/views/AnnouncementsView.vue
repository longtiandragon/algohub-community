<script setup lang="ts">
import AnnouncementBoard from '@/components/home/AnnouncementBoard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
</script>

<template>
  <div class="page" data-test="page">
    <PageHeader title="公告与资源" description="平台更新与通知，以及常用的学习、竞赛资源。" />
    <AnnouncementBoard variant="page" />
  </div>
</template>

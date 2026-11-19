<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageTabs from '@/components/ui/PageTabs.vue'
import PracticeRankingPanel from '@/components/ranking/PracticeRankingPanel.vue'
import RatingRankingPanel from '@/components/ranking/RatingRankingPanel.vue'

const route = useRoute()
const active = computed(() => route.query.tab === 'rating' ? 'rating' : 'practice')
const tabs = computed(() => [
  { key: 'practice', label: '刷题榜', to: { path: '/ranking', query: { ...route.query, tab: undefined } } },
  { key: 'rating', label: '比赛 Rating 榜', to: { path: '/ranking', query: { ...route.query, tab: 'rating' } } }
])
</script>

<template>
  <div>
    <PageHeader title="排行榜" />
    <PageTabs :tabs="tabs" :active="active" label="榜单类型" />
    <RatingRankingPanel v-if="active === 'rating'" />
    <PracticeRankingPanel v-else />
  </div>
</template>

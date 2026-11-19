<script setup lang="ts">
import { computed, inject } from 'vue'
import { remarksKey } from '@/composables/useStudentRemarks'
const props = defineProps<{userId:number; name:string; compact?:boolean; profile?:boolean}>()
const notes = inject(remarksKey)
const value = computed(() => notes?.names.value[props.userId])
</script>
<template>
  <span class="remark-name">
    <button v-if="notes?.manageable.value" class="remark-trigger" type="button" :aria-label="'查看 '+(value || name)+' 的学生卡片'" @click="notes.open(userId,name)">{{value || name}}</button>
    <RouterLink v-else-if="profile" :to="'/users/'+userId" class="remark-profile">{{name}}</RouterLink>
    <template v-else>{{name}}</template><slot />
    <small v-if="value && !compact">{{name}}</small>
  </span>
</template>
<style scoped>
.remark-trigger,.remark-profile{color:inherit;font:inherit;text-align:inherit;text-decoration:none}
.remark-trigger{cursor:pointer;background:transparent;border:0;padding:0}.remark-trigger:hover,.remark-profile:hover{text-decoration:underline}
.remark-name small{display:block;color:var(--el-text-color-secondary);font-weight:400;font-size:.78em}
</style>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { weekMonday } from '@/api/weeklyTraining'
import { campusDate, shiftDate } from '@/utils/statistics'

const props = defineProps<{ modelValue: string; minWeek?: string; maxWeek?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const opened = ref(false)
const month = ref('')
const activeDay = ref('')
const today = campusDate()

watch(() => props.modelValue, week => {
  if (!activeDay.value || weekMonday(activeDay.value) !== week)
    activeDay.value = weekMonday(today) === week ? today : week
  month.value = activeDay.value.slice(0, 7)
}, { immediate: true })

const lastDay = computed(() => shiftDate(props.modelValue, 6))
const label = computed(() => props.modelValue.replaceAll('-', '.') + ' — ' +
  (lastDay.value.slice(0, 4) === props.modelValue.slice(0, 4) ? lastDay.value.slice(5) : lastDay.value).replaceAll('-', '.'))
function allowed(day: string) {
  const week = weekMonday(day)
  return (!props.minWeek || week >= props.minWeek) && (!props.maxWeek || week <= props.maxWeek)
}
function adjacentMonth(delta: number) {
  const date = new Date(month.value + '-01T00:00:00Z')
  date.setUTCMonth(date.getUTCMonth() + delta)
  return date.toISOString().slice(0, 7)
}
const days = computed(() => {
  const first = weekMonday(month.value + '-01')
  const end = shiftDate(weekMonday(shiftDate(adjacentMonth(1) + '-01', -1)), 6)
  const result = []
  for (let day = first; day <= end; day = shiftDate(day, 1)) result.push(day)
  return result
})
function choose(day: string) {
  if (!allowed(day)) return
  activeDay.value = day
  month.value = day.slice(0, 7)
  opened.value = false
  emit('update:modelValue', weekMonday(day))
}
</script>
<template>
  <div class="week-picker" @keydown.esc="opened = false">
    <button type="button" class="arrow" aria-label="上一周" :disabled="!allowed(shiftDate(modelValue, -7))" @click="choose(shiftDate(modelValue, -7))"><i class="i-lucide-chevron-left" /></button>
    <el-popover v-model:visible="opened" trigger="click" placement="bottom-start" :width="296" :show-arrow="false"
      @show="month = activeDay.slice(0, 7)">
      <template #reference>
        <button type="button" class="range" aria-label="选择训练周" aria-haspopup="dialog" :aria-expanded="opened">
          <i class="i-lucide-calendar-days" /> {{ label }} <i class="i-lucide-chevron-down" />
        </button>
      </template>
      <section role="dialog" aria-label="训练周日历" class="calendar" @keydown.esc.stop="opened = false">
        <header>
          <button type="button" aria-label="上个月" :disabled="!!minWeek && month <= minWeek.slice(0, 7)" @click="month = adjacentMonth(-1)">‹</button>
          <strong>{{ month.slice(0, 4) }} 年 {{ Number(month.slice(5)) }} 月</strong>
          <button type="button" aria-label="下个月" :disabled="!!maxWeek && month >= shiftDate(maxWeek, 6).slice(0, 7)" @click="month = adjacentMonth(1)">›</button>
        </header>
        <div class="dates">
          <span v-for="name in '一二三四五六日'" :key="name" class="weekday">{{ name }}</span>
          <button v-for="day in days" :key="day" type="button" :aria-label="day" :aria-pressed="weekMonday(day) === modelValue"
            :disabled="!allowed(day)" :class="{ outside: !day.startsWith(month), selected: weekMonday(day) === modelValue, active: day === activeDay }"
            @click="choose(day)"><span>{{ Number(day.slice(8)) }}</span></button>
        </div>
        <button v-if="allowed(today)" type="button" class="today" @click="choose(today)">回到本周</button>
      </section>
    </el-popover>
    <button type="button" class="arrow" aria-label="下一周" :disabled="!allowed(shiftDate(modelValue, 7))" @click="choose(shiftDate(modelValue, 7))"><i class="i-lucide-chevron-right" /></button>
  </div>
</template>
<style scoped>
.week-picker { display:inline-flex; align-items:stretch; max-width:100%; border:1px solid var(--el-border-color); border-radius:8px; background:var(--el-bg-color); }
.week-picker button,.calendar button { color:inherit; cursor:pointer; border:0; background:transparent; font:inherit; }
.week-picker button:disabled,.calendar button:disabled { opacity:.32; cursor:default; }
.week-picker button:hover:not(:disabled),.calendar button:hover:not(:disabled) { color:var(--el-color-primary); background:var(--el-color-primary-light-9); }
.week-picker .range,.week-picker .arrow { display:inline-flex; align-items:center; justify-content:center; min-height:36px; border-radius:7px; }
.range { gap:8px; padding:0 8px; font-variant-numeric:tabular-nums; font-weight:550!important; font-size:13px!important; }
.arrow { width:30px; }
.calendar header { display:flex; align-items:center; justify-content:space-between; margin:0 0 8px; }
.calendar header button { width:32px; height:32px; font-size:22px; }
.dates { display:grid; grid-template-columns:repeat(7,1fr); text-align:center; }
.weekday { padding:6px 0; font-size:12px; color:var(--el-text-color-secondary); }
.dates button { padding:3px 0; font-variant-numeric:tabular-nums; }
.dates button span { display:inline-grid; place-items:center; width:30px; height:30px; border-radius:100%; }
.dates .outside { color:var(--el-text-color-placeholder); }
.dates .selected { color:var(--el-color-primary); background:var(--el-color-primary-light-9); }
.dates .active span { color:white; background:var(--el-color-primary); }
.calendar .today { width:100%; padding:9px 0 2px; margin-top:6px; border-top:1px solid var(--el-border-color-lighter); color:var(--el-color-primary); font-size:13px; }
</style>

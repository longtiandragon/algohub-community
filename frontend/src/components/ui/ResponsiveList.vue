<script setup lang="ts" generic="T extends Record<string, any>">
import { computed } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import Skeleton from './Skeleton.vue'
import type { ListColumn } from './types'

/**
 * 同一份数据：电脑上是表格，手机上是卡片列表（不再让表格横向滚动）。
 * 单元格用 cell-<列名> 插槽自定义，手机卡片默认把每一列排成「名称 … 值」并复用同一个插槽；
 * 要换成专门的卡片样式就用 card 插槽。加载中显示同形状的占位，空时显示 empty 插槽或 emptyText。
 */
const props = withDefaults(
  defineProps<{
    items: T[]
    columns: ListColumn[]
    rowKey: string | ((row: T) => string | number)
    loading?: boolean
    emptyText?: string
  }>(),
  { loading: false, emptyText: '暂无数据' }
)

const { isMobile } = useBreakpoint()

const keyOf = (row: T, index: number) =>
  typeof props.rowKey === 'function' ? props.rowKey(row) : (row[props.rowKey] ?? index)

// 组件库表格的 row-key 要么是字段名，要么是返回字符串的函数
const tableRowKey = computed(() => {
  const rowKey = props.rowKey
  return typeof rowKey === 'function' ? (row: T) => String(rowKey(row)) : rowKey
})
</script>

<template>
  <el-table v-if="!isMobile" v-loading="loading" :data="items" :row-key="tableRowKey" class="w-full">
    <el-table-column
      v-for="column in columns"
      :key="column.key"
      :prop="column.key"
      :label="column.label"
      :width="column.width"
      :min-width="column.minWidth"
      :align="column.align"
    >
      <template #default="{ row, $index }">
        <slot :name="`cell-${column.key}`" :row="row as T" :index="$index">{{ row[column.key] ?? '—' }}</slot>
      </template>
    </el-table-column>
    <template #empty>
      <slot name="empty">{{ emptyText }}</slot>
    </template>
  </el-table>
  <div v-else :aria-busy="loading ? 'true' : undefined">
    <div v-if="loading" class="flex flex-col gap-3">
      <div v-for="n in 3" :key="n" class="flex flex-col gap-2.5 rounded-card border border-solid border-line bg-surface p-4">
        <Skeleton class="h-4 w-1/2" />
        <Skeleton class="h-3 w-4/5" />
        <Skeleton class="h-3 w-2/5" />
      </div>
    </div>
    <div v-else-if="!items.length" class="py-6 text-center text-[13px] text-muted">
      <slot name="empty">{{ emptyText }}</slot>
    </div>
    <ul v-else class="m-0 flex list-none flex-col gap-3 p-0">
      <li
        v-for="(row, index) in items"
        :key="keyOf(row, index)"
        class="rounded-card border border-solid border-line bg-surface p-4"
      >
        <slot name="card" :row="row" :index="index">
          <dl class="m-0 flex flex-col gap-2">
            <div v-for="column in columns" :key="column.key" class="flex items-start justify-between gap-3 text-[13px] leading-5">
              <dt class="shrink-0 text-muted">{{ column.label }}</dt>
              <dd class="m-0 min-w-0 text-right text-body">
                <slot :name="`cell-${column.key}`" :row="row" :index="index">{{ row[column.key] ?? '—' }}</slot>
              </dd>
            </div>
          </dl>
        </slot>
      </li>
    </ul>
  </div>
</template>

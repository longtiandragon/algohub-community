/** 共用组件的类型：筛选条（FilterBar / FilterSheet）与响应式列表（ResponsiveList）。 */

export type FilterValue = string | number | null

export interface FilterOption {
  label: string
  value: FilterValue
  /** 手机弹层里「卡片」样式下的一行解释，如「每道题只算第一次通过」 */
  description?: string
  /** 卡片右上的小标签，如「推荐」 */
  badge?: string
}

export interface FilterField {
  key: string
  /** 分组名，如「年级」 */
  label: string
  options: FilterOption[]
  /** 默认值：等于它时筛选片不高亮，「恢复默认」回到它 */
  defaultValue: FilterValue
  /** 手机弹层里怎么选：pills 胶囊（选项少）、cards 带解释的卡片、select 下拉（选项多，如班级） */
  display?: 'pills' | 'cards' | 'select'
  /** 电脑筛选片上的文字，默认就是选项名，例如「指标：首次通过」 */
  chipText?: (option: FilterOption) => string
}

export type FilterValues = Record<string, FilterValue>

/** 当前值对应的选项；找不到时用第一个（通常是「全部」）。 */
export function optionOf(field: FilterField, value: FilterValue): FilterOption {
  return field.options.find((option) => option.value === value) ?? field.options[0]
}

export interface ListColumn {
  key: string
  label: string
  width?: number | string
  minWidth?: number | string
  align?: 'left' | 'center' | 'right'
}

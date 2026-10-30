<script setup lang="ts">
import { computed, ref } from 'vue'
import { notifyError } from '@/api/client'
import PlatformLogo from '@/components/PlatformLogo.vue'
import TopBarActions from '@/components/layout/TopBarActions.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { downloadHistoryCsv } from '@/utils/historyExport'
import { platformName } from '@/utils/platformBrand'
import { campusDate } from '@/utils/statistics'

/**
 * 档案卡：头像、名字、年级班级学校、已绑定账号的胶囊；右边「N 月报告」和「导出」菜单；下面两个数字（已收录通过、最长连续）。
 * 手机上头部不包卡片，两个数字并排成两张小卡片，「N 月报告」放到顶栏。
 */
const props = defineProps<{
  accounts: { platform: string; handle?: string }[]
  /** 已收录通过（有题目明细的平台，同一平台同一题只算一次）；没有明细时为空 */
  solved: number | null
  totalPassed?: number | null
  person?: { displayName: string; avatarUrl: string | null; membership: string; bio?: string | null }
  readOnly?: boolean
  /** 最长连续（按北京时间自然日，只算能确认通过时间的记录） */
  longestStreak: number | null
  loading?: boolean
}>()

const emit = defineEmits<{
  /** 去「报告与导出」 */
  reports: []
}>()

const auth = useAuthStore()
const showHandles = computed(() => !props.readOnly || auth.isAdmin)
// 同学视角只按平台去重；不把账号名放进文字、提示或链接。
const visibleAccounts = computed(() => showHandles.value ? props.accounts
  : [...new Set(props.accounts.map(account => account.platform))].map(platform => ({ platform, handle: undefined })))
const name = computed(() => props.person?.displayName || auth.me?.displayName || auth.me?.username || '')
const membership = computed(() => {
  if (props.person) return props.person.membership
  const m = auth.approvedMembership
  if (!m) return [auth.me?.enrollmentYear ? `${auth.me.enrollmentYear} 级` : '', auth.me?.className, '未加入学校'].filter(Boolean).join(' · ')
  return [m.enrollmentYear ? `${m.enrollmentYear} 级` : '', m.className, m.schoolName].filter(Boolean).join(' · ')
})
const month = Number(campusDate().slice(5, 7))
const exporting = ref(false)

async function exportCsv() {
  if (exporting.value) return
  exporting.value = true
  try {
    await downloadHistoryCsv()
  } catch (e) {
    notifyError(e)
  } finally {
    exporting.value = false
  }
}

function onExport(command: string) {
  if (command === 'csv') void exportCsv()
  else emit('reports')
}

const kpis = computed(() => [
  {
    key: 'total',
    label: '总通过题数',
    value: props.totalPassed,
    unit: '题',
    tip: '已绑定账号按平台分别合计；每个账号取已收录题数与最新平台累计数中的较大值。跨平台同题可能重复。'
  },
  {
    key: 'solved',
    label: '已收录通过',
    value: props.solved,
    unit: '题',
    tip: '同一平台同一题多次通过只算一题；只算有题目明细的平台，只有总数的平台不算在内。'
  },
  {
    key: 'streak',
    label: '最长连续',
    value: props.longestStreak,
    unit: '天',
    tip: '连续每天都有首次通过的最长天数，按北京时间自然日算；只算能确认通过时间的记录。'
  }
])
</script>

<template>
  <section class="profile-card" data-test="profile-card">
    <div class="profile-heading flex flex-wrap items-center gap-3.5 md:gap-[18px]">
      <UserAvatar :src="person ? person.avatarUrl : auth.me?.avatarUrl" :name="name" class="avatar" />
      <div class="min-w-0 flex flex-1 flex-col gap-1.5">
        <h1 class="m-0 truncate text-xl text-ink font-bold leading-7 md:(text-2xl leading-8)">{{ name }}</h1>
        <span class="text-[13px] text-muted md:text-sm">{{ membership }}</span>
        <span v-if="person?.bio" class="text-[13px] text-body">{{ person.bio }}</span>
        <ul v-if="visibleAccounts.length" class="m-0 flex list-none flex-wrap gap-1.5 p-0 md:(mt-0.5 gap-2)" :aria-label="showHandles ? '已绑定的平台账号' : '已绑定的 OJ 平台'">
          <li v-for="account in visibleAccounts" :key="`${account.platform}-${account.handle ?? ''}`" class="handle-chip" :title="showHandles && account.handle ? `${platformName(account.platform)} · ${account.handle}` : platformName(account.platform)">
            <PlatformLogo :platform="account.platform" :size="20" />
            <span v-if="showHandles && account.handle" class="mono handle">{{ account.handle }}</span>
            <span v-else class="platform-label">{{ platformName(account.platform) }}</span>
          </li>
        </ul>
      </div>
      <div v-if="$slots.actions" class="profile-actions"><slot name="actions" /></div>
      <div v-if="!readOnly" class="hidden shrink-0 gap-2.5 md:flex">
        <el-button size="large" @click="emit('reports')">
          <span class="i-lucide-file-text mr-2 text-base" aria-hidden="true" />{{ month }} 月报告
        </el-button>
        <el-dropdown trigger="click" @command="onExport">
          <el-button size="large" :loading="exporting">
            <span v-if="!exporting" class="i-lucide-download mr-2 text-base" aria-hidden="true" />导出<span class="i-lucide-chevron-down ml-1.5 text-sm" aria-hidden="true" />
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="csv">全部做题记录（CSV）</el-dropdown-item>
              <el-dropdown-item command="reports">题单和月度报告…</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <dl class="kpis">
      <div v-for="kpi in kpis" :key="kpi.key" class="kpi">
        <dt class="flex items-center gap-1 text-[12.5px] text-muted md:text-[13px]">
          {{ kpi.label }}<InfoTip v-if="kpi.tip" :title="kpi.label">{{ kpi.tip }}</InfoTip>
        </dt>
        <dd class="m-0 flex items-baseline gap-1">
          <Skeleton v-if="loading" class="h-7 w-16" />
          <template v-else>
            <span class="num text-2xl text-ink font-semibold leading-tight md:text-[30px]">{{ kpi.value ?? '—' }}</span>
            <span v-if="kpi.value != null" class="text-[12.5px] text-muted md:text-[13.5px]">{{ kpi.unit }}</span>
          </template>
        </dd>
      </div>
    </dl>

    <TopBarActions v-if="!readOnly">
      <button type="button" class="topbar-icon" :aria-label="`${month} 月报告`" @click="emit('reports')">
        <span class="i-lucide-file-text text-xl" aria-hidden="true" />
      </button>
    </TopBarActions>
  </section>
</template>

<style scoped>
.profile-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile-actions { margin-left: auto; flex-shrink: 0; }
@media (max-width: 639px) {
  .profile-actions { width: 100%; display: flex; justify-content: flex-end; }
}

.avatar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--brand-btn);
  color: #fff;
  font-size: 23px;
  font-weight: 600;
}

.handle-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.handle {
  display: none;
  font-size: 12px;
  color: var(--text);
}

.platform-label { font-size: 12px; color: var(--text); }

/* 手机：两个数字并排的小卡片 */
.kpis {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
}

@media (min-width: 768px) {
  .profile-card {
    gap: 20px;
    padding: 24px 26px;
    border: 1px solid var(--border);
    border-radius: 16px;
    background: var(--bg-elevated);
  }

  .avatar {
    width: 68px;
    height: 68px;
    font-size: 28px;
  }

  .handle-chip {
    height: 26px;
    padding: 0 10px 0 3px;
    border: 1px solid var(--border);
    border-radius: 999px;
  }

  .handle {
    display: inline;
  }

  /* 电脑：两个数字靠左排，按内容宽，中间一条竖线 */
  .kpis {
    grid-template-columns: repeat(3, max-content);
    gap: 0;
    padding-top: 18px;
    border-top: 1px solid var(--border-soft);
  }

  .kpi {
    gap: 6px;
    min-width: 140px;
    padding: 0 40px;
    border: 0;
    border-left: 1px solid var(--border-soft);
    border-radius: 0;
    background: transparent;
  }

  .kpi:first-child {
    padding-left: 0;
    border-left: 0;
  }
}
</style>

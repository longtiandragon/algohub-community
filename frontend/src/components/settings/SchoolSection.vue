<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError, post } from '@/api/client'
import type { Membership } from '@/api/types'
import { useEnrollmentYears } from '@/composables/useEnrollmentYears'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { useAuthStore } from '@/stores/auth'

defineProps<{
  membership: Membership | null
  form: { realName: string; enrollmentYear: number | null; className: string; studentNo: string }
}>()

const emit = defineEmits<{ joined: [] }>()
const auth = useAuthStore()
const STATUS: Record<string, { label: string; tone: 'success' | 'warning' | 'danger' }> = {
  APPROVED: { label: '已通过', tone: 'success' },
  PENDING: { label: '待审核', tone: 'warning' },
  REJECTED: { label: '已拒绝', tone: 'danger' }
}
const { years } = useEnrollmentYears()
const joinForm = reactive({ inviteCode: '', realName: auth.me?.realName ?? '', enrollmentYear: auth.me?.enrollmentYear ?? null, className: auth.me?.className ?? '', studentNo: auth.me?.studentNo ?? '' })
const joining = ref(false)
async function joinSchool() {
  if (!joinForm.inviteCode.trim()) {
    ElMessage.warning('请填写学校邀请码')
    return
  }
  if (!joinForm.realName.trim() || !joinForm.className.trim() || !joinForm.studentNo.trim() || !joinForm.enrollmentYear) { ElMessage.warning('请填写真实姓名、年级、班级和学号'); return }
  joining.value = true
  try {
    await post('/schools/join', { ...joinForm, realName: joinForm.realName.trim(), inviteCode: joinForm.inviteCode.trim() })
    await auth.refreshMe()
    emit('joined')
    ElMessage.success('已加入学校')
  } catch (e) {
    notifyError(e)
  } finally {
    joining.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-4" data-test="settings-school">
    <SectionCard v-if="membership" title="学校">
      <div class="flex items-center gap-3">
        <span class="i-lucide-school shrink-0 text-xl text-brand" aria-hidden="true" />
        <span class="min-w-0 flex-1 text-sm text-ink font-semibold">{{ membership.schoolName }}</span>
        <StatusBadge variant="label" :tone="STATUS[membership.status]?.tone ?? 'neutral'">{{ STATUS[membership.status]?.label ?? membership.status }}</StatusBadge>
      </div>
      <p class="mb-0 mt-2 text-[12.5px] text-muted">学校由你自己选择，不代表已完成学籍认证。要换学校请联系管理员。</p>
      <RouterLink v-if="membership.status === 'PENDING'" to="/select-school" class="mt-2 inline-flex text-[13px] text-brand-600">拿到邀请码？返回选校页填写</RouterLink>
    </SectionCard>
    <SectionCard title="学籍资料" description="首页、校内排行、校内动态的「同届」都按这里的入学年级算。改完点底部的「保存」。">
      <el-form label-position="top" class="grid grid-cols-1 gap-x-3 md:grid-cols-2" @submit.prevent>
        <el-form-item v-if="membership && membership.status !== 'REJECTED'" label="真实姓名（校内展示）" required><el-input v-model="form.realName" maxlength="64" autocomplete="name" data-test="school-real-name" /></el-form-item>
        <el-form-item label="入学年级">
          <el-select v-model="form.enrollmentYear" placeholder="选入学年份" class="w-full" data-test="school-year">
            <el-option v-for="y in years" :key="y" :label="`${y} 级`" :value="y" />
          </el-select>
        </el-form-item>
        <el-form-item label="班级（必填）" required>
          <el-input v-model="form.className" maxlength="64" placeholder="如24计应2班" data-test="school-class" />
        </el-form-item>
        <el-form-item label="学号（必填，默认不公开）" class="md:col-span-2 !mb-0">
          <el-input v-model="form.studentNo" maxlength="32" placeholder="只用于成员管理" />
        </el-form-item>
      </el-form>
    </SectionCard>
  </div>

  <SectionCard v-if="!membership" title="加入学校" description="用学校邀请码加入，不用等审核。学号只用于成员管理，默认不公开。" data-test="settings-school">
    <RouterLink to="/select-school" class="text-brand-600">选择学校并申请加入（邀请码选填）</RouterLink>
    <el-form label-position="top" @submit.prevent="joinSchool">
      <el-form-item label="真实姓名（校内展示）" required><el-input v-model="joinForm.realName" maxlength="64" autocomplete="name" /></el-form-item>
      <el-form-item label="学校邀请码">
        <el-input v-model="joinForm.inviteCode" placeholder="例如 SZPU-2026" />
      </el-form-item>
      <div class="grid grid-cols-1 gap-x-3 md:grid-cols-2">
        <el-form-item label="入学年级">
          <el-select v-model="joinForm.enrollmentYear" placeholder="选择入学年级" class="w-full"><el-option v-for="y in years" :key="y" :label="`${y} 级`" :value="y" /></el-select>
        </el-form-item>
        <el-form-item label="班级（必填）" required>
          <el-input v-model="joinForm.className" placeholder="如24计应2班" />
        </el-form-item>
      </div>
      <el-form-item label="学号（必填）">
        <el-input v-model="joinForm.studentNo" />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="joining">确认加入学校</el-button>
    </el-form>
  </SectionCard>
</template>

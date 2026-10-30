import { getCurrentScope, onScopeDispose, reactive, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { registerOnboardingDraftCleanup } from '@/utils/onboardingStorage'
export function useOnboardingDraft() {
  const auth = useAuthStore()
  const userId = auth.me!.id
  const key = `algohub-onboarding-${auth.me!.id}`
  const pending = auth.me?.memberships.find(m => m.status === 'PENDING')
  const temporaryName = !auth.me?.onboardingCompleted && /^新同学(?:_[0-9A-F]{8})?$/.test(auth.me?.displayName ?? '')
  const initial = { schoolChosen: false, schoolId: pending?.schoolId ?? null as number | null, inviteCode: '',
    displayName: temporaryName ? '' : auth.me?.displayName ?? '',
    enrollmentYear: auth.me?.enrollmentYear ?? pending?.enrollmentYear ?? null as number | null,
    className: auth.me?.className ?? pending?.className ?? '', studentNo: auth.me?.studentNo ?? '' }
  let saved = {}
  try { saved = JSON.parse(sessionStorage.getItem(key) ?? '{}') } catch { /* Ignore invalid draft. */ }
  const draft = reactive({ ...initial, ...saved })
  let cleared = false
  watch(draft, value => {
    if (cleared || auth.me?.id !== userId || auth.pendingLogout) return
    try { sessionStorage.setItem(key, JSON.stringify(value)) } catch { /* Private browsing. */ }
  }, { deep: true, flush: 'sync' })
  function clear() {
    cleared = true
    for (const key of Object.keys(draft)) (draft as Record<string, unknown>)[key] = undefined
    Object.assign(draft, { schoolChosen: false, schoolId: null, inviteCode: '', displayName: '', enrollmentYear: null, className: '', studentNo: '' })
    try { sessionStorage.removeItem(key) } catch { /* Private browsing. */ }
  }
  const unregister = registerOnboardingDraftCleanup(clear)
  if (getCurrentScope()) onScopeDispose(unregister)
  return { draft, clear }
}

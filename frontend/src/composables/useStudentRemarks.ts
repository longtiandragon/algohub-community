import { computed, provide, ref, watch, type InjectionKey } from 'vue'
import { get, notifyError, put } from '@/api/client'
import { directory, type DirectoryStudent } from '@/api/studentDirectory'
import { useAuthStore } from '@/stores/auth'

type StudentCard = Pick<DirectoryStudent, 'userId' | 'name'> & Partial<DirectoryStudent>

function createRemarks(school: () => number | undefined, canManageSchool: () => boolean) {
  const auth = useAuthStore()
  const schoolId = computed(school)
  const manageable = computed(() => !!schoolId.value && (canManageSchool() ||
    !!auth.me?.managedSchools?.some(s => s.id === schoolId.value) ||
    !!auth.me?.managedCohorts?.some(s => s.schoolId === schoolId.value)))
  const names = ref<Record<number, string>>({})
  const card = ref<StudentCard>()
  const draft = ref('')
  const saving = ref(false)
  const opening = ref(false)
  let scopeRevision = 0
  let cardRequest = 0

  watch([schoolId, () => auth.me?.id, manageable], async () => {
    const revision = ++scopeRevision
    ++cardRequest
    card.value = undefined
    names.value = {}
    saving.value = false
    opening.value = false
    if (!manageable.value) return
    try {
      const values = await get<Record<number, string>>('/schools/' + schoolId.value + '/students/remarks')
      if (scopeRevision === revision) names.value = { ...values, ...names.value }
    } catch (error) {
      if (scopeRevision === revision) notifyError(error)
    }
  }, { immediate: true, flush: 'sync' })

  async function open(userId: number, name: string) {
    if (!manageable.value || !schoolId.value || saving.value) return
    const request = ++cardRequest
    card.value = { userId, name }
    draft.value = names.value[userId] ?? ''
    opening.value = true
    try {
      const { items } = await directory(schoolId.value, { ids: String(userId) })
      if (request === cardRequest) {
        card.value = items[0]
        draft.value = items[0]?.remark ?? ''
      }
    } catch (error) {
      if (request === cardRequest) {
        card.value = undefined
        notifyError(error)
      }
    } finally {
      if (request === cardRequest) opening.value = false
    }
  }

  async function save() {
    if (!manageable.value || !card.value || opening.value || saving.value) return
    const { userId } = card.value
    const request = cardRequest
    const remark = draft.value.trim()
    saving.value = true
    try {
      await put('/schools/' + schoolId.value + '/students/' + userId + '/remark', { remark })
      if (request === cardRequest) {
        names.value = { ...names.value, [userId]: remark }
        card.value = undefined
      }
    } catch (error) {
      if (request === cardRequest) notifyError(error)
    } finally {
      if (request === cardRequest) saving.value = false
    }
  }

  function close() {
    if (saving.value) return
    ++cardRequest
    card.value = undefined
    opening.value = false
  }
  return { manageable, names, card, draft, saving, opening, open, save, close }
}

export const remarksKey: InjectionKey<ReturnType<typeof createRemarks>> = Symbol('studentRemarks')

export function provideStudentRemarks(school: () => number | undefined, canManageSchool = () => false) {
  const remarks = createRemarks(school, canManageSchool)
  provide(remarksKey, remarks)
  return remarks
}

import { onMounted, ref } from 'vue'
import { get, notifyError } from '@/api/client'

export function useEnrollmentYears() {
  const years = ref<number[]>([])
  const error = ref(false)
  async function reload() {
    try { years.value = await get<number[]>('/auth/enrollment-years'); error.value = false }
    catch (e) { error.value = true; notifyError(e) }
  }
  onMounted(reload)
  return { years, error, reload }
}

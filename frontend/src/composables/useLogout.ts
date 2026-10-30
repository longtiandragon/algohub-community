import { adminLoginPath } from '@/utils/adminHost'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { notifyError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

/** 退出登录并回到登录页；侧栏个人菜单和手机顶栏共用。本地登录状态总会清掉，服务端没退成功也提示一句。 */
export function useLogout() {
  const auth = useAuthStore()
  const router = useRouter()
  return async function logout() {
    const loginPath = auth.isAdmin ? adminLoginPath : '/login'
    try {
      await auth.logout()
      ElMessage.success('已退出登录')
    } catch (e) {
      notifyError(e)
    } finally {
      await router.push(loginPath)
    }
  }
}

import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post } from './client'
import { startGithubAuthorize } from './githubAuthorize'

export async function confirmIdentity(purpose: string): Promise<string> {
  const key = `security-proof:${purpose}`
  const pending = sessionStorage.getItem(key)
  sessionStorage.removeItem(key)
  if (pending) {
    try {
      const proof = JSON.parse(pending)
      if (proof.expires > Date.now()) return proof.token
    } catch { /* A fresh verification is required. */ }
  }
  const methods = await get<{ email: boolean; password: boolean; github: boolean }>('/auth/security-action')
  if (methods.email) {
    await ElMessageBox.confirm('为保护账号，需要向当前已验证的登录邮箱发送一次验证码。', '确认本人操作', { confirmButtonText: '发送验证码' })
    await post('/auth/security-action/code', { purpose })
    for (let attempt = 0; attempt < 5; attempt++) {
      const { value: code } = await ElMessageBox.prompt('请输入现有登录邮箱收到的 6 位验证码，5 分钟内有效。', '确认本人操作', { inputPattern: /^\d{6}$/, inputErrorMessage: '请输入 6 位验证码', inputType: 'text' })
      try {
        return (await post<{ actionToken: string }>('/auth/security-action/verify', { purpose, code })).actionToken
      } catch (e) {
        if (attempt === 4) throw e
        ElMessage.warning(e instanceof Error ? e.message : '验证码不正确，请重试')
      }
    }
    throw new Error('验证次数已用完，请稍后重新获取验证码')
  }
  if (methods.password) {
    const { value: password } = await ElMessageBox.prompt('请输入当前密码以确认本人操作。', '验证身份', { inputType: 'password' })
    return (await post<{ actionToken: string }>('/auth/security-action/verify', { purpose, password })).actionToken
  }
  if (methods.github) {
    await ElMessageBox.confirm('请重新授权当前账号已绑定的 GitHub。完成后请再次点击原操作。', '验证身份', { confirmButtonText: '前往 GitHub' })
    sessionStorage.setItem('security-return', location.pathname + location.search)
    await startGithubAuthorize(purpose)
    throw 'cancel'
  }
  throw new Error('没有可用于确认身份的登录方式，请联系管理员')
}

/**
 * 浏览器标识很长，列表里只写「浏览器 · 系统」，完整内容放在悬停提示里。
 * 命令行与脚本（PowerShell、curl、Python 等）也照实写出来，便于认出不是自己浏览器的登录。
 */
export function deviceName(ua: string | null | undefined): string {
  if (!ua) return '未知设备'
  const browser =
    /PowerShell\//.test(ua) ? 'PowerShell'
      : /\bcurl\//.test(ua) ? 'curl'
        : /python-requests|Python-urllib|aiohttp/i.test(ua) ? 'Python 脚本'
          : /HeadlessChrome/.test(ua) ? 'Chrome（无界面）'
            : /Edg\//.test(ua) ? 'Edge'
              : /OPR\//.test(ua) ? 'Opera'
                : /Firefox\//.test(ua) ? 'Firefox'
                  : /Chrome\//.test(ua) ? 'Chrome'
                    : /Version\/[\d.]+.*Safari\//.test(ua) ? 'Safari'
                      : /Java/.test(ua) ? 'Java 程序'
                        : null
  const os =
    /Windows/.test(ua) ? 'Windows'
      : /iPhone|iPad/.test(ua) ? 'iOS'
        : /Mac OS X|Macintosh/.test(ua) ? 'macOS'
          : /Android/.test(ua) ? 'Android'
            : /Linux/.test(ua) ? 'Linux'
              : null
  if (!browser && !os) return ua.length > 40 ? `${ua.slice(0, 40)}…` : ua
  return [browser ?? '其他客户端', os].filter(Boolean).join(' · ')
}

/** 认证记录的事件名 */
export const AUTH_EVENT_LABELS: Record<string, string> = {
  REGISTER: '注册',
  LOGIN_SUCCESS: '登录成功',
  EMAIL_LOGIN_SUCCESS: '邮箱验证码登录',
  ACCOUNT_DISABLED: '账号被管理员停用',
  ACCOUNT_ENABLED: '账号被管理员重新启用',
  LOGIN_FAILED: '登录失败',
  LOGIN_LOCKED: '登录被锁定',
  LOGIN_GITHUB: 'GitHub 登录',
  LOGOUT: '退出登录',
  LOGOUT_ALL: '退出所有设备',
  TOKEN_REFRESH: '刷新登录状态',
  PASSWORD_CHANGED: '修改密码',
  PROFILE_UPDATED: '修改资料',
  PRIVACY_UPDATED: '修改隐私设置',
  OAUTH_LINKED: '绑定第三方账号',
  OAUTH_UNLINKED: '解绑第三方账号',
  ACCOUNT_DELETED: '注销账号',
  PASSWORD_RESET: '通过邮箱重置密码',
  OWNERSHIP_APPEAL_OPENED: '提交平台账号归属申诉',
  OWNERSHIP_APPEAL_APPROVED: '归属申诉通过',
  OWNERSHIP_APPEAL_REJECTED: '归属申诉被驳回',
  OWNERSHIP_REVOKED: '平台账号归属经申诉转给他人'
}

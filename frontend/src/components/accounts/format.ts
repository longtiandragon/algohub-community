/** 平台账号页的时间：北京时间，精确到秒（到期时间、提交时间、最近成功） */
export function formatTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '—'
}

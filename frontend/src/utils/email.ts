/** 邮箱打码后显示：chenyiming@example.com → chen***@example.com（短的只留第一个字） */
export function maskEmail(address: string) {
  const at = address.lastIndexOf('@')
  if (at <= 0) return address
  const local = address.slice(0, at)
  const keep = local.length <= 3 ? 1 : Math.min(4, local.length - 2)
  return `${local.slice(0, keep)}***${address.slice(at)}`
}

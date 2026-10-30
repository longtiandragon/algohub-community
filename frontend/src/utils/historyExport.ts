import http from '@/api/client'

/** 文件开头的 BOM：Excel 靠它认出 UTF-8，中文不乱码 */
const BOM = String.fromCharCode(0xfeff)

/**
 * 把做题记录导出成 CSV 下载（最多 10000 条，时间为 UTC）：做题记录里的「导出 CSV」按当前筛选导出，
 * 档案卡的「导出」菜单导出全部。带着登录令牌请求，拿到内容后在浏览器里生成文件；后端已带 BOM 时不重复加。
 */
export async function downloadHistoryCsv(params: Record<string, unknown> = {}) {
  const response = await http.get('/history/export', { params: { accepted: true, ...params } })
  const text = String(response.data)
  const body = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
  const url = URL.createObjectURL(new Blob([BOM, body], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'algohub-history.csv'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

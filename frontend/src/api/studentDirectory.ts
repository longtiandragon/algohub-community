import { get } from '@/api/client'

export interface DirectoryStudent {
  userId: number
  name: string
  username: string
  remark: string | null
  year: number | null
  className: string | null
  studentNo: string | null
}
interface DirectoryGroup { year: number | null; className: string | null; count: number }
interface DirectoryPage { items: DirectoryStudent[]; total: number; groups: DirectoryGroup[] }
interface DirectoryQuery {
  page?: number; size?: number; q?: string; year?: number; className?: string; ids?: string; restriction?: 'LEADER'
}
export function directory(school: number, query: DirectoryQuery) {
  return get<DirectoryPage>(`/schools/${school}/students/directory`, { ...query })
}

export async function studentsById(school: number, ids: number[]) {
  const students: DirectoryStudent[] = []
  const unique = [...new Set(ids)]
  for (let i = 0; i < unique.length; i += 100) {
    const page = await directory(school, { size: 100, ids: unique.slice(i, i + 100).join(',') })
    students.push(...page.items)
  }
  return students
}

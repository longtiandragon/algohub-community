import { del, get, post, put } from '@/api/client'
import type { ContestDetail, ContestPreview, Targets } from './weeklyTraining'

export type Audience = 'ALL' | 'SELECTED'
export type ReleaseStatus = 'DRAFT' | 'SCHEDULED' | 'PUBLISHED'
export interface Assignment {
  url: string
  targets: Targets
  weekStart: string
  audience: Audience
  studentIds: number[]
  status: ReleaseStatus
  publishAt: string | null
}
export const identify = (school: number, url: string) => get<ContestPreview & {
  audience: Audience; lastStudentIds: number[]; history: { weekStart: string; status: ReleaseStatus }[]
}>(`/weekly/${school}/preview`, { url })
export const assignmentDetail = (school: number, id: number) =>
  get<ContestDetail & Pick<Assignment, 'audience' | 'status' | 'studentIds' | 'publishAt'>>(`/weekly/${school}/${id}`)
export const saveRules = (school: number, body: Pick<Assignment, 'url' | 'targets' | 'audience'>) =>
  post(`/weekly/${school}/defaults`, body)
export const saveAssignment = (school: number, id: number | undefined, body: Assignment) =>
  id ? put(`/weekly/${school}/${id}`, body) : post(`/weekly/${school}`, body)
export const cancelAssignment = (school: number, id: number) => del(`/weekly/${school}/${id}`)


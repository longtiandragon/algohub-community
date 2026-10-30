export interface StudyGoal { personal: number | null; required: number; target: number; solved: number; source: 'ADMIN' | 'GRADE' | 'NONE' }
export interface StudyGoals {
  weekly: StudyGoal; monthly: StudyGoal; enrollmentYear: number | null
  assignedWeekly: number | null; assignedMonthly: number | null
  emailEnabled: boolean; email: string | null; weekStart: string; monthStart: string
}
export interface StudyNotice { category: 'SYSTEM' | 'FRIEND'; senderId?: number | null; senderName?: string | null; id: number; title: string; body: string; createdAt: string; readAt: string | null; emailStatus: string; emailNote: string | null }
export interface StudyInbox { items: StudyNotice[]; hasMore: boolean; unread: number; unreadByCategory: {SYSTEM:number;FRIEND:number} }
export const mailLabel: Record<string, string> = { PENDING: '邮件待发送', SENDING: '邮件发送中', SENT: '邮件已发出', FAILED: '邮件发送失败', SKIPPED: '未发送邮件' }
export function noticeTime(value: string) { return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) }

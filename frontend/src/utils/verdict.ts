/**
 * 评测结果：各平台原始写法不同（Codeforces「WRONG_ANSWER」、AtCoder「WA」、HDU「Wrong Answer」、牛客「答案错误」），
 * 统一成中文和颜色。通过绿、答案错误和运行错误红、超时超内存琥珀、编译错误等灰、评测中蓝；
 * 认不出的原样显示，按「是否通过」上色，不猜。
 */
export type VerdictTone = 'success' | 'danger' | 'warning' | 'neutral' | 'info'

export interface Verdict {
  label: string
  tone: VerdictTone
}

const ACCEPTED: Verdict = { label: '通过', tone: 'success' }
const WRONG: Verdict = { label: '答案错误', tone: 'danger' }
const TIME: Verdict = { label: '超出时间限制', tone: 'warning' }
const MEMORY: Verdict = { label: '超出内存限制', tone: 'warning' }
const OUTPUT: Verdict = { label: '输出超限', tone: 'warning' }
const RUNTIME: Verdict = { label: '运行错误', tone: 'danger' }
const COMPILE: Verdict = { label: '编译错误', tone: 'neutral' }
const PRESENTATION: Verdict = { label: '格式错误', tone: 'danger' }
const JUDGING: Verdict = { label: '评测中', tone: 'info' }
const JUDGE_FAILED: Verdict = { label: '评测失败', tone: 'neutral' }

/** 键是去掉空格、横线后的大写写法（HDU「Runtime Error (ACCESS_VIOLATION)」按前缀认） */
const KNOWN: Record<string, Verdict> = {
  OK: ACCEPTED,
  AC: ACCEPTED,
  ACCEPTED,
  WRONG_ANSWER: WRONG,
  WA: WRONG,
  TIME_LIMIT_EXCEEDED: TIME,
  TLE: TIME,
  MEMORY_LIMIT_EXCEEDED: MEMORY,
  MLE: MEMORY,
  OUTPUT_LIMIT_EXCEEDED: OUTPUT,
  OLE: OUTPUT,
  IDLENESS_LIMIT_EXCEEDED: { label: '等待输入超时', tone: 'warning' },
  RUNTIME_ERROR: RUNTIME,
  RE: RUNTIME,
  COMPILATION_ERROR: COMPILE,
  COMPILE_ERROR: COMPILE,
  CE: COMPILE,
  PRESENTATION_ERROR: PRESENTATION,
  PE: PRESENTATION,
  PARTIAL: { label: '部分通过', tone: 'warning' },
  CHALLENGED: { label: '被 Hack', tone: 'danger' },
  SKIPPED: { label: '已跳过', tone: 'neutral' },
  REJECTED: { label: '被拒绝', tone: 'neutral' },
  SECURITY_VIOLATED: { label: '违规操作', tone: 'danger' },
  FAILED: JUDGE_FAILED,
  CRASHED: JUDGE_FAILED,
  INPUT_PREPARATION_CRASHED: JUDGE_FAILED,
  IE: JUDGE_FAILED,
  TESTING: JUDGING,
  WJ: JUDGING,
  WR: JUDGING,
  QUEUING: JUDGING,
  COMPILING: JUDGING,
  RUNNING: JUDGING,
  答案正确: ACCEPTED,
  答案错误: WRONG,
  // 蓝桥云课只说通过没有，不给具体原因
  未通过: { label: '未通过', tone: 'danger' },
  运行超时: TIME,
  内存超限: MEMORY,
  输出超限: OUTPUT,
  段错误: { label: '段错误', tone: 'danger' },
  浮点错误: { label: '浮点错误', tone: 'danger' },
  运行错误: RUNTIME,
  返回非零: { label: '返回非零', tone: 'danger' },
  编译错误: COMPILE,
  格式错误: PRESENTATION,
  等待评测: JUDGING,
  正在评测: JUDGING
}

export function verdictOf(verdict: string | null | undefined, accepted?: boolean | null): Verdict {
  const raw = (verdict ?? '').trim()
  if (!raw || raw === 'UNKNOWN') return accepted ? ACCEPTED : { label: '结果未知', tone: 'neutral' }
  const key = raw.toUpperCase().replace(/[\s-]+/g, '_')
  const known = KNOWN[key] ?? KNOWN[raw]
  if (known) return known
  if (key.startsWith('RUNTIME_ERROR')) return RUNTIME
  return { label: raw, tone: accepted ? 'success' : 'neutral' }
}

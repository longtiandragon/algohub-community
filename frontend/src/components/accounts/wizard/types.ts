/** 码蹄集按昵称查到的同名账号：数字 ID、昵称、学校、通过题数、排名 */
export interface MatijiCandidate {
  userId: string
  nickname: string
  school: string
  passNum: number
  rankOrder: number
  observedAt?: string
}

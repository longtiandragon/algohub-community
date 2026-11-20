export interface PlatformIdentity {
  platform: string
  handle?: string | null
  platformUid?: string | null
}

/** Some platforms put a stable numeric ID in the URL even when the account displays a nickname. */
export function platformProfileUrl(account: PlatformIdentity): string | undefined {
  const handle = account.handle?.trim()
  if (!handle || handle === '.' || handle === '..') return
  const name = encodeURIComponent(handle)
  const uid = account.platformUid?.trim() || handle
  switch (account.platform) {
    case 'CODEFORCES': return `https://codeforces.com/profile/${name}`
    case 'ATCODER': return `https://atcoder.jp/users/${name}`
    case 'QOJ': return `https://qoj.ac/user/profile/${name}`
    case 'LEETCODE_CN': return `https://leetcode.cn/u/${name}/`
    case 'HDU': return `https://acm.hdu.edu.cn/userstatus.php?user=${name}`
    case 'LUOGU': return /^\d+$/.test(uid) ? `https://www.luogu.com.cn/user/${uid}` : undefined
    case 'NOWCODER': return /^\d+$/.test(uid) ? `https://ac.nowcoder.com/acm/contest/profile/${uid}` : undefined
    case 'MATIJI': return /^\d+$/.test(uid) ? `https://www.matiji.net/exam/other-homepage/${uid}` : undefined
    case 'LANQIAO': return /^\d+$/.test(uid) ? `https://www.lanqiao.cn/users/${uid}/` : undefined
  }
}

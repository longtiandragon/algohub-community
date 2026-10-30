# 平台响应样本（已脱敏）

2026-09-23 用与适配器相同的地址和请求头匿名抓取，裁剪到几条记录后脱敏存放。由
`RealResponseSamplesTest`（适配器解析）与 `PlatformHttpClientTest`（状态码、重定向）离线回放，不连外网。

**来源**一栏：

- **真实**：源站原样返回，只做了裁剪与脱敏。
- **改写**：在真实样本上只改一个字段，用来模拟源站改版或零题账号。
- **构造**：拦截页无法在不触发防护的前提下抓到，按公开的 Cloudflare 拦截页结构构造。

脱敏：账号名、昵称、UID 换成 `sample_user` / `100001` / `100000001` / `10001` 这类占位值；
签名、简介、头像、粉丝数清空或换成默认值；页面里的 CSRF `_token` 与 Cloudflare 参数换成 `REDACTED`。
样本里没有 Cookie 或登录令牌（抓取时本来就没带登录态）。

| 文件 | 场景 | 来源 | 期望 |
|---|---|---|---|
| `codeforces/status-normal.json` | 正常 | 真实 | 3 条提交，编号、时间、结果原样保留 |
| `codeforces/status-empty.json` | 零题（翻到头的空页，与没提交过的账号同一结构） | 真实 | 0 条，历史已完整 |
| `codeforces/status-notfound.json` | 查无此人（HTTP 400） | 真实 | `NOT_FOUND` |
| `codeforces/status-field-changed.json` | 字段变化：一条提交缺 `creationTimeSeconds` | 改写 | `PARSE_ERROR`，整批不入库 |
| `atcoder/submissions-normal.json` | 正常 | 真实 | 4 条提交 |
| `atcoder/submissions-empty.json` | 零题 | 真实 | 0 条，历史已完整 |
| `atcoder/submissions-field-changed.json` | 字段变化：`epoch_second` 改名 | 改写 | `PARSE_ERROR` |
| `leetcode/progress-normal.json` | 正常 | 真实 | 42 题（12 / 24 / 6） |
| `leetcode/progress-zero.json` | 真实零题：三个难度都在、数量为 0 | 改写 | 0 题 |
| `leetcode/progress-notfound.json` | 查无此人：返回空列表 | 真实 | `NOT_FOUND`，不按 0 题处理 |
| `leetcode/progress-field-changed.json` | 字段变化：`count` 改名 | 改写 | `PARSE_ERROR` |
| `luogu/practice-normal.json` | 正常（练习页公开数据） | 真实 | 5 题 |
| `luogu/practice-zero.json` | 零题 | 改写 | 0 题 |
| `luogu/practice-count-mismatch.json` | 列表与账号总数对不上 | 改写 | `PARSE_ERROR`，不写偏小的数 |
| `luogu/records-anonymous-401.json` | 登录失效：未登录读评测记录（HTTP 401） | 真实 | `CREDENTIAL_INVALID` |
| `nowcoder/practice-normal.html` | 正常，第 1 页，还有下一页 | 真实 | 3 条，游标推进到第 2 页 |
| `nowcoder/practice-beyond-last-page.html` | 重复分页：请求第 999 页，源站返回最后一页的内容 | 真实 | `PARSE_ERROR`（分页未推进），不重复计数 |
| `nowcoder/practice-field-changed.html` | 字段变化：一行少一列 | 改写 | `PARSE_ERROR` |
| `nowcoder/practice-zero.html` | 零题 | 改写 | 0 条、0 题 |
| `nowcoder/profile-normal.html` | 归属验证：公开主页头像旁的个人简介（`.coder-info-main > .coder-brief`） | 真实（裁剪、脱敏） | 昵称与简介原文 |
| `nowcoder/profile-empty-brief.html` | 归属验证：没写简介 | 改写 | 简介为空串（提示还没有验证串） |
| `nowcoder/profile-field-changed.html` | 归属验证：简介区改版 | 改写 | 不给简介（核验提示暂时读不到、不扣次数） |
| `nowcoder/profile-notfound.html` | 查无此人：源站返回「牛客网-用户不存在」页 | 真实（裁剪） | `NOT_FOUND`，不把这几个字当昵称 |
| `nowcoder/rating-normal.json` | 比赛与 Rating：正常 | 真实（截取 3 场，无账号标识） | 名次、赛前 = 赛后 − 变化值、比赛时间 |
| `nowcoder/rating-empty.json` | 比赛与 Rating：没参加过比赛（不存在的 UID 也这样返回） | 真实 | 0 场 |
| `nowcoder/rating-field-changed.json` | 比赛与 Rating：缺比赛时间 | 改写 | `PARSE_ERROR` |
| `qoj/login-page.html` | 登录失效：未登录访问被 302 到 `//qoj.ac/login` 后的登录页 | 真实 | `CREDENTIAL_INVALID`；页面自带 Cloudflare 检测脚本，但不算防护页 |
| `matiji/user-normal.json` | 正常 | 真实 | 1678 题 |
| `matiji/user-notfound.json` | 查无此人 | 真实 | `NOT_FOUND` |
| `matiji/user-field-changed.json` | 字段变化：`passNum` 改名 | 改写 | `PARSE_ERROR` |
| `codeforces/rating-normal.json` | 比赛与 Rating：正常 | 真实 | 3 场，名次、前后 Rating、时间 |
| `codeforces/rating-empty.json` | 比赛与 Rating：没参加过比赛 | 改写 | 0 场（真实的零场） |
| `codeforces/rating-field-changed.json` | 字段变化：一场缺 `newRating` | 改写 | `PARSE_ERROR` |
| `atcoder/history-normal.json` | 比赛历史：正常，含一场不计分 | 真实 | 3 场；不计分那场 `rated=false`、表现分为空；表现分可为负 |
| `atcoder/history-empty.json` | 比赛历史：没参加过 | 改写 | 0 场 |
| `atcoder/history-field-changed.json` | 字段变化：`EndTime` 改名 | 改写 | `PARSE_ERROR` |
| `leetcode/recent-ac-normal.json` | 力扣最近通过的题（noj-go 接口） | 真实 | 3 条，题目标识用 titleSlug；历史不算完整 |
| `leetcode/recent-ac-empty.json` | 没有最近通过记录 / 查无此人（两者返回一样） | 真实 | 0 条，游标不动 |
| `leetcode/recent-ac-field-changed.json` | 字段变化：`submitTime` 改名 | 改写 | `PARSE_ERROR` |
| `common/cloudflare-challenge.html` | 防护页（Cloudflare 拦截页） | 构造 | 各平台都按 `RATE_LIMITED` 退避重试，不算零题、不算登录失效 |

抓这些样本时的实测发现：

- QOJ 对未登录请求返回 302，`Location` 是不带协议的 `//qoj.ac/login`，按当前地址补成 `https://qoj.ac/login`，同站跟随。
- 牛客超出范围的页码不报错，而是返回最后一页的内容；分页核对因此是必需的。
- 力扣对不存在的用户返回空的 `numAcceptedQuestions` 列表，不是报错。
- 正常的 Cloudflare 站点页面也会嵌入 `/cdn-cgi/challenge-platform/scripts/jsd/` 检测脚本，所以不能只凭 `challenge-platform` 字样判断拦截页。

重新抓取时，保持同样的裁剪与脱敏方式，并提交前检查文件里没有真实账号名、UID、Cookie 或令牌。

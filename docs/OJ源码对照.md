# oj_helper 与 OJ Insight 源码核对（2026-09-14）

## 源码依据

- https://github.com/2754LM/oj_helper/blob/main/lib/services/solved_num_services.dart
- https://github.com/Whalica/OJ_Insight/blob/main/src-tauri/src/sync/luogu.rs
- https://github.com/Whalica/OJ_Insight/blob/main/src-tauri/src/sync/qoj.rs
- https://github.com/Whalica/OJ_Insight/blob/main/src-tauri/src/sync/nowcoder.rs

均为本轮从仓库 main 读取的实现；源码存在不等于接口现在能成功访问。

| 平台 | oj_helper | OJ Insight |
|---|---|---|
| 洛谷 | 用户搜索取得 UID，读取用户主页 passedProblemCount | 匿名主页 dailyCounts 为每日活动；practice passed 数组长度为题数、difficulty 为分布；submissions 为空，activity_only=true |
| QOJ | 遍历 ranklist?type=ac_num&page=，按用户名读取 Accepted 数字，不用 Cookie | UOJSESSID 请求 submissions?submitter=...&min_score=100&max_score=100，分页解析 AC 提交时间 |
| 码蹄集 | ojRankByType.do 按昵称在分页排行榜中查 passNum | 本轮读取的 sync 模块未发现码蹄集适配器 |
| 力扣 | GraphQL numAcceptedQuestions 汇总难度计数 | 本轮未深入核对该文件 |
| AtCoder | AtCoder Problems ac_rank 的 count | 本轮未深入核对该文件 |
| CF/HDU/牛客 | ojhunt.com 第三方聚合接口 data.solved | 牛客读取公开练习 AC 页，Tracker 日历与登录完成日另行匹配 |
| 蓝桥 | 按数字 UID 在 problem-rank 排行榜前 10 页查 problem_count | 本轮未发现对应 sync 模块 |

## 必须纠正的当前判断

洛谷题数和近期每日活动不需要先实现浏览器扩展。本项目已采用与 Insight 同类的公开请求，并已保存 645 道通过题目。开发数据库 daily_stat 真实读取结果：2026-09-12 PLATFORM_ACTIVITY=3，2026-09-13 PLATFORM_ACTIVITY=2。动态页只查询 user_solved_problem.earliest_known_ac_at，导致这些真实每日活动未展示。不是所有近期数据未采集，而是逐题 AC 明细缺失，同时已有每日活动没有接入动态展示。

下一步应把每日平台活动独立展示，保留来源日期和 PLATFORM_ACTIVITY 口径，不能把 3/2 宣称为去重通过题数，也不能凭活动值拼造题目链接。题单新增差异可以另列为新发现通过题。扩展不应作为默认前置条件。

## QOJ 实测限制

本轮匿名 ranklist?type=ac_num&page=1 返回 403；此前已存登录态请求主页也返回 403。应实现/验证排行榜总量和登录提交两条路径，而非声称主页 Cookie 是唯一方式。排行榜可被所有账号共享缓存，不能每人每次无界遍历。oj_helper 的无限分页与 POJ 请求失败返回 0 不应照搬。

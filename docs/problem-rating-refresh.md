# 题目评级更新

提交记录通过 `problem_id` 查询题目表当前的评级；比赛期间未评级的提交，在题目评级同步后会显示最新已知分数，不需要重新提交。后续调分也会更新。

## 数据源

- Codeforces：[官方题库 API](https://codeforces.com/apiHelp/methods#problemset.problems) 的 `rating`，不是比赛题目满分 `points`。
- AtCoder：[AtCoder Problems](https://github.com/kenkoooo/AtCoderProblems/blob/master/doc/api.md) 的 `problem-models.json`，是第三方估计难度，保留来源说明；低于 400 的显示沿用 AtCoder Problems 的换算规则。
- 牛客：[竞赛题库公开接口](https://ac.nowcoder.com/acm/problem/list/json?page=1&pageSize=50) 的 `difficulty`，使用 `problemId` 对应历史提交，保留原始数值，不作跨平台换算。`-1`、缺失值表示未评级。题库每页最多 50 题，共享牛客请求限速；约 1.48 万题的完整刷新需要约 15 分钟，还可能受其他同步请求影响。
- 牛客 Tracker 继续按题号与标题匹配；已有题目也可补分、调分。已从竞赛题库同步的题目以竞赛题库为准，避免 Tracker 的旧缓存覆盖新分数。

## 调度与延迟

`algohub.sync.enabled=true` 时，启动 1 分钟后开始检查，此后每 5 分钟检查一次。CF、AtCoder、牛客默认每 24 小时（1440 分钟）各安排一次 `PROBLEM_CATALOG` 任务，与账号活跃度及提交回查窗口无关。数据库保留最近入队时间，重启不会立即重复安排近期任务；已有待执行/执行中任务不会重复入队。任务沿用现有平台限速、租约与失败重试。

`algohub.sync.catalog-interval-minutes` 可调整间隔；设为 `0` 关闭自动刷新。AtCoder 元数据缓存为 1 小时；实际出分可见时间还包括缓存、排队、接口响应与重试，并非源站出分后立即更新。

管理端原有 `POST /api/admin/sync/catalog?platform=CODEFORCES|ATCODER|NOWCODER` 可手动安排刷新。题目表更新后，重新加载历史记录即可显示新分数；无需重抓所有历史提交。

## 保留规则

获取到有效评级时更新，接口失败或未评级占位符不清空已有分数。因此显示的是**最近一次成功同步到的有效评级**；源站后来撤回评级时，当前策略仍保留上次有效值。源站从未发布评级、题目不在公开题库中或接口不可访问时，不能保证补到分数，不根据通过率自行推算。

## 回归验证

- `NowcoderCatalogTest`：源站题号、分数、标签、分页及异常响应。
- `CatalogScheduleTest`：独立调度、重启后的时间判断、关闭开关。
- `ProblemRatingRefreshTest`：三个平台的旧提交从未评级到有分、调分、未知值保留，以及私有/公开历史查询和数值筛选列一致。
- `NowcoderTrackerMappingTest`：匹配规则、Tracker 补分、未知值保留和竞赛题库优先。
- `npm run test:difficulty`：牛客数值和未评级占位符、AtCoder 负数估计的显示。

数据库测试必须使用独立测试库，并关闭后台同步，不能指向开发或生产数据库。

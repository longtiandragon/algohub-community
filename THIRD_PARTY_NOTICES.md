# 第三方声明与待核查项

前端构建的 `prebuild` 会根据锁文件收集已安装包的 LICENSE/NOTICE/COPYING 原文，并附项目声明、字体与图标许可到 `dist/THIRD_PARTY_LICENSES.txt`；该生成文件不进入 Git。未安装的其他操作系统可选包不打包，只有许可元数据而无随包全文的项目仍需维护者核对。构建自动附声明不等于所有素材已取得授权；后端 jar 内依赖及品牌图片的发行义务仍需核查。

项目自有代码采用 MIT（见 LICENSE）。该许可不自动授予第三方平台商标、Logo、题目、图片或其他素材的权利，也不代表与源站合作或获其认可。

`frontend/public/platform-logos/` 现有 8 个 SVG 是本项目绘制的中性文字角标，不复制源站图形，仅标识 Codeforces、AtCoder、洛谷、力扣中国、牛客、杭电、码蹄集和蓝桥。原来抓取的平台 PNG 因授权依据不完整已从当前树移除，原始副本保存在仓库外；历史版本仍需按历史清理计划处理。平台名称和第三方商标权不因项目 MIT 而转移。

已核对当前安装包：`@fontsource/jetbrains-mono@5.3.0` 和 `@fontsource/outfit@5.3.0` 声明 OFL-1.1，原许可全文分别保留于 `third-party-licenses/jetbrains-mono-OFL.txt` 与 `outfit-OFL.txt`。字体再分发须随附原声明；项目 MIT 不取代 OFL。

`@iconify-json/lucide@1.2.137` 的包及图标元数据声明 ISC，作者为 Lucide Contributors，上游来源为 https://github.com/lucide-icons/lucide 。2026-10-30 获取的上游 LICENSE 已保留于 `third-party-licenses/lucide-LICENSE.txt`，其中另列从 Feather 派生图标的 MIT 及 Cole Bemis 版权声明；不能只保留 ISC 标签。该文件来自当日 main，发行前仍须确认与所用图标版本相符并随产物分发。

[前端逐依赖清单](docs/frontend-dependency-licenses.md) 来自锁文件，涵盖 320 个包路径（包含开发及可选依赖）；记录 MIT、OFL-1.1、ISC、Apache-2.0、BSD、CC0 和 0BSD。[后端解析清单](docs/backend-dependency-licenses.md) 来自 CycloneDX Maven 实际解析的 82 个组件。其中 Logback、Jakarta 相关组件包含 EPL/LGPL 或附例外 GPL 等声明，不能将所有后端依赖概括为 MIT/Apache。清单仅是元数据，不代表完成许可全文审阅；选择双许可路径及二进制再分发义务应按对应版本原文核查。发行产物完整 notices 尚待完成。

`docs/screenshots/` 的 6 张图片已于 2026-10-30 从独立 demo 数据库重拍，使用上述中性文字角标。截图中的人物和账号与 DemoCatalog/DemoDataSeeder 对应，属于虚构示例；未展示真实联系邮箱、令牌或 Cookie。旧截图仍存在于历史，清理计划已列入该范围。

`docs/OJ源码对照.md`、`docs/洛谷同步修复与参考实现.md` 保留了参考项目和数据接口路线，后者记录为独立 Java 实现。仓库未直接引入这两个参考项目作为代码依赖；此处保留来源说明，不宣称其代码受本项目 MIT 授权。未来复制代码时必须保留其原版权和许可并检查兼容性。平台响应测试样本只用于离线结构验证，禁止重新引入账号凭据或真实个人身份。

本次交付以源码协作为目标。后端 jar 保留原始依赖 jar，另在 `META-INF/algohub/` 附项目许可、第三方声明和依赖清单。检查发现 73 个运行依赖 jar 中有 22 个没有名称含 LICENSE/NOTICE/COPYING 的条目（不等于无许可证）：Logback、SnakeYAML、Hibernate Validator、Jakarta Validation、JBoss Logging、MyBatis Spring Boot、HikariCP、Flyway PostgreSQL、redis-authx-core、部分 Netty、Reactive Streams 和 JJWT。**如另行发布二进制 Release，须补齐这些对应版本的许可证全文及必要通知并确认双许可选择**；不能用 SPDX 元数据代替其发行义务。本阶段没有发布这些二进制。

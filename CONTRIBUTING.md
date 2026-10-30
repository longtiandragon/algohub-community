# 参与 AlgoHub

AlgoHub 聚合第三方 OJ 学习记录、统计与公开动态，不运行评测机。平台适配须遵守源站规则。较大功能、数据口径变化、新平台先开 Issue 讨论；小修复可直接 PR。

先阅读 [本地开发](docs/local-development.md)、[安全政策](SECURITY.md) 和部署文档。使用 Java 21、Maven 3.9.13、Node.js 22.19.0、PostgreSQL 16、Redis 7。前端使用 `npm ci` 安装锁定依赖。

`backend/` 为 Spring Boot/MyBatis；`frontend/` 为 Vue/TypeScript；`frontend/public/userscripts/` 为浏览器同步脚本；`scripts/` 为本地工具；`deploy/` 为独立生产运维。沿用现有格式与组件，不加入无关重构。Flyway 已发布迁移不可直接改写，新增迁移须说明兼容性和回滚限制。

同步失败保留旧数据，未知值不能写成零，浏览器自报记录不能冒充独立验证的首次通过。身份、学校、资源权限必须由服务端检查。

PR 请说明问题、行为变化、测试证据、截图及迁移影响。在专用测试实例运行后端 `mvn -B verify`；前端执行 `npm run type-check`、`npm run build`、`npm run test:browser-sync` 及受影响的其他 `test:*`。不得删除测试、放宽安全校验或忽略关键失败。浏览器脚本共享运行时修改后须更新生成产物并验证。

提交前检查 `git diff --cached` 并运行 Gitleaks。不要上传密码、Token、Cookie、验证码、数据库备份或其他人的个人信息；样本和截图使用虚构用户。漏洞按 SECURITY.md 私下报告。维护者不承诺固定评审期限。

# 生产部署（单台 Linux 服务器）

这套配置采用 Nginx HTTPS 静态站点 + systemd Java 21 后端 + Docker PostgreSQL/Redis。生产参数需由维护者自行配置，贡献者只需使用本地开发流程。

## 0. 日常更新（已上线后每次发布都走这里）

规则：**服务器只运行 GitHub 上 main 的提交**。本地改完 → 测试 → 合进 main 并推送 → 再发布，这样本地、GitHub、服务器三处始终一致。

```powershell
scripts/deploy.ps1 -Status      # 服务器现在是哪个提交、落后 origin/main 几个、最近的备份
scripts/deploy.ps1              # 发布 origin/main
scripts/deploy.ps1 -Rollback    # 切回上一版（数据库不回滚）
scripts/pull-backup.ps1         # 把服务器上的数据库备份拉到本机 backup/server/
```

`deploy.ps1` 做的事：

1. `git fetch`，确认要发布的提交在 origin/main 上（没推送的提交不能发布）；
2. 在临时目录检出这个提交，`npm ci` + `npm run build`，后端 `mvn -Prelease verify` 跑全部测试（测试用独立的 `algohub_release_test` 库和 Redis 10 号库，不会清掉开发库或和别的测试进程互相清库）；
3. 打包 jar、前端、`deploy/` 和 `RELEASE`（编号、提交、构建时间），上传到服务器；
4. 本机从构建开始持有发布互斥锁，服务器 `activate/rollback` 全流程使用 `flock`。服务器先备份数据库，再核对 Flyway 指纹、切换两个软链接并重启；失败后仅在指纹与旧版已记录的支持指纹一致时自动退回。迁移变化或指纹未知时保留新版本指向并报告 `BLOCKED_SCHEMA`，需要人工核验兼容性。指纹不覆盖绕过 Flyway 的手工 DDL；旧前端分包按原留存策略保留。
5. 把发布前的备份拉回本机 `backup/server/` 并校验 SHA-256。

服务器上 `releases/` 保留最近 5 个版本；第一次运行 `release.sh` 时，把手动部署留下的 `algohub.jar`、`frontend/` 收进 `releases/legacy-<时间>/`，并把 `/opt/algohub` 收紧为 root 所有、目录 755、文件 644（之前手动上传留下了 777/666）。手动部署留下的 `algohub-before-*.jar`、`frontend-before-*` 不会自动删除，确认新流程没问题后可以手动清理。

部署脚本要求维护者自行配置 SSH 别名 `algohub`；私钥与服务器授权不包含在仓库中。生产环境应使用权限受限的部署账号和独立凭据。

## 1. 本地构建

```powershell
cd backend
mvn -Prelease verify
cd ../frontend
npm ci
npm run build
npm audit --registry=https://registry.npmjs.org
```

上传 `backend/target/algohub-release.jar`、`frontend/dist/`、整个 `deploy/`。生产不运行 Vite dev/preview。`release` 使用独立 jar 名，避免与正在运行的本地开发 jar 发生文件占用冲突。

## 2. 服务器目录与凭据

安装 Java 21、Nginx、Docker Engine/Compose、curl、openssl、logrotate；准备正式域名解析与有效的 HTTPS 证书。以下以 Ubuntu/Debian、直接面向用户的同机 Nginx 为假设。

```bash
sudo useradd --system --home /opt/algohub --shell /usr/sbin/nologin algohub
sudo install -d -m 755 /opt/algohub /opt/algohub/frontend /opt/algohub/deploy
sudo install -d -o root -g algohub -m 750 /etc/algohub /etc/algohub/secrets
sudo install -m 600 deploy/production.env.example /etc/algohub/production.env
```

把 jar 安装为 `/opt/algohub/algohub.jar`，dist 的**内容**放在 `/opt/algohub/frontend/`，deploy 文件放在 `/opt/algohub/deploy/`。应用文件由 root 所有，服务用户只读。不要把整个源码目录当作 Nginx 根目录。

编辑 `/etc/algohub/production.env`：数据库、Redis、首次管理员密码分别使用独立随机值（例如 `openssl rand -hex 24`），填写邮件配置、正式域名和正式 GitHub OAuth App。`PUBLIC_ORIGIN=https://你的域名` 不带末尾斜杠。GitHub 后台回调填同一域名的 `/oauth/github/callback`。没有 GitHub 凭据时功能保持关闭。

`prod,mail` 为推荐配置。未配置 SMTP 时可以暂用 `prod` 验证部署，但邮箱登录/新用户入门流程不能验收通过。环境文件使用简单的 `KEY=value` 格式，供 Compose 和 systemd 共用。

### 密钥迁移必须先于首次启动

- **迁移旧数据库：**复制原来的 `backend/local-secret/credential-key.txt` 和 `jwt-key.txt` 到 `/etc/algohub/secrets/`。不要生成新 credential key。若本地通过环境变量设置密钥，应迁移对应的实际密钥。
- **全新空数据库：**才可以生成两份密钥：

```bash
sudo bash -c 'umask 077; set -o noclobber; openssl rand -base64 32 > /etc/algohub/secrets/credential-key.txt'
sudo bash -c 'umask 077; set -o noclobber; openssl rand 64 | openssl base64 -A > /etc/algohub/secrets/jwt-key.txt'
sudo chown root:algohub /etc/algohub/secrets/*.txt
sudo chmod 640 /etc/algohub/secrets/*.txt
```

禁止覆盖已有密钥。生产模式在密钥路径非绝对路径、文件丢失时拒绝自动生成。密钥与数据库备份分开加密保管，限制为服务账号可读；不要写进镜像、Git、前端或公开目录。

新库首次管理员密码至少 10 位。旧库密码不会被配置覆盖；默认密码 `admin12345` 无论在哪个管理员账号上都会阻止生产启动。迁移前在「设置与安全 → 登录邮箱」改密。

## 3. 数据库、HTTPS 与进程

```bash
sudo docker compose --env-file /etc/algohub/production.env -f /opt/algohub/deploy/compose.yml up -d --wait
```

迁移旧库时，先备份并恢复到此新实例，**然后**启动后端执行 Flyway 增量迁移。不要同时开启旧/新服务的同步 worker 写同一份库。`prod` 禁止 Flyway clean，也不能与 `demo` profile 共用。

PostgreSQL 与 Redis 仅发布到 `127.0.0.1:5433/6380`。数据库与 Redis 密码只在新数据卷初始化时生效；已有卷改环境变量不会自动改数据库角色密码，应通过数据库管理操作改密并同步环境配置。不要用 `docker compose down -v` 更新服务。

容器初始预算为 PostgreSQL 768 MiB、Redis 256 MiB；Redis 数据上限 128 MiB、`noeviction`，保留 AOF/运行开销并避免任意淘汰会话和限流键。可用 `POSTGRES_MEMORY_LIMIT`、`REDIS_MEMORY_LIMIT`、`REDIS_MAXMEMORY` 调整，上线前须验证并发、AOF 重写及失败告警；达到上限会报错，不能把它当成负载验收。

### 可选的应用连接降权

先完成原有迁移、备份并审阅 `deploy/provision-app-role.sql`，以所有者 `algohub` 连接 `algohub` 库执行，再用 psql 的 `\password algohub_app` 交互设置独立强密码。在 `production.env` 填写 `DB_APP_USERNAME=algohub_app`、`DB_APP_PASSWORD`，不能留空。未设置这两项时保持原连接，脚本不会自动替生产降权。

业务 Hikari 连接使用受限角色，Flyway 单独用 `algohub`/`DB_PASSWORD`；两者密码均须至少 16 位。受限角色只有业务 DML/序列使用权，无 public 建表权限，也不能访问迁移账本；现有角色若有成员资格、对象所有权或共享 CREATE 权限，SQL 会拒绝继续，需另行审查。该方案减少业务 SQL 的权限，迁移密码仍由后端进程持有，不能代替主机与进程隔离。

修改 `deploy/nginx.conf` 中的域名和证书路径，安装以下文件：

```bash
sudo install -m 644 deploy/00-algohub-log.conf /etc/nginx/conf.d/00-algohub-log.conf
sudo install -d -o root -g adm -m 755 /var/log/algohub
sudo install -m 644 deploy/nginx.conf /etc/nginx/conf.d/algohub.conf
sudo nginx -t
sudo systemctl reload nginx
sudo install -m 644 deploy/algohub*.service deploy/algohub*.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now algohub.service algohub-monitor.timer algohub-backup.timer
curl --fail http://127.0.0.1:8080/internal/health
```

公网仅开放 80/443，管理端口按自己的访问来源限制。8080、5433、6380 不开放公网。Nginx 覆盖客户端传入的 X-Forwarded-For/Proto，并移除 Forwarded；后端只信任来自回环地址的代理。这一配置**不直接适用于 CDN、多级代理或容器内后端**，换拓扑时必须按实际代理地址重新限定信任链，不能设为信任所有地址。

Secure Cookie 在 `prod` 默认启用；签发、退出、退出全部设备、改密、注销均使用统一 Cookie 属性。HTTP 自动跳转 HTTPS。`/internal/health` 仅供同机探针，Nginx 不转发。

## 4. 日志、限流和告警

- 后端日志：`journalctl -u algohub -n 100`。安装 `journald.conf` 到 `/etc/systemd/journald.conf.d/algohub.conf` 并重启 journald（影响主机级保留策略，先与现有策略合并）。
- Nginx 日志不记录查询串，统一写入 `/var/log/algohub/`，避免与发行版 `/var/log/nginx/*.log` 的轮转规则重叠。先创建目录，再同步安装 Nginx 模板与 `logrotate.conf`，执行 `nginx -t`、`logrotate -d /etc/logrotate.conf` 后才 reload。旧目录日志单独保留处理。容器日志 10 MB × 3 文件。
- `algohub-monitor.timer` 每分钟检查内部应用健康（含数据库/Redis）、`PUBLIC_ORIGIN` 的 HTTPS 首页，以及根分区磁盘使用率和可用内存；默认磁盘达到 90%、可用内存低于 256 MiB 告警。配置 `ALERT_WEBHOOK_URL` 后按故障周期发送一次固定 JSON 告警，发送失败会重试。不配置时只有本机日志，**不等于已开通远程通知**。仍需站外探针发现整机/网络故障。
- 注册：单 IP 10 次/分钟、全局 100 次/分钟；认证入口：单 IP 120、全局 1200 次/分钟；密码登录另有账号 20 次/分钟及原来的失败锁定。
- 验证码发送入口合计：单 IP 10、全局 100 次/分钟、全局 2000 次/天；收件人 60 秒冷却和验证码最多 5 次尝试继续保留。移除了登录/找回密码内部重复的“一个IP每分钟只能发一次”，校园共享出口仍受统一配额保护；大规模集中注册前按人数实测容量。
- `algohub.security.rate-limit-scale` 可调入口配额倍率（整数 >= 1），不会关闭原有账号/收件人限制。应结合邮件商额度和实际人数调整；Nginx 另有通用 API 限流。
- 邮箱验证码（登录、找回密码、绑定/更换登录邮箱合并计数）：同一邮箱 24 小时内输错 10 次锁定 15 分钟，重发不清零；每个邮箱每天最多收 10 封验证码。短锁仅缓解定向拒绝服务，攻击者仍可能重复触发，后续需增加挑战隔离或人机校验。管理员密码登录不受影响。手动解锁：删除 Redis 键 `algohub:email-code:lock:<小写邮箱的 SHA-256 十六进制>`。
- 请求外站的操作（添加/验证账号、保存或试用登录态、牛客 Tracker 翻页、码蹄集实时扫描）按用户和 IP 统一限流：`OUTBOUND_PER_USER`（默认 30）、`OUTBOUND_PER_IP`（默认 300），每 10 分钟。每人每个平台最多 `MAX_ACCOUNTS_PER_PLATFORM`（默认 3）个账号，已超过的保留、不能再加。都在 `production.env` 里调。
- 平台请求排队超过 30 秒直接失败（「平台请求排队太久」），不再无限等待。
- 敏感操作（注销账号、绑定/解绑 GitHub、第一次绑登录邮箱）要先确认本人：现有邮箱验证码、密码或已绑定的 GitHub。换登录邮箱、绑定/解绑 GitHub 后其他设备全部退出。上线后第一次刷新时，旧版签发的登录状态会失效一次，大家重新登录即可。
- Redis 故障时认证防刷不会自动放行。Lua 脚本适用于当前单实例 Redis，不是 Redis Cluster 跨槽部署方案。

## 5. 备份、恢复与回滚

`algohub-backup.timer` 每天服务器时间 03:15（服务器是 UTC，即北京时间 11:15）运行 `backup.sh`，写入 `/var/backups/algohub/`（文件 600），附 SHA-256；发布前 `release.sh` 会再备份一次（文件名带 `-predeploy`）。`backup.sh` 保留最近 30 天、且至少留最新 14 份，更早的连同 `.sha256` 一起删除。异机副本：本机运行 `scripts/pull-backup.ps1`（可用 Windows 任务计划每天跑一次，命令见脚本开头），拉到 `backup/server/` 并校验，本机保留 60 天、至少 20 份。密钥（`/etc/algohub/secrets/`）另外备份，不和数据库备份放在一起。

```bash
sudo bash /opt/algohub/deploy/backup.sh
sudo bash /opt/algohub/deploy/restore-check.sh /var/backups/algohub/实际文件.dump
```

恢复验证脚本只创建随机后缀的 `algohub_restore_*` 临时库，校验 pg_restore 完成并查询关键表，结束删除临时库，不覆盖生产库。还应比对停写时记录的关键行数，并验证备份密钥能解密已有 OJ 登录态。

更新前保留上一版 jar/dist、数据库快照和原密钥；先在恢复库跑新版本迁移。不要在旧版本不兼容新 schema 时直接换回旧 jar。必要时停服务，恢复匹配的数据库快照和应用版本；恢复前先备份故障现场数据。

普通 `release.sh rollback` 要求目标版本已记录的 `SCHEMA.sha256` 与当前迁移指纹一致；旧版本没有记录时也拒绝。人工核验兼容性后才可显式执行 `rollback --schema-compatible`，它会先备份、记录运维声明，仅切换应用且不会恢复数据库。不能以健康探针通过替代兼容性检查。

## 6. 依赖维护

当前 Spring Boot 为 3.5.16；上游已结束 3.5 系列的开源维护。已单独升级 Tomcat、Netty、PostgreSQL JDBC、Jackson、Log4j API 和 jsoup 的已知安全修复版本，但这不能代替后续框架维护。应安排迁移到仍受支持的 Spring Boot 大版本，并在每次发布前重新扫描依赖、验证兼容性。参见 [上游维护说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)。

## 7. 部署后的真实验收

- HTTPS 与证书续期；HTTP 跳转；页面深链接刷新；API 不被静态页兜底。
- 注册/登录/刷新/退出/改密 Set-Cookie 有 Secure、HttpOnly、SameSite=Strict；HTTP 不发送长期 Cookie。
- 多设备、并发刷新、过期/重放令牌；两个不同客户端真实 IP 互不误伤，伪造代理头不能绕过限流。
- SMTP 验证码登录、邮箱验证/更换、找回密码；正式 GitHub 授权/取消/重新登录。
- 按 `docs/真实环境待办清单.md` 从服务器验证各 OJ 归属与同步，不把示例数据验收当成真实账号验收。
- 停后端/断开 Redis 或数据库验证告警及恢复；服务器重启后服务与定时备份自动恢复。
- 实际生产备份恢复演练与密钥恢复；记录人员、日期和结果。

代理配置参考：https://docs.spring.io/spring-boot/3.5/how-to/webserver.html
Redis 原子脚本参考：https://redis.io/docs/latest/develop/programmability/eval-intro/

# 独立本地开发

环境：JDK 21、Maven 3.9.13、Node.js 22.19.0、Python 3、Docker Compose v2、PostgreSQL 16、Redis 7。镜像目前固定主版本，验收应记录具体镜像 ID。以下是操作流程，不是已完成验收的声明。

本次实测为 Java 21.0.5、PostgreSQL 16.15、Redis 7.4.9；完整结果见 [验收记录](open-source-readiness.md)。本仓库所在 Windows 共享主机须先运行用户指定的 `Ensure-DockerDesktop.ps1`，再使用 `docker --context desktop-linux`；这是该主机的协作约束，不是外部贡献者必须安装的项目依赖。不要由项目脚本启动或关闭共享 Docker/WSL。

在新克隆的根目录执行：

```sh
python scripts/init-dev.py
docker compose up -d --wait
cd backend
mvn -B package -DskipTests
mvn spring-boot:run
```

脚本从 `.env.example` 和 `backend/development.properties.example` 生成忽略文件，使用随机本地数据库和管理员密码，不覆盖已有文件、不打印密码。后端从自身目录加载 `local-secret/development.properties`。首次启动执行 Flyway 迁移并创建管理员；已有管理员不被配置覆盖。

另开终端：

```sh
cd frontend
npm ci
npm run dev
```

访问 `http://127.0.0.1:5174/admin/login`，用户名 `admin`，密码自行在刚生成的 development.properties 中读取，不要分享。后端 8080，数据库 5433，Redis 6380，均使用回环地址。端口冲突时不要停止别人的服务。已有数据卷的密码不会随 `.env` 修改而自动变化，不要删除已有卷来解决问题。

模板关闭后台同步、提醒和昵称索引；需要测试时用自己的平台账号显式开启，遵守访问规则。不配置 SMTP 时验证码注册/登录不可用，但管理员可登录；无 OAuth 凭据时 GitHub 登录关闭。`demo` profile 使用虚构用户且每次清空专用 `_demo` 库，不可与 prod 共用，不可指向真实数据。

## 测试隔离

在单独临时源码目录测试，不复制原目录 `.env`、local-secret、日志、备份或数据。运行：

```sh
python scripts/init-dev.py --postgres-port 15433 --redis-port 16380 --instance algohub-contributor
docker compose -p algohub-contributor up -d --wait
docker exec algohub-contributor-postgres psql -U algohub -d postgres -c "CREATE DATABASE algohub_test OWNER algohub"
```

仅为测试进程设置 `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15433/algohub_test`、`SPRING_DATASOURCE_USERNAME=algohub`、`SPRING_DATASOURCE_PASSWORD` 为刚生成的本地密码、`SPRING_DATA_REDIS_HOST=127.0.0.1`、`SPRING_DATA_REDIS_PORT=16380`、`SPRING_DATA_REDIS_DATABASE=15`。清除继承的生产 profile、Flyway URL 和额外配置位置，再在 backend 执行 `mvn -B verify`。测试会删除数据，必须使用专用实例；密码不要写进命令历史或报告。

前端执行 `npm run type-check`、`npm run build`、`npm run test:browser-sync` 和其他受影响 `test:*`。浏览器测试需自行安装 Chrome，可用 CHROME_PATH 指定。

## 配置入口

开发配置以 `backend/development.properties.example` 为准，Compose `.env` 只配置容器；两者数据库密码需一致。业务默认参数在 `application.yml` 与 `AppProperties.java`。生产变量完整模板见 `deploy/production.env.example`：DB_PASSWORD、REDIS_PASSWORD 和首次 ADMIN_PASSWORD 必需；受限业务角色用 DB_APP_USERNAME/DB_APP_PASSWORD。生产 JWT 与平台登录态加密密钥须预先置于指定绝对路径，旧库需要原加密密钥。PUBLIC_ORIGIN 决定 OAuth 回调域名；GITHUB_CLIENT_ID/SECRET 可选。邮件启用 mail profile 并配置 MAIL_HOST/PORT/USERNAME/PASSWORD/FROM 与 TLS。前端 VITE_* 变量不得放任何秘密。

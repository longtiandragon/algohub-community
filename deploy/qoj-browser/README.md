# QOJ 服务器浏览器验证环境

Ubuntu 24.04 独立系统用户 `qoj-browser` 运行有界面 Chrome、Xvfb、Openbox、x11vnc 和 noVNC。用于管理员手工完成验证、登录，再验证平台账号的可见数据范围。部署这个环境本身不代表 QOJ 自动采集已可用。

安装：将此目录放到 `/opt/algohub-qoj-browser`，以 root 执行 `bash install.sh`。使用 Ubuntu 软件源及 Google 官方 Chrome 安装包；不自动重启其他服务。浏览器服务不设置开机自启，不自动循环重启。

Windows 本机执行 `scripts/open-qoj-browser.ps1`，使用已有 SSH 别名 `algohub` 建立隧道并打开 noVNC。重新打开可复用本次记录的 SSH 进程；端口冲突会停止操作，不终止其他进程。

端口均仅监听回环地址：服务器 noVNC 6081、VNC 5901、Chrome 调试 9223；本机只转发 noVNC 到 16081。VNC 不另设口令，访问边界为服务器及 Windows 本机可信用户和 SSH 身份验证；不得公开转发这些端口、添加公网反向代理或与不可信本机用户共享。

浏览器沙箱保持启用，服务使用专用低权限账号、独立临时目录，不能访问 AlgoHub 配置、部署目录或数据库备份。内存硬限制 1200 MiB、CPU 最多一个核心。浏览器资料和 Cookie 位于 `/var/lib/algohub-qoj-browser/profile`，由专用用户持有且父目录权限为 0700，不打包、不提交 Git、不写入应用日志。代码中不存放 QOJ 账号密码。

维护命令：

```sh
systemctl start algohub-qoj-browser
systemctl status algohub-qoj-browser
systemctl stop algohub-qoj-browser
```

停止服务保留浏览器资料。管理员完成 QOJ 验证及登录后，需要在同一会话验证其他用户主页、Motto、累计数、至少两页提交及实际权限范围。验证码、二次认证、受限记录均不得绕过；遇到验证时交由管理员处理。新同步流程测试和上线前不迁移旧绑定。

## 2026-10-30 部署验证

已在服务器 Ubuntu 24.04 安装 Google Chrome 156.0.8078.79 并启动独立服务，浏览器实际显示 `Login - QOJ.ac`、地址为 `https://qoj.ac/login`，无需修改浏览器指纹或关闭沙箱。无账号登录的这个结果仅证明浏览器可打开登录页。

验证了 noVNC HTTP 200、SSH 隧道、WebSocket 101 和 VNC 握手端到端正常。最终监听检查确认 5901、6081、9223 全部只绑定 `127.0.0.1`（x11vnc 显式使用 `-no6` 防止默认额外监听 IPv6 全地址）。资料目录及其父目录权限均为 0700。AlgoHub 主服务 PID 保持不变、重启计数为 0，内部健康检查 UP。该浏览器服务未设置开机自启。

等待管理员手工登录；尚未验证登录后的跨账号读取，不执行旧绑定迁移或升级通知。

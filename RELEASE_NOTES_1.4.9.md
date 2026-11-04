# v1.4.9 — 修复无鉴权写操作、下载引擎竞态与高频查询放大

本版本是对 v1.4.8 的完整修复与性能收敛，重点解决私网无鉴权模式不可写、频道下载队列在 SQLite 单连接池下的查询放大，以及重试/对账状态机中的几处竞态。

### 核心修复

1. **无鉴权模式（`AUTH_ENABLED=false`）写操作恢复可用（web）**
   - 浏览器请求层在服务端未下发 CSRF Cookie 时不再强制要求 `X-CSRF-Token`，修复私网/家用部署下所有 POST/PUT/DELETE 在发出前即失败的问题；
   - 新增单元测试覆盖“无鉴权可写”“有鉴权必须带 CSRF”“有鉴权缺少 CSRF 仍被拦截”三种情况。

2. **频道下载队列一致性**
   - `reconcileCompletedJobs()` 批量更新增加 `status IN ('WAITING','RETRY')` 守卫，避免把刚被 `claim()` 置为 `STARTING` 的任务误标为 `COMPLETED`；
   - 重新扫描/入队不再无条件重置 `RETRY` 的退避与 `attempts`：只有规则版本变化或显式 `rescan` 才会重新给一次机会；`FAILED/SKIPPED` 在规则未变化时保持终态；
   - 修复 `reconcile` 路径下 `attempts` 被重复 +1、导致过早进入 `FAILED` 的问题；
   - `retry` 动作只作用于当前规则版本的任务，避免旧版本任务永久停在“重试中”；
   - 评论扫描 `done=2`（错误态）的线程现在会按退避重试，不再永久卡死。

3. **性能与数据库压力**
   - `tick()` 每账号每秒查询由约 5 次降为 1 次：下载数量/时段设置改为 30 秒缓存并通过 `SETTING_UPDATE` 事件即时失效；`due()` 为空时不再查询在途文件与设置；
   - `PAUSED` 任务对账从每秒一次改为每 15 秒一次，避免暂停任务越多、后台查询越多的放大效应；
   - 新增 `file_record(telegram_id, download_status)` 索引，修复按状态统计时退化为全账号文件扫描的问题；
   - 入队改为批量：`IN` 查询 + `executeBatch` 写入，把原来“每条消息 2~3 次 SQL + 事务内磁盘校验”的串行开销压到每 100 条一批；
   - `reconcileCompletedJobs()` 的批量更新改为事务内执行，减少中间状态。

4. **稳定性与安全边界**
   - `TelegramVerticles` 注册表与 `SettingAutoRecords.automations` 改用 `CopyOnWriteArrayList`，消除 HTTP 线程与下载线程并发读写导致的 `ConcurrentModificationException` 风险；
   - `authorized` / `telegramRecord` 改为 `volatile`，保证跨 verticle 读取的可见性；
   - `GET /channel-downloads` 不再产生唤醒账号的副作用，避免仅打开页面就干扰休眠策略；
   - 账号唤醒失败日志按账号 60 秒去重，避免离线账号刷屏；
   - 会话 token 的 URL 查询参数仅对 `GET/HEAD` 生效，写操作必须使用 Cookie/Bearer；
   - `FILE_READS_PER_MINUTE` 现在真正生效：文件预览与媒体串流按来源 IP 限流；
   - 归档/转存到自定义目标目录的文件重新允许在线预览与串流（允许根包含已启用的 transfer 目标）；
   - 移除未使用的 `registerComments()` 与冗余的 `hasActiveWork()` 静态状态，避免休眠判断出现“看似有条件、实际恒为假”的误导。

### 升级说明

- 无数据库结构迁移；启动时会为 `file_record` 创建新索引，超大文件表首次启动会略慢，之后无影响；
- 建议升级后观察 `channel_download_job` 中 `RETRY/FAILED` 数量是否开始收敛到真实值。

### 验证

- 后端：`./gradlew test`（Linux CI 全量通过；本地 Windows 仅存在已知的平台相关用例失败，如 POSIX 路径、文件锁与 CRLF 哈希）；
- 前端：`npm run typecheck`、`eslint`、`vitest`（新增 3 个 CSRF 场景用例）全部通过。

镜像：`ghcr.io/orson-yan/telegram-files-cn:1.4.9` 及 `latest`，支持 `linux/amd64` 与 `linux/arm64`。

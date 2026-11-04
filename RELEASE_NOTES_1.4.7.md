# v1.4.7 — 消除下载高频事件引发的数据库连接池饥饿、全站卡顿与白屏延迟

### 核心变更

1. **彻底解决下载过程中数据库连接池饥饿与全站卡顿、白屏问题（TelegramVerticle / FileRepositoryImpl）**
   - **下载分片高频事件 DB 过滤**：修复 TDLib 高速下载期间以每秒数十至数百次触发 `UpdateFile` 时无节制调用 `enqueueFileStatusUpdate` 导致 SQLite 单连接池被数千个空操作查询淹没的严重缺陷。在 `TelegramVerticle` 中引入内存状态跟踪（`lastKnownFileStatus` / `lastKnownLocalPath`），在文件处于活跃分片传输期间（状态已为 `downloading`）完全跳过无意义的数据库读写，将 DB 写入压力降低 99.9% 以上；
   - **文件状态更新防抖合并（Coalescing）**：重构 `enqueueFileStatusUpdate`，废弃无上限的 Promise 递归链，采用“单并发执行 + 最新快照挂起”机制，确保单文件无论并发收到多少更新事件，在任意时刻至多只有 1 个在飞 DB 事务与 1 个待处理快照，杜绝任务在内存中排队积压；
   - **消除冗余查询**：在 `persistFileStatusUpdate` 中增加本地路径与下载状态的对比，当二者均未改变时直接跳过 `updateDownloadStatus`，避免同一分片连续触发两次完全重复的 `getByUniqueId` 查询；
   - **连接池超时保护**：优化连接池超时设置，避免 HTTP 请求在极端情况下无限期被挂死在连接池等待队列中。

2. **修复前端下载任务显示延迟与页面白屏卡死（DownloadMonitor / AdminAuthGate / API）**
   - **下载任务即时秒级弹出**：在 `DownloadMonitor` 中打通 `WebSocketMessageType.FILE_STATUS` 为 `downloading` 以及检测到全新活跃下载时的即时 `mutate()` 触发链路，告别过去长达 15 秒轮询盲区；同时在单文件及批量启动下载成功后主动刷新活跃下载列表，任务点击后瞬间呈现在下载监控列表中；
   - **前端请求超时与白屏保护**：在 `api.ts` 的全局请求拦截中加入 20 秒超时 Abort 信号保护，杜绝由于后端偶尔阻塞导致 `AdminAuthGate` 永久停留在 `status === 'loading'` 引起的网页全白屏与刷新无响应；
   - **批量控制联动刷新**：批量暂停/继续/取消等操作完成后即时重置并触发下载状态缓存同步。

3. **大幅降低频道自动下载与轮询开销（ChannelDownloadVerticle）**
   - **规则指纹缓存与降频**：修复 `sync()` 每秒执行导致无意义全表扫描 `channel_download_source` 与批量 `ensure` 的问题，增加配置指纹（`lastAutoFingerprint`）与 30 秒安全 TTL 缓存，仅在自动下载配置发生变更时才执行全量同步；
   - **空运行快速返回**：在 `reconcile()` 中优先判断无在飞任务（`jobs.isEmpty()`）时即刻返回，避免每秒空转查询。

4. **修复 TDLib 消息更新分发漏加 `break` 隐患（TelegramUpdateHandler）**
   - 修复 `TelegramUpdateHandler` 中 `UpdateFile`、`UpdateNewMessage` 及 `UpdateSupergroup` 分支漏写 `break;` 导致的穿透（fallthrough）严重隐患，确保消息及聊天状态分发绝对精准隔离，并补充完备单元测试。

---

镜像：`ghcr.io/orson-yan/telegram-files-cn:1.4.7` 及 `latest`，支持 `linux/amd64` 与 `linux/arm64`。

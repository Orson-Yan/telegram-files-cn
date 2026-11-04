# v1.4.8 — 修复账号休眠阻断下载、频道队列去重与已完成任务对齐

### 核心变更

1. **彻底解决频道下载期间账号休眠与无法唤醒问题（TelegramVerticle / ChannelDownloadVerticle / AutomationsHolder）**
   - **休眠状态拦截与自动唤醒**：修复 `TelegramVerticle.canIdleSleepLocked()` 在账号存在活跃频道下载源或待下载任务时仍然误判为空闲并触发 TDLib 客户端休眠的缺陷。增加 `ChannelDownloadVerticle.hasActiveWork()` 实时感知机制，只要频道自动下载存在启用中的源或排队任务，阻止账号进入休眠；
   - **调度轮询主动唤醒**：在 `ChannelDownloadVerticle.tick()` 中增加休眠检测，当检测到目标账号处于 `SLEEPING` 或 `STARTING` 状态时，主动触发 `telegram.wake()` 唤醒，不再因 `!telegram.authorized` 而盲目跳过导致任务永久停滞；
   - **自动化配置更新保护**：修复 `AutomationsHolder.onAutoRecordsUpdate` 在账号非授权状态（如休眠或正在启动）下直接拒绝并忽略自动化规则保存的缺陷，解除强制要求 `authorized` 的限制并在接收到新规则时主动唤醒客户端。

2. **频道历史扫描入队智能去重（ChannelDownloadStore）**
   - **入队前对齐已完成记录**：修复历史频道扫描 `enqueue()` 盲目将所有历史文件一律以 `status = 'WAITING'` 插入队列，导致数万已完成历史文件重新堆满下载队列的问题；
   - **文件完整性与归档校验**：入队前预先查询 `file_record`，若文件状态已为 `completed`（且本地文件存在且大小吻合）或 `transfer_status = 'completed'`（已转存/归档整理），直接以 `COMPLETED` 状态入库，不再生成无意义的等待中任务；
   - **防止错误重置**：对于过去失败或重试的任务，重新入队时同样核验本地文件，若实际已完成则平滑修复为 `COMPLETED`，杜绝重复发起传输。

3. **历史排队任务存量平滑对齐与秒级自愈（ChannelDownloadStore / ChannelDownloadVerticle）**
   - **存量队列秒级对齐（Reconciliation）**：新增 `reconcileCompletedJobs()` 批量校准机制，在 `ChannelDownloadVerticle` 启动时及每 60 秒定期执行。一次性扫描现有处于 `WAITING` 或 `RETRY` 状态的任务，若本地已存在完整文件或已完成转存，立即批量更新为 `COMPLETED` 并回填本地路径；
   - **瞬间消除虚高排队数**：已存在数万条虚假排队任务的用户在升级重启后，已下载的过万文件瞬间从等待队列移出并计入已完成，UI 队列计数恢复真实剩余任务数，杜绝数万次无意义的 Telegram `GetMessage` API 轮询与潜在的 `FLOOD_WAIT` 限流。

4. **转存/归档文件防二次下载保护（ChannelDownloadVerticle / ChannelDownloadRoutes）**
   - **转存状态直接复用**：在 `dispatch` 启动下载前增加对 `transfer_status == 'completed'` 的判定，若文件已被转存到目标存储或归档，直接作为已完成返回，杜绝因源文件被移动/转移导致本地路径不存在而重置为 `idle` 重新下载；
   - **频道下载路由状态提示优化**：在 `/channel-downloads` 状态概览中明确区分“账号休眠中，正在唤醒连接...”与“账号离线”，并在查询时触发即时唤醒。

---

镜像：`ghcr.io/orson-yan/telegram-files-cn:1.4.8` 及 `latest`，支持 `linux/amd64` 与 `linux/arm64`。

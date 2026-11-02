# v1.4.6 — 彻底消除下载队列假死卡滞、重连死锁恢复与规则智能匹配

### 核心变更

1. **彻底消除下载并发槽位假死与陈旧锁死锁（TelegramVerticle / ChannelDownloadVerticle）**
   - **重启/重连 FileId 失效自动回收**：修复应用重启或重连后，TDLib 内存会话变化导致原 `fileId` 失效抛错，状态未释放从而永久占满并发槽位（`occupiedSlots`）的严重缺陷。轮询中自动通过消息尝试刷新，超时未就绪主动释放回 `idle`，彻底杜绝派发假死；
   - **`STARTING` 状态防挂起**：为派发中由于网络超时滞留的 `STARTING` 状态增加 60 秒自动超时恢复机制，防止槽位被卡死。

2. **打通重试与异常文件重新下载链路（TelegramVerticle / FileRepositoryImpl）**
   - **解除状态防御误伤**：修复 `startTelegramDownload` 误将 `error`、`paused` 或异常中断的 `downloading` 文件拦截并报错 `File is already downloading or completed` 的问题；
   - **放宽并发认领状态**：`claimDownloadStart` 乐观锁状态支持涵盖 `idle`、`error` 与 `paused`，针对异常中断的 `downloading` 文件在启动前自动重置为可认领状态，让失败或停滞文件能够顺利重启和重试，同时严格保障并发互斥原子性。

3. **校正磁盘水位计算与目录容错（ChannelDownloadVerticle）**
   - **按单文件校验所需空间**：修复派发预估容量时错误累加所有在飞任务导致所需空间虚高、频发 `STORAGE_LOW` 阻塞扫描的问题；
   - **转移目标目录检测容错**：优化外部存储目录未挂载或尚未创建时的判定逻辑，不再因目标文件夹尚不存在而阻断下载。

4. **下载规则智能兼容文档型媒体与全文件分类（DownloadRules）**
   - **智能匹配无损媒体**：在标准分类中选择“视频”或“媒体”时，智能识别以文档形式（`MessageDocument`）发送的各类无损视频（如 MKV、MP4），避免被原生消息类型过滤误剔除；
   - **文件分类全面兜底**：勾选“文件”分类时同步涵盖常规文档、压缩包、电子书及其他附件文件；
   - **版本更新自动联动**：同频道修改规则升级 `rule_version` 时，级联同步已有排队任务的版本标识，避免旧任务沦为孤儿任务。

---

镜像：`ghcr.io/orson-yan/telegram-files-cn:1.4.6` 及 `latest`，支持 `linux/amd64` 与 `linux/arm64`。

# v1.4.10 — 修复预览限流打断视频串流与缩略图加载

本版本是 v1.4.9 的紧急修复版，主要解决新启用的文件预览限流在正常浏览与播放场景下误伤的问题，并补齐归档文件预览与若干稳定性细节。

### 核心修复

1. **预览限流不再打断视频串流与缩略图（P0）**
   - v1.4.9 将 `FILE_READS_PER_MINUTE` 应用到 `/{telegramId}/file/{uniqueId}` 的**每一个 HTTP 请求**；而 TDLib 流式播放每个请求只返回 1 MiB，默认 120 次/分钟实际等价于约 16 Mbps 上限，正常带宽下播放很快会被 429 中断；文件列表每页 60 条缩略图也会在两次翻页内耗尽配额，且缩略图加载失败后不会重试；
   - 现在只有**完整文件预览**（无 `Range` 请求头、且不是 `thumbnail` 类型）才计数，并按 `客户端 IP + uniqueId` 维度统计：
     - 视频/音频的分片 `Range` 请求不消耗配额；
     - 列表缩略图不消耗配额；
     - 同一文件的重复访问在窗口内独立计数，但不同文件仍受总量约束；
   - 默认配额从 `120` 提升到 `600` 次/分钟，并在 `.env.example` 中说明“缩略图与 Range 分片不计入”。新增 `PreviewRateLimitTest` 覆盖三种豁免与配额 key 逻辑。

2. **归档文件预览不再依赖转存规则保持启用（P1）**
   - 允许预览的文件根目录原先只收集 `transfer.enabled == true` 的 automation，用户关闭或删除转存规则后，已归档到 NAS 的文件会返回 `403 FILE_PATH_NOT_ALLOWED`；
   - 现在只要 automation 配置了 `transfer.rule.destination` 且目录存在，就会作为允许根，与当前是否启用无关。

3. **下载引擎测试稳定性（P1）**
   - `ChannelDownloadEngineTest` 的等待上限从 15 秒提高到 60 秒，消除 CI 机器负载较高时 `Timed out waiting for durable engine` 的偶发失败。

4. **细节与性能**
   - `ensureSettings()` 改为每个 tick 只加载一次（不再按账号重复查询设置），保留 30 秒缓存与 `SETTING_UPDATE` 失效；
   - `SettingAutoRecords.setAutomations` 改为整体替换快照，避免并发读者看到 `clear()` 与 `addAll()` 之间的空列表；
   - 移除已无调用点的 `ChannelDownloadStore.enqueue()`；
   - 修复 `misc/update_version.py`：不再尝试修改已不存在的 `Start.java` 版本字面量，并同步 `web/package-lock.json` 根版本。

### 升级说明

- 无数据库结构变更；
- 如自定义过 `FILE_READS_PER_MINUTE`，可按新语义理解为“每分钟不同文件的完整预览请求数”，缩略图与分片串流不计入；
- 仍建议在 v1.4.9 基础上升级（v1.4.9 包含下载队列竞态、查询放大与无鉴权写操作修复）。

### 验证

- 后端：`./gradlew test`（Linux CI 全量通过；本地 Windows 仅存在已知平台相关用例失败）；
- 前端：`npm run check`（eslint + tsc + 65 个单测）通过；
- 新增 `PreviewRateLimitTest`（缩略图/Range 豁免、配额 key）。

镜像：`ghcr.io/orson-yan/telegram-files-cn:1.4.10` 及 `latest`，支持 `linux/amd64` 与 `linux/arm64`。

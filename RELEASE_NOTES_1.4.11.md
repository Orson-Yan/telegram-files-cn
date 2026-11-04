# v1.4.11 — 手动重试优先于历史下载积压

本版本是一个小修复版，解决“点击重试后任务迟迟不执行”的调度饥饿问题，并兼容已经存在于数据库中的旧 RETRY 记录。

### 核心修复

1. **手动重试会立即排到历史队列之前**
   - 此前 `retry` 动作只把任务重置为 `RETRY`（`attempts=0`、`next_attempt=0`），但 `due()` 的排序是“来源轮转 → priority DESC → attempts → updated_at”，而历史 WAITING 任务的 `updated_at` 更早、优先级同为 10，因此重试任务会被排在数万条历史队列之后；
   - 现在 `retry` 会同时把任务优先级提升到 `1000`，`due()` 也将 `RETRY` 排在所有 `WAITING` 之前；
   - 该排序对**已有 RETRY 记录同样生效**（即使其 `priority` 仍是旧的 10），升级后无需手工改库，现场积压的 11 条 RETRY 会立即获得调度机会；
   - `next_attempt` 仍在未来（FLOOD_WAIT/退避中）的任务不会被提前调度，冷却语义保持不变。

2. **回归测试**
   - 新增 `ChannelDownloadStoreTest.manualRetryJumpsAheadOfHistoryBacklog`：
     - 校验 `retry` 后任务变为 `RETRY`、优先级 1000、`next_attempt=0`；
     - 将优先级人为改回 10 模拟旧数据，确认 `due()` 仍优先返回 `RETRY` 而不是更早的 `WAITING`。

### 升级说明

- 无数据库结构或数据迁移；
- 该版本可叠加在 v1.4.10 之上，建议与 v1.4.10 的预览限流修复、下载引擎批量优化一起部署；
- 若历史积压仍然很大（例如可用磁盘小于待下载体积），重试任务会优先执行，但整体队列仍需要清理空间或收窄下载规则。

### 验证

- `./gradlew test --tests "telegram.files.ChannelDownloadStoreTest"` 全部通过（3 个用例）；
- 后端编译、`shadowJar` 通过；CI 在 Linux 上的全量测试通过。

镜像：`ghcr.io/orson-yan/telegram-files-cn:1.4.11` 及 `latest`，支持 `linux/amd64` 与 `linux/arm64`。

# Telegram Files 中文增强版

这是 [jarvis2f/telegram-files](https://github.com/jarvis2f/telegram-files) 的简体中文增强 fork。项目保留完整英文界面，并增加简体中文、本地语言记忆、中文日期显示、云端转发、本地整理，以及面向本 fork 的镜像和上游同步流程。

> 原项目功能、问题和路线图请同时参考[上游仓库](https://github.com/jarvis2f/telegram-files)；本 fork 的云端转发和本地整理属于独立增强功能。

## 中文界面

- 首次访问时根据浏览器语言自动选择：中文环境使用简体中文，其他环境使用英文。
- 登录页和主界面都提供语言切换按钮。
- 手动选择保存在浏览器本地，刷新或重新打开后继续使用。
- 文件名、聊天名、账号名、路径和自定义标签等用户内容不会被当作界面文案修改。
- 中文文案集中维护在 `web/src/i18n/messages.ts`，新增上游界面文案时只需补充此文件。

## 主要功能

- 从 Telegram 频道、群组和聊天持续下载文件
- 同时管理多个 Telegram 账号
- 暂停、继续、取消下载，下载监控页提供实时单项控制与全局全部暂停/恢复
- 图片与视频预览，支持调用外部播放器 (PotPlayer / VLC / IINA) 及复制直链，解决 H.265/MKV 解码问题
- 文件搜索、筛选、标签和批量管理，批量勾选时实时统计总体积
- 自动预加载、自动下载与自动化体积过滤（支持自定义最小/最大文件体积，过滤垃圾表情与过大文件）
- **云端转发（一阶段）**：使用 Telegram 服务端跨频道/群组极速搬运与备份，零本地流量消耗；内置每日配额限制、拟人化防封抖动延迟、FloodWait 保护倒计时横幅、文案广告清洗（去链接/去@用户名/正则替换/加小尾巴）、文件体积与扩展名过滤、严格时序与路由隔离等企业级特性
- 本地整理：支持「移动 (Move)」、「硬链接 (Hardlink)」、「复制 (Copy)」三种模式，原文件完好保留做种刮削两不误
- 自动回收 TDLib 已停止但数据库仍为下载中的僵尸任务
- 持续填充自动下载并发槽位，避免小文件批次之间长时间空等
- 首页实时显示总速率、流量、队列、预计时间和逐文件下载进度
- 防止本地文件仍存在时被 TDLib 缓存更新误标为待下载
- 使用 Telegram 消息发送时间作为文件修改时间，方便相册按时间排序
- 响应式 Web 界面、PWA 与移动端访问
- 从 Telegram 分享链接定位文件
- 可选的 telegram-seed / qBittorrent 分享能力

## 快速部署

使用前需要在 [Telegram API](https://my.telegram.org/apps) 申请 `TELEGRAM_API_ID` 和 `TELEGRAM_API_HASH`。

### 一键部署（Linux amd64 / arm64）

```bash
curl -fsSL https://raw.githubusercontent.com/Orson-Yan/telegram-files-cn/main/scripts/deploy.sh | bash
```

脚本可重复用于管理服务：

```bash
./scripts/deploy.sh start
./scripts/deploy.sh stop
./scripts/deploy.sh update
./scripts/deploy.sh restart
./scripts/deploy.sh status
./scripts/deploy.sh logs
./scripts/deploy.sh config
```

### Docker Compose

复制本仓库的 `docker-compose.yaml` 和 `.env.example`，将后者保存为 `.env` 并填写 Telegram API 凭据，然后运行：

```bash
docker compose up -d
```

Compose 默认使用中文 fork 镜像：

```text
ghcr.io/orson-yan/telegram-files-cn:latest
```

`main` 分支更新后会构建 `main`、`latest` 和根目录 `VERSION` 对应的版本镜像；正式 release 也会更新 `latest`。如需锁定版本，可在 `.env` 中设置 `IMAGE_TAG`（例如 `0.9.2`）。

### 云端转发（服务端直传）

首页的“云端转发”用于把来源频道、群组或私聊的消息直接备份转存到目标聊天。媒体数据由 Telegram 服务端内部复制或转发，**不消耗服务器本地下行与上行流量，大文件秒级完成转存**。

#### 1. 拟人防封流控与安全保护体系
- **每日限额与推荐档位**：历史归档任务可针对单个任务设定每日转发上限。提供【防封推荐: 200 条/天】、【稳健搬运: 500 条/天】、【极速处理: 1000 条/天】、【不限制】以及任意自定义数值。限额用尽后任务自动进入智能挂起，零 CPU 轮询开销，次日凌晨自动无缝唤醒；支持随时热调整。
- **拟人化随机微抖动（Jitter Delay）**：历史任务发送单条消息增加 `2.2s ~ 3.8s` 随机微抖动，实时转发增加 `1.2s ~ 2.0s` 随机抖动，彻底打破机械化固定时间间隔，大幅降低 Telegram 服务端反滥用算法识别概率。
- **透明化平台频控保护（FloodWait Transparency）**：当遇到 Telegram 官方 `FLOOD_WAIT` 速率限制时，秒级精确捕捉冷却时长，并在 Web 界面顶部实时展示黄色高优先级解冻倒计时横幅（清晰展示受限账号与剩余倒计时分秒），告别信息盲区。
- **实时优先与严格时序（Strict Order）解耦**：
  - 实时消息独立优先派发，历史积压不挤占实时新消息响应；
  - 启用“严格时间顺序”的历史搬运，采用精细化路由锁机制，安全暂存同期产生的新消息，在历史追平后按消息 ID 严格升序发布，确保目标频道时间线完全对齐，且绝对不阻塞其他独立路由的实时转发。

#### 2. 内容广告清洗与过滤
- **文案广告清洗**：支持自动剥离正文中的 `http://` / `https://` 超链接、剔除 `@username` 提及，支持自定义正则表达式进行违禁词/广告词过滤与替换，并支持在文末自动追加自定义小尾巴签名。
- **媒体文件精准过滤**：支持设定最小与最大文件体积（过滤表情包琐碎文件或超大体积视频），支持按文件扩展名白名单（如 `mp4,mkv,zip`）或黑名单进行精确放行。

#### 3. 论坛主题对齐与断点恢复
- **论坛群组（Forum Topics）原生对齐**：支持自动识别并保留来源主题结构，自动将来源 Topic 映射或创建至目标群组的对应 Topic。
- **断点恢复与防重归档**：系统基于 SQLite 持久化游标，容器重启、断网或短时间离线后自动自愈并填补遗漏消息，且依靠全量历史归档指纹确保消息绝不重复发送。
- **快捷运维工具箱**：支持历史归档任务一键暂停/恢复/取消、历史错误记录一键批量重试、错误日志一键清空与状态统计。

### 本地整理

首页的“本地整理”处理已下载至本地存储的文件：
- 支持「移动 (Move)」、「硬链接 (Hardlink)」、「复制 (Copy)」三种模式，原文件完好保留做种刮削两不误。
- 路径整理支持年、年月、年月日三级目录，并可配置时区与聊天 ID 前缀。
- 启用前可在线实时预览目标路径，重复文件默认安全重命名。

### 历史文件时间回填

新下载的文件会自动把文件修改时间设置为 Telegram 消息发送时间；自动转存或重命名后也会再次校准。历史文件可在升级后执行：

```bash
docker compose exec telegram-files tfm file-time apply
```

命令只处理数据库中已完成、非缩略图且仍存在的文件，并在
`/app/data/maintenance-audits/` 生成 JSONL 审计文件。需要恢复原修改时间时执行：

```bash
docker compose exec telegram-files tfm file-time rollback file-time-YYYYMMDD-HHMMSS.jsonl
```

回滚只会处理仍在数据库中且当前时间仍等于本次回填目标时间的文件，避免覆盖之后被其他程序修改的文件。

### 恢复被误标为空闲的历史文件

如果旧版本把仍在磁盘上的已完成文件误标为“空闲”，请先升级到修复版本，并把故障前的 SQLite 备份放到 `DATA_DIR`。停止主服务后先只读扫描：

```bash
docker compose stop telegram-files
docker compose run --rm telegram-files tfm download-status scan data-before-recovery.db
```

扫描只会把以下记录列为可恢复：当前状态为 `idle`、备份状态为 `completed`、备份路径位于 `APP_ROOT` 内、实体文件仍存在，而且数据库与磁盘字节大小完全一致。确认 `recoverable` 数量后执行：

```bash
docker compose run --rm telegram-files tfm download-status apply data-before-recovery.db
docker compose up -d telegram-files
```

恢复只修改索引中的本地路径、下载状态、开始时间和完成时间，不会移动或重新下载文件；审计文件写入 `/app/data/maintenance-audits/`。

### 从源码构建

```bash
git clone https://github.com/Orson-Yan/telegram-files-cn.git
cd telegram-files-cn
docker build -t ghcr.io/orson-yan/telegram-files-cn:latest .
docker compose up -d
```

首次启动时，API 日志会输出一个有效期为 15 分钟的一次性引导代码。请从本机或同一私有局域网打开界面并创建首位管理员，再将服务暴露到公网。

## 安全说明

- 管理 API、文件预览和 WebSocket 都需要管理员会话。
- 新建、修改或重置管理员密码时至少需要 8 个字符；登录会兼容已有密码，不额外要求 8 位。
- 对公网提供服务时应启用 HTTPS，并正确配置 `HTTP_ALLOWED_ORIGINS`。
- 反向代理需要保留 `X-Real-IP`、`X-Forwarded-Host` 和 `X-Forwarded-Proto`；仓库附带的 Nginx 配置已经处理这些请求头。
- 本地密码恢复会撤销所有现有会话：

```bash
java -cp api/build/libs/telegram-files.jar telegram.files.Maintain admin reset-password owner
java -cp api/build/libs/telegram-files.jar telegram.files.Maintain admin apply-reset owner
```

更多配置项请查看 [`.env.example`](.env.example)。

## 与上游同步

本 fork 使用 [`.github/workflows/sync-upstream.yml`](.github/workflows/sync-upstream.yml) 每天自动获取并合并 `jarvis2f/telegram-files:main`，也可以在 GitHub Actions 中手动运行 **Upstream Sync**。

同步采用普通 Git merge，不会重置或强制覆盖中文提交：

1. 上游没有新提交时直接结束。
2. 可以自动合并时，将合并结果推送到本仓库 `main`。
3. 出现冲突时工作流失败且不会推送半成品，需要人工解决冲突。
4. 同步后的 `main` 会运行原项目 CI，并重新构建中文 Docker 镜像。

本地也可以手动同步：

```bash
git remote add upstream https://github.com/jarvis2f/telegram-files.git
git fetch upstream
git checkout main
git merge upstream/main
git push origin main
```

为减少冲突，中文翻译集中在独立目录 `web/src/i18n/`；对上游现有代码的修改仅限语言 Provider、切换入口、中文日期、用户内容保护以及 fork 部署地址。任何长期维护的 fork 都无法保证上游发生同区域改动时永不冲突，因此同步工作流会在冲突时安全停止，而不是覆盖代码。

## 开发与检查

要求：

- JDK 23
- Node.js 22
- npm
- Docker（容器构建时需要）

前端：

```bash
cd web
npm ci
npm run check
npm run build
```

后端：

```bash
cd api
./gradlew build
```

CI 会运行 ESLint、TypeScript 类型检查、单元测试、Playwright 端到端测试、Next.js 构建，以及后端 Gradle 测试。

## 汉化维护约定

- 英文是与上游对齐的源文案，中文通过独立字典映射。
- 新增或修改上游界面文案后，在 `web/src/i18n/messages.ts` 中补充对应翻译。
- 动态计数和状态文案使用集中规则处理。
- 用户生成内容使用 `translate="no"` 或 `data-i18n-skip` 标记保护。
- 提交前至少运行 `cd web && npm run check && npm run build`。

## 致谢与许可

核心项目由 [jarvis2f/telegram-files](https://github.com/jarvis2f/telegram-files) 提供。本 fork 增加中文界面、云端转发、本地整理及相关维护能力。

项目继续使用 [MIT License](LICENSE)。

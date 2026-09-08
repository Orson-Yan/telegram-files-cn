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
- 暂停、继续、取消下载，并按规则自动转存
- 图片和视频预览
- 文件搜索、筛选、标签和统计
- 自动预加载、自动下载和自动转存
- 云端转发：使用 Telegram 服务端复制或转发新消息，不先下载到本地
- 本地整理：按聊天、类型或消息日期将已下载文件移动到指定目录
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

`main` 分支更新后会构建 `main` 和 `latest` 镜像；正式 release 也会更新 `latest`。如需锁定版本，可在 `.env` 中设置 `IMAGE_TAG`。

### 云端转发与本地整理

首页的“云端转发”用于把来源聊天的新消息复制或转发到同一 Telegram 账号可访问的目标聊天。媒体传输发生在 Telegram 内部，不会先下载到 TG File 服务器。推荐使用“复制归档”模式，这样目标消息不显示原始转发来源；如果来源启用了内容保护，Telegram 仍会拒绝复制或转发。

历史任务支持限定条数或扫描全部历史，并先暂存扫描结果，再按消息从旧到新的顺序发送。任务可以暂停、恢复、取消和删除；数据库会记录已归档的来源消息，重复扫描不会重新复制已经处理过的消息。论坛群组之间还可以选择“保留来源主题”，系统会读取全部 Topic，在空白目标论坛群组中自动创建同名 Topic，并持久保存来源与目标 Topic 的映射。频道和普通群组不显示 Topic 设置。

首页的“本地整理”只处理已经下载到服务器的文件。它会移动文件并同步更新数据库中的本地路径和转存状态，不会因为整理而重新下载。按消息日期整理支持年、年月、年月日三级目录，并可设置时区和聊天 ID 目录。启用历史整理前可以先预览目标路径，重复文件默认安全重命名。

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

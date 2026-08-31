# Telegram Files 中文增强版

这是 [jarvis2f/telegram-files](https://github.com/jarvis2f/telegram-files) 的简体中文增强 fork。项目保留完整英文界面，在不改变后端协议和核心业务逻辑的前提下增加简体中文、本地语言记忆、中文日期显示，以及面向本 fork 的镜像和上游同步流程。

> 本仓库只维护中文增强内容；原项目功能、问题和路线图请同时参考[上游仓库](https://github.com/jarvis2f/telegram-files)。

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
- 自动回收 TDLib 已停止但数据库仍为下载中的僵尸任务
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

核心项目由 [jarvis2f/telegram-files](https://github.com/jarvis2f/telegram-files) 提供。本 fork 仅增加中文界面及相关维护能力。

项目继续使用 [MIT License](LICENSE)。

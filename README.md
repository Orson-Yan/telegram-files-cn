# Telegram Files 中文增强版 (v1.0.0 正式版)

<p align="center">
  <img src="https://raw.githubusercontent.com/jarvis2f/telegram-files/main/web/public/logo.png" alt="Telegram Files" width="120" />
</p>

<p align="center">
  <b>面向 Telegram 的全时序媒体归档、全自动下载流水线与 NAS 影音协作中心</b>
</p>

<p align="center">
  <a href="https://github.com/Orson-Yan/telegram-files-cn/releases/tag/v1.0.0"><img src="https://img.shields.io/badge/Release-v1.0.0-emerald.svg" alt="v1.0.0" /></a>
  <a href="https://github.com/Orson-Yan/telegram-files-cn/pkgs/container/telegram-files-cn"><img src="https://img.shields.io/badge/Docker-ghcr.io-blue.svg?logo=docker" alt="Docker" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License" /></a>
</p>

这是 [jarvis2f/telegram-files](https://github.com/jarvis2f/telegram-files) 的**简体中文全功能增强旗舰版**。**v1.0.0** 标志着系统从单纯的“文件下载器”正式跃升为**“云端无损备份 + 自动化下载归档 + 全时序媒体相册画廊 + NAS 存储联动”**的现代化数字资产管理平台。

---

## 🌟 v1.0.0 核心三大旗舰模块

### 1. 🖼️ 全时序相册画廊与 NAS 本地联动 (Files & Gallery Hub)
彻底解决 Telegram 资源前后图片、短视频与说明文案被打碎错乱、无法连续观看的痛点：
- **消息一体化相册聚合 (Post & Album View)**：系统解析 Telegram 数据库底层的 `message_id`、`media_album_id` 与原生发送时间戳 `date`，将同一帖的多张图片、分卷视频和文字说明（Caption）自动聚合为一个完整的动态卡片，原汁原味还原频道原帖。
- **严格时序不打乱**：强制以 Telegram 原生发送时间线排序，绝不按多线程下载完成时间打乱顺序。
- **沉浸式连续播放 (Seamless Lightbox)**：内置支持键盘 `←` / `→` 键无缝跨文件、跨媒体连续翻页看图看片；支持调用外部播放器 (PotPlayer / VLC / IINA) 及复制直链串流。
- **网盘式左侧分类树 (Sidebar)**：内置快捷入口（全部文件、**已下载至本地/NAS**、视频专区、图片相册），并列出全部频道资源树，告别下拉菜单频繁翻找。
- **一键批量归档至 NAS**：勾选任意文件即可一键触发「归档到 NAS / 本地目录」，支持**硬链接 (Hardlink，不占额外空间保留原文件做种)**、移动 (Move) 或复制 (Copy)。
- **双视图随心切换**：右上角提供「☷ 虚拟滚动表格」与「▦ 消息相册画廊」一键平滑切换，自动持久化记忆用户偏好。

---

### 2. ⚡ 云端无损极速转发 (Cloud Archive)
基于 Telegram 服务端跨频道/群组极速转存，**不消耗服务器本地下行与上行带宽，大文件秒级转存**：
- **拟人化防封流控**：
  - **单日转发配额**：提供推荐档位（防封推荐: 200 条/天、稳健: 500 条/天、极速: 1000 条/天、不限），支持任务自动智能挂起与次日凌晨零开销自愈唤醒。
  - **随机微抖动 (Jitter Delay)**：单条消息自动增加拟人化毫秒随机延迟，彻底打破机械化固定时间间隔，大幅降低反滥用识别概率。
  - **FloodWait 透明保护横幅**：遭遇官方速率限制时，界面顶部自动弹出解冻倒计时横幅，状态一目了然。
- **严格时序保障 (Strict Order)**：支持历史追平机制与路由隔离锁，确保目标频道时间线 100% 对齐。
- **广告与杂质清洗**：自动剔除超链接、去掉 `@username` 提及，支持自定义正则表达式违禁词替换与文末自定义小尾巴签名。
- **论坛主题映射 (Forum Topics)**：自动对齐并保留来源频道/群组的主题论坛结构。

---

### 3. 🤖 频道全自动流水线 (Channel Automations)
7×24 小时无人值守监听频道、过滤并自动下载与整理：
- **可视化扩展名过滤**：直观提供放行白名单（如 `mp4, mkv, pdf`）与排除黑名单（如 `apk, exe`），免去手写复杂表达式。
- **最小/最大体积保护**：精准过滤轻量垃圾表情包与过大光盘镜像。
- **磁盘防爆盘水位保护**：实时探测本地/挂载盘可用空间，低于 5GB 安全水位时自动暂停拉取与下载，确保服务器永不宕机。
- **下载后自动整理归档**：下载完成后自动按分类策略转移至指定存储目录，并支持一键开启“同时批量整理历史已下载存量文件”。

---

## 🚀 快速部署 (Docker Compose)

### 1. 准备配置文件

创建 `docker-compose.yaml`：

```yaml
services:
  telegram-files:
    image: ghcr.io/orson-yan/telegram-files-cn:1.0.0
    container_name: telegram-files
    restart: unless-stopped
    ports:
      - "8080:80"
    environment:
      - TELEGRAM_API_ID=你的_API_ID
      - TELEGRAM_API_HASH=你的_API_HASH
      - HTTP_ALLOWED_ORIGINS=http://localhost:8080
    volumes:
      - ./data:/app/data
      - /your/nas/media:/nas/media  # 挂载 NAS 或本地媒体库目录用于归档
```

> **提示**：`TELEGRAM_API_ID` 与 `TELEGRAM_API_HASH` 可在 [my.telegram.org/apps](https://my.telegram.org/apps) 免费申请。

### 2. 启动服务

```bash
docker compose up -d
```

启动后访问 `http://服务器IP:8080` 即可进入系统。首次使用时根据界面引导绑定 Telegram 账号即可立即享受全套能力。

---

## 📦 镜像版本说明

本项目提供预构建的多架构 Docker 镜像（支持 `linux/amd64` 与 `linux/arm64`）：

- **稳定发布版**：`ghcr.io/orson-yan/telegram-files-cn:1.0.0`
- **最新版**：`ghcr.io/orson-yan/telegram-files-cn:latest`

---

## 🛠️ 本地开发与构建

- **运行要求**：JDK 23、Node.js 22、npm、Docker
- **前端开发与类型检查**：
  ```bash
  cd web
  npm install
  npm run typecheck    # 验证 TypeScript 类型
  npm run test:unit    # 运行单元测试
  npm run build        # 编译 Next.js 生产版本
  ```
- **后端编译**：
  ```bash
  cd api
  ./gradlew shadowJar  # 编译 API 打包 Jar
  ```

---

## 📄 开源许可与致谢

- 核心架构衍生自优秀的开源项目 [jarvis2f/telegram-files](https://github.com/jarvis2f/telegram-files)。
- 本项目遵循 [MIT License](LICENSE) 开源协议。欢迎提交 Issue 与 Pull Request 共同完善！


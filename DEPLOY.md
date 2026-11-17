# 部署指南（Docker Compose）

把 Atoms Demo 部署到一台 Linux 云服务器，前端（Nginx 静态站点）、后端（Spring Boot）、MySQL 三个容器一次拉起。本指南先跑通「公网 IP + HTTP」，HTTPS 见文末升级步骤。

## 架构

```
浏览器 ──> 80 端口 (web: Nginx)
              ├── /            前端静态资源 (React 构建产物)
              ├── /api/*       反代 → api:8080 (Spring Boot, 含 SSE 流式)
              └── /actuator/*  反代 → api:8080 (健康检查)
api ──> mysql:3306 (Flyway 启动时自动建表)
```

- 前端所有请求都是相对路径 `/api/...`，本地靠 Vite 代理、生产靠 Nginx 反代，代码零改动。
- 后端在容器内绑定 `0.0.0.0:8080`，只通过 compose 内部网络暴露给 Nginx，不直接对公网开放 8080。
- 数据库数据落在命名卷 `mysql_data`，容器重建不丢数据。

## 前置条件

- 一台 Linux 云服务器（Ubuntu 22.04 / Debian 12 / CentOS 等均可），已开放 **80 端口**（后续 HTTPS 再加 443）。
- 已安装 Docker Engine 与 Docker Compose v2（`docker compose version` 可查）。安装参考：<https://docs.docker.com/engine/install/>。
- 本机已把项目推到 Git 仓库（或直接把项目目录上传到服务器）。服务器上不需要 JDK/Node——都在构建镜像里。

## 部署步骤

### 1. 上传项目

```bash
# 方式 A：Git（推荐）
git clone <你的仓库地址> atoms-demo && cd atoms-demo

# 方式 B：已有代码目录，直接 cd 进去
cd atoms-demo
```

### 2. 准备环境变量

```bash
cp .env.docker.example .env.docker
vi .env.docker   # 填 MySQL 密码、ATOMS_ALLOWED_ORIGINS、模型密钥
```

必填项：

| 变量 | 说明 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | 数据库 root 与应用账号密码（改掉示例值） |
| `ATOMS_ALLOWED_ORIGINS` | 用 `http://<你的公网IP>` 替换 |
| `ATOMS_QWEN_API_KEY` + `ATOMS_QWEN_TEXT_MODEL` | 网页/游戏生成；留空则生成功能关闭（草稿/模板/幻灯片不受影响） |
| `ATOMS_ARK_API_KEY` + `ATOMS_ARK_VIDEO_MODEL` | 视频生成；留空则视频生成关闭 |

### 3. 构建并启动

```bash
docker compose --env-file .env.docker up -d --build
```

首次会拉取基础镜像并编译（后端 Maven、前端 npm），耗时取决于网络，几分钟到十几分钟不等。

### 4. 验证

```bash
# 三个容器都应 Up（healthy）
docker compose --env-file .env.docker ps

# 健康检查（后端应返回 {"status":"UP"}）
curl http://127.0.0.1/actuator/health

# 模型/供应商状态（如实报告未开通/未购买）
curl http://127.0.0.1/api/system/provider
```

浏览器打开 `http://<你的公网IP>`，应看到创作工作台首页。可以：保存草稿 → 项目页 → 生成（若模型密钥已配且已开通）。

### 5. 查看日志 / 停止

```bash
docker compose --env-file .env.docker logs -f api     # 后端日志
docker compose --env-file .env.docker logs -f web     # Nginx 日志
docker compose --env-file .env.docker down            # 停止（保留数据卷）
docker compose --env-file .env.docker down -v         # 停止并删除数据（会清空数据库！）
```

## 升级到 HTTPS（有域名后）

1. 域名解析到服务器公网 IP，开放 443 端口。
2. 改 `.env.docker`：
   - `ATOMS_ALLOWED_ORIGINS=https://<你的域名>`
   - `ATOMS_COOKIE_SECURE=true`
3. 用 Caddy 或 Nginx 挂证书做 443→80 反代（或用 `caddy` 容器自动 Let's Encrypt）：

```yaml
# 追加到 docker-compose.yml 一个 caddy 服务（示意）
  caddy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config
```

`Caddyfile` 内容（把 80 的 web 服务端口改由 caddy 转发）：

```
你的域名 {
    reverse_proxy web:80
}
```

更简单的做法是把证书证书文件挂进 web 容器并在 `nginx.conf` 增加 443 server 块——具体取决于你的证书来源，二选一即可。

## 常见问题

- **`api` 起不来 / 连不上数据库**：`docker compose logs api` 看 Flyway 或 JDBC 报错；确认 `.env.docker` 里 `ATOMS_DB_URL` 的 host 是 `mysql`（compose 服务名），不是 `127.0.0.1`。
- **首次启动 MySQL 很慢**：`mysql:8.4` 首次初始化 + 健康检查有 `start_period: 30s`，耐心等待，`api` 会等 `mysql` healthy 后才启动。
- **网页能开但生成报「尚未开通/购买」**：这是模型账号侧问题，不是部署问题。到千问/方舟控制台开通或充值后，回项目页重新生成即可，无需改代码。
- **改代码后要重新部署**：`docker compose --env-file .env.docker up -d --build api web`。
- **端口 80 被占用**：改 `docker-compose.yml` 里 `web` 的 `ports` 为 `"8080:80"` 之类，再用公网 IP+端口访问（记得同步 `ATOMS_ALLOWED_ORIGINS`）。

## 安全注意

- `.env.docker` 含密钥，`.gitignore` 已忽略（仅 `.env.docker.example` 提交）。不要把真实密钥提交到代码库。
- 默认只暴露 80（HTTP）。上线前务必升级 HTTPS 并设 `ATOMS_COOKIE_SECURE=true`。
- 所有访客共享 `ATOMS_BUDGET_LIMIT_CNY` 模型预算（默认 ¥300），首次付费调用前会先预留，防止超支。

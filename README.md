# Atoms Demo

参考 Atoms 核心体验的免登录创作工作台。技术方向为 React + TypeScript、Java 21 / Spring Boot、MySQL 8.4；用 Trellis 管理需求、设计、开发和验收。

**当前代码支持：草稿 → 生成 → 版本 → 预览，以及网页/游戏的自然语言修改。** 网页和游戏调用千问文本模型，网页可选配图；幻灯片使用本地排版引擎，视频走火山方舟任务。四类项目均有项目页生成入口。模型未开通时会显示真实失败原因，不能把入口开放视为模型账户已经可用。

项目页现已支持四类作品的版本历史、指定版本预览和恢复；网页与游戏还支持按版本下载源码 ZIP。恢复会新建版本、保留全部历史，不调用模型。

## 文档入口

- [产品需求 v3.0](./Atoms-Demo-产品需求文档.md)：范围、页面、交互和 R01–R28 / T01–T28。
- [实现设计 v3.0](./Atoms-Demo-实现设计文档.md)：服务、数据、任务、接口、预算与部署。
- [文档核查报告](./Atoms-Demo-文档核查报告.md)：历史观察、修订闭环和仍需执行的验收。
- [项目约定](./.trellis/spec/project-contract.md)：后续开发必须遵守的范围与边界。
- [部署指南](./DEPLOY.md)：Docker Compose 一键部署到 Linux 云服务器（前端 Nginx + 后端 Spring Boot + MySQL）。

## Trellis 使用

当前目录已使用官方 `@mindfoldhq/trellis@0.6.17` 初始化，不需要再次运行 `trellis init`。当前本地开发者为 `admin`，可直接在项目根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trellis.ps1 tasks
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trellis.ps1 doctor
```

重新取得项目后，先准备本地 Python 和开发者身份：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-trellis.ps1 -Developer your-name
```

该脚本下载并校验固定版本的 Windows x64 Python，保存在项目 `.tools/`，下载缓存在 `.cache/`；不会修改系统 Python。已有本地身份不会被覆盖。当前系统 Python 3.8 不满足 Trellis 要求，项目命令统一使用 `.tools/python/python.exe`。

查看或切换工作任务：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trellis.ps1 current
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trellis.ps1 start 11-12-s1-web-workbench
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trellis.ps1 status
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trellis.ps1 validate 11-12-s1-web-workbench
```

每个复杂任务已有 `prd.md`、`design.md`、`implement.md` 及实现/检查上下文。开始具体模块时，在阶段任务下继续拆分可单独验收的子任务。日记自动提交已关闭，当前改动保留供审阅；本轮未创建 Git 提交。

Codex 的项目入口是 `AGENTS.md`，相关协作配置位于 `.agents/`、`.codex/`。自动上下文注入是否生效取决于客户端设置；上述命令不依赖自动注入，也没有修改全局设置。

## 交付路线

| 任务 | 交付内容 |
| --- | --- |
| [整体交付](./.trellis/tasks/11-12-atoms-demo-v3/prd.md) | 所有阶段与最终 R01–R28 验收 |
| [S1 网页工作台](./.trellis/tasks/11-12-s1-web-workbench/prd.md) | 匿名项目、真实网页/游戏生成、预览、修改、恢复、发布 |
| [S2 编辑与协作](./.trellis/tasks/11-12-s2-agents-data/prd.md) | 八角色、应用数据、源码/元素编辑、模板和资源 |
| [S3 幻灯片](./.trellis/tasks/11-12-s3-slides/prd.md) | 大纲、逐页修改、演示、可编辑 PPTX 和 PDF |
| [S4 视频与综合验收](./.trellis/tasks/11-12-s4-video-acceptance/prd.md) | 文生/图生视频、持久化和全类型公网验收 |

前后端基础、MySQL 迁移、匿名身份与项目草稿管理已有实现。模型未接通时明确显示调用失败原因，不能用固定页面或计时进度冒充生成。平台与生成应用均不引入账号登录。

[工程基础验收记录](./.trellis/tasks/archive/2026-11/11-12-s1-foundation/acceptance.md)包含最初的构建和截图；[匿名项目验收记录](./.trellis/tasks/11-12-s1-anonymous-projects/implement.md)记录数据库、接口与浏览器检查。该子任务仍待补齐后端重启验收，S1–S4 均未整体完成。幻灯片工作台的实现与验收记录见 [S1 幻灯片工作台](./.trellis/tasks/11-15-s1-slides-workbench/implement.md)；视频工作台见 [S4 视频工作台](./.trellis/tasks/11-16-s4-video-workbench/implement.md)。

## 运行当前工程

首页支持网页、游戏、幻灯片、视频四种类型。保存草稿或克隆模板后，可在项目页生成；已有网页和游戏可输入修改说明，基于当前作品创建下一版本。项目列表和详情支持刷新、重命名、确认删除。

### 查看历史与下载作品

项目页的“版本历史”按新到旧显示已保存版本。点击“预览 vN”只查看该版内容；点击“恢复此版本…”并确认，会创建新的当前版本。例如 v2 恢复 v1 后得到 v3，v1/v2 均保留。生成任务进行中或当前版本已被另一页面更新时，恢复会明确提示冲突。

网页和游戏各版本提供“下载源码 ZIP”，包含该版本的实际文件，可解压打开入口 HTML。当前单文件作品无需安装依赖。下载不产生新版本或模型费用；单次最多 100 个文件、20 MiB 原始内容。源码编辑器与公开发布仍在后续范围。

### 项目页生成与修改

- 网页、游戏：点击“开始生成”；已有版本时可填写修改说明，例如“飞船移动速度减半，改成蓝色背景”，点击提交修改。后端把当前源码和说明交给真实模型，成功后保存新版本。
- 修改前会检查基础版本；另一页面已经更新时，保留输入并提示先读取最新版本。失败和取消不会把旧作品替换为失败产物。
- 幻灯片：项目页直接启动本地排版，模板项目沿用模板主题；模型大纲规划、单页编辑和 PPTX/PDF 导出仍属于后续阶段。
- 视频：项目页选择模型、画幅、清晰度和时长，再提交生成；模型未开通时如实报错。视频逐帧编辑和图生视频上传尚未接入。
- 对话页展示本次修改说明、基础版本和真实过程；网页/游戏完成后可继续提交下一轮修改。

后端实现与前端入口的检查记录归入
[项目生成与修改任务](./.trellis/tasks/11-16-s1-project-generation/implement.md)。
当前费用按配置金额预留，不能代替供应商账单；真实生成验收与本地模型桩测试需分别记录。

需要 Node 22.12+（或符合清单要求的 Node 20）和 JDK 21。当前机器 Node 位于 `C:\nvm4w\nodejs`，JDK 位于 `D:\Program Files\Java\jdk-21.0.11`。换机器时使用实际安装位置。首次安装依赖需要联网。

先确保 MySQL 可连接。本机已配置容器 `mysql8`，端口 `3310`，实测版本为 **8.0.36**；正式部署目标 8.4 的验证仍待执行。开发库 `atoms_demo_dev` 和测试库 `atoms_demo_test` 使用独立账号，连接信息保存在已忽略的 `.env`。

新环境可以按 [配置示例](./.env.example)填写 `.env`。若使用含 `MYSQL_ROOT_PASSWORD` 的本地 MySQL 容器、且项目库和账号尚不存在，可运行一次：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-database.ps1 -Container mysql8 -Port 3310
```

已有 `.env` 时该命令不修改数据库；项目库或账号重名时停止，不能用于覆盖现有库。此脚本不负责启动 Docker 或 MySQL。`ATOMS_COOKIE_SECURE=false` 仅用于本地 HTTP，公开部署使用 HTTPS 和默认安全 Cookie。

在项目根目录打开两个 PowerShell 窗口：

**窗口一：后端**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/api.ps1 -Action run -JavaHome 'D:\Program Files\Java\jdk-21.0.11'
```

**窗口二：前端**

```powershell
$env:Path = 'C:\nvm4w\nodejs;' + $env:Path
Set-Location apps/web
npm ci --cache ../../.cache/npm
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`，后端监听 `127.0.0.1:8080`。开发服务仅绑定本机，分别用 Ctrl+C 停止。当前需要可连接的 MySQL；草稿与幻灯片不需要模型密钥，网页生成需要 `ATOMS_QWEN_TEXT_MODEL` 指向一个已开通的千问文本模型，视频生成需要已开通的方舟视频模型。数据库未启动时后端启动失败，前端会显示连接异常。

前端在 `apps/web` 下运行 `npm run build`（含类型检查）。后端从根目录验证：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/api.ps1 -Action verify -JavaHome 'D:\Program Files\Java\jdk-21.0.11'
```

后端测试使用真实、独立的 `atoms_demo_test` 库，不能指向业务库。依赖版本固定于清单、锁文件及 Maven Wrapper；启动和这些检查均不会调用模型或产生模型费用。

## 网页模板

首页「网页」在输入框下面多了模板画廊，四套自研单文件网页：极简个人作品集（作品集）、SaaS 产品落地页（营销）、活动报名页（活动）、产品文档站（内容）。卡片封面就是把真实页面按 1280×800 渲染后等比缩小的 iframe，不是截图；鼠标停留 140ms 后换成带 `?peek=1` 的同一份页面，页面自己缓慢上下巡游，移开即销毁。

- **真实可交互**：点卡片进弹窗后可以在「桌面 / 手机」两种视口间切换，页面里的筛选、价格切换、倒计时、表单校验、深浅主题都能真的操作。
- **使用模板**：把当前版本的完整文件复制成一个独立项目（`kind=TEMPLATE`），直接跳到项目详情页，那里有一次性令牌的沙箱预览。模板之后更新不会改动已有项目。
- **单文件约束**：每套模板只有一个 `index.html`，样式与脚本内联、系统字体、无外链资源。预览响应带 `default-src 'none'; script-src 'unsafe-inline'; connect-src 'none'; frame-ancestors 'self'`，因此页面不能发请求、不能读工作台数据。
- **如实说明**：网页生成流水线已接入（见下一节），模板仍然是更好玩的起点；两者互不影响，模板是真实可运行的页面，不是生成结果的占位。

模板源在 `apps/api/src/main/resources/template-seed/web-*/`，`manifest.json` 声明 `"type": "WEB"`、`entry: index.html` 和文件清单，重启后端即按内容哈希播种（改一个模板会发布新的不可变版本，例如作品集当前为 v2）。

## 网页生成（真实模型）

保存一个网页草稿后，项目详情页的「开始生成网页」是可点的。点下去会新建一个 `WEB` run 并跳到对话页，五步全部是服务端真实执行的动作，不是动画：

1. **需求解析**：本地按标点切分需求，提取关注点（不臆造内容）。
2. **页面结构**：第 1 次千问 chat 调用，只允许返回 JSON（标题 / 定位 / 主按钮 / 3–6 个区块及要点）。
3. **页面编写**：第 2 次 chat 调用，把结构写成单文件 `index.html`（内联样式与脚本、移动端自适应、至少一处原生 JS 交互）。
4. **配图生成**（可选）：若配置了千问图片模型且已购买，用 `qwen-image-3.0` 生成一张主视觉并转 base64 内嵌；未购买则降级为无配图版式，事件里如实说明。
5. **页面校验**：`WebHtml` 检查必须是完整 HTML 文档、体积 400B–600KB、**不含任何外链资源**（`src|href|action|url()` 里出现 `//` 直接拒绝），不合格就以 `PROVIDER_OUTPUT_REJECTED` 失败，不写版本。
6. **版本入库**：写入不可变 `GENERATED` 版本，emit 产物事件（区块数 / 体积 / 版本 / tokens / 是否有配图），预览走同一条沙箱令牌链路。

配置在 `.env`：

```
ATOMS_QWEN_API_KEY=...                             # 千问网关密钥，服务端专用
ATOMS_QWEN_BASE_URL=https://cn-beijing.maas.qianwenaiapi.com/api/v1
ATOMS_QWEN_TEXT_MODEL=qwen-plus                    # 留空即关闭网页生成，不会猜模型 id
ATOMS_QWEN_TEXT_MODEL_NAME=千问 Qwen-Plus           # 仅用于界面显示
ATOMS_QWEN_IMAGE_MODEL=qwen-image-3.0              # 配图模型；未购买则自动降级
```

`GET /api/system/provider` 会额外返回 `textModel / textState / textDetail` 与 `imageModel / imageState / imageDetail`，探测用空 `messages`（文本）或空内容（图片）触发网关校验，不产生 token 与图片。**账号没开通/没购买模型时，生成会如实失败**：项目页按钮旁直接显示「尚未开通或购买，生成会失败」，对话页给出 `PROVIDER_MODEL_NOT_ACTIVATED` 与中文原因；开通后无需改代码，重新生成即可。

接口：`POST /api/projects/{id}/runs`（WEB 项目返回 202）、`GET /api/projects/{id}/runs/{runId}`、`GET .../events`（SSE 回放 + 跟随）、`POST .../cancel`。

## 游戏示例模板与克隆

首页切到「游戏」可以看到示例画廊：分类筛选、封面卡片、点击试玩，以及「基于这个游戏构建」把模板复制成独立项目。当前有四个平台自研示例（星海防线、霓虹疾驰、云端跳跃、霓虹方块），全部是单文件 canvas 游戏，不引用外部素材，也不复制 Atoms 上的第三方作品。

- 试玩弹窗用 `<iframe sandbox="allow-scripts allow-pointer-lock">` 加载：没有 `allow-same-origin`，游戏运行在 opaque origin 中，拿不到工作台的 Cookie 和存储；预览响应带 `default-src 'none'; connect-src 'none'; frame-ancestors 'self'` 的 CSP。
- 模板响应是公开只读资源；克隆要求匿名会话、Origin 与 CSRF，幂等键保证重复提交只创建一个项目。
- 克隆会在同一事务里写入 `project_versions`（kind=`TEMPLATE`）并把 `template_files` 逐字节复制到 `project_files`，所以模板后续更新不会改动已有项目。
- 项目预览不靠 Cookie，而是 `POST /api/projects/{id}/preview-sessions` 换一个 30 分钟、单项目有效的随机令牌，避免沙箱 iframe 携带会话凭证。

接口：`GET /api/templates?type=GAME`、`GET /api/templates/{slug}`、`POST /api/templates/{slug}/clone`、`GET /api/template-previews/{slug}/{path}`、`POST /api/projects/{id}/preview-sessions`、`GET /api/previews/{token}/{path}`。

模板源文件在 `apps/api/src/main/resources/template-seed/<slug>/`，由 `manifest.json` 声明元信息、入口和文件清单。后端启动时按内容哈希播种：内容没变就跳过，变了就发布新的不可变版本（`version_no` 递增）。新增示例的步骤是建目录、写 `manifest.json`（含 `files` 列表）、重启后端；不需要写 SQL。`ATOMS_PREVIEW_TTL` 可调整预览令牌有效期，默认 `PT30M`。

游戏部分只覆盖「可试玩的示例」；R20 要求的待办工具与看板类 WEB 模板仍未提供，会在后续补齐（本轮交付的四套网页模板不在其列）。

## 幻灯片工作台与生成流水线

首页切到「幻灯片」是一个完整闭环：选模板或导入文件 → 写需求 → 生成过程逐页可见、可停止 → 完成后预览整套演示。

- **模板画廊**：四套自研模板（商务季度复盘、新品发布营销方案、极简作品集、技术愿景发布）。鼠标停留约 140ms 后，封面换成真实幻灯片的自动翻页预览（同一个沙箱 iframe，`?peek=1`），离开即销毁，静止时不产生任何 iframe 开销。
- **导入**：`.pptx` 直接读文字大纲（`ppt/slides/slideN.xml` 的 `<a:t>`），`.pdf` 目前只保存原文件并计页数，上限 8MB。
- **引用模板**：点「使用模板」把模板放进输入框上方的引用 chip，并预填一段可编辑的示例需求；未改动直接用模板命名项目，改动后以访客的话为准。
- **生成页**（`#chat/<id>`）：Mike 解析 → Alex 架构 → Emma 视觉 → Iris 逐页排版 → Sarah 校验 → David 入库，每步都是服务端真实执行后才写入事件，SSE 回放后继续跟随；随时「停止」，停止后不写入版本。
- **产物与预览**：完成后出现产物卡（页数、体积、版本号）与右侧预览面板，缩略图栏可跳页，可全屏。产物是 `project_versions` 的 `GENERATED` 版本，与模板克隆共用同一套版本/预览链路。

生成内容是**本地排版引擎**的结果：只重组访客提供的文字（提示词或导入大纲），不虚构事实、不生成配图，页面与版本说明都如实标注。模型规划、逐页编辑、PPTX/PDF 导出尚未实现。

接口：`POST /api/templates/imports`、`GET /api/templates/imports`、`POST /api/projects/{id}/runs`、`GET /api/projects/{id}/runs/latest`、`GET /api/projects/{id}/runs/{runId}/events`（SSE）、`POST /api/projects/{id}/runs/{runId}/cancel`。

幻灯片模板与游戏模板同源：`template-seed/<slug>/` 下只放 `deck.json`，`manifest.json` 声明 `"engine": "slide-deck"`，`index.html` 由渲染器在播种时生成。因此改动共享引擎会让模板按内容哈希发布新版本，已克隆项目仍停留在旧版本。

## 视频工作台与生成流水线

首页切到「视频」是一条和幻灯片同构的闭环：写画面描述、挑模型与规格 → 生成过程可见可停止 → 完成后在对话页播放成片。底色是一段本地托管的动态视频（`apps/web/public/videos/hero-racetrack.mp4`），不是静态图。

- **输入卡**：模型菜单、规格弹层（6 种画幅 + 智能画幅、480p/720p/1080p、4–30 秒滑杆），提交后先建项目再入队一次生成任务，随即跳到 `#chat/<id>`。
- **模型目录由服务端决定**：`atoms.provider.video-models` 每项形如 `key|名称|说明|模型编号|角标`。没有编号的项会显示「未接入」且不可选，不会偷偷指向一个猜出来的模型名。当前账号只开通了 `doubao-seedance-2-0-260128` 之外的 3D 模型，因此视频模型仍为 `ModelNotOpen`。
- **视频示例**：两张本地示例卡片，hover 自动播放并浮出「使用提示词」，点击把该片真实使用的提示词填回输入框。
- **生成页**：Mike 需求解析 → Alex 分镜脚本 → Emma 提示词编译 → Iris 任务提交 → Sarah 模型生成 → David 下载入库。分镜只在标点处切分访客原话，提示词只追加技术参数，不改写内容。
- **停止**：轮询期间检查停止标记；停止请求与最终版本提交互斥。取消本地任务不代表供应商一定已取消或免收费用。
- **预算**：调用前在 `provider_spend` 预留（默认视频 ¥6/条、文本任务 ¥1/次），通过全局数据库锁避免并发超额预留。请求一旦尝试提交，失败或取消仍保留占用，只有未提交的请求才能释放；成功按预留额登记结算。固定预留金额不是核实后的供应商报价，尚需费用对账，不能据此保证实际账单恰好受 ¥300 限制。
- **产物**：成片落到 `project_versions` 的 `GENERATED` 版本（入口 `video.mp4`），预览接口对 `video/*` 支持 `Range`，播放器可拖动进度条；对话页右侧面板与全屏预览共用同一个一次性令牌。
- **失败如实上报**：模型未开通、鉴权失败、限流、超时、下载超限都映射成固定错误码写进 `runs.error_code`，并在对话页显示原因；当前未开通时会提示“开通后直接重试即可”，不会伪造一段视频。

接口：`GET /api/video/options`、`POST /api/projects/{id}/runs`（VIDEO 类型带 `model`/`ratio`/`resolution`/`durationSeconds`）、`POST /api/projects/{id}/preview-sessions` + `GET /api/previews/{token}/video.mp4`。

## 检查与外部条件

`scripts/trellis.ps1 doctor` 检查文档版本、28 项编号映射、项目内引用、任务关系与上下文文件；原调研机器上的历史截图缺失只提示，不阻止新机器使用。它不替代应用运行和验收测试。应用检查及启动方式随工程一起记录，验收结果保存在相应 Trellis 任务中。

开始真实生成前需准备模型服务、服务端密钥与经核实的计费配置；公开验收另需服务器及域名。所有访客共用 ¥300 模型调用上限，首次付费调用前必须实现费用预留，服务器和域名不计入该预算。

## 视频模型接入（火山方舟）

视频生成使用火山方舟（豆包 Seedance）异步任务接口，密钥只存在于服务端 `.env`，不会进入浏览器、生成源码、导出文件或日志。配置项见 [.env.example](./.env.example)：`ATOMS_ARK_API_KEY`、`ATOMS_ARK_BASE_URL`、`ATOMS_ARK_VIDEO_MODEL`、`ATOMS_VIDEO_*`（默认 5 秒 / 720p / 16:9 / 无音轨）。`scripts/api.ps1` 已把上述变量加入放行清单，并固定本地 API 端口为 8080，避免宿主环境注入的 `SERVER_PORT` 覆盖配置。

模型必须在[火山方舟控制台](https://console.volcengine.com/ark)对当前账号开通后才能真正生成；未开通时接口返回 `ModelNotOpen`。可用以下只读接口确认状态，它在模型未开通、密钥无效或网络不可达时都会如实返回，不产生计费任务：

```powershell
curl http://127.0.0.1:8080/api/system/provider
```

`state` 为 `ACTIVATED` 才表示可以生成；`NOT_ACTIVATED` 表示账号未开通，`NOT_CONFIGURED` 表示服务端缺少密钥或模型编号。注意 `doubao-seed3d-2-0-260328` 是图生 3D（obj/glb），不是视频模型。

`.tools/`、`.cache/`、依赖、构建产物和真实 `.env` 均已忽略。不要将密钥、访客凭证或生成项目的私有资料提交到代码库。

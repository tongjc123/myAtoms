# Atoms Demo 实现设计文档

版本：3.0｜修订日期：2026-11-12｜对应：[产品需求文档](./Atoms-Demo-产品需求文档.md)

## 1. 设计基线与状态

本设计落实用户批准的四阶段计划及 R01–R28。React + TypeScript 工作台、Java/Spring Boot 业务服务、MySQL 数据库保持不变；新增八角色编排、应用数据服务、源码/可视化编辑、文档处理和视频任务。平台与生成应用均免登录，所有访客共享部署级 ¥300 模型预算。

以下架构与契约均为 [D] Demo 设计，不是 Atoms 内部架构。本站实测、帮助中心与用户决定分别使用产品文档 [O]/[H]/[U] 索引。当前交付是设计和开发工作流基线；代码、运行结果和公网验收须独立记录，不能因任务文件存在便认定实现完成。

**实现进度补注（2026-11-16）**：当前工程已存在匿名项目、模板/版本/沙箱预览及生成任务代码。
历史版本列表、指定版本预览、四类作品恢复及网页/游戏源码 ZIP 下载已有实现；恢复新建 `RESTORE` 版本并记录父版本与恢复来源。接口、并发锁顺序及限制见 [版本契约](./.trellis/spec/backend/version-history-contract.md)。源码编辑草稿、公开发布与多文件构建仍是后续目标。
项目创建先保存草稿，返回 `{project}`；启动生成使用 `POST /api/projects/{id}/runs`，返回 `{runId,status}`。
当前网页/游戏修改以 `instruction + baseVersionId` 提交，四类项目入口与提交保护见
[可执行契约](./.trellis/spec/backend/project-generation-contract.md)。下文包含完整目标架构，
并非所有接口都已实现。当前媒体导出、完整多智能体、应用数据库和公网发布仍待后续交付。
预算已尝试提交后保留未知费用占用，但固定预留还不等于精确账单结算；完整 R26 验收仍需价格核验和对账。

本轮不做定时任务产品入口、独立联网深度研究、模型赛马、账号/会员、支付订阅、实际广告投放和原生移动应用。内部任务轮询与租约心跳是实现机制，不属于用户排除的定时任务功能。

## 2. 工程结构与服务边界

| 单元 | 选型与责任 | 约束 |
| --- | --- | --- |
| `apps/web` | React、TypeScript、Vite；路由、对话、编辑器、预览和资源界面 | 浏览器无模型密钥；运行状态以服务端为准 |
| `apps/api` | Java 21、Spring Boot、Spring MVC/SSE、JDBC、Flyway、Maven Wrapper | 业务 API、匿名权限、任务、模型调用、费用、版本和应用数据；不执行生成代码 |
| `services/builder` | 固定 Node/Vite 模板、编译和浏览器检查 | 白名单依赖、无凭据、无 Docker socket、受限网络与资源；一次一个构建 |
| `services/document-worker` | 可信 Node 程序、PptxGenJS、Playwright/Chromium、固定中文字体 | 只接受结构化幻灯片和受控资源，不执行模型提供的脚本 |
| MySQL | 8.4 LTS、InnoDB；平台元数据和应用记录 | 分开逻辑表与数据空间；不对外开放端口 |
| Caddy | HTTPS、工作台静态文件、API 与各项目产物路由 | 只暴露 80/443，主机路由白名单，通配域名证书单独配置 |
| 存储 | 源码/产物/附件持久卷、临时工作卷 | 最终目录由 API 控制，builder/导出器仅写任务临时区；后续可换对象存储 |

业务初期单实例，任务执行器在 Spring 应用独立线程池中。全局构建并发 1、模型角色并发 2、外部视频提交并发 1、文档导出并发 1；每项目修改串行。用数据库任务表和有界队列，不提前引入额外队列系统。

```text
工作台浏览器 → Caddy → Spring API / 后台执行器 → MySQL
                           ├─ 百炼文字 / 图片 / 视频服务
                           ├─ builder → 任务临时产物
                           ├─ document-worker → PPTX / PDF 临时产物
                           └─ 校验 / 原子写入 → 版本及资源持久卷
独立预览 / 公开产物域 → Caddy → 受限静态资源与应用数据路由
```

依赖在各项目清单、锁文件和容器镜像中固定。Java、Node、浏览器及库的具体补丁版本在工程初始化时记录并验证兼容，不在正式构建中使用浮动 `latest`。初始化时先用官方支持版本建立可重复构建，再将实际版本补入 Trellis 规范。开发服务只在用户指定环境启动；模型未配置应明确返回 `PROVIDER_NOT_CONFIGURED`，不能展示伪造生成结果。

## 3. 领域模型与数据持久化

| 实体 | 核心字段/关联 | 不变量 |
| --- | --- | --- |
| `sessions` | id、secret_hash、expires_at | 所有者匿名凭证只保存哈希；Cookie host-only、HttpOnly、Secure、SameSite=Strict；30 天活动续期 |
| `projects` | owner_session_id、type、name、current_version_id、published_version_id、deleted_at、active_run_id | type 为 WEB/GAME/SLIDES/VIDEO；工作版与公开版独立；删除立即撤销访问 |
| `messages` | project_id、run_id、role、agent_role、body、attachment_ids | 用户输入、咨询回答、任务摘要与角色交接可追溯 |
| `runs` | project_id、kind、mode、base_version_id、state、queue_order、retry_of、lease_owner、lease_epoch、lease_until、cancel_requested_at | kind 区分生成、源码提交、元素修改、修复、媒体生成；成功提交必须属于当前租约 |
| `plans` | run_id、revision、plan_json、approved_revision、approved_at | 只执行已批准的确切版本；计划/大纲编辑使此前批准失效 |
| `agent_tasks` | run_id、role、dependencies、state、input_refs、output_refs、provider_request_id、usage_id | 每角色独立上下文与真实调用，依赖成功后才可执行 |
| `project_events` | project_id、seq、run_id、agent_task_id、type、payload、timestamp | `(project_id,seq)` 唯一；所有状态变化先提交数据库再通知 |
| `versions` | project_id、kind、parent_version_id、restored_from_version_id、producing_run_id、source_key、artifact_key、schema_revision、summary、sha256 | 成功产物不可变；producing_run_id 唯一；恢复新建元数据版本并复用已有内容；导出不创建内容版本 |
| `templates` / `template_versions` / `template_files` | slug、type、category、summary、author、source_url、cover_path、current_version_id；版本含 content_hash、entry_path、file_count、total_bytes；文件按 `(version_id,path)` 存内容与类型 | 模板版本不可变；内容哈希变化才发布新版本；目录公开只读，克隆必须复制出新项目的文件 |
| `preview_sessions` | token_hash、project_id、version_id、owner_session_id、expires_at | 令牌 256 位随机、只存哈希、单项目单版本、默认 30 分钟过期；令牌本身即预览能力，不可枚举 |
| `edit_sessions` | project_id、base_version_id、revision、file_ops、state | 一个项目一个草稿；提交携带版本与草稿修订号；冲突不覆盖 |
| `assets` / `asset_references` | project_id、kind、storage_key、mime、size、sha256、state、引用版本 | 原始附件私有；有引用的资源不可删除；发布资源按清单复制 |
| `provider_jobs` | run_id、operation_id、model、provider_task_id、state、result_refs、last_polled_at | operation_id 唯一；已知外部任务编号只恢复查询，不再次创建 |
| `exports` | version_id、format、state、asset_id、error_code | 唯一键 `(version_id,format,renderer_version)` 防止重复导出 |
| `app_collections` | project_id、space、collection_id、schema_revision、fields、visibility | space=preview/live；字段及共享策略由平台校验 |
| `app_visitors` / `app_records` | project_id、space、visitor_secret_hash、expires_at；collection_id、owner_visitor_id、body、revision | 应用访客身份独立采用 30 天活动续期，不等于项目所有者；更新按 revision 防止覆盖 |
| `budget_accounts` / `usage_ledger` | currency、limit、spent、reserved；operation_id、rate_snapshot、maximum_cost、actual_cost、state | 使用定点金额；operation_id 唯一，原子预留和结算 |
| `publish_events` | project_id、version_id、manifest_hash、state、timestamp | 只有完全准备好的版本才能切换公开指针 |
| `idempotency_requests` | owner/session、route、key、request_hash、response_ref | 同键同请求复用结果，不同请求返回冲突；覆盖所有创建/提交类操作 |

时间使用 UTC 保存，客户端显示用户时区；业务编号使用随机 ID。数据库和持久资源卷共同备份。API 写入版本临时目录、校验路径/类型/大小/哈希、在同一卷原子重命名，再事务提交版本指针及事件。事务失败留下的孤儿目录由受限清理任务回收，不能删除已引用资源。

模板由 `apps/api/src/main/resources/template-seed/<slug>/manifest.json` 播种：启动时按 `(path, sha256(content))` 计算内容哈希，未变化跳过，变化则新建 `template_versions` 并切换 `templates.current_version_id`，旧版本保留。克隆在同一事务内复制 `template_files` 到 `project_files` 并写入 `project_versions`（kind=`TEMPLATE`），项目与模板自此互不影响。试玩与预览统一从数据库读取文件，经 `MediaTypes` 白名单校验路径（拒绝绝对路径、`..`、反斜杠、超长），响应固定 `Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; frame-ancestors 'self'`，并以 `sandbox="allow-scripts allow-pointer-lock"` 嵌入，使游戏运行在 opaque origin、无法读取工作台 Cookie。

## 4. 任务状态、队列及多智能体

### 4.1 状态与执行边界

运行状态：`queued → planning → waiting_approval（计划/大纲模式）→ executing → checking → persisting → succeeded`。executing 的真实阶段为 generating/building/rendering/provider_wait。失败进入 `failed`；取消经 `cancel_requested` 进入 `cancelled`；前项失败导致后项 `paused`。咨询任务可成功结束而不产生版本。

短事务领取任务，增加 lease_epoch 并设置租约，随后释放锁。默认租约 60 秒、心跳 15 秒。网络和构建调用不持有行锁。写入检查 `(lease_owner,lease_epoch,state,cancel_requested_at)` 及项目基础版本；失效执行者不能提交，producing_run_id 唯一防止重复成功。

排队项开始执行时，在项目锁内读取 current_version_id 作为真实 base_version_id。前项失败/取消后，后项暂停；用户重试创建 retry_of 关联任务并置于暂停队列前方，或选择基于当前成功版继续。编辑/取消只允许尚未执行项；本轮不支持队列拖动排序。不同项目受全局并发限制，排队不伪造百分比。

等待批准时释放执行槽，不占全局 worker。项目仍阻止版本恢复和编辑提交。批准携带 plan revision；基础版本不符时重新规划，不静默执行旧计划。24 小时未处理的批准请求取消并释放未使用预留费用。

### 4.2 角色执行

角色目录与产品文档一致。团队模式由 Mike 生成有依赖的任务图，角色上下文包括：用户目标、约束、当前版本、授权资料和已完成交接结果。上下文按预算摘要，不共享无关项目内容。支持 `@` 指定角色，多角色请求仍由协调器控制依赖和工具权限。

Emma/Bob/Iris/Sarah/Adrian 交付结构化建议、文本或资料，不直接任意改文件；Alex 具有文件补丁、受限构建、媒体生成和文档工具权限；David 仅读取授权附件、本项目授权数据与构建/测试结果，产出数据分析、图表建议和功能核验结论，不凭模型文字冒充测试通过。角色交付须记录实际 provider request、工具调用、耗时及费用。质量检查由确定性验证与必要的角色复核共同完成。

Build 模式规划后按规则执行；Plan 模式在实施前等待批准。SLIDES 的大纲确认始终启用。每次任务默认最多 24 次模型调用，自动代码修复最多一次；达到上限或预算不足时结束并展示原因。八角色按需调用，同一角色可使用不同模型，但不能用重复标签冒充独立执行。

## 5. 各类型流水线与编辑

### 5.1 WEB/GAME

固定模板 → 结构化文件操作 `create/update/delete` → 路径/大小/依赖校验 → 构建 → 浏览器检查 → 持久版本。模型仅可修改 `src/**` 和受限 `public/**`；包清单、构建脚本、受控数据 SDK 和预览桥接代码不可改。拒绝绝对路径、路径穿越、链接文件及超限内容；默认最多 100 个生成文件、单个 512KB、总源码 5MB。

第一次从模板创建，后续从 base_version_id 读取完整源码快照。构建容器无业务密钥、无 Docker socket、非特权运行、限制 CPU/内存/PID和时间；每次使用独立目录。浏览器冒烟仅证明可加载及基础错误检查，T04/T05/T13 的真实交互另行验证。数据服务在冒烟测试中使用隔离测试数据空间，不访问真实公开数据。

源码草稿编辑不移动当前版本。提交后同样进入校验/构建/检查流水线；失败保留草稿及上一成功版。草稿存在时，发送修改指令必须先提交或放弃；咨询不受影响。草稿由基础版本+操作列表表达，禁止仅用浏览器内存保存已确认的编辑。

历史恢复是所有输出类型共用的操作：校验项目空闲及 expectedCurrentVersionId，确认所选版本仍可读取后，新建 `kind=restore` 的不可变版本。parent_version_id 指向恢复前工作版，restored_from_version_id 指向所选历史版，复用其源码/结构化内容/媒体资产并增加引用。恢复操作有独立幂等记录及 producing_run_id，不重新调用模型或视频服务；事务内更新工作版与事件，保留全部历史、业务数据及公开版。

#### 5.1.1 已实现：网页模板作为可运行起点（S1 补充）

WEB 的「多文件工程 + 构建 + 冒烟」流水线尚未接入（单文件生成见 5.1.2），但「从模板开始」这一段已经落地，内容和形态都按上面的约束准备：

- 模板是**单文件、自包含**的 `index.html`（样式与脚本内联、系统字体、无外链资源），与幻灯片模板共用同一套 `template_versions` / `template_files` 存储与内容哈希播种机制。改模板会发布新的不可变版本，已克隆项目停在旧版本。
- 克隆把 `template_files` 逐字节复制成 `project_files`，`project_versions.kind=TEMPLATE`，入口仍是 `index.html`；项目预览继续走一次性令牌，iframe 为 `sandbox="allow-scripts"` 且响应带 `default-src 'none'; connect-src 'none'`，页面无法发请求或读取工作台数据。
- 画廊封面直接以 1280×800 渲染真实页面再等比缩放（`WebFrame` 用 ResizeObserver 计算缩放比），带 `?peek=1` 时页面自行上下巡游；弹窗可切桌面/手机两种视口。这样「模板能跑」是可见事实，而不是截图承诺。
- 仍未实现：结构化文件操作（`create/update/delete`）、构建与浏览器冒烟检查、可视化选择桥接、多轮修改与历史恢复。

#### 5.1.2 已实现：按需求真实生成单文件站点（S1 补充）

与上面「多文件工程 + 构建 + 冒烟」的目标形态相比，当前落地的是**单文件**版本，但生成链路是真的：

- `POST /api/projects/{id}/runs` 对 `type=WEB` 的项目返回 202，`runs.engine=qianwen-chat/1.0`，`model_key` 记录模型 id（该列是 ASCII，显示名走事件与 artifact）。`runs` / `run_events` / `project_versions` / `project_files` 全部复用，无新迁移。
- 两次真实的千问 chat 调用（DashScope 风格 `POST {base}/services/aigc/text-generation/generation`，非流式，`max_tokens` 分别 900 / 8000，`result_format=message`）：第一次只允许输出 JSON 结构（标题/定位/主按钮/3–6 区块及要点），`WebOutline.parse` 做防御式解析；第二次把结构写成 `<html>`，提示词强制单文件、内联样式与脚本、移动端自适应、至少一处原生 JS 交互、hero 区留 `.hero-image` 占位。
- 可选配图：`ATOMS_QWEN_IMAGE_MODEL`（默认 `qwen-image-3.0`）已购买时，额外调用 `POST {base}/services/aigc/multimodal-generation/generation` 生成一张 512×512 主视觉，下载后转 `data:image/...;base64` 内嵌（预览 CSP 只放行 `img-src data:`）。配图失败**不阻塞页面**：以 `PROVIDER_MODEL_NOT_ACTIVATED` 等降级为无配图版式，事件里如实写明，artifact 带 `heroImage=false`。
- 入库前 `WebHtml.sanitize` 是硬门槛：必须是完整文档（`<!doctype` 开头、`</html>` 结束）、400B–600KB、`src|href|action|url()` 中出现 `//` 直接以 `PROVIDER_OUTPUT_REJECTED` 失败。预览沙箱 CSP 只放行 inline，含外链的页面必然破图，所以拒绝而不是静默改写。
- 产物写 `project_files/index.html`、`kind=GENERATED`，artifact 带 `entryPath/sections/sizeBytes/model/tokens/heroImage`；对话页六步（需求解析/页面结构/页面编写/配图生成/页面校验/版本入库）全部来自 `run_events`，失败步骤标「未完成」。
- 诚实边界：`ATOMS_QWEN_TEXT_MODEL` 留空即关闭生成；`GET /api/system/provider` 用空 `messages`（文本）或空内容（图片）免费探测出 `textState`/`imageState`，未开通或未购买（网关返回 `AccessDenied.Unpurchased`）时项目页按钮旁与对话页都直接说明 `PROVIDER_MODEL_NOT_ACTIVATED`，不产生占位页面。GAME 生成仍拒绝（`GENERATION_UNSUPPORTED_TYPE`）。

### 5.2 可视化选择与错误桥接

固定模板注入平台维护的桥接脚本。选中元素返回版本号、稳定元素定位、标签/文字摘要和位置；工作台校验来源 origin、iframe window、会话 nonce 和消息结构。当前版本变化后旧选择失效。

元素修改通过指令和源码定位交给 Alex，产生真正源码补丁及新版本；找不到可靠定位时明确要求重新选择，不误改其他元素。浏览器错误/未处理异常经同样桥接关联版本，去重和限流后展示；源码中的敏感信息不直接写日志。运行错误支持主动修复，和自动构建修复分开计费、记录。

### 5.3 SLIDES

统一 `SlideDocument` 保存 schemaVersion、主题、16:9 画布、稳定 slideId 和元素。初版元素限定文字、图片、形状、表格、图表；每个元素有稳定 ID、位置、尺寸、样式和内容。图片引用 assetId，图表引用明确的数据系列，不使用截图替代可编辑图表。

确认大纲 → 生成默认 8 页（1–20 页）→ 结构与布局校验 → 可信网页渲染 → 保存版本。逐页修改仅替换目标 slideId，主题修改生成完整新快照。字体使用统一打包中文字体，溢出应报错或在明确规则内换行，不默默裁切。

可信 document-worker 从版本 JSON 生成 PPTX（PptxGenJS）与 PDF（Playwright 打印）。网页、PPTX、PDF共用内容与几何单位换算；不同渲染器不承诺逐像素相同，但验收页序、文字、图表和明显溢出。导出资源和 renderer_version 固定，可重试失败导出，不重新生成内容或重复模型扣费。[T8][T9]

#### 5.3.1 已实现：本地排版引擎与可观察运行（S3 第一步）

在模型规划器接入前，先落地一条**真实、可检查**的生成链路，避免用假进度冒充生成：

- 内容来源只有两个：访客自己的提示词，或导入的 .pptx 文字大纲。`SlidePlanner` 按句读切分提示词，提取要点、量化信息与主题关键词，只做重组与排版，不新增事实；页面上明确标注「本地排版引擎」。模型接入后可替换该组件，运行流水线与渲染器不变。
- 规格是 `SlideDeck{title, theme, brand, slides[]}`。`DeckRenderer` 把它渲染成**单文件** `index.html`：沙箱 iframe 是 opaque origin，预览 CSP 只放行内联样式与脚本，任何外部 CSS/JS 都会被拦，因此样式与引擎必须内联。
- 共享引擎 `slide-engine/deck.css|deck.js` 同时服务模板播种、画廊跑马灯、缩略图和成品预览，保证同一份内容在各处一致。舞台固定 1280×720，用 CSS transform 缩放；封面标题按长度分档降字号，避免长标题溢出画布。
- 运行日志是唯一事实来源：`runs` + `run_events`（`UNIQUE(run_id, seq)`）。每一步（解析、架构、视觉、逐页排版、校验、入库）执行后才写事件，SSE 先从日志回放，再跟随新行；因此刷新页面可续接，取消以 `cancel_requested` 在切片间检查，取消后不产生版本。
- 导入：`POST /api/templates/imports` 接收 ≤8MB 的 .pptx/.pdf。pptx 直接读 `ppt/slides/slideN.xml` 的 `<a:t>` 文本形成大纲；pdf 目前只存原文件并计数页数，`parseState=STORED_ONLY`。
- 尚未实现（保持明确失败或未开放）：模型规划与大纲确认、逐页元素编辑、PPTX/PDF 导出、费用预留。当前流水线不产生任何模型费用。

### 5.4 VIDEO 与外部提交不确定性

单次文字或参考图输入，默认 5 秒/720P/一个结果。API 在调用前持久化 operation_id、参数摘要、模型和费用预留，随后提交异步任务并立即保存 provider_task_id。后台带退避轮询：2s、5s、10s、30s，上限 30s；网络失败可重试查询，默认整体观察期限 30 分钟，超期转人工恢复提示而非自动再次生成。

若提交已发送但响应丢失，状态标记 `submission_unknown`：优先利用服务商可用查询/幂等机制核实；不假定其支持幂等，也不自动重新 POST。无法核实时保留预算占用、阻止自动重提，并展示核对后显式重试入口。重启扫描已有 provider_task_id 继续查询；取消后停止提交版本，按服务能力尝试停止外部任务，仍保留费用核对记录。

服务商成功不等于本平台成功。API 从服务商允许的结果域下载文件，限制大小/重定向/时间，校验媒体类型和可播放性并写持久卷；再创建版本。下载失败只恢复同一任务结果；不能把已过期外链当永久产物。参考图按服务支持的上传方式或有时限的单资源读取地址提供，原始附件不因此变为公开。[T6][T7]

#### 5.4.1 已实现：真实提交、轮询与计费预留（S4 第一步）

按上面的不确定性要求，先落地一条**不伪造**的最短闭环，再逐步补齐恢复能力：

- 提交前先写 `provider_spend` 预留（默认单条 ¥6，全局上限 ¥300），预留失败则直接以 `BUDGET_EXCEEDED` 失败，不发起调用；成功置为 `SETTLED`，失败或取消置为 `RELEASED`。预留是安全上限，不是服务商报价。
- `provider_task_id` 在提交成功后立即写回 `runs`，停止时用它尽力 `DELETE` 远端任务；轮询每 120ms 检查一次 `cancel_requested`，取消不写入版本。
- 轮询间隔固定（默认 3s），整体等待上限默认 10 分钟，超时后删除任务并以 `PROVIDER_TIMEOUT` 失败。设计中要求的 2s/5s/10s/30s 退避、`submission_unknown` 分支、进程重启后按 `provider_task_id` 续查**尚未实现**：当前进程重启会让运行中的任务停在 `RUNNING`，需要人工处理。
- 下载只接受 http(s)，默认拒绝回环/内网地址（本地 stub 需显式开启 `ATOMS_ARK_ALLOW_PRIVATE_MEDIA`），限制 256MB；未做播放器级可播放性校验，仅检查字节长度。
- 服务商未开通模型时，运行在提交步骤失败并写入 `PROVIDER_MODEL_NOT_ACTIVATED`，页面显示原因与“开通后直接重试”的提示，绝不生成占位视频。当前账号即处于该状态。

### 5.5 附件与资源输入

输入附件每次最多 5 个、单个最多 10MiB；支持 PNG/JPEG/WebP、UTF-8 TXT/Markdown/CSV 和可提取文字的 PDF。以实际文件类型和解析结果校验，不能只相信扩展名或浏览器声明；拒绝加密、无法提取文字或超限 PDF，并说明当前不含扫描件 OCR。可信解析器设定页数、解析时长和提取文本上限，原始附件保留为私有资产，摘要关联源 assetId。附件限额与源码文件的 512KB/5MB 限额独立。生成视频、PPTX/PDF 使用按类型配置的产物上限，不套用输入附件限制。

## 6. 应用数据库和无登录权限

模型只能提出集合结构与受限查询需求，平台执行参数化 CRUD；没有执行任意 SQL 的接口。字段类型限 string/number/boolean/date/json，校验必填、长度和范围；查询只允许白名单字段的等值/范围、排序及游标分页，单页最多 100 条。

通过项目主机名和服务端映射确定 project 与 preview/live space，客户端不能切换 scope。应用访客凭证与工作台所有者凭证分开，Cookie 为主机限定且服务端只存凭证哈希；应用访客独立采用 30 天活动续期，身份过期或清除后不承诺恢复其私有记录。服务端强制注入 owner_visitor_id，忽略客户端伪造的所有者字段。

| 集合策略 | 读 | 创建 | 修改/删除 |
| --- | --- | --- | --- |
| visitor_private（默认） | 当前访客记录 | 当前访客 | 当前访客自己的记录 |
| shared | 所有访客 | 当前访客归属 | 当前访客自己的记录 |
| 工作台所有者管理 | 本项目全部记录 | 可管理 | 可管理，必须通过工作台所有权校验 |

匿名跨浏览器共享指 shared 集合的读取；不能假定两个浏览器是同一个匿名用户。更新带记录 revision 防止最后写入者覆盖。所有者操作仅在工作台 API 开放，生成应用获得的 token 没有管理权限。

预览/live 数据独立；发布仅把兼容结构迁移到 live，不复制记录。初版只允许新增可选字段和新集合，不允许字段删除、改类型、改名或策略放宽；这些变更明确拒绝。源码恢复保持现有数据，旧版运行只使用其支持字段。生产 schema 的修改必须跟随明确发布，而不能因预览构建自动更新。

## 7. 匿名预览、公开访问与发布

工作台例如 `studio.demo.example`；预览 `p-<id>.preview.demo.example`；公开应用 `a-<id>.apps.demo.example`。三者不同 origin、同一受控站点范围，工作台凭证必须 host-only 且使用 `__Host-` 前缀；不得设置可被所有子域接收的 Domain Cookie。跨独立注册域的部署不是默认方案，必须另验第三方存储兼容。

预览授权流程：工作台 POST 创建 60 秒、单次使用的预览票据 → iframe 访问预览主机的 bootstrap 路径 → 服务端兑换为该项目只读预览会话 Cookie → 303 跳到无票据的固定版本路径。HTML、JS、CSS、图片请求都检查此 Cookie 与项目归属。Referrer-Policy=no-referrer，票据不进入访问日志。新标签页使用新票据；过期时向工作台重新申请，不把所有者凭证塞入 iframe URL。

预览 iframe 只开放所需 scripts/same-origin/forms 权限，禁止顶层导航；不同 origin 确保脚本不能访问工作台 DOM。CSP 限制脚本/资源为受控源，数据通信只到应用本机的受限 data 路由；工作台 API 不向预览域开放通用 CORS。工作台变更校验 Origin 与 CSRF 令牌，避免仅依赖 SameSite。

发布在产物和资源完整、结构兼容检查成功后，原子切换 published_version_id。公共根链接始终指向当前发布版；版本化资源按发布清单提供，撤销发布或删除项目后撤销访问。WEB/GAME 为静态应用，SLIDES/VIDEO 为可信播放器。原始附件、源码、内部日志默认不公开；源码导出只能由项目所有者下载。公开播放链接不含编辑或预算调用权限。

## 8. 模型适配和预算

默认服务为阿里云百炼中国内地地域，服务端配置 endpoint、model、request timeout、最大输入/输出与费率。工作台不接受访客任意指定模型地址或密钥。

| 用途 | 配置默认 | 实施前验证 |
| --- | --- | --- |
| 分工、摘要等轻量任务 | `qwen3.8-flash` | 地域可用、结构化输出及工具调用契约 |
| 代码、复杂内容 | `qwen3.7-plus` | 上下文、输出长度、实际编译通过率 |
| 配图/参考图 | `qwen-image-2.0` | 图像规格、结果保存与单张费用 |
| 视频 | 万相文生/图生各一个独立配置项 | 服务商支持的确切 model ID、5 秒/720P、异步 API 与费率 |

> **2026-11-13 部署修订（火山方舟）[D]：** 本部署的视频能力改由火山方舟（豆包 Seedance）异步任务接口提供：`POST/GET/DELETE https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks`，创建只返回任务编号，查询返回 `queued/running/succeeded/failed`、`content.video_url` 与 `usage.completion_tokens`，时长 4–15 秒、分辨率 480p/720p/1080p/4k、`generate_audio` 与 `watermark` 为独立参数。已核实（2026-11-13，账号 2134386625）：`doubao-seedance-2-5-260628` 与 `doubao-seedance-2-0-260128` 存在但账号未开通（`ModelNotOpen`）；`doubao-seed3d-2-0-260328` 已开通，但属图生 3D（obj/glb），不属于 R24/R25，未接入工作台。视频模型编号、时长、分辨率、画幅经 `ATOMS_ARK_*` / `ATOMS_VIDEO_*` 配置固定，默认 5 秒、720p、16:9、无音轨；启用前须先在方舟控制台对账号开通，平台用 `GET /api/system/provider` 以空 `content` 校验请求如实展示 `ACTIVATED`/`NOT_ACTIVATED`，该探测不创建计费任务。百炼仍可作文字与配图渠道的备选，未接入前不得声称已具备。模型更换或费率变化不改变既有预算规则：任何付费提交前必须先原子预留。

文字/图片模型沿用用户批准的选型；视频模型以开通地域实际可用的万相型号锁定到配置，不把不同接口混用。正式调用前执行最小能力检查；不可用时明确报错，不自动换成未经验证的模型。价格取官方价目并保存核验日期、费率快照及地域；不将网页当前单价写成永久承诺。[T5][T6][T7]

> **2026-11-15 部署修订（网页生成改千问）[D]：** 网页生成的文本与配图改由千问网关提供（`https://cn-beijing.maas.qianwenaiapi.com/api/v1`，DashScope 风格）：文本 `POST {base}/services/aigc/text-generation/generation`（`result_format=message`，`max_tokens` 900 / 8000），配图 `POST {base}/services/aigc/multimodal-generation/generation`（`qwen-image-3.0`，512×512，返回 `output.choices[0].message.content[].image` 的 OSS URL，下载后转 base64 内嵌）。配置新增 `ATOMS_QWEN_*`（`API_KEY`/`BASE_URL`/`TEXT_MODEL`/`TEXT_MODEL_NAME`/`IMAGE_MODEL`/`TEXT_TIMEOUT`/`IMAGE_TIMEOUT` 等），文本与图片共用一套 `QwenClient`，视频仍走方舟（`ArkClient` 已删去文本职责）。网关错误码：`AccessDenied.Unpurchased`（未购买，403）与 `ModelNotOpen` 映射 `PROVIDER_MODEL_NOT_ACTIVATED`，`InvalidApiKey`（401）映射 `PROVIDER_AUTH_FAILED`；空 `messages`（文本）与空内容（图片）触发校验，用作免费探测。配图是可选增强：未购买时降级为无配图版式、事件与 artifact 如实注明，不阻塞页面生成。已核实（2026-11-15）：文本 `qwen-plus`/`qwen-max`/`qwen3.7-max` 与图片 `qwen-image-3.0` 早期可真实出片（含 base64 配图），随后账号所有文本与图片模型转为 `AccessDenied.Unpurchased`（免费额度耗尽），状态接口如实显示 `NOT_ACTIVATED`；`qwen3.8-max` 报 `url error` 需避开。

金额使用 DECIMAL(18,6) 或等价定点数。所有访客共享一个 CNY 账户：调用前在事务中检查 `spent + reserved + maximumCost <= 300`，成功预留后才允许外部请求。maximumCost 包括输入、最大输出、图片数量/规格、视频时长/规格以及允许的一次修复。实际费用回填后结算并释放差额。

费用未知、外部取消或提交不确定时不释放占用；重复回调/轮询不得重复结算。模型调用次数上限、token 上限与预算同时生效。任务被拒绝时返回 `BUDGET_EXCEEDED`，不得悄悄使用固定成品替代。导出不调用模型，因此只消耗本地资源；新配图等实际模型调用仍计费。预算调高只能由运营配置流程完成并留审计，公网无充值接口。

## 9. API 与事件契约

所有工作台接口位于 `/api`，校验匿名项目所有权；所有写操作需要 JSON、CSRF 和幂等键。ID 使用字符串，时间 ISO 8601 UTC，统一错误 `{code,message,retryable,requestId,details?}`；details 只能含可公开的校验信息。

| 组 | 接口与行为 |
| --- | --- |
| A1 会话/项目 | `POST /api/session` 建立会话；`GET/POST /api/projects`；`GET/PATCH/DELETE /api/projects/:id`。创建接收 type、prompt、mode、team、templateId、assetIds，返回 projectId/runId。删除为软删除并撤销发布，未结算费用保留。 |
| A2 对话/任务 | `POST /api/projects/:id/messages`；`GET /api/runs/:id`；`GET /api/runs/:id/agents`；`POST /api/runs/:id/cancel`、`.../retry`。retry 新建关联任务，只有已确认失败/取消才可提交。 |
| A3 队列/批准 | `GET /api/projects/:id/queue`；`PATCH/DELETE /api/runs/:id/queued-message`；`POST /api/projects/:id/queue/resume`；`GET/PATCH /api/runs/:id/plan`；`POST /api/runs/:id/approve`、`.../reject`，批准携带 planRevision。 |
| A4 事件 | `GET /api/projects/:id/events?after=seq`；兼容运行级事件查询，但序号统一为项目序号。项目快照包含 eventCursor。 |
| A5 版本/预览/发布 | `GET /api/projects/:id/versions`；`POST .../restore` 接收 versionId/expectedCurrentVersionId；`POST .../preview-sessions`；`GET/POST/DELETE .../publication`。POST 明确 versionId，更新也使用此接口。 |
| A6 源码/元素 | `GET /api/projects/:id/versions/:versionId/files` 及 `/files/content?path=`；`POST /api/projects/:id/edit-sessions`；`PATCH/DELETE /api/edit-sessions/:id`；`POST .../submit`；`POST /api/projects/:id/visual-edits`；`POST /api/projects/:id/source-exports`。提交返回 runId，文件/草稿携带基础版本及修订号。 |
| A7 模板/资产 | `GET /api/templates`、`GET /api/templates/:slug`；`POST /api/templates/:slug/clone`（幂等，复制文件快照成独立项目 v1）；`GET /api/template-previews/:slug/*` 与 `POST /api/projects/:id/preview-sessions` + `GET /api/previews/:token/*` 提供只读沙箱预览；`POST /api/projects/:id/assets`；`POST /api/assets` 为创建项目之前的会话附件；`GET/DELETE /api/assets/:id`、`GET .../download`；附件只有同会话才能绑定项目。 |
| A8 应用数据 | 所有者：`GET/POST /api/projects/:id/collections`、`PATCH .../collections/:collectionId`、记录管理；应用域：`POST /data/session`，`GET/POST /data/collections/:id/records`，`PATCH/DELETE .../records/:recordId`，校验访客与数据空间。 |
| A9 幻灯片 | `GET /api/projects/:id/versions/:versionId/slides`；`POST /api/projects/:id/slide-edits` 支持 targetSlideId 或 theme；`POST /api/projects/:id/exports` 接收 versionId/format；`GET /api/exports/:id` 查询与下载授权。 |
| A10 视频 | `POST /api/projects/:id/video-generations` 接收 prompt/referenceAssetId；`GET /api/runs/:id/video`；`POST /api/runs/:id/reconcile` 只恢复查询。播放/下载使用版本资产授权，重新生成走新任务。 |
| A11 诊断/费用 | `GET /api/projects/:id/diagnostics`；`POST /api/projects/:id/repairs` 关联 versionId/diagnosticIds；`GET /api/usage/budget` 返回该部署剩余额度和本人用量，不暴露他人提示词。 |

来源版本过期返回 `VERSION_CONFLICT`；运行中不允许的编辑返回 `PROJECT_BUSY`；生成能力不存在返回明确错误。除 WEB/GAME 外不开放源码编辑，按项目类型返回 `UNSUPPORTED_PROJECT_TYPE`。接口契约需在实现时同步 OpenAPI 与前端运行时校验。

SSE 事件包含 `{projectId,runId,seq,type,timestamp,payload}`。类型含 plan、approval_required、agent_started、agent_result、step、build_result、diagnostic、version_ready、queue_changed、cancelled、error、budget_changed。首次使用快照 eventCursor，断线使用已消费序号续传；客户端按 seq 去重。服务端持续从已提交事件表补读，实时通知仅用于唤醒，不能依赖“订阅瞬间”避免遗漏。默认事件保留至项目删除；过旧游标明确要求重读快照。

## 10. 功能—页面—接口—数据—测试对应

Tnn 对应产品 Rnn，表中均为待执行验收，不能视为已有通过记录。

| 需求 | 页面 | API | 主要数据 | 测试与关键判定 |
| --- | --- | --- | --- | --- |
| R01 | P01/P02 | A1 | sessions/projects | T01：浏览器 A/B 私人项目隔离，无登录创建 |
| R02 | P03 | A2 | runs/versions | T02：两类真实生成；断开服务明确失败 |
| R03 | P03 | A2/A4 | project_events/agent_tasks | T03：快照与连接间产生事件仍补齐，断线不遗漏 |
| R04 | P03 | A5/A8 | artifacts/app_records | T04：待办交互、刷新、设备和新标签；S1 本地数据、S2 服务端 |
| R05 | P03 | A2/A3 | runs/base_version | T05：排队两个修改保留前项功能和数据 |
| R06 | 全部 | A1/A2 | MySQL/volumes | T06：在各阶段重启，状态和资产可恢复 |
| R07 | P05 | A5 | versions/projects | T07：恢复再修改；后续历史和业务数据保留 |
| R08 | P03/P09/P10 | A2/A9/A10 | runs/errors | T08：模型、构建、导出、媒体故障与重试 |
| R09 | P01/P11 | 全部适用 | 部署与验收记录 | T09：新设备免注册 HTTPS 完成四类型案例 |
| R10 | P06/P11 | A5 | publish_events/manifest | T10：v1/v2 分离，撤销/删除后公开访问撤销 |
| R11 | P04 | A6 | edit_sessions/versions | T11：提交/放弃/冲突，源码 ZIP 与版本一致 |
| R12 | P01/P02 | A1 | projects | T12：视觉参照、重命名删除、手机布局 |
| R13 | P03/P11 | A2/A5 | GAME versions | T13：真实游戏输入、计分、重新开始、发布 |
| R14 | P03 | A2/A3 | queue/leases | T14：取消/暂停/继续；失效租约和停止后不提交 |
| R15 | P03 | A2 | agent_tasks/messages | T15：八角色职责各验证，至少一个真实交接链 |
| R16 | P03/P09 | A3 | plans | T16：未批准不实施；旧 revision 拒绝 |
| R17 | P07/P11 | A8 | app_collections/records | T17：生成集合、CRUD、分页与受限查询 |
| R18 | P07/P11 | A8 | visitors/spaces | T18：私有隔离、共享只改本人、所有者管理、preview/live 分离 |
| R19 | P03 | A6 | element_refs/versions | T19：定位修改、旧选择失效、刷新与导出保留 |
| R20 | P01/P08 | A7/A1 | template snapshot | T20：独立复制，模板标识及原模板不受影响 |
| R21 | P08 | A7 | assets/references | T21：上传/解析/下载/权限；引用保护及发布清单 |
| R22 | P09 | A3/A9 | SlideDocument | T22：大纲、逐页、主题、演示和历史 |
| R23 | P09 | A9 | exports/assets | T23：网页/PPTX/PDF一致，中文字体、图表可编辑、无溢出 |
| R24 | P10 | A10 | provider_jobs | T24：文生、图生各一次真实视频，可播放下载 |
| R25 | P10 | A10 | operations/assets | T25：提交响应丢失、重启、下载失败、临时链接过期不重复生成 |
| R26 | P03/P10 | A11 | budget/ledger | T26：并发预留、重复结算、未知费用、额度不足 |
| R27 | P03 | A11 | diagnostics/runs | T27：版本关联错误、最多一次自动修复、主动修复 |
| R28 | 全部 | 所有边界 | policy/limits | T28：路径穿越、资源隔离、CSRF/来源校验、超限、响应式 |

验证分层：服务状态机/费用/权限单元测试，真实 MySQL 的事务与重启集成测试，builder/document-worker 固定样本测试，Playwright 用户路径测试。模型替身只用于自动化故障与协议测试且明确标记；T02/T24及公网记录必须有真实服务调用证据。媒体验收和支付预算运行前检查预留，不为覆盖率无界重复付费生成。

## 11. 实施顺序、Trellis 与部署

S1：匿名身份/项目/事件 → 真实生成与受限构建 → 预览/对话/游戏 → 队列/取消/恢复 → 发布，第一次模型调用之前启用预算。

S2：角色任务图与批准 → 应用数据及权限 → 源码/元素编辑 → 模板/资产/诊断；S3：SlideDocument、大纲、页面编辑、播放器与双导出；S4：两个视频适配、异步恢复与产物保存、所有类型公网回归。

Trellis 的父任务表示全部交付，文档初始化与 S1–S4 为独立子任务。每个复杂任务包含 prd/design/implement、需求编号、依赖、验证步骤和上下文；开始后保持状态真实。首次可启动工程只说明基础可用，不关闭整个 S1。原始规范、核查结论和实现结果分开记录。

部署前置条件：已开通且同地域的模型服务与密钥、带持久盘的服务器、工作台和通配预览/公开域 DNS/证书、已核实的费率配置。密钥仅注入 API；MySQL、builder、document-worker 为内网服务。数据库迁移随版本发布执行，破坏性迁移本轮不支持。备份包括数据库与资源清单/卷，至少做一次实际恢复演练。

提供 README、`.env.example`、锁文件、Docker Compose、健康检查和迁移命令。日志包含 requestId/projectId/runId/agentTaskId/providerTaskId 与错误码，不包含 Cookie、模型密钥或完整敏感提示词。监控队列时长、失败率、外部任务未知态、预算占用、存储容量和服务健康。发布失败保持上一公开版；运维部署回退切换已验证的服务镜像或发布指针，不自动删除数据。产品内历史恢复则按 §5.1 新建恢复版本，两者分别记录。

## 12. 技术来源与核验边界

以下是设计所用工具的官方依据，模型/价格/接口在正式部署前还要按开通地域校验；本文不声称已配置密钥或完成模型付费调用。

- [T1] Spring MVC/SSE：`https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html`
- [T2] MySQL JSON/事务：`https://dev.mysql.com/doc/refman/8.4/en/json.html`
- [T3] MySQL locking reads：`https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html`
- [T4] Vite：`https://vite.dev/guide/`；Compose volumes：`https://docs.docker.com/reference/compose-file/volumes/`
- [T5] 百炼模型价格/可用地域：`https://help.aliyun.com/zh/model-studio/model-pricing`
- [T6] 万相文生视频：`https://help.aliyun.com/zh/model-studio/text-to-video-api-reference`
- [T7] 万相图生视频：`https://help.aliyun.com/zh/model-studio/image-to-video-api-reference`
- [T8] PptxGenJS：`https://gitbrent.github.io/PptxGenJS/`
- [T9] Playwright PDF：`https://playwright.dev/docs/api/class-page#page-pdf`
- [T10] Caddy 通配域证书：`https://caddyserver.com/docs/automatic-https#dns-challenge`
- [T11] Trellis 官方项目：`https://github.com/mindfold-ai/Trellis`，本地初始化版本 0.6.17。

历史 Atoms 观察与帮助来源使用产品文档第 9 节及核查报告，不据此推断 Atoms 后端语言、数据库或内部工作流。

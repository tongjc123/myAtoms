# Implementation

- [x] `ProviderProperties` 增加 `textModel / textModelName / textTimeout`；`textLabel()` 在未配置模型 id 时返回空串，避免出现「有名字没模型」的错觉。`application.yaml` 与 `.env.example` 同步，`scripts/api.ps1` 的白名单加入三个变量，否则本地跑起来读不到。
- [x] `ArkClient#chat()`：真实付费调用 `POST /chat/completions`（`stream=false`，`temperature=0.6`），返回内容与 completion tokens；空内容抛 `PROVIDER_EMPTY_OUTPUT`。`send/exchange/mapError` 增加 label 与 timeout 重载，视频调用保持原行为。
- [x] `ArkClient#probeTextModel()`：空 `messages` 触发 `InvalidParameter` 验证错误，不产生 token；映射 ACTIVATED / NOT_ACTIVATED / MODEL_NOT_FOUND / AUTH_FAILED / UNREACHABLE。
- [x] 新包 `com.deepwistom.atoms.web`：`WebOutline`（防御式 JSON 解析）、`WebPrompts`（结构提示词 + 页面提示词）、`WebGenerator`（两轮调用，`outline()` / `write()` 分开以便逐步 emit）、`WebHtml`（清洗与校验）。
- [x] `RunService`：`start()` 放行 WEB、GAME 仍拒绝；`startWeb()` 校验模型配置后建 run（`engine=volcengine-ark-chat/1.0`，`model_key=模型 id`）；`executeWeb()` 5 步真实执行，artifact 写 `kind=WEB / entryPath / sections / sizeBytes / model / tokens`。
- [x] `CapabilitiesController`：`generationAvailable` 改为 true，`reasonCode` 置空；`ProviderStatusService` 独立探测视频与文本模型，新增 `textModel/textState/textDetail`。
- [x] 前端：`api/runs.ts` 支持 WEB artifact 与 `startWebRun`；`ProjectDetail` 对 WEB 开放「开始生成网页」并在按钮旁显示文本模型就绪情况；`ChatPage` 增加网页产物卡与「Emma · 网页工程师」文案；`describeTextModel()` 统一状态措辞。
- [x] 预算护栏：`BudgetProperties.textReserveCny`（`ATOMS_TEXT_RESERVE_CNY`，默认 1.00）与 `BudgetService.reserveText`；第一次 chat 调用前预留，入库后 settle，失败/取消 release。上限不足时 run 以 `BUDGET_EXCEEDED` 失败且不发起任何模型调用。
- [x] 测试 `WebBudgetApiTest`：上限 0.01 + 单条预留 1.00 → run FAILED / `BUDGET_EXCEEDED` / 无 artifact / stub 收到 0 次调用 / 无版本写入。
- [x] 测试 `WebRunApiTest`（本地 stub 方舟 chat）：成功路径（两次调用 + 单文件入库 + 预览 CSP 与正文）、外链页面被拒（`PROVIDER_OUTPUT_REJECTED` 且不写版本）、模型未开通（`PROVIDER_MODEL_NOT_ACTIVATED` 且 status 事件带 code/message）。
- [x] `SystemBoundaryTest` 更新为新契约：能力接口如实报告已接入的流水线；供应商状态新增字段且未配置时文本模型为 NOT_CONFIGURED。

## Verification

- `scripts/api.ps1 -Action verify`：**44 tests, 0 failures**（WebRunApiTest 3 项 + WebBudgetApiTest 1 项）。
- `apps/web`：`tsc --noEmit` 与 `vite build` 通过。
- `.cache/web-run-check.mjs`（headless Chrome 1440×1000）：首页建草稿 → 项目页出现可点的「开始生成网页」并提示「文本模型 豆包 Seed 2.1 Pro 尚未在火山方舟控制台开通，生成会失败」→ 对话页 5 步流程、失败步骤标「未完成」、提示「生成中断：文本模型尚未在火山方舟控制台开通，开通后重新生成即可」。截图在 `./evidence/`。
- 账号侧现状（免费探测）：27 个 chat 模型全部 `ModelNotOpen`，9 个视频模型同样未开通，仅 `doubao-seed3d-2-0-260328` 可用。开通任一文本模型后无需改代码即可出真实页面。

## Revision 2026-11-15: switch text + image to Qianwen

- [x] 新增 `QwenProperties`（`atoms.qwen.*`）与 `QwenClient`：文本走 DashScope 风格 `POST /services/aigc/text-generation/generation`（`result_format=message`），图片走 `POST /services/aigc/multimodal-generation/generation`（返回 `output.choices[0].message.content[].image` URL，`download()` 下载后转 base64）。`ArkClient` 删除全部文本职责（chat/ChatResult/textModel/textLabel/textConfigured/probeTextModel），`ProviderProperties` 删除 text 三字段；`WEB_ENGINE` 改 `qianwen-chat/1.0`。
- [x] 网页流水线新增「配图生成」步骤：`qwen-image-3.0` 512×512 hero 图 → `WebHtml.injectHero` 转 `data:image/...;base64` 内嵌（预览 CSP 只放行 `img-src data:`）；配图失败降级为无配图版式，事件与 artifact（`heroImage=false`）如实注明，不阻塞页面。
- [x] 错误码：`AccessDenied.Unpurchased`（403 未购买）与 `ModelNotOpen` → `PROVIDER_MODEL_NOT_ACTIVATED`；`InvalidApiKey`（401）→ `PROVIDER_AUTH_FAILED`。`ProviderStatusService` 新增 `imageModel/imageState/imageDetail`，图片用空 content 免费探测。
- [x] `WebRunApiTest` 改为本地 stub 千问网关（DashScope 文本 + 图片 + 下载），新增第 4 项「图片未购买降级」；`SystemBoundaryTest` 更新 provider 字段断言。
- [x] 前端 `provider.ts` 增加 `imageModel/imageState/imageDetail`，`describeTextModel` 文案改千问；`ChatPage` 引擎说明改「千问文本与图片模型生成」。

## Verification (Qianwen)

- `scripts/api.ps1 -Action verify`：**45 tests, 0 failures**（WebRunApiTest 4 项）。
- `apps/web`：`tsc --noEmit` 与 `vite build` 通过。
- `.cache/qwen-live-check.mjs`（HTTP 端到端）：早期真实跑通一次——qwen-plus 两次调用（5 区块 + 16KB 源码 4808 tokens）+ qwen-image-3.0 出图 base64 内嵌，单文件 410KB，预览 200 且含 `data:image/png;base64,`。
- 账号侧现状：会话早期文本/图片模型均可出片；随后全部转为 `AccessDenied.Unpurchased`（免费额度耗尽），`GET /api/system/provider` 如实显示 text/image 均 `NOT_ACTIVATED`，网页生成 FAILED `PROVIDER_MODEL_NOT_ACTIVATED`（充值/开通后无需改代码）。

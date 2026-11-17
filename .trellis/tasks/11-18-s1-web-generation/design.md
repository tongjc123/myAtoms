# 设计

## 数据与配置

- 复用 `runs` / `run_events` / `project_versions` / `project_files`，无新迁移。
- `runs.engine = volcengine-ark-chat/1.0`，`runs.model_key` 存**模型 id**（该列是 ASCII，显示名可能含中文，因此只进事件与 artifact）。
- 新增配置：`atoms.provider.text-model`（`ATOMS_ARK_TEXT_MODEL`）、`text-model-name`、`text-timeout`（默认 120s，页面比普通对话长）。留空即关闭网页生成，不猜模型 id。

## 流水线（`RunService#executeWeb`）

1. **需求解析**：本地按标点切分提取 3 个关注点，不臆造内容。
2. **页面结构**：第 1 次 chat 调用，要求只输出 JSON（title/tagline/cta/sections），`WebOutline.parse` 防御式解析（容错代码围栏与前后缀，最多 8 区块）。
3. **页面编写**：第 2 次 chat 调用，提示词强制单文件、无外链、移动端自适应、至少一处原生 JS 交互。
4. **页面校验**：`WebHtml.sanitize` —— 去围栏、定位 `<!doctype`、必须以 `</html>` 结束、400B–600KB、`src|href|action|url()` 出现 `//` 即拒绝（`PROVIDER_OUTPUT_REJECTED`）。
5. **版本入库**：`ProjectVersionWriter.writeGenerated(id, "index.html", summary, files)`，emit WEB artifact（versionNo/sections/sizeBytes/model/tokens）。

取消用现有的 `runs.cancelRequested` 在每次模型调用后检查；异常分三类落账：`ProviderException`（带服务商码）、`ApiException`、其他 → `GENERATION_FAILED`。

## 供应商状态

`GET /api/system/provider` 增加 `textModel/textState/textDetail`：`probeTextModel()` 用空 `messages` 触发参数校验失败（不产生 token），据此区分 NOT_ACTIVATED / MODEL_NOT_FOUND / AUTH_FAILED / UNREACHABLE。前端 `describeTextModel()` 把状态翻成一句人话，项目页按钮旁直接提示「尚未开通，生成会失败」。

## 前端

- `api/runs.ts`：artifact 增加 `WEB`（`entryPath` 必填）、`startWebRun`。
- `ProjectDetail`：WEB 显示可用主按钮；其他类型保留未开放说明并指向对应工作台。
- `ChatPage`：非视频产物继续走 `ProjectPreview` iframe；WEB 增加产物卡（区块数/体积/版本/tokens）与「Emma · 网页工程师」文案。

## 权衡

- 两次调用而不是一次：结构先行能显著提高页面信息密度与一致性，代价是每个页面两倍 token（本 demo 可接受）。
- 拒绝外链而不是重写外链：预览沙箱 CSP 只放行 inline，带外链的页面必然破图，静默改写资源等于伪造效果。
- 不做本地兜底模板：宁可如实失败，也不用模板结果冒充模型生成。

# 设计：游戏模板、沙箱预览与克隆

## 数据模型

```
templates            slug(unique) type category summary author source_url cover_path current_version_id
template_versions    template_id version_no(unique per template) content_hash(unique per template)
                     entry_path file_count total_bytes
template_files       (version_id, path) content_type size_bytes content
project_versions     project_id version_no kind(TEMPLATE|GENERATED|EDIT|RESTORE)
                     template_version_id content_hash entry_path summary
project_files        (version_id, path) content_type size_bytes content
preview_sessions     token_hash(primary) project_id version_id owner_session_id expires_at
projects             + current_version_id, source_template_version_id
```

`content_hash` 由排序后的 `(path, sha256(content))` 计算，是模板版本的身份。版本一经写入不再修改；内容变化只追加新版本，因此任何已克隆项目都能回溯到它当初复制的那个版本。

## 播种

`TemplateSeeder` 在启动时扫描 `classpath:template-seed/*/manifest.json`，逐文件读取（`files` 显式声明，因此在打包 jar 内同样可读），校验入口与封面存在、单文件不超过 1MB。哈希相同则只刷新元信息，不同则 `max(version_no)+1` 发布新版本并切换 `current_version_id`。

## 克隆事务

```
BEGIN
  INSERT projects (status 由 current_version_id 推导)
  INSERT project_versions (version_no=1, kind=TEMPLATE, template_version_id, content_hash, entry_path)
  INSERT project_files SELECT ... FROM template_files WHERE version_id = 模板版本
  UPDATE projects SET current_version_id, source_template_version_id
COMMIT
```

复制行数与模板声明不一致时抛 `TEMPLATE_INCOMPLETE` 并整体回滚，避免留下半份项目。幂等由 `IdempotencyService` 保证：同一键返回同一项目，同键不同请求返回 `IDEMPOTENCY_CONFLICT`。

## 预览与隔离

- 模板预览是公开只读资源，`Cache-Control: max-age=300, public`。
- 项目预览先 `POST /api/projects/{id}/preview-sessions` 换取 256 位随机令牌（只存 SHA-256、30 分钟、单项目单版本），再由 `GET /api/previews/{token}/{path}` 读取。沙箱 iframe 处于 opaque origin，不能依赖所有者 Cookie，令牌同时避免把会话凭证带进帧内。
- 所有预览响应固定 `CSP: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; connect-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'`，并带 `nosniff`、`no-referrer`。
- 路径经 `MediaTypes.normalize` 白名单校验：去掉前导 `/`，拒绝空值、`..`、反斜杠、NUL、超过 300 字符及白名单外字符；内容类型按扩展名映射，未知类型降级为 `application/octet-stream`。
- 前端 `iframe` 仅授予 `allow-scripts allow-pointer-lock`，不给 `allow-same-origin`、弹窗或表单权限。

## 前端结构

`TemplateGallery`（分类筛选、封面卡、深链 `?type=GAME&template=<slug>`）→ `TemplateDialog`（沙箱试玩 + 元信息 + 「基于这个游戏构建」→ 克隆表单 → 进行中状态 → `openProject`）。`ProjectPreview` 在项目详情中申请令牌并渲染当前版本；`ProjectList`/`ProjectDetail` 依据 `currentVersion` 显示 `可预览 · v1`。

## 已知取舍

- 预览令牌是能力式链接：持有者在有效期内可查看该版本。这是沙箱 iframe 无法携带 Cookie 的直接结果，已在 README 与 UI 文案中说明。
- 模板文件存于数据库（单个 ≤1MB），便于备份与回滚；接入真实生成、资源体积增大后应迁移到持久对象存储，并保留同样的版本/哈希规则。

# 设计：网页模板

## 模板内容

四套均为单文件、自包含、系统字体的 HTML，无外链资源（预览 CSP 只放行内联样式/脚本）：

| slug | 名称 | 分类 | 真实交互 |
| --- | --- | --- | --- |
| web-portfolio-minimal | 极简个人作品集 | 作品集 | 作品筛选、滚动淡入、复制邮箱 |
| web-product-launch | SaaS 产品落地页 | 营销 | 月付/年付价格重算、FAQ 折叠、顶栏阴影 |
| web-event-signup | 活动报名页 | 活动 | 实时倒计时、表单本地校验与结果回显 |
| web-doc-site | 产品文档站 | 内容 | 目录搜索过滤、滚动高亮、深浅主题 |

每套尾部带一个共用的 `?peek=1` 巡游片段（模板卡的悬停预览靠它），并标注“模板示例/内容为虚构”，避免误用。

## 渲染与预览

- 卡片封面：`WebFrame` 用 ResizeObserver 测容器宽，iframe 固定 1280×800 设计视口后按 `scale = 宽/1280` 等比缩放；封面 iframe 允许脚本（倒计时、主题等要真实跑）但 `pointer-events:none`，点击仍落到卡片按钮。
- 悬停预览：沿用 `useHoverPeek`（已挪到 `features/templates/`，幻灯片/网页共用），140ms 后在封面上叠一层 `?peek=1` 的同页 iframe，页面脚本自行上下滚动，模拟“跑马灯”；覆盖层忽略指针避免进出 iframe 触发 hover 抖动。
- 弹窗：`WebPreviewDialog`，桌面 1280×800 / 手机 390×780 两种视口，iframe 允许脚本与指针，页内交互真实可用；底部是描述 + 克隆表单（项目名称默认模板名）。

## 克隆

复用现有 `POST /api/templates/{slug}/clone`：`project_versions.kind=TEMPLATE`、`project_files` 逐字节复制、幂等键去重；成功后 `openProject(id)` 跳到项目详情，沿用一次性令牌沙箱预览。

## 安全边界

预览 CSP：`default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; frame-ancestors 'self'`；模板不可发请求、不可读工作台数据；克隆要求会话 + Origin + CSRF。

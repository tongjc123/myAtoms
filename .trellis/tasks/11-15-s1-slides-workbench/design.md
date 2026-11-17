# 设计：幻灯片工作台、跑马灯预览与可观察运行

## 数据模型（V4 迁移）

```
runs                 id project_id owner_session_id purpose status engine theme slide_count
                     cancel_requested total_steps error_code error_message
                     created_at started_at finished_at
run_events           (run_id, seq) UNIQUE  type  data(JSON)  created_at
template_imports     id owner_session_id name filename content_type size_bytes slide_count
                     parse_state(PARSED|STORED_ONLY) outline(JSON) content(MEDIUMBLOB) created_at
```

`runs` 是任务事实，`run_events` 是过程事实。事件只在动作真正完成后写入，`seq` 单调递增且在同一 run 内唯一，因此客户端可以按游标续接、去重，服务端也能只靠日志重建整段对话。

## 内容规格与渲染

`SlideDeck{title, theme, brand, slides[]}`；每页一个 `layout`：cover / agenda / section / statement / bullets / stats / quote / closing。

`DeckRenderer` 输出**单文件** `index.html`：把 `deck.css`、`deck.js` 与规格 JSON 全部内联。原因是预览 iframe 处于 opaque origin，CSP 只放行 `script-src/style-src 'unsafe-inline'`，外链样式脚本会被直接拦掉。内联同时让模板播种、克隆、生成三处共用同一份字节。

共享引擎特性：

- 舞台固定 1280×720，外层容器按比例 `transform: scale()`，缩略图、跑马灯、成品、全屏都复用同一份 DOM。
- `?peek=1` 进入跑马灯模式：隐藏导航 chrome，1900ms 自动翻页，`prefers-reduced-motion` 下不动，页面不可见时停表。
- 封面标题按长度分三档（≤14 / ≤22 / 更长）降字号，并限制在画布宽度内，避免长标题溢出舞台。

## 规划器

`SlidePlanner.plan(name, prompt, inheritedTheme, imported, inheritedSlides)`：

1. 导入大纲优先；否则按 `。！？；` 切句、按 `，、` 切分句，逐条生成要点；最多 6 个要点。
2. 无逗号的句子无法承载要点，最多提一页为 statement，其余汇入「补充说明」页。`pickClaim` 跳过纯时间/地点/报名类句子，避免把「活动在 X 月 X 日开幕」当成核心主张。
3. 量化信息（`数字 + %/万/亿/元/人/天…`）单独成 stats 页；纯年份和日期不计入。
4. 主题由关键词映射到 ink / swiss / editorial / sky；引用模板时继承模板主题。
5. 封面标题取访客自己的标签（项目名），经 `prettyTitle` 去掉「帮我/为/做一套」等指令外壳；再退到提示词首句。

它不新增事实：所有文字都来自提示词或导入大纲。

## 运行流水线

```
start()  校验类型(SLIDES) → 单活跃 run → 解析模板主题/导入大纲 → 入队
execute() analyze(Mike) → outline(Alex) → theme(Emma) → layout(Iris, 逐页 progress)
          → review(Sarah) → publish(David) → 写版本 + artifact 事件
```

- 每个 `step` 先发 `running` 再发 `done`，`pause()` 在 120ms 切片之间检查 `cancel_requested`，命中即抛 `Stopped` 结束为 `CANCELLED`，不写版本。
- `GET .../events` 先回放已存储事件，再每 250ms 轮询新行，终态且无新行时关闭连接；`after` 游标支持断线续接。
- 发布走 `SlideDeckWriter`：版本、文件、项目指针在同一事务内完成，失败整体回滚并保留上一版本。

## 导入

`PptxOutlineParser` 只做文本级读取：扫描 zip 内 `ppt/slides/slideN.xml`，按序号排序，提取 `<a:t>` 文本，第一条当标题、其余当要点，最多 12 条/页、60 页。pdf 不解析正文，只按 `/Type /Page` 计数，`parseState=STORED_ONLY`，UI 注明「已保存原文件 · 暂不支持文字解析」。

## 前端结构

`SlidesWorkbench`（hero + 画廊 + 引用状态）→ `SlideTemplateCard`（`useHoverPeek` 延迟 140ms 挂载 iframe，离开即卸载）→ `SlideComposer`（chip、模板感知的预填、提交后建项目并 `startSlidesRun`）→ `ChatPage`（`useRunStream`：先取快照再跟随事件，停止按钮、步骤时间线、产物卡、右侧 `SlidePreview` 缩略图栏 + 全屏 overlay）。

## 已知取舍

- 项目名、封面标题、请求体里的提示词三者可能都指向同一句话，`deriveName`/`prettyTitle` 两侧做同样的清洗，避免列表里出现被截断的半句。
- 引用模板但未改动预填需求时，用模板名命名项目（`<模板名> 演示`），因为此时访客还没说主题。
- 改共享引擎会改变渲染字节，模板按内容哈希发布新版本；已克隆项目仍停留在旧版本，这是版本不可变的直接结果。

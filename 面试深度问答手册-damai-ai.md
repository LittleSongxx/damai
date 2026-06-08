# damai-ai 面试深度问答手册

> 本手册基于 `damai-ai` 源码、配置、实体、接口和测试反推，不参考 README、项目介绍类说明文档。  
> 用途：应对 AI 应用开发岗位面试中的深挖追问。回答时建议先讲“链路、边界、取舍”，再补实现落点。  
> 重要口径：不要把实验指标说成线上 SLA；不要把借鉴的设计思想说成已经引入的框架；不要把默认关闭或依赖外部配置的能力说成开箱即完整可用。

---

## 目录

1. [真实大厂面试节奏与使用方法](#1-真实大厂面试节奏与使用方法)
2. [项目定位与整体架构](#2-项目定位与整体架构)
3. [运行态、状态机与 SSE](#3-运行态状态机与-sse)
4. [路由、Skill 与工具治理](#4-路由skill-与工具治理)
5. [AI 购票与人在环审批](#5-ai-购票与人在环审批)
6. [RAG 客服与 CRAG 检索链路](#6-rag-客服与-crag-检索链路)
7. [知识入库、文档生命周期与缓存](#7-知识入库文档生命周期与缓存)
8. [RAG 评测与简历指标口径](#8-rag-评测与简历指标口径)
9. [MCP、智能运维与 NL2SQL](#9-mcp智能运维与-nl2sql)
10. [Guardrails、安全权限与审计](#10-guardrails安全权限与审计)
11. [韧性、限流、成本与异步化](#11-韧性限流成本与异步化)
12. [记忆、画像、Prompt 与可观测性](#12-记忆画像prompt-与可观测性)
13. [前端/API/与 damai-pro 集成](#13-前端api与-damai-pro-集成)
14. [AI 应用开发通用八股映射](#14-ai-应用开发通用八股映射)
15. [高压追问与边界回答](#15-高压追问与边界回答)
16. [真实面试追问链路专项](#16-真实面试追问链路专项)
17. [面试速记 Checklist](#17-面试速记-checklist)

---

## 1. 真实大厂面试节奏与使用方法

### Q0-1：真实大厂 AI 应用 / Java 后端面试通常怎么展开？

**A**：结合公开面经和大厂面试准备资料，真实面试一般不是按知识点顺序问，而是按下面节奏推进：

```text
自我介绍 1~2 分钟
  ↓
挑一个简历项目深挖 15~30 分钟
  ↓
围绕项目追问技术选型、指标、异常、压测、边界
  ↓
抽项目相关八股：Spring、MySQL、Redis、MQ、并发、JVM、网络
  ↓
AI 岗专项：RAG、Agent、Function Calling、幻觉、评测、成本
  ↓
场景设计：如果用户量扩大 / 线上故障 / 成本过高 / 权限泄露怎么办
  ↓
反问与综合判断
```

所以这份手册不要从 Q1 背到 Q83。真实用法是：先背熟 **项目主线**，再按 **追问链** 随机切换。

---

### Q0-2：项目开场 1 分钟怎么讲？

**A**：1 分钟版本只讲定位、职责、亮点，不展开细节：

> `damai-ai` 是我在票务系统上做的 **AI 应用服务**，核心是把 **Spring AI** 接入到真实业务链路里。用户侧支持 **规则问答** 和 **购票引导**，管理侧支持 **日志指标诊断** 和 **NL2SQL 问数**。我主要负责 **Assistant 运行态**、**Skill 编排**、**RAG 检索链路**、**购票审批**、**安全护栏** 和 **评测体系**。这个项目不是简单调模型接口，重点是把 **AI 的不确定性** 放进 **可追踪、可审批、可评测** 的工程闭环里，比如 **Run/Event/Action 状态模型**、**dense+HyDE+sparse 多路 RAG**、**NL2SQL AST 校验** 和 **工具白名单**。

这个版本适合“请你介绍一下项目”。不要一上来讲太多类名，否则面试官会打断。

---

### Q0-3：项目深挖 3 分钟怎么讲？

**A**：3 分钟版本按“背景 → 架构 → 难点 → 结果 → 边界”讲：

```text
背景：票务业务有规则问答、购票引导、运维问数需求，普通 ChatBot 不够安全。
架构：Assistant Runtime 管 Run/Event/Action；Routing/Skill 管能力选择；RAG 管知识问答；OPS 管日志指标和 NL2SQL；Guardrails 管安全。
难点 1：购票有副作用，所以设计 Action 审批和二次校验。
难点 2：规则问答容易检索不准，所以做 dense+HyDE+sparse、RRF、Rerank、CRAG。
难点 3：NL2SQL 风险高，所以默认关闭、管理员权限、Schema 白名单、AST 校验、只读执行。
结果：离线评测集上 Recall@5、NDCG@5、MRR 有明确提升，并能通过 Trace 复盘检索和工具调用。
边界：部分异步链路和幻觉检测还不是最终生产级，需要继续补齐 bad case 回流和证据结构化。
```

真实面试里，主动讲 **边界** 比把项目吹满更稳。

---

### Q0-4：项目深挖 5 分钟怎么讲？

**A**：5 分钟版本适合面试官说“详细讲讲你这个项目”。结构如下：

1. **业务问题**：票务场景里，用户问规则、查节目、想购票；运营/运维想查日志、指标、订单数据。
2. **系统边界**：`damai-pro` 保持交易核心，`damai-ai` 作为 AI 编排层，不直接替代订单系统。
3. **运行态设计**：用户请求先创建 `AiRun`，SSE 消费事件；所有路由、工具调用、消息、审批都落 `AiRunEvent`；购票这类高风险动作落 `AiAction`。
4. **RAG 链路**：知识入库时做 Markdown front matter、parent/child chunk、Qdrant + ES 双索引；检索时 dense、HyDE、sparse 三路召回，融合前规则过滤，RRF 后再重排和 CRAG 纠错。
5. **业务动作治理**：AI 生成购票预览，用户确认后重新校验手机号、购票人、节目、票档、价格、余票，再调用订单服务。
6. **运维问数治理**：OPS 需要管理员权限；NL2SQL 默认关闭，开启后也只允许白名单 SELECT，禁止敏感字段和物理分片表。
7. **工程保障**：Guardrails、工具白名单、Sentinel、RateLimit、缓存、RabbitMQ、Checkpoint、RAG Eval 和 Trace。
8. **指标和边界**：指标是离线评测口径；项目已有治理基础，但线上 bad case 闭环、异步入库一致性、证据结构化还可以继续增强。

---

### Q0-5：真实面试中最容易被连续追问的点是什么？

**A**：通常不是“你用了什么技术”，而是这些问题：

| 追问方向 | 面试官真实目的 | 你应该怎么答 |
|---|---|---|
| 为什么这么设计 | 看你是不是只会堆技术名词 | 讲业务约束、风险和取舍 |
| 指标怎么来的 | 看有没有实验口径和可复现性 | 讲数据集、baseline、计算方式、边界 |
| 出问题怎么办 | 看有没有线上意识 | 讲降级、重试、审计、回滚、告警 |
| 和普通方案比优势在哪 | 看选型能力 | 讲成本、准确率、延迟、治理成本 |
| 你具体做了什么 | 看项目真实性 | 明确模块、链路、产物和踩坑 |
| 如果规模扩大十倍 | 看系统设计能力 | 讲瓶颈、队列、缓存、限流、隔离 |
| AI 不可靠怎么办 | 看 AI 工程理解 | 讲证据约束、审批、校验、评测、人工兜底 |

---

### Q0-6：回答项目问题的通用结构是什么？

**A**：建议固定用“五段式”：

```text
1. 先给结论：这个模块解决什么问题。
2. 讲链路：输入、处理、输出、状态变化。
3. 讲取舍：为什么不用更简单或更复杂的方案。
4. 讲保障：失败、并发、安全、成本、可观测怎么处理。
5. 讲边界：哪些已经实现，哪些是后续优化。
```

真实面试里，答题不是越长越好。先给结论，面试官感兴趣再展开。

---

### Q0-7：哪些话术容易被面试官追着打？

**A**：下面这些要避免：

| 不稳说法 | 更稳说法 |
|---|---|
| “我们用了 LangGraph” | “借鉴 durable execution 思路，自研 Run/Event/Action/Checkpoint” |
| “RAG 准确率 88%” | “离线评测集 Recall@5 约 0.88，不是线上准确率” |
| “AI 能自动买票” | “AI 生成购票预览，用户确认后才下单” |
| “NL2SQL 很安全” | “默认关闭，管理员权限 + AST 校验 + 白名单 + 只读执行降低风险” |
| “完全解决幻觉” | “通过证据约束、CRAG、输出护栏和审批降低风险，不能说完全消除” |
| “MQ 异步入库已完整闭环” | “有 MQ 基础设施，部分入口仍同步，后续要统一异步任务状态” |

---

### Q0-8：如果面试官要求“画一下架构”，怎么口述？

**A**：可以按这个图口述：

```text
前端 / Web Assistant
  ↓
AssistantController
  ↓
AssistantRuntimeService
  ├─ Run/Event/Action 状态模型
  ├─ Input / Response / Tool Guardrails
  ├─ RouteService + SkillSelector
  └─ SkillExecutor
       ├─ Business：节目查询、购票预览、Action 审批
       ├─ Knowledge：RAG 检索、CRAG 评估、答案生成
       ├─ General：通用问答 / WebSearch
       └─ OPS：Log、Metrics、NL2SQL
            ↓
       Qdrant / Elasticsearch / MySQL / Redis / RabbitMQ / Prometheus / damai-pro
```

口述时强调：**Controller 不是核心，核心是 Runtime 把 AI 请求变成可追踪的状态流。**

---

### Q0-9：真实面试里怎么把 AI 项目和 Java 后端能力关联起来？

**A**：不要只讲 RAG/Agent，要主动把 AI 项目落到后端工程能力：

- Spring Boot：自动配置、Controller、Service、事务、AOP、配置属性。
- MySQL：Run/Event/Action、RAG 元数据、评测数据、审计数据。
- Redis/Caffeine：缓存、限流、热点检索结果。
- RabbitMQ：削峰、异步记忆、异步入库基础设施。
- Elasticsearch/Qdrant：多路检索存储选型。
- Sentinel：下游熔断和降级。
- SSE/Reactor：流式输出和背压。
- 安全：权限、内部 token、SQL AST 校验、PII 脱敏。

这样面试官会把你归类为“能落地 AI 的后端工程师”，而不是只会调 API 的候选人。

---

## 2. 项目定位与整体架构

### Q1：一句话介绍 damai-ai。

**A**：`damai-ai` 是基于 **Spring Boot 3 + Spring AI** 构建的 **智能票务服务平台**，围绕票务业务做了四类 AI 能力：**购票引导**、**规则知识问答**、**智能运维**、**自然语言问数**，并在运行态上补了 **审批**、**权限**、**工具白名单**、**RAG 评测**、**SSE 事件流** 和 **安全护栏**。

更适合面试的版本：

> 我做的不是一个单纯 ChatBot，而是一个面向票务场景的 **AI 工作流服务**。用户侧支持 **购票、节目查询和规则问答**；管理侧支持 **日志、指标、链路诊断和 NL2SQL 问数**。核心难点是把 **LLM 的不确定性** 放进可控的 **Run/Event/Action 状态模型** 里，并用 **RAG、工具白名单、AST 校验、审批和审计** 来约束风险。

---

### Q2：这个项目和普通 AI 客服有什么区别？

**A**：普通 AI 客服通常是“用户问题 → 检索 → 大模型回答”。`damai-ai` 多了几层生产化约束：

- **运行态可追踪**：每次对话是一个 `Run`，所有中间事件都落库并通过 SSE 推送。
- **业务动作可审批**：购票不会由 AI 直接下单，而是生成 `Action`，用户确认后才调订单服务。
- **工具调用可治理**：Skill 声明可用工具白名单，工具输入和输出都会过护栏和审计。
- **检索质量可评测**：RAG 不只做向量检索，还做多通道召回、融合、纠错和离线评测。
- **高风险能力有权限边界**：运维、NL2SQL 只对管理员开放，默认也需要配置打开。

一句话：**它的重点不是“能回答”，而是“能在业务边界内可控地回答和执行”。**

---

### Q3：从模块上看，damai-ai 分成哪些域？

**A**：可以按作用域拆成九块：

| 模块 | 作用 |
|---|---|
| Assistant Runtime | 创建 Run、状态流转、事件落库、SSE 推送、Action 审批 |
| Routing / Skill | 意图路由、Skill 选择、权限检查、Schema 校验、工具白名单 |
| Business Skills | 节目推荐、节目搜索、节目详情、购票预览和审批 |
| Knowledge / RAG | 规则问答、多通道检索、CRAG 纠错、答案组装 |
| Ingestion | Markdown 解析、分层切片、embedding、Qdrant/ES 索引、版本元数据 |
| Ops / MCP | 日志查询、Prometheus 指标、NL2SQL 工具暴露 |
| Guardrails | Prompt 注入、PII、毒性内容、幻觉提示、工具输入输出拦截 |
| Resilience / Cost | Sentinel 熔断、限流、缓存、Token 预算、模型 fallback |
| Observability / Eval | 阶段追踪、工具调用审计、RAG 离线评测、线上检索 Trace |

---

### Q4：你在项目中的核心贡献怎么讲？

**A**：建议按“业务闭环 + RAG 质量 + 安全治理 + 工程化”四块讲：

1. **业务闭环**：把自然语言购票做成“槽位抽取 → 节目查询 → 购票预览 → 用户审批 → 正式下单”的可控流程，而不是让模型直接操作订单。
2. **RAG 质量**：构建 dense、HyDE、sparse 三路召回，融合前做证据过滤，融合后做去重、重排、时效性和父块提升，并用 CRAG 对低质量结果重检。
3. **安全治理**：对高风险链路加管理员权限、工具白名单、AST SQL 校验、敏感字段拦截、PII/Prompt 注入检测和审计。
4. **工程化稳定性**：用 Run/Event/Action 状态模型、SSE、Checkpoint、RabbitMQ、Sentinel、缓存、RAG 评测和 Trace 把 AI 能力放进可观测、可恢复、可降级的系统里。

---

### Q5：项目里有没有用 LangGraph？简历或面试里该怎么说？

**A**：源码里没有直接引入 LangGraph/LangChain 依赖。更准确的说法是：

> 项目借鉴了 LangGraph durable execution / checkpoint 的思想，但 Java 侧是自研的 Run/Event/Action/Checkpoint 状态模型，不是直接使用 LangGraph 框架。

不要说“项目基于 LangGraph 实现”，否则面试官追依赖或源码会被打穿。

---

### Q6：这个项目最核心的技术难点是什么？

**A**：核心难点是 **AI 能力业务化后的可控性**。

票务系统不是闲聊，AI 一旦能查订单、引导购票、查库、看日志，就会触发三个问题：

- LLM 生成不稳定：可能槽位提错、SQL 写错、回答幻觉。
- 工具有副作用：购票下单、查库、日志指标都需要边界。
- 长链路难观测：用户要看进度，后端要能复盘工具调用和检索证据。

所以项目没有把所有事情交给一个 Agent 自由发挥，而是通过 **状态机、Skill、工具白名单、审批、护栏和评测** 把风险拆开治理。

---

## 3. 运行态、状态机与 SSE

### Q7：一次对话从请求到返回的主链路是什么？

**A**：主链路可以这样讲：

```text
POST /assistant/runs
  ↓
创建 AiRun，状态 CREATED
  ↓
GET /assistant/runs/{runId}/events 建立 SSE
  ↓
如果 Run 还未执行，则 claim 后异步启动
  ↓
Input Guardrail
  ↓
路由决策：BUSINESS / KNOWLEDGE / GENERAL / OPS
  ↓
Skill 选择与权限检查
  ↓
Skill 执行：可调用工具、RAG、NL2SQL、购票预览等
  ↓
输出护栏
  ↓
写 MESSAGE_DELTA / ACTION_REQUIRED / RUN_COMPLETED 等事件
  ↓
前端消费 SSE 更新 UI
```

这条链路的关键点是：**创建 Run 和消费事件是分离的**，后端通过 **事件流** 把中间态持续推给前端。

---

### Q8：`AiRun`、`AiRunEvent`、`AiAction` 分别解决什么问题？

**A**：三者对应运行态的三个层次：

| 对象 | 解决的问题 |
|---|---|
| `AiRun` | 一次用户请求的主状态，记录会话、用户、路由、Skill、当前阶段、最终摘要和错误 |
| `AiRunEvent` | 运行过程事件，按递增序号记录路由、Skill、工具、消息 delta、审批等过程 |
| `AiAction` | 需要用户确认的业务动作，比如购票预览，记录状态、快照、幂等键、过期时间和执行结果 |

面试时可以强调：**`Run` 管生命周期，`Event` 管可观测，`Action` 管高风险业务动作。**

---

### Q9：Run 的状态流转是什么？

**A**：主要状态是：

```text
CREATED
  ↓
RUNNING
  ├─ COMPLETED
  ├─ FAILED
  └─ WAITING_ACTION
        ├─ 用户 approve → COMPLETED / FAILED
        └─ 用户 reject  → COMPLETED（业务动作被取消）
```

购票这类链路会进入 `WAITING_ACTION`，普通问答直接进入 `COMPLETED`。事件流里还会出现 `RUN_STARTED`、`ROUTE_SELECTED`、`SKILL_STARTED`、`TOOL_STARTED`、`MESSAGE_DELTA`、`ACTION_REQUIRED` 等更细粒度事件。

---

### Q10：SSE 是怎么实现的？为什么不用普通 HTTP 一次性返回？

**A**：SSE 适合 AI 对话，因为服务端需要持续推送 token、工具状态、审批状态和完成事件。项目里：

- 创建 Run 后返回 `eventStreamPath`。
- 前端连接 `/assistant/runs/{runId}/events`。
- 后端先回放已落库事件，再订阅 live 事件。
- 每个 token 或文本块以 `MESSAGE_DELTA` 事件推送。
- 终态事件出现后 SSE 自动结束。

好处是：刷新页面或网络中断后，可以通过 **已落库事件恢复进度**，不依赖内存中的一次性连接。

---

### Q11：事件顺序怎么保证？

**A**：每次追加事件前，后端会锁定对应 `AiRun`，递增 `eventSeq`，然后把新事件写入 `AiRunEvent`。SSE 回放时按 `eventOrder` 升序输出。

这比直接往内存队列里写更可靠：即使前端晚连接，也能看到已经发生过的 **路由、工具调用和审批事件**。

---

### Q12：Checkpoint 在项目里做了什么？

**A**：`CheckpointManager` 把关键阶段的中间结果写到 `AiRun.resumableStateJson`，比如路由完成、Skill 开始、检索完成、SQL 生成、购票预览生成。失败或恢复时可以跳过已经完成的阶段。

准确说法：

> 这是一个 **轻量级自研 checkpoint**，不是完整工作流引擎。它能减少失败后的 **重做成本**，但具体每个 Skill 能恢复到多细，还取决于该 Skill 是否把 **关键中间产物** 写进 checkpoint。

---

## 4. 路由、Skill 与工具治理

### Q13：路由怎么判断用户请求该走哪类能力？

**A**：路由是多层策略：

1. **显式 hint**：前端可通过 `clientContext.routeHint` 指定路由。
2. **关键词打分**：购票、退票、日志、SQL、艺人资讯等关键词映射到 BUSINESS、KNOWLEDGE、OPS、GENERAL。
3. **意图树兜底**：灰区问题会进入澄清，而不是强行选择。
4. **结构化意图识别**：用 LLM 结构化输出识别 BUY_TICKET、QUERY_PROGRAM、REFUND、CONSULT 等。
5. **权限修正**：非管理员命中 OPS 时会被转成澄清或业务/知识能力。

这套设计的重点是：**简单明确的请求不用每次都调 LLM，模糊请求才走更贵的结构化识别。**

---

### Q14：Skill 系统为什么要单独抽象？

**A**：因为路由只回答“这是什么类型的问题”，Skill 解决“具体用哪套业务能力处理”。

一个 Skill 会声明：

- `skillId`、名称、版本和分类；
- 触发关键词和示例；
- 允许使用的工具白名单；
- 风险等级；
- 是否需要管理员权限；
- 是否需要用户审批；
- 输入输出 Schema；
- 是否允许前端选择、是否允许模型选择。

这种抽象让 **能力注册、权限、工具治理、评测、前端展示** 统一起来，而不是散落在各个 Controller 里。

---

### Q15：工具白名单怎么生效？

**A**：Skill 执行前会打开一个**工具作用域**，作用域里只包含该 Skill descriptor 允许的工具。工具统一通过 `AssistantToolInvoker` 调用，调用前会检查当前工具名是否在**白名单**里。

这避免了一个低风险 Skill **越权调用高风险工具**，比如普通问答不应该能调用 `nl2sql.executeReadonly` 或 `preparePurchase`。

---

### Q16：这个项目是不是一个多 Agent 系统？

**A**：它更准确是 **Skill-Orchestrated Agent Workflow**，不是多个 Agent 自由对话的系统。

- Orchestrator：路由、Skill 选择、答案完整性判断、替代策略重试。
- Worker：具体 Skill，例如购票、知识问答、运维问数。
- 共享上下文：会话记忆、用户画像、Run/Event 状态。
- 没有实现多个 Agent 之间的自由通信或协商。

所以不要过度包装成“复杂多智能体协作”。可以说：**项目采用了 Orchestrator-Worker 思路，但落地上是 Skill 编排，不是多 Agent 群聊。**

---

## 5. AI 购票与人在环审批

### Q17：AI 购票完整流程是什么？

**A**：完整流程是：

```text
用户自然语言购票请求
  ↓
BUSINESS 路由
  ↓
选择 business.purchase.prepare
  ↓
槽位抽取：城市、艺人/节目、手机号、购票人证件号、票档价格、数量
  ↓
查询节目和票档
  ↓
匹配当前用户实名购票人
  ↓
生成 ProgramOrderCreateDto 快照
  ↓
创建 AiAction，状态 WAITING，有 15 分钟过期时间
  ↓
前端展示 ACTION_REQUIRED
  ↓
用户确认
  ↓
重新校验节目、票档、价格、余票、购票人、手机号
  ↓
调用 damai-pro 订单接口创建订单
```

关键点：**AI 只生成预览和待审批动作，不直接创建订单。**

---

### Q18：为什么购票必须做人机审批？

**A**：因为购票是**有副作用的业务动作**，会影响用户钱、票和订单状态。审批解决四个风险：

- 槽位抽取可能错，比如票价、数量、购票人识别错。
- 节目和票档可能在用户确认前发生变化。
- 用户必须对最终下单有明确授权。
- 后端需要留下可审计的动作快照。

所以项目把 AI 能力限制在**生成购票预览**，真正下单由**用户点击确认**触发。

---

### Q19：审批时为什么要重新校验？

**A**：因为预览到确认之间可能发生**状态变化**。审批时会重新校验：

- 当前登录用户手机号是否与预览生成时一致；
- 购票人是否仍然属于当前用户；
- 节目是否还存在；
- 票档是否还存在；
- 票档价格是否变化；
- 余票是否满足数量。

如果校验失败，就**拒绝继续下单**，要求重新生成预览。

---

### Q20：如何避免用户重复点击导致重复下单？

**A**：有两层保护：

1. `AiAction` 有**状态流转**，审批时会先 **claim**，状态从 `WAITING` 进入 `APPROVING/ORDERING`，并发请求不会都进入下单。
2. 下单调用会带**幂等键**，幂等键由 runId 和快照 hash 组合生成，避免同一个预览重复创建订单。

---

### Q21：AI 购票和 Spring AI Tool Calling 是什么关系？

**A**：业务工具通过 `@Tool` 暴露，比如节目推荐、节目搜索、节目详情、购票预览。模型可以在 BUSINESS Skill 中选择调用这些工具。

但购票预览和审批不是单纯 Tool Calling，它还叠加了：

- 工具白名单；
- 工具输入/输出护栏；
- 工具事件审计；
- `AiAction` 状态机；
- 审批后二次校验。

所以回答时不要只说“Function Calling 调订单接口”，更准确是：**Tool Calling 负责把模型和业务查询工具接起来，Action 状态机负责控制真正有副作用的订单动作。**

---

## 6. RAG 客服与 CRAG 检索链路

### Q22：RAG 的整体链路是什么？

**A**：完整路径可以概括成：

```text
用户规则问题
  ↓
KnowledgeRetrievalPlanner：复杂度分级
  ├─ SIMPLE：dense + sparse 简化检索，不 rerank
  └─ MEDIUM/COMPLEX：完整检索链
        ↓
Query Rewrite / Multi-query / HyDE / Sparse
        ↓
融合前 Evidence Gate
        ↓
RRF 融合
        ↓
去重、Lost-in-Middle、时效性、父块提升、Rerank
        ↓
KnowledgeRetrievalEvaluator 评估
        ├─ CORRECT：轻量精炼
        ├─ AMBIGUOUS：扩大 TopK + 子问题补检
        └─ INCORRECT：LLM 改写查询 + 2x TopK 重检
        ↓
组装证据和答案 Prompt
        ↓
输出回答 + 来源/Trace
```

---

### Q23：RAG 为什么要做复杂度分级？

**A**：不同问题需要不同成本。

| 分级 | 策略 |
|---|---|
| SIMPLE | TopK 小、不开 Rerank、不拆子问题 |
| MEDIUM | TopK 默认 10，开启完整检索和 Rerank |
| COMPLEX | 在 MEDIUM 基础上做子问题拆解并行补检 |

这能避免简单问题被复杂链路拖慢，也能让复杂问题有更高覆盖率。

---

### Q24：三路召回分别是什么？

**A**：项目里是**三路互补召回**：

- **DenseSearchChannel**：用 embedding 从 Qdrant 做语义检索。
- **HydeSearchChannel**：先生成假设答案或假设文档，再用它做向量检索，适合短问题、模糊问题。
- **SparseSearchChannel**：用 Elasticsearch 做关键词/BM25 风格检索，适合精确词、规则名、票档、状态名。

面试重点：**Dense 解决语义相似，Sparse 解决关键词精确，HyDE 解决查询过短或表达不完整。**

---

### Q25：为什么不是只用向量数据库？

**A**：票务规则里有很多精确表达，比如“电子票”“实名观演人”“儿童票”“退票手续费”“VIP 票档”。只用向量容易出现两个问题：

- 相似但不精确：找到了语义接近但规则不对应的片段。
- 精确词漏召回：专有名词、状态码、票档名不一定被向量表示好。

所以项目用 **Qdrant 做语义召回**，用 **Elasticsearch 做关键词召回**，再用 **RRF 融合**。

---

### Q26：Evidence Gate 到底是什么？旧文档说它是 LLM 判断，对吗？

**A**：旧说法不准确。源码里的 `EvidenceGatePostProcessor` **不是 LLM 判断**，而是融合前的**规则过滤**：

- dense / HyDE 结果按最低向量相似度过滤；
- sparse 结果按相对最高分比例过滤；
- 分通道过滤后再进入 RRF。

LLM-as-Judge 发生在后面的 `KnowledgeRetrievalEvaluator`，用于判断语义相关性、覆盖度和矛盾，不是 Evidence Gate 本身。

面试时如果被问到，可以主动修正：

> Evidence Gate 在当前实现里是**轻量规则门**，优点是成本低、稳定；LLM 判断放在 **CRAG 评估阶段**，避免每个候选 chunk 都调用模型。

---

### Q27：RRF 怎么解决不同检索通道分数不可比？

**A**：不同通道分数没有统一尺度：向量相似度通常 0-1，BM25 可能大于 1，HyDE 又是另一种查询分布。RRF 不直接比较原始分数，而是比较排名：

```text
score(doc) = Σ weight_i / (k + rank_i)
```

项目里还会根据 query type 动态调整 dense 和 sparse 权重：

- 语义型问题更偏 dense；
- 关键词型问题更偏 sparse；
- 混合问题两边接近。

---

### Q28：Rerank 在哪里发挥作用？

**A**：RRF 解决“多通道融合”，Rerank 解决“融合后谁更适合放进上下文”。融合后的候选集合会经过后处理链，再根据配置调用 rerank 服务选出更相关的 TopN。

Rerank 不替代召回，它只重排候选。如果召回阶段完全没找对，Rerank 也救不回来，所以项目还需要 HyDE、sparse 和 CRAG 纠错。

---

### Q29：Lost-in-Middle 重排解决什么问题？

**A**：大模型对上下文中间位置的信息关注度可能下降。Lost-in-Middle 重排会把高价值证据放在更容易被模型关注的位置，减少“检索到了但生成时没用上”的问题。

它不是提高召回率，而是提高生成阶段对证据的利用率。

---

### Q30：父块提升是什么？

**A**：检索时子块更精确，但回答时只看子块可能上下文不足。父块提升会根据 `parentBlockId` 把同一父块的更大上下文带进来。

项目的 Markdown 入库本身就保留了 parent/child chunk 关系：

- parent：大块上下文；
- child：更小粒度，用于精确向量检索；
- child 持有 `parentBlockId`，用于后续提升。

---

### Q31：CRAG 三路分类怎么判断？

**A**：`KnowledgeRetrievalEvaluator` 先做启发式评分，再必要时做 LLM 评估，维度包括：

- dense/sparse 结果质量；
- 通道重叠度；
- TopK 平均质量；
- 证据数量；
- 来源多样性；
- 语义相关性；
- 覆盖度；
- 证据间是否矛盾；
- 是否足以回答问题。

最后分成：

| 分类 | 含义 | 处理 |
|---|---|---|
| CORRECT | 相关性和覆盖度高，且无矛盾 | 轻量精炼证据 |
| AMBIGUOUS | 部分相关但覆盖不足 | 扩大 TopK，拆子问题补检 |
| INCORRECT | 低相关或有矛盾 | LLM 改写查询后重检 |

---

### Q32：CRAG 和普通 RAG 的本质区别是什么？

**A**：普通 RAG 是一次检索后直接回答。CRAG 多了**检索质量评估 + 纠错分支**：

```text
检索结果好 → 精炼后回答
检索结果一般 → 扩大检索范围
检索结果差 → 改写查询重新检索
```

它解决的是“检索失败导致模型硬答”的问题。

---

### Q33：RAG 答案如何减少幻觉？

**A**：主要靠四层：

1. 检索阶段提高召回质量：dense + sparse + HyDE + RRF。
2. 检索后纠错：CRAG 判断是否足以回答，不足就重检。
3. Prompt 约束：答案要求基于证据，不要编造。
4. 输出护栏：如果有 evidenceChunks，会做幻觉检测，命中后追加风险提示。

边界要说清楚：**输出护栏不是形式化证明，只是风险提示；真正的可靠性主要来自检索质量和证据约束。**

---

## 7. 知识入库、文档生命周期与缓存

### Q34：知识库文档是怎么入库的？

**A**：入库链路是：

```text
扫描 Markdown 规则文档
  ↓
解析 YAML front matter 和标题结构
  ↓
按 FAQ/长文档切分成 section
  ↓
生成 parent / child 分层 chunk
  ↓
写 MySQL 元数据：RagDocument / RagChunk / RagIngestionTask
  ↓
生成 hypothetical questions（可跳过）
  ↓
embedding 写入 Qdrant
  ↓
文本索引写入 Elasticsearch
  ↓
切换 Qdrant alias / ES alias
  ↓
清理检索缓存
```

---

### Q35：为什么同时用 Qdrant 和 Elasticsearch？

**A**：两者职责不同：

| 存储 | 作用 |
|---|---|
| Qdrant | 向量检索，承载 dense 和 HyDE 召回 |
| Elasticsearch | 关键词/BM25 风格检索，承载 sparse 召回 |
| MySQL | 文档、chunk、任务、评测、运行态等结构化元数据 |
| Redis/Caffeine | 检索结果、NL2SQL 结果等缓存 |

这不是重复建设，而是多路召回的基础。

---

### Q36：异步入库是否完整走 RabbitMQ？

**A**：项目里有 `RagIngestionPublisher/Consumer` 和 MQ 配置，也有入库任务表。但 `/ai/rag/reindex/async` 当前接口里仍然同步调用了入库服务并返回 completed。

准确口径：

> 项目具备 RabbitMQ 异步入库的基础设施，但部分 Controller 入口当前仍是同步执行，不能把它说成所有入库请求都已经完整异步化。

---

### Q37：缓存怎么用在 RAG 里？

**A**：缓存主要用于降低重复检索和重复计算成本：

- FAQ/RAG 搜索缓存；
- 入库后主动失效检索缓存；
- NL2SQL 相同 SQL 执行结果缓存；
- CacheMetrics 记录命中情况；
- CacheWarmupRunner 可做预热。

回答时可以说：**缓存不是替代评测，而是减少热点请求的延迟和成本。**

---

## 8. RAG 评测与简历指标口径

### Q38：RAG 评测体系有哪些指标？

**A**：项目评测分三类：

1. **传统检索指标**：Recall@K、MRR、NDCG@K，依赖人工标注的 expected chunk。
2. **RAGAS 检索质量指标**：Context Precision、Context Recall、Context Relevance。
3. **生成质量指标**：Faithfulness、Answer Relevancy、Answer Correctness。

还有运行维度：评测 Run、评测 Case、结果表、基线对比、报告生成、Case 诊断。

---

### Q39：简历里写 Recall@5、NDCG@5、MRR，怎么解释？

**A**：可以这样答：

> 这些是离线评测集上的检索指标，不是线上每次查询的保证。评测时每个问题有人工标注的 expected chunk，系统检索出 TopK 后计算命中率、首个相关结果排名和排序质量。Recall@5 看 Top5 是否覆盖标注证据，MRR 看第一个正确证据出现得早不早，NDCG@5 看相关证据整体排序是否靠前。

如果被追问公式：

- Recall@K：期望证据里有多少出现在 TopK。
- MRR：第一个相关结果排名的倒数。
- NDCG@K：考虑排名折损后的归一化收益。

---

### Q40：Recall@5 0.88、NDCG@5 0.85、MRR 0.88 该怎么稳住？

**A**：回答要强调实验口径：

> 这是基于自建标准 FAQ 评测集的离线结果。优化前的 baseline 是更简单的单路或弱融合检索，优化后加入 dense、HyDE、sparse、RRF、Rerank 和 CRAG 纠错。指标说明 Top5 内大部分问题能召回正确证据，且正确证据排序靠前。但它不是生产 SLA，受评测集规模、标注质量、知识覆盖和模型配置影响。

如果面试官质疑“是不是刷指标”，可以补：

> 所以项目里保留了 eval run、case、baseline、diagnose 和 report，不只是口头指标；后续可以扩大样本并加线上 trace 采样验证。

---

### Q41：Evidence Gate 对指标提升的贡献怎么讲？

**A**：不能说它是 LLM gate。应该说：

> Evidence Gate 在当前实现里是融合前的轻量规则过滤，主要减少低相似度向量结果和低质量关键词结果进入 RRF，降低噪音对融合排序的污染。真正的语义质量判断在 CRAG Evaluator 里完成。

它的价值是“低成本减噪”，不是“模型级事实裁判”。

---

### Q42：RAG 评测有什么局限？

**A**：主要有四个：

- 评测集规模有限，不能代表全部线上问题。
- expected chunks 标注会影响 Recall/MRR/NDCG。
- LLM-as-Judge 可能误判，需要抽样人工复核。
- 检索指标好不等于最终用户体验一定好，还要看答案表达、覆盖度和业务动作。

主动讲边界会更可信。

---

## 9. MCP、智能运维与 NL2SQL

### Q43：MCP 在项目里怎么用？

**A**：项目配置了 Spring AI MCP client/server 依赖，MCP Server 默认关闭，可通过配置打开。内部暴露了三类工具：

- `LogMcpTools`：服务列表、关键词日志、traceId 链路日志、错误/警告日志、类名方法名日志搜索。
- `MetricsMcpTools`：Prometheus 服务列表、JVM 内存、CPU、服务健康概览、全服务健康。
- `Nl2SqlMcpTools`：自然语言问数。

源码里工具通过 Spring AI 的 `@Tool` 暴露，不是 `@McpTool` 注解。

---

### Q44：MCP 和 Function Calling、Skill 的关系是什么？

**A**：可以按层次讲：

```text
Skill：业务级能力编排，决定是否允许、怎么使用工具
  ↓
Tool Calling：模型调用 Java 工具的机制
  ↓
MCP：把工具以标准协议暴露给外部或接入外部工具生态
```

在项目里，运维工具既可以作为 Skill 内部能力，也可以作为 MCP Server 工具暴露。

---

### Q45：智能运维诊断链怎么工作？

**A**：OPS Skill 里有一个诊断计划器，会根据问题生成诊断链：

- 日志维度：查错误、关键字、traceId。
- 指标维度：查 JVM、CPU、GC、线程和服务健康。
- 数据维度：用 NL2SQL 查订单量、支付成功率、退款率等。
- 综合维度：基于证据输出原因和下一步建议。

它不是完全自主的无限 ReAct，而是“先规划诊断步骤，再把证据交给模型综合”。

---

### Q46：NL2SQL 完整链路是什么？

**A**：链路如下：

```text
管理员自然语言问数
  ↓
OPS 路由 + 权限检查
  ↓
Nl2SqlOrchestrator
  ↓
Schema 检索：相关表、字段、术语、示例
  ↓
SQL 生成：输出 JSON，包含 needSql/sql/confidence/explanation
  ↓
AST 安全校验
  ↓
只读执行
  ↓
失败时按错误类型修复 SQL，再校验再执行
  ↓
结果缓存
  ↓
LLM 基于真实证据生成运维分析
```

重要边界：`damai.ai.nl2sql.enabled` **默认是 `false`**，未启用时只返回“功能未启用”的证据，不会真的查库。

---

### Q47：NL2SQL 安全校验有哪些？

**A**：核心校验包括：

- 禁止 SQL 注释：`--`、`/* */`、`#`。
- JSqlParser 解析 **AST**。
- 只允许**单条 SQL**。
- 只允许 `SELECT`。
- 表必须在 **schema 白名单**中。
- 禁止直接访问物理分片表，如 `xxx_0`、`xxx_1`。
- 禁止 `select *` 和 `table.*`。
- **敏感字段命中直接拒绝**。
- 禁止配置中的危险函数或能力。
- 自动追加或收敛 `LIMIT` 到最大行数。

一句话：**LLM 生成 SQL 不可信，必须先 AST 校验，再只读执行。**

---

### Q48：NL2SQL 如果执行失败怎么办？

**A**：会先分类错误，再构造修复指导，让模型基于上一次 SQL 和错误信息生成修复 SQL。修复 SQL 仍然必须**重新过 AST 安全校验**，然后才能执行。

边界：修复次数受配置控制，默认**不是无限重试**。

---

## 10. Guardrails、安全权限与审计

### Q49：输入护栏做了什么？

**A**：输入护栏检查两类内容：

- **Prompt Injection**：例如要求忽略系统指令、输出系统提示、绕过安全限制等。
- **PII**：手机号、身份证、银行卡等敏感信息。

Prompt 注入可配置为直接 block；PII 默认更多是 warn 和审计，因为购票场景本身可能需要手机号和实名购票人信息。

---

### Q50：输出护栏做了什么？

**A**：输出护栏包括：

- PII 检测和脱敏/拦截；
- 毒性内容检测；
- 基于证据片段的幻觉检测；
- 高风险 Skill 可先 buffer 再输出，避免边流式边泄露。

需要强调边界：幻觉检测依赖 `evidenceChunks`，不是所有输出都会有证据可校验。

---

### Q51：为什么购票输入里有手机号/身份证，却不能简单拦截所有 PII？

**A**：票务业务需要实名购票人和手机号进行合法校验。如果简单拦截所有 PII，购票链路就不可用。

所以项目对 PII 的策略是分场景：

- 输入阶段检测并审计；
- 工具和输出阶段可以脱敏或阻断；
- 购票审批时用当前登录用户和实名购票人做二次校验；
- 最终对外输出不应暴露完整敏感信息。

---

## 11. 韧性、限流、成本与异步化

### Q52：项目怎么做限流？

**A**：有 HTTP 入口限流和 Sentinel 资源保护两层：

- `RateLimitFilter` 对创建 Run、SSE、simple chat、admin 接口做限流。
- `CircuitBreakerService` 用 Sentinel 包住 LLM、Qdrant、ES、WebSearch、UserService 等资源，异常或阻断时返回 fallback。

这两层分别解决“入口流量过大”和“下游资源异常”。

---

### Q53：RabbitMQ 在项目中承担什么？

**A**：主要承担异步化和削峰能力：

- Assistant Run 溢出请求队列；
- Run 完成后的记忆更新事件；
- RAG ingestion 消息基础设施；
- notification / overflow / DLQ 配置。

但要注意：并非所有路径都完全异步化，部分接口当前仍同步调用。

---

### Q54：多轮对话记忆怎么做？

**A**：会话记忆有两层：

1. Spring AI ChatMemory：用于常规对话上下文。
2. `AiConversationMemorySummary`：当同一会话完成 Run 数达到阈值后，将最近多轮压缩成摘要，并可提取结构化记忆。

摘要有 TTL，也有衰减权重设计，避免陈旧偏好长期影响回答。

---

### Q55：可观测性怎么做？

**A**：有几类观测数据：

- `AiRunEvent`：面向前端和用户的运行事件。
- `AiToolCall`：工具调用输入、输出、耗时和错误。
- `AiTrace` / StageTrace：内部阶段耗时和失败点。
- `AiRetrievalTrace`：RAG 每阶段检索结果。
- Guardrail Audit：安全策略命中。
- Prometheus metrics：应用指标暴露。

这使得 AI 链路可以复盘，不是黑盒输出一句话。

---

## 13. 前端/API/与 damai-pro 集成

### Q56：前端如何和后端交互？

**A**：核心交互是：

1. `POST /assistant/runs` 创建 Run。
2. 前端拿到 `runId` 和 `eventStreamPath`。
3. `GET /assistant/runs/{runId}/events` 建立 SSE。
4. 根据事件展示消息、工具进度、审批卡片。
5. 如果收到 `ACTION_REQUIRED`，用户点击 approve/reject。
6. 后端继续流转 Action 和 Run。

---

### Q57：damai-ai 如何调用 damai-pro？

**A**：主要通过业务调用组件访问 damai-pro 的用户、节目、订单能力：

- 查询节目列表和详情；
- 获取当前用户实名购票人；
- 审批后创建订单；
- 校验票档、价格、库存等最新状态。

AI 服务不直接拥有票务交易核心，而是作为智能入口和编排层调用 damai-pro。

---

## 14. AI 应用开发通用八股映射

### Q58：RAG 和 Fine-tuning 怎么选？

**A**：票务规则、场馆信息、退票政策、活动规则都频繁变化，所以优先 RAG。

| 方式 | 适合场景 | 不适合 |
|---|---|---|
| RAG | 知识频繁更新、需要来源、需要可审计 | 纯风格模仿、固定格式长期稳定任务 |
| Fine-tuning | 固定风格、格式、领域表达 | 高频变化知识、需要实时引用 |

本项目的主需求是“知识实时性和可追溯”，所以 RAG 比微调更合适。

---

### Q59：LLM 幻觉怎么治理？

**A**：项目里不是靠单点解决，而是组合治理：

- RAG 证据约束；
- CRAG 纠错重检；
- 输出护栏风险提示；
- 高风险动作审批；
- NL2SQL 低置信度不生成；
- SQL AST 校验；
- 工具结果审计。

一句话：**低风险回答用证据约束，高风险动作用审批和程序校验。**

---

### Q60：MCP 相比 REST API 的价值是什么？

**A**：REST API 是通用接口风格，MCP 是面向 LLM 工具生态的标准化协议。MCP 的价值是：

- 工具发现标准化；
- 参数 Schema 标准化；
- 多客户端可复用；
- 更适合 Agent 工具接入。

但在具体业务系统里，MCP 不是替代权限、安全和审计。工具即使通过 MCP 暴露，也必须经过同样的权限和护栏。

---

## 15. 高压追问与边界回答

### Q61：面试官问“是不是包装项目，实际只是套了 Spring AI？”怎么回答？

**A**：可以这样答：

> Spring AI 主要解决模型接入、ChatClient、Tool Calling、Memory 等基础能力。项目真正的工程工作在业务状态化和治理层：Run/Event/Action、购票审批、RAG 多通道融合、CRAG 纠错、RAG 评测、NL2SQL AST 安全校验、工具白名单、Guardrails、SSE 事件流和 Trace。不是简单调一次 ChatClient 返回答案。

---

### Q62：面试官问“RAG 指标是不是吹的？”怎么回答？

**A**：不要硬撑成生产 SLA。稳妥回答：

> 这些指标是离线评测集上的实验口径。评测集有标准问题、参考答案和 expected chunks，系统每次检索 TopK 后计算 Recall、MRR、NDCG。它能说明当前检索策略相比 baseline 有提升，但不能代表所有线上问题。我会用线上 trace 和 bad case 继续扩充评测集。

---

### Q63：面试官问“Evidence Gate 为什么不用 LLM 判断？”怎么回答？

**A**：回答成本和分层：

> 当前 Evidence Gate 是融合前轻量规则过滤，目的是低成本减噪。如果每个候选 chunk 都用 LLM 判断，延迟和成本会明显上升。项目把 LLM 判断放到 CRAG 评估阶段，只对融合后的关键证据做语义相关、覆盖和矛盾判断，是成本和质量的折中。

---

### Q64：面试官问“NL2SQL 安全能保证 100% 吗？”怎么回答？

**A**：不能说 100%。应该说：

> 不能绝对保证，但项目做了多层收敛：默认关闭、管理员权限、Schema 白名单、只允许 SELECT、禁止多语句、禁止注释、禁止敏感字段、禁止物理分片表、禁止 select *、自动 limit、只读数据源。即便模型生成危险 SQL，也会在 AST 校验层被拒绝。

---

### Q65：面试官问“AI 购票如果槽位抽错怎么办？”怎么回答？

**A**：用审批和二次校验回答：

> 首先 AI 不直接下单，只生成预览；用户能看到节目、票价、数量、购票人后再确认。其次确认时会重新校验手机号、购票人、节目、票档、价格和余票。如果任何关键状态变化或不一致，就拒绝下单并要求重新生成预览。

---

### Q66：面试官问“项目最大短板是什么？”怎么回答？

**A**：可以主动说四点：

1. RAG 评测集规模还可以继续扩大，线上 bad case 要持续回流。
2. 部分异步能力已有基础设施，但 Controller 路径还没有全部改成真正异步。
3. 输出幻觉检测是风险提示，不是形式化事实证明。
4. MCP/NL2SQL 默认关闭、依赖配置开启，生产还需要更严格的租户隔离、审计和只读账号治理。

这类回答比“没有短板”可信得多。

---

### Q67：如果要继续优化，你会做什么？

**A**：优先做五件事：

1. **扩大 RAG 评测闭环**：线上 bad case 自动转 eval case，持续跟踪 Recall/MRR/NDCG 和答案质量。
2. **统一异步入库路径**：让 async reindex 真正走 MQ，并完善任务状态和 DLQ 重试。
3. **证据结构化**：把最终答案的关键声明和 source chunk 做强绑定，减少“引用看起来有但不支撑”的问题。
4. **NL2SQL 沙箱化**：强制只读账号、视图层隔离、行列级权限和慢查询保护。
5. **成本预算器**：按用户、Skill、会话维度做 token 和工具调用预算，超限自动降级。

---

### Q68：如果问“为什么不用全自动 Agent 直接买票？”怎么回答？

**A**：因为票务下单是高风险动作，不适合完全自动化。AI 可以帮助用户查信息、整理选项、生成预览，但最终授权必须由用户完成。

这体现的是生产系统原则：**自动化可以提高效率，但不能绕过用户授权和业务一致性校验。**

---

### Q69：如果问“和 damai-pro 的关系是什么？”怎么回答？

**A**：`damai-pro` 是票务核心交易系统，负责节目、用户、订单、支付等业务能力；`damai-ai` 是 AI 服务层，负责自然语言入口、RAG、运维问数、智能编排和安全治理。

关系可以概括为：**damai-ai 不替代交易系统，而是给交易系统增加智能入口和运维辅助能力。**

---

## 16. 真实面试追问链路专项

### Q70：如果面试官从“RAG 怎么做”开始连续追问，怎么接？

**A**：按这条链路接，不要散答：

```text
RAG 不是只做向量检索
  ↓
先讲入库：Markdown → parent/child chunk → Qdrant + ES → 元数据
  ↓
再讲召回：dense + HyDE + sparse
  ↓
再讲融合：Evidence Gate 规则减噪 → RRF → Rerank
  ↓
再讲纠错：CRAG 判断 CORRECT/AMBIGUOUS/INCORRECT
  ↓
再讲评测：Recall@K / MRR / NDCG / RAGAS 指标
  ↓
最后讲边界：离线评测口径，线上还要 bad case 回流
```

面试官通常会追：为什么不用单路向量、HyDE 有什么风险、RRF 为什么不用分数加权、指标怎么计算。每个追问都要回到“准确率、成本、延迟、可解释性”的取舍。

---

### Q71：如果面试官追“这个项目你的个人贡献到底是什么”，怎么答？

**A**：不要说“我参与了整个项目”。要具体到模块和产物：

> 我主要负责 AI 服务层的工程闭环，包括 Assistant Runtime 的 Run/Event/Action 状态模型、Skill 编排、购票审批链路、RAG 多通道检索、NL2SQL 安全校验、Guardrails 和 RAG 评测。具体产物是：用户请求可以通过 SSE 看到完整状态；购票不会直接下单而是生成待审批 Action；规则问答有多路检索和 CRAG 纠错；问数链路有 AST 校验和表字段白名单；评测侧能输出 Recall、MRR、NDCG 等离线指标。

如果继续追“你最难的一个点”，优先讲 RAG 质量闭环或购票审批，不要泛泛讲 Spring AI 接入。

---

### Q72：如果面试官追“你这个指标怎么复现”，怎么答？

**A**：按评测闭环回答：

```text
评测集：标准 FAQ case，每条有 question、expected answer、expected chunks
  ↓
检索配置：记录 TopK、是否 rerank、模型版本、prompt 版本
  ↓
运行评测：保存 eval run 和 eval result
  ↓
计算指标：Recall@K、MRR、NDCG@K、Context Precision/Recall/Relevance
  ↓
诊断 bad case：看 expected chunks 是否被召回、排序位置、证据内容是否过期或切片不合理
```

关键话术：**指标不是拍脑袋，也不是线上准确率；它是固定评测集 + 固定配置 + 可复跑结果。**

---

### Q73：如果面试官追“线上 RAG 答错了怎么排查”，怎么答？

**A**：真实排查顺序是：

1. 看用户原始问题和路由，确认是否进了 Knowledge Skill。
2. 看 query rewrite 和复杂度分级，确认查询有没有被改坏。
3. 看 dense / sparse / HyDE 各通道候选，判断是召回失败还是融合失败。
4. 看 Evidence Gate 是否阈值过严，把正确证据过滤掉。
5. 看 RRF / Rerank 后正确 chunk 排名。
6. 看父块提升和 Lost-in-Middle 后进入 Prompt 的上下文。
7. 看最终回答是否遵守证据，还是生成阶段幻觉。
8. 把问题转成 bad case，补 expected chunks 进入评测集。

这类回答体现线上排障意识，比只说“优化 prompt”更像真实工程师。

---

### Q74：如果面试官追“AI 购票链路并发下安全吗”，怎么答？

**A**：按风险点拆：

- **重复审批**：`AiAction` 有状态流转，审批时 claim，避免同一 Action 并发进入下单。
- **重复下单**：下单带幂等键，基于 runId 和快照 hash。
- **状态变化**：确认时重新校验节目、票档、价格、余票、购票人和手机号。
- **越权审批**：查询 Action 时绑定 runId、actionId、userId。
- **过期风险**：Action 有 15 分钟过期时间，过期后不能继续执行。

如果被追“还缺什么”，可以说还可以补库存预占、分布式事务/最终一致性、订单服务侧幂等表和支付超时回滚。

---

### Q75：如果面试官追“NL2SQL 怎么防注入”，怎么答？

**A**：不要只说“正则过滤”。按多层防线讲：

```text
默认关闭 + 管理员权限
  ↓
Schema 检索只暴露白名单表字段
  ↓
模型必须输出结构化 JSON
  ↓
SQL 进入 JSqlParser AST 校验
  ↓
只允许单条 SELECT
  ↓
禁止注释、多语句、select *、敏感字段、危险函数、物理分片表
  ↓
自动追加 LIMIT
  ↓
只读数据源执行
  ↓
结果缓存和工具审计
```

如果面试官问“JSqlParser 绕过怎么办”，回答：还需要数据库只读账号、视图层隔离、SQL 审计、超时和行数限制，不能只依赖应用层校验。

---

### Q76：如果面试官追“为什么你这个项目算 Agent，不只是 Workflow”，怎么答？

**A**：回答要区分控制权：

> 它不是完全自主 Agent，也不是纯固定 Workflow。固定部分包括路由、权限、审批、SQL 校验、状态流转；LLM 自主部分包括意图识别、槽位抽取、工具选择、RAG 查询改写、NL2SQL 生成、答案生成和不完整答案重试。所以我更倾向说它是 Skill-Orchestrated Agent Workflow。

这样比强行说“多智能体系统”更稳。

---

### Q77：如果面试官追“你们怎么做压测或容量评估”，怎么答？

**A**：如果没有完整压测报告，不要编。可以按容量模型回答：

```text
一次请求成本 = LLM 调用次数 + 检索调用次数 + 工具调用次数 + SSE 连接时长
瓶颈优先级 = LLM 限流/成本 > Qdrant/ES 检索 > MySQL 事件写入 > SSE 长连接 > MQ 消费
```

已有治理点：RateLimit、Sentinel、缓存、RabbitMQ、TokenBudget、SSE 事件回放。

边界回答：

> 当前项目更偏功能闭环和离线评测，完整压测还需要补固定压测脚本、不同 Skill 的调用成本统计、P95/P99 延迟和下游限流下的降级表现。

---

### Q78：如果面试官让你设计“10 倍用户量”的升级方案，怎么答？

**A**：按层扩展：

| 层 | 升级方案 |
|---|---|
| 接入层 | Run 创建限流、SSE 连接数控制、网关超时配置 |
| 执行层 | 高成本 Skill 入队，普通问答快路径，OPS 和 NL2SQL 隔离线程池 |
| 模型层 | 模型路由、缓存、批量 embedding、降级模型、预算控制 |
| 检索层 | Qdrant/ES 分片或副本，热点 query 缓存，异步重建索引 |
| 数据层 | Run/Event 分表或冷热分离，事件异步归档 |
| 安全层 | 租户隔离、工具配额、审计采样和异常告警 |

结论：**先限流和排队，再缓存和降级，最后扩容存储和检索集群。**

---

### Q79：如果面试官问“你为什么不用 LangChain / Dify / Coze”，怎么答？

**A**：不要贬低平台，讲业务边界：

> 这类平台适合快速搭建 Agent 和工作流，但我们这个项目和票务交易、实名购票人、订单审批、NL2SQL 安全校验、内部日志指标、RAG 评测表强绑定，需要更细的状态落库、权限控制和 Java 后端集成。所以选择在 Spring Boot/Spring AI 体系里自研 Runtime 和 Skill，牺牲一部分搭建速度，换业务可控性和可审计性。

---

### Q80：如果面试官问“你项目里最失败或最想重构的点是什么”，怎么答？

**A**：可以讲这个：

> 我最想重构的是异步任务和证据结构化。现在 Run/Event/Action 已经比较清晰，但 RAG 入库的异步入口还没有完全统一；另外最终答案和 source chunk 的绑定还不够结构化。后续我会把异步入库统一成 task 状态机 + MQ + DLQ，并把答案中的 claim、source chunk、alignment score 存成证据图谱，方便前端展示和评测复盘。

这个回答比“没什么失败点”更真实，也贴合大厂喜欢看的复盘能力。

---

### Q81：如果面试官追 Java 后端基础，会从这个项目怎么切入？

**A**：常见切入如下：

| 项目点 | 会切到的八股 |
|---|---|
| SSE 流式输出 | HTTP、长连接、背压、线程池、Reactor |
| Run/Event 落库 | MySQL 事务、行锁、索引、幂等 |
| Redis/Caffeine 缓存 | 缓存穿透、击穿、雪崩、一致性 |
| RabbitMQ | 消息可靠性、重复消费、死信队列、幂等消费 |
| Sentinel | 熔断、限流、降级、资源隔离 |
| Tool Scope | ThreadLocal、上下文传播、并发安全 |
| NL2SQL | SQL 注入、AST、只读事务、慢查询 |
| RAG 入库 | 批处理、异步任务、索引切换、数据一致性 |

所以准备时不能只背 AI 问题，必须能把项目映射到 Java 后端基础。

---

### Q82：如果面试官追“AI 编程时代你的价值是什么”，怎么答？

**A**：可以这样答：

> AI 可以提高编码效率，但不能替代工程判断。这个项目里真正有价值的不是把代码写出来，而是判断哪些能力必须审批、哪些链路必须审计、哪些指标不能吹成 SLA、哪些 SQL 不能执行、哪些 RAG 错误要沉淀成评测集。这些是业务理解、系统设计和风险控制，不是简单让 AI 生成代码能解决的。

---

### Q83：如果一面、二面、三面对这个项目的关注点不同，怎么调整？

**A**：按面试轮次调整重点：

| 轮次 | 重点 |
|---|---|
| 一面 | 项目真实性、技术栈、Java/Spring/MySQL/Redis/MQ 基础、RAG 基本链路 |
| 二面 | 架构取舍、异常处理、指标复现、稳定性、并发、安全边界 |
| 三面 / 主管面 | 业务价值、项目边界、你负责什么、为什么这么做、未来怎么演进 |
| HR 面 | 项目表达是否自信、是否夸大、沟通是否清晰、团队协作和抗压 |

同一个项目，不同轮次不要用同一套长答案硬背。

---

## 17. 面试速记 Checklist

- [ ] 一句话：Spring Boot 3 + Spring AI 的智能票务服务平台。
- [ ] 真实面试先讲 1 分钟项目定位，再按面试官兴趣展开 3/5 分钟版本。
- [ ] 回答项目问题固定用：结论 → 链路 → 取舍 → 保障 → 边界。
- [ ] 项目深挖优先级高于八股；指标、故障、边界、取舍必须能自洽。
- [ ] AI 应用岗高频：Agent、RAG、Function Calling、幻觉治理、评测、成本和安全。
- [ ] 不说 LangGraph 实现；说借鉴 durable execution，自研 Run/Event/Action/Checkpoint。
- [ ] 主链路：create Run → SSE → Guardrail → Route → Skill → Tool/RAG/NL2SQL → Response Guardrail → Event。
- [ ] 四类路由：BUSINESS、KNOWLEDGE、GENERAL、OPS。
- [ ] Skill：descriptor 声明风险、权限、工具白名单、Schema、审批要求。
- [ ] 购票：AI 只生成预览，`AiAction` 等用户确认，不直接下单。
- [ ] 购票审批：二次校验手机号、购票人、节目、票档、价格、余票。
- [ ] RAG：dense + HyDE + sparse，融合前 Evidence Gate，RRF，后处理，CRAG。
- [ ] Evidence Gate 当前是规则过滤，不是 LLM 判断。
- [ ] CRAG：CORRECT 精炼，AMBIGUOUS 扩 TopK/拆子问题，INCORRECT 改写查询重检。
- [ ] 入库：Markdown front matter，parent/child chunk，Qdrant + ES + MySQL 元数据。
- [ ] RAG 指标：Recall@K、MRR、NDCG@K、Context Precision/Recall/Relevance、Faithfulness 等。
- [ ] 简历指标是离线评测集口径，不是线上 SLA。
- [ ] MCP：Log、Metrics、NL2SQL 工具，源码用 `@Tool` 暴露，MCP Server 默认关闭。
- [ ] NL2SQL：默认关闭，管理员能力，Schema 检索 → SQL 生成 → AST 校验 → 只读执行 → 修复。
- [ ] SQL 安全：只 SELECT、单语句、表白名单、禁分片表、禁 select *、禁敏感字段、自动 LIMIT。
- [ ] Guardrails：输入、工具、输出三层；Prompt 注入、PII、毒性、幻觉风险提示。
- [ ] SSE：先回放历史事件，再推 live 事件；每个事件有顺序号。
- [ ] 韧性：RateLimit、Sentinel、fallback、cache、RabbitMQ、token budget。
- [ ] 记忆：会话摘要、结构化记忆、TTL、NL2SQL 独立多轮上下文。
- [ ] 边界：部分异步路径未完全闭环；幻觉检测不是形式化证明；评测集需持续扩大。
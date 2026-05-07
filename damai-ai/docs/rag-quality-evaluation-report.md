# damai-ai RAG 深度评估报告

本文从当前 `damai-ai` 代码实现出发，对照前沿 RAG 理论与开源实践，评估项目在文档工程、检索、重排、生成、评测和生产化方面的优缺点，并给出适合大麦票务业务的文档扩充建议。

## 1. 结论摘要

当前 `damai-ai` 的知识助手不是一个简单的向量问答 Demo，而是已经具备一定工程完整度的 **Hybrid RAG**：

- **文档解析**：`MarkdownLoader` 以 FAQ 结构解析 Markdown，并生成稳定 `chunkId`、`headingPath`、`keywords`、`searchText` 等 metadata。
- **双路召回**：`HybridSearchService` 同时使用 Qdrant dense retrieval 和 Elasticsearch sparse retrieval。
- **结果融合**：使用 RRF 将 dense/sparse 结果融合。
- **证据控制**：有证据预算、去重、snippet 截断、低置信拒答。
- **可观测性**：保存 dense/sparse/fused/final hits，并通过 assistant run event 暴露检索过程。
- **测试基础**：已有 `MarkdownLoaderTest`、`RagFusionSupportTest`、`RerankServiceTest`、`KnowledgeRetrievalEvaluatorTest`、`KnowledgeRetrievalOrchestratorTest`。

但如果对标更成熟的 RAG 系统，当前项目的短板也比较明确：

| 优先级 | 欠缺点 | 影响 |
| --- | --- | --- |
| P0 | 缺少 RAG 离线评测集和指标体系 | 无法量化判断改动是否真的提升效果 |
| P0 | rerank 仍是关键词重叠启发式 | dense/sparse 召回后最终排序质量不稳定 |
| P0 | 文档仍以通用 QA FAQ 为主 | RAG 价值没有充分体现，业务深度不足 |
| P1 | query planning/query rewriting 较弱 | 对复杂、多意图、口语化问题适应性不足 |
| P1 | metadata filtering 没有真正用起来 | 已生成 metadata，但检索阶段没有充分利用 |
| P1 | retrieval chunk 与 synthesis context 未解耦 | 检索粒度和回答上下文粒度被绑定 |
| P1 | Prompt 证据引用能力较弱 | 用户难以定位答案来自哪条规则 |
| P2 | 索引生命周期和生产稳定性仍粗糙 | 重建、增量更新、多实例、超时重试等存在风险 |
| P2 | 缺少面向运维和业务异常的知识库 | 运维助手和业务助手的 RAG 价值未释放 |

建议路线：

1. **先建立评测集**，不要盲目堆技术。
2. **升级 rerank**，这是当前质量收益最高的技术点。
3. **扩充文档类型**，从 FAQ 扩展到业务流程、规则决策表、异常 SOP、API/错误码、运维 Runbook。
4. **引入 metadata filtering 和 adaptive retrieval**，让不同问题走不同检索策略。
5. **改善证据引用和上下文组装**，让回答更可信、更可审计。

## 2. 当前 RAG 架构复盘

### 2.1 数据源规模

当前知识库位于：

`damai-core-service/src/main/resources/datum`

统计结果：

| 指标 | 当前值 |
| --- | ---: |
| Markdown 文件 | 10 |
| FAQ 条目 | 72 |
| 总字符数 | 8603 |

当前文档列表：

| 文档 | FAQ 数 | 字符数 |
| --- | ---: | ---: |
| `交易安全和防诈骗-相关问题与回答.md` | 6 | 646 |
| `儿童票和特殊入场-相关问题与回答.md` | 6 | 654 |
| `入场安检和禁带物品-相关问题与回答.md` | 7 | 801 |
| `电子票和数字票-相关问题与回答.md` | 7 | 917 |
| `节目取消和退票-相关问题与回答.md` | 11 | 1645 |
| `节目订票-相关问题与回答.md` | 9 | 916 |
| `订单支付和超时-相关问题与回答.md` | 7 | 769 |
| `账号实名和观演人-相关问题与回答.md` | 6 | 811 |
| `购票限制和抢票规则-相关问题与回答.md` | 6 | 660 |
| `配送取票和票品交付-相关问题与回答.md` | 7 | 784 |

结论：当前知识库覆盖了用户侧通用票务 FAQ，但规模仍非常小，更像客服 FAQ 子集。它适合验证 RAG 链路，但还不足以充分体现 RAG 在复杂票务系统中的价值。

### 2.2 文档解析与 chunk

核心类：`MarkdownLoader`

当前能力：

- **按 Markdown 结构解析**：`#` 作为文档标题，`##` 作为 FAQ 问题。
- **FAQ 级 chunk**：每条 FAQ 生成 `问题：...\n\n答案：...`。
- **长 FAQ 再切分**：超过阈值才使用 `TokenTextSplitter`。
- **稳定 ID**：`chunkId = md5(sourceFile + headingPath + contentHash + partIndex)`。
- **metadata 较丰富**：包含 `name`、`title`、`label`、`keywords`、`source`、`sourceFile`、`docTitle`、`question`、`section`、`headingPath`、`contentHash`、`searchText`、`chunkType`、`partIndex`、`partCount`、`sequence`。

这是当前项目做得比较好的地方。相比很多只做固定长度切块的 RAG Demo，`damai-ai` 已经注意到了 Markdown 结构和 FAQ 语义边界。

不足：

- **只支持当前 FAQ 模式**：对流程文档、表格、错误码、API 文档、Runbook、决策树文档没有专门 parser。
- **没有 YAML/frontmatter metadata**：文档级标签、适用场景、有效时间、业务域、权限等级等只能从文件名或关键词推断。
- **没有上下级层次索引**：FAQ chunk 之间没有文档摘要、章节摘要、父子关系。
- **检索 chunk 和生成 context 未解耦**：检索时用的文本和最终放入 prompt 的文本基本一致。

### 2.3 索引与检索

核心类：`HybridSearchService`

当前查询链路：

```text
用户问题
  -> normalize / synonym rewrite
  -> Qdrant dense search
  -> Elasticsearch sparse search
  -> RRF 融合
  -> keyword-overlap rerank
  -> final sources
  -> documents
  -> prompt assembly
  -> LLM answer
```

优点：

- **Hybrid search 方向正确**：dense 适合语义召回，sparse 适合精确词、实体、规则词召回。
- **RRF 简洁稳健**：不用强行归一化 Qdrant 和 ES 分数。
- **ES 字段加权**：`searchText^4`、`question^3`、`keywords^2`、`text^2`、`docTitle^1`，符合 FAQ 检索特点。
- **有降级策略**：Qdrant 或 ES 异常时返回空结果，不直接中断。

不足：

- **ES mapping 未看到中文 analyzer 配置**：当前字段只是 `text`，中文分词、同义词、拼写纠错、拼音/简繁处理等未显式配置。
- **metadata 未参与过滤**：虽然 metadata 很丰富，但检索时没有按 `label`、`source`、`chunkType`、`docTitle` 等过滤或 boost。
- **query rewrite 是硬编码同义词拼接**：容易提高召回但引入噪声。
- **召回参数固定**：`topK`、rerank 开关、证据预算都是固定值，没有根据问题类型动态调整。
- **索引生命周期分裂**：启动时会 `vectorStore.add` 和 cache，完整 Qdrant + ES 重建依赖 `/ai/rag/reindex`，需要更清晰的生产索引状态管理。
- **手写 HTTP 访问 Qdrant/ES**：缺少统一 timeout、retry、backoff、错误码处理和客户端指标。

### 2.4 重排

核心类：`RerankService`

当前默认使用：

```text
query 分词 -> 关键词集合 -> 文档 contains 命中数 / queryKeywords.size
```

优点：

- 快速、简单、可解释。
- 对当前小 FAQ 数据集足够轻量。

不足：

- 中文 query 没有真正分词，只按标点和空格切分。
- 不能判断语义等价、否定、条件、上下文约束。
- 对长文本中偶然包含关键词的文档容易误判。
- `rerankWithLLM` 存在但没有接入主链路，而且 LLM rerank 成本和稳定性也需要评测。

对照 Haystack/NVIDIA NeMo 的实践，reranking 在 hybrid retrieval 场景尤其关键，因为 dense、sparse、keyword 各自的分数分布不同，最终进入 LLM 的 context 应由统一相关性模型重新排序。当前项目在这里还有明显提升空间。

### 2.5 检索规划与纠错

核心类：`KnowledgeRetrievalPlanner`、`KnowledgeRetrievalOrchestrator`

当前能力：

- trim query。
- 固定 topK = 8。
- 固定证据预算。
- 低置信且证据不足时，把最长 token 拼到 rewritten query 后再检索一次。

不足：

- `subQuestions` 当前实际只有原问题，没有真正 query decomposition。
- 没有 query classification，例如区分：规则问答、流程解释、订单状态、节目推荐、异常排查、总结类问题。
- 没有 multi-query retrieval，例如为同一个问题生成 3 个不同角度查询再融合。
- 没有澄清策略，例如用户只问“能退吗”时，应追问节目、订单状态、出票状态或退票政策，而不是直接检索通用 FAQ。

### 2.6 证据评估与拒答

核心类：`KnowledgeRetrievalEvaluator`

当前评分：

```text
denseTop * 0.35
+ sparseTop * 0.25
+ dense/sparse overlap * 0.15
+ finalCount * 0.15
+ source diversity * 0.10
```

优点：

- 有明确拒答边界。
- 不让 LLM 在低证据情况下强答。
- 记录 confidenceScore/confidenceLevel。

不足：

- 权重和阈值没有基于评测集校准。
- sparse score 用 `/12` 归一化比较粗糙。
- 只看检索分数，不评估答案是否 faithful。
- 没有区分“未召回”和“召回了但证据冲突”。
- 没有 LLM-as-a-judge 或规则评估来检查 answer grounding。

### 2.7 Prompt 组装和引用

核心类：`KnowledgePromptAssemblyService`

当前 Prompt 规则很清晰：只能基于证据回答，证据不足必须说明，先结论再依据。

不足：

- context 只是原文拼接，没有给每条证据编号。
- 模型回答不需要显式引用证据 ID。
- 系统提示没有要求“每个结论对应证据”。
- 没有 context compression 或 answer-focused distillation。
- 没有处理 “Lost in the Middle” 问题，只按当前顺序拼接到预算上限。

## 3. 前沿 RAG 实践对比

### 3.1 文档工程：从固定 chunk 到结构化/语义 chunk

前沿实践强调：chunk 质量直接决定检索质量。

常见方式：

- **固定窗口 chunking**：简单但容易切断语义。
- **结构感知 chunking**：按 Markdown heading、HTML section、PDF 章节、表格、代码块切分。
- **语义 chunking**：按句子 embedding 相似度聚合。
- **LLM-based chunking**：把长文拆成自包含 propositions。
- **父子 chunk**：小 chunk 用于检索，大 chunk/父章节用于生成。

当前项目已经做到了 FAQ 结构感知，这是优点。下一步应重点做：

- 支持多文档类型 parser。
- 支持文档级 metadata。
- 支持 parent-child chunk。
- 支持 summary chunk。

### 3.2 检索：Hybrid Search 是正确方向，但要用好 metadata

Meilisearch、Qdrant、Haystack 等实践都强调 hybrid search 的价值：

- dense 解决语义相似。
- sparse/BM25 解决关键词、实体、编号、错误码。
- metadata 解决过滤和精确约束。

当前项目已经有 dense + sparse + RRF，但 metadata filtering 尚未发挥作用。

建议引入这些过滤维度：

| metadata | 用途 |
| --- | --- |
| `domain` | refund/order/payment/entry/program/ops |
| `docType` | faq/policy/procedure/api/error_code/runbook |
| `audience` | user/customer_service/admin/ops/developer |
| `sourceOfTruth` | markdown/mysql/api/swagger/log/prometheus |
| `effectiveFrom/effectiveTo` | 规则有效期 |
| `businessEntity` | Program/Order/TicketCategory/User/PayBill |
| `riskLevel` | 普通说明/强规则/安全提醒/高风险操作 |

### 3.3 Query Rewriting：从同义词扩展到意图澄清

当前 `rewriteQuery` 是硬编码同义词扩展。

更成熟的设计通常包含：

- 同义词扩展。
- 拼写纠错。
- 口语化转正式检索词。
- 意图分类。
- multi-query 生成。
- query decomposition。
- metadata filter 推断。

对于 `damai-ai`，建议不要一上来完全依赖 LLM 改写，而是采用混合策略：

```text
规则词典 + 业务实体识别 + LLM 可选改写 + 评测约束
```

例如：

| 用户问题 | 推荐检索计划 |
| --- | --- |
| “能退吗” | 追问节目/订单状态，或检索 refund 通用规则 |
| “票出了还能改观演人吗” | domain=realname + ticket + order_status |
| “支付了但订单没变” | domain=payment + order + troubleshooting |
| “库存为什么不一致” | domain=ops + inventory + redis + reconciliation |

### 3.4 Rerank：当前最值得升级的质量模块

Haystack 的 reranking 实践指出：hybrid retrieval 下 reranker 能统一不同检索来源的排序偏差，并提升 Recall@K、MRR、NDCG。

当前项目建议分层升级：

| 阶段 | 方案 | 说明 |
| --- | --- | --- |
| P0 | 改进启发式 rerank | 中文分词、字段权重、标题/问题/关键词 boost |
| P1 | 接入 cross-encoder reranker | 如 bge-reranker、gte-reranker、Cohere Rerank、Jina Reranker |
| P1 | 增加 rerank trace | 保存 rerank 前后分数和排序变化 |
| P2 | LLM rerank fallback | 仅用于低置信或高价值问题，避免高成本常态化 |

### 3.5 上下文组装：从拼接到压缩与引用

LlamaIndex 生产化建议中特别强调：**用于检索的 chunk 和用于 synthesis 的 context 可以不同**。

当前项目可以借鉴：

- 小 chunk 检索，大窗口生成。
- FAQ 命中后带上同一文档相邻 FAQ。
- 命中 API 错误码后带上错误码解释 + 处理 SOP。
- 命中流程节点后带上前置条件和后置状态。
- 每条证据带 `sourceId`，要求答案引用。

推荐 Prompt context 格式：

```text
[证据 E1]
标题：退票政策
来源：节目取消和退票-相关问题与回答.md
适用域：refund
内容：...

[证据 E2]
标题：订单取消后可以恢复吗？
来源：订单支付和超时-相关问题与回答.md
适用域：order
内容：...
```

答案要求：

```text
每个关键结论必须标注证据编号，例如：[E1]。
如果证据之间存在冲突，必须说明冲突并拒绝给确定结论。
```

### 3.6 评测：当前最大工程缺口

RAGAS、Qdrant、Haystack 等实践都强调 RAG 必须评测，而且应拆成检索评测和生成评测。

当前项目只有单元测试，没有系统性质量评测。

建议建立 `rag-eval` 数据集：

| 字段 | 说明 |
| --- | --- |
| `query` | 用户问题 |
| `intent` | refund/order/payment/entry/ops 等 |
| `expectedDocIds` | 应命中的 chunkId 或 sourceFile/question |
| `expectedAnswer` | 参考答案 |
| `mustMention` | 必须提到的要点 |
| `mustNotMention` | 禁止幻觉内容 |
| `expectedConfidence` | HIGH/MEDIUM/LOW |
| `tags` | 多跳、口语、错别字、低证据等 |

建议指标：

| 维度 | 指标 |
| --- | --- |
| 检索 | Recall@K、Precision@K、MRR、NDCG、Context Precision、Context Recall |
| 重排 | MRR@K、NDCG@K、rerank 前后 top1 命中率 |
| 生成 | Faithfulness、Answer Relevancy、Response Groundedness |
| 安全 | 低证据拒答准确率、规则外问题拒答率 |
| 性能 | dense latency、sparse latency、rerank latency、LLM latency、end-to-end latency |

## 4. 当前项目最值得改进的地方

### P0-1：建立 RAG 离线评测集

这是第一优先级。没有评测集，rerank、rewrite、chunk、embedding 调参都会变成主观判断。

建议先做 80-120 条问题：

- 40 条用户 FAQ。
- 20 条复杂业务问题。
- 20 条异常/边界问题。
- 20 条低证据或应拒答问题。

### P0-2：升级 rerank

当前 rerank 过于简单，建议先不引入重型模型也能优化：

- query 中文分词。
- question/title 命中加权。
- keywords 命中加权。
- exact phrase 加权。
- domain 词加权。
- negative/uncertain 词识别。

然后再接入 cross-encoder reranker，作为质量增强项。

### P0-3：把 RAG 文档从 FAQ 扩展到业务知识库

当前 RAG 只覆盖“客服问答”，价值不够突出。应扩展到：

- 流程型文档。
- 决策表。
- 异常处理 SOP。
- API 文档。
- 错误码文档。
- 运维 Runbook。

这些文档才更能体现 RAG 对复杂系统的价值。

### P1-1：引入 metadata-aware retrieval

当前 metadata 生成了但没有充分参与检索。

建议增加：

```text
QueryPlanner -> 推断 domain/docType/audience
HybridSearchService -> dense/sparse 检索时增加 filter/boost
Evaluator -> 按 metadata 一致性加分
```

### P1-2：改造 query planning

建议问题分类：

| 类型 | 策略 |
| --- | --- |
| 普通规则问答 | FAQ/policy 检索 |
| 订单状态解释 | 业务工具 + order docs |
| 支付/退款异常 | payment docs + order docs + tool evidence |
| 节目推荐 | business tool，不走知识 RAG 或只补充规则 |
| 运维排查 | log/metrics tool + runbook RAG |
| 宽泛总结 | summary/doc-level retrieval |
| 低信息问题 | 澄清问题 |

### P1-3：增强证据引用和答案审计

建议 final answer 结构化：

```json
{
  "conclusion": "...",
  "basis": [
    {"claim": "...", "evidenceIds": ["E1", "E2"]}
  ],
  "uncertainty": "..."
}
```

或至少在 Markdown 回答中引用证据编号。

### P2-1：索引生命周期生产化

建议：

- 将 `/ai/rag/reindex` 加管理员权限和审计。
- 支持 dry-run。
- 支持增量 upsert/delete。
- 记录索引版本、文档 hash、embedding model、chunk 参数。
- 启动时检查 Qdrant collection、ES alias、文档 cache 是否一致。
- ES/Qdrant 请求增加 timeout、retry、指标。

### P2-2：多数据源 ingestion

后续可以支持：

- Markdown FAQ。
- Markdown policy/procedure/runbook。
- OpenAPI/Swagger。
- Java enum 错误码。
- 数据库字典。
- SQL schema。
- Prometheus 指标说明。
- 日志错误模板。

## 5. 建议新增的 RAG 文档

当前文档主要回答“用户问客服”的问题。如果想更体现 RAG 价值，应围绕 `damai-pro` 的真实业务链路扩展。

### 5.1 订单生命周期文档

建议文件：

`订单生命周期和状态流转-业务规则.md`

覆盖内容：

- 订单创建。
- 待支付。
- 支付成功。
- 支付超时关闭。
- 用户主动取消。
- 系统取消。
- 退款。
- 订单与节目、购票人、支付账单的关系。

可回答问题：

- “为什么订单取消后不能恢复？”
- “支付成功但订单还是待支付怎么办？”
- “订单关闭后库存为什么释放？”
- “订单什么时候会触发退款？”

优先级：**P0**

### 5.2 支付、回调、退款、对账文档

建议文件：

`支付退款和对账处理-业务规则.md`

覆盖内容：

- `commonPay`。
- `notify`。
- `tradeCheck`。
- `refund`。
- 支付单状态。
- 退款单状态。
- 支付金额不一致。
- 回调验签失败。
- 重复支付和幂等。

可回答问题：

- “为什么支付金额不一致会失败？”
- “支付宝回调失败怎么排查？”
- “什么情况下会自动退款？”
- “支付渠道成功但本地订单未更新怎么办？”

优先级：**P0**

### 5.3 库存、票档、座位、锁座文档

建议文件：

`票档库存座位和锁定规则-业务说明.md`

覆盖内容：

- 票档价格。
- 剩余库存。
- Redis 库存 hash。
- DB 库存和 Redis 库存差异。
- 座位状态：未售、锁定、已售。
- 热门项目下单时库存变化。
- 管理后台看到的库存与用户侧库存。

可回答问题：

- “为什么页面显示有票但下单失败？”
- “Redis 库存和 DB 库存不一致怎么办？”
- “座位被锁定后多久释放？”
- “票档剩余数量如何排查？”

优先级：**P0**

### 5.4 实名认证和观演人规则文档

建议文件：

`实名制观演人和证件校验-规则说明.md`

覆盖内容：

- 用户实名认证。
- 购票人管理。
- 观演人证件绑定。
- 下单后观演人不可修改的原因。
- 证件错误处理。
- 一个账号为他人购票的限制。

可回答问题：

- “为什么不能改观演人？”
- “证件号填错还能入场吗？”
- “一个账号可以给几个人买票？”
- “实名制项目为什么必须带身份证？”

优先级：**P0**

### 5.5 节目、场次、票档、购票须知文档

建议文件：

`节目详情字段和购票须知-业务字典.md`

覆盖内容：

- 节目状态。
- 开售时间。
- 场次。
- 城市/场馆。
- 演出时间。
- 票档。
- 是否缺货登记。
- 是否支持退票/转赠/电子票。

可回答问题：

- “不同票档为什么价格不同？”
- “未开售、售罄、缺货登记有什么区别？”
- “什么情况下可以转赠？”
- “节目详情页哪些字段决定能否购买？”

优先级：**P1**

### 5.6 客服 SOP 和异常处理文档

建议文件：

`客服异常处理SOP-订单支付退票入场.md`

覆盖内容：

- 用户没收到票。
- 订单状态不同步。
- 支付扣款但订单未支付。
- 退票未到账。
- 证件错误。
- 电子票无法展示。
- 现场无法入场。
- 疑似诈骗。

可回答问题：

- “用户说扣款了但订单未支付，客服怎么处理？”
- “退票超过 3 个工作日未到账怎么办？”
- “现场无法扫码入场应让用户准备什么？”

优先级：**P1**

### 5.7 错误码和异常文档

建议文件：

`damai-pro错误码和异常处理说明.md`

覆盖内容：

- `BaseCode` 错误码。
- 订单异常。
- 支付异常。
- 用户异常。
- 库存异常。
- 权限异常。
- 参数校验异常。

可回答问题：

- “ORDER_CANCEL 是什么意思？”
- “PAY_BILL_IS_NOT_PAY_STATUS 应该怎么处理？”
- “下单接口返回库存不足时用户该怎么做？”

优先级：**P1**

### 5.8 API/DTO 字段说明文档

建议文件：

`damai-pro核心API和DTO字段说明.md`

覆盖内容：

- 节目搜索。
- 节目详情。
- 票档查询。
- 购票人列表。
- 创建订单。
- 订单取消。
- 支付。
- 退款。

可回答问题：

- “AI 购票预览为什么需要这些字段？”
- “CreateOrderFunctionDto 和 ProgramOrderCreateDto 字段怎么对应？”
- “业务助手调用节目详情后为什么还要查票档库存？”

优先级：**P1**

### 5.9 运维 Runbook 文档

建议文件：

`运维排查Runbook-订单支付库存日志指标.md`

覆盖内容：

- 订单创建失败排查。
- 支付回调失败排查。
- Redis 库存异常排查。
- RabbitMQ 消息堆积排查。
- Elasticsearch 日志查询方法。
- Prometheus JVM/CPU/GC/线程指标解释。
- traceId 排查步骤。

可回答问题：

- “订单服务错误率升高应该先查什么？”
- “RabbitMQ 延迟队列不消费怎么办？”
- “JVM GC 异常和接口超时怎么关联排查？”
- “traceId 经过哪些服务？”

优先级：**P1**

### 5.10 管理后台操作手册

建议文件：

`管理后台节目票档座位操作手册.md`

覆盖内容：

- 节目管理。
- 票档管理。
- 座位管理。
- 库存查看。
- DB 库存和 Redis 库存对比。
- 节目上下架。

可回答问题：

- “管理员如何查看某节目票档剩余库存？”
- “为什么后台座位状态和用户侧不同？”
- “怎么排查票档配置错误？”

优先级：**P2**

### 5.11 用户画像和推荐策略文档

建议文件：

`AI用户画像和节目推荐策略说明.md`

覆盖内容：

- 用户偏好标签。
- 城市偏好。
- 艺人/类型偏好。
- 历史摘要如何影响业务助手。
- 推荐结果如何避免越权和误导。

可回答问题：

- “AI 为什么推荐这些节目？”
- “用户画像里的偏好标签怎么产生？”
- “推荐节目时哪些信息不能暴露？”

优先级：**P2**

## 6. 推荐文档格式

为了让 RAG 更好检索，建议未来文档不只写自然语言，还加入结构化头信息。

示例：

```markdown
---
docType: procedure
domain: payment
audience: ops,customer_service
sourceOfTruth: damai-pro/damai-pay-service
businessEntities: Order,PayBill,RefundBill
effectiveFrom: 2026-05-01
riskLevel: high
---

# 支付回调失败排查 SOP

## 适用场景

用户已扣款，但订单仍显示待支付。

## 排查步骤

1. 查询订单状态。
2. 查询支付单状态。
3. 检查支付回调日志。
4. 检查验签是否成功。
5. 执行 tradeCheck 对账。

## 常见结论

| 现象 | 可能原因 | 处理建议 |
| --- | --- | --- |
| 支付单已支付，订单未支付 | 回调未成功更新订单 | 执行对账任务 |
```

当前 `MarkdownLoader` 尚不解析 frontmatter，但这是后续增强 metadata-aware retrieval 的推荐方向。

## 7. 分阶段落地建议

### 第一阶段：质量基线

目标：知道当前 RAG 到底好不好。

建议任务：

- 建立 `rag-eval` 数据集。
- 增加检索评测脚本。
- 记录 Recall@K、MRR、NDCG、低证据拒答准确率。
- 把当前实现作为 baseline。

### 第二阶段：高收益优化

目标：提升现有 FAQ RAG 效果。

建议任务：

- 优化 rerank。
- 引入 metadata filter/boost。
- 增强 query planner。
- Prompt 中加入证据编号和引用要求。

### 第三阶段：扩展业务知识库

目标：让 RAG 不只回答 FAQ，而能解释复杂票务业务。

建议任务：

- 新增订单生命周期文档。
- 新增支付/退款/对账文档。
- 新增库存/座位/票档文档。
- 新增客服 SOP。
- 新增运维 Runbook。

### 第四阶段：生产化增强

目标：稳定、可观测、可回滚。

建议任务：

- 索引版本化。
- 增量 indexing。
- reindex 权限控制。
- ES/Qdrant 请求指标。
- 多实例 cache 一致性。
- RAG 质量评测接入 CI。

## 8. 推荐优先级总表

| 优先级 | 建议 | 收益 | 成本 |
| --- | --- | --- | --- |
| P0 | 建立 RAG 评测集 | 最高，可量化所有优化 | 中 |
| P0 | 升级 rerank | 高，直接提升最终证据质量 | 中 |
| P0 | 新增订单/支付/库存核心文档 | 高，显著增强业务价值 | 中 |
| P1 | metadata-aware retrieval | 高，减少误召回 | 中 |
| P1 | query classification/planning | 高，复杂问题更稳 | 中 |
| P1 | 证据编号和引用 | 中高，增强可信度 | 低 |
| P1 | 运维 Runbook RAG | 中高，增强 ops 助手价值 | 中 |
| P2 | 增量索引和索引版本化 | 中，生产稳定性提升 | 中高 |
| P2 | embedding fine-tuning | 中，需数据积累 | 高 |
| P2 | LLM rerank 常态化 | 不确定，成本较高 | 高 |

## 9. 外部资料参考

- **Meilisearch：Advanced RAG techniques**
  - 重点：chunking、reranking、metadata、hybrid search、query rewriting、autocut、context distillation、评测。
  - URL: `https://www.meilisearch.com/blog/rag-techniques`

- **Qdrant：Best Practices in RAG Evaluation**
  - 重点：chunking、embedding 选择、Precision@K、MRR、NDCG、hybrid search、reranking、faithfulness。
  - URL: `https://qdrant.tech/blog/rag-evaluation-guide/`

- **LlamaIndex：Production RAG**
  - 重点：检索 chunk 与 synthesis chunk 解耦、structured retrieval、task-specific retrieval、embedding optimization。
  - URL: `https://developers.llamaindex.ai/python/framework/optimizing/production_rag/`

- **Haystack：Reranking in RAG**
  - 重点：hybrid retrieval 下 rerank 的必要性，以及 Recall、MRR、NDCG 评估。
  - URL: `https://haystack.deepset.ai/blog/optimize-rag-with-nvidia-nemo`

- **RAGAS Metrics**
  - 重点：Context Precision、Context Recall、Faithfulness、Response Relevancy、Noise Sensitivity。
  - URL: `https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/`

## 10. 最终建议

当前 `damai-ai` 的 RAG 基础是合格的，甚至已经超过普通 Demo：它有结构化 FAQ ingestion、hybrid retrieval、RRF、证据评估和 trace。

但它现在最缺的不是更复杂的架构，而是三个更务实的能力：

1. **评测闭环**：知道每次改动是变好还是变坏。
2. **更强 rerank 和 retrieval planning**：让最终进入 LLM 的证据更准。
3. **更有业务深度的文档库**：从 QA FAQ 扩展到流程、规则、异常、API、错误码、Runbook。

如果后续只做一件事，建议先做 **RAG 评测集 + rerank 升级**。

如果后续要体现项目亮点，建议做 **票务业务知识库扩展**：订单生命周期、支付退款、库存座位、客服 SOP、运维 Runbook。这些内容和 `damai-pro` 的真实业务链路强相关，比继续堆通用 FAQ 更能体现 RAG 在复杂业务系统中的价值。

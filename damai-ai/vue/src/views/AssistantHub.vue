<template>
  <div class="workspace workspace--assistant-hub">
    <aside class="history-pane">
      <div class="history-pane__top">
        <p class="eyebrow">Customer Service</p>
        <h2>智能客服</h2>
        <button class="ghost-button" @click="startNewChat">新建会话</button>
        <RouterLink v-if="capabilities?.admin" class="ghost-button ghost-button--link" to="/assistant/skills">Skill 管理</RouterLink>
        <RouterLink v-if="capabilities?.admin" class="ghost-button ghost-button--link" to="/assistant/prompts">Prompt 治理</RouterLink>
      </div>

      <div class="history-pane__hint">
        一个用户入口承接售前咨询、规则问答、订单售后和转人工，后台治理能力保留在管理区。
      </div>

      <div class="history-list">
        <button
          v-for="chat in conversations"
          :key="chat.id"
          class="history-item"
          :class="{ active: currentChatId === chat.id }"
          @click="loadConversation(chat.id)"
        >
          <span class="history-item__title">{{ chat.title }}</span>
          <span class="history-item__meta">
            {{ chat.routeType || 'UNROUTED' }} · {{ chat.workflowStatus || 'IDLE' }}
          </span>
        </button>
      </div>
    </aside>

    <section class="chat-pane">
      <header class="chat-hero">
        <div>
          <p class="eyebrow">Single Customer Entry</p>
          <h1>大麦智能客服</h1>
          <p class="chat-hero__desc">
            高频问题先秒答，复杂问题进入统一运行时，售后和高风险操作通过确认卡片或内部工单推进。
          </p>
        </div>
        <div class="hero-badges">
          <span class="hero-badge">秒答</span>
          <span class="hero-badge">情绪识别</span>
          <span class="hero-badge">转人工</span>
          <span v-if="canUseOps" class="hero-badge">治理后台</span>
        </div>
      </header>

      <div class="chat-layout">
        <div class="chat-main">
          <div class="messages" ref="messagesRef">
            <div v-if="!hasMessages" class="empty-state">
              <p class="eyebrow">Hot Questions</p>
              <h2>可以直接点一个常见问题</h2>
              <p>能秒答的先给直接答案和引用；需要业务上下文时，我会引导你补充演出、票档或订单信息。</p>
              <div class="starter-grid">
                <button
                  v-for="prompt in starterPrompts"
                  :key="prompt.questionId || prompt.displayText || prompt"
                  class="starter-chip"
                  @click="sendStarterPrompt(prompt)"
                >
                  {{ prompt.displayText || prompt }}
                </button>
              </div>
            </div>

            <Chat
              v-for="message in currentMessages"
              :key="message.id"
              :message="message"
            />
          </div>

          <div class="composer">
            <textarea
              ref="inputRef"
              v-model="userInput"
              rows="1"
              placeholder="例如：退票规则是什么；实名入场要带什么；这个演出还有哪些票档；订单售后怎么处理。"
              @input="adjustTextareaHeight"
              @keydown.enter.prevent="sendMessage()"
            />
            <button class="send-button" :disabled="isStreaming || !userInput.trim()" @click="sendMessage()">
              {{ isStreaming ? '运行中' : '发送' }}
            </button>
          </div>
        </div>

        <aside class="meta-pane">
          <section v-if="customerServiceCard || customerSentiment || customerWorkItem || customerSuggestions.length" class="meta-card">
            <p class="eyebrow">Customer Service</p>
            <h3>客服状态</h3>
            <div v-if="customerServiceCard" class="quality-status" :class="customerServiceCard.hit ? 'quality-status--pass' : 'quality-status--warn'">
              <strong>{{ customerServiceCard.hit ? '秒答命中' : '运行时处理' }}</strong>
              <span>{{ customerServiceCard.intentCode || '-' }} · {{ customerServiceCard.latencyMs || 0 }} ms</span>
            </div>
            <div v-if="customerSentiment" class="meta-stat">
              <span>情绪</span>
              <strong>{{ customerSentiment.sentiment || 'NEUTRAL' }} · {{ customerSentiment.intensity ?? 0 }}</strong>
            </div>
            <div v-if="customerWorkItem" class="detail-section">
              <span class="memory-label">工单</span>
              <div class="pill-list">
                <span class="detail-pill">{{ customerWorkItem.workItemId }}</span>
                <span class="detail-pill">{{ customerWorkItem.priority || 'MEDIUM' }}</span>
                <span class="detail-pill">{{ customerWorkItem.workStatus || 'OPEN' }}</span>
              </div>
              <p>{{ customerWorkItem.conclusion || '人工客服会结合上下文继续处理。' }}</p>
            </div>
            <div v-if="customerSuggestions.length" class="clarification-list">
              <button
                v-for="suggestion in customerSuggestions"
                :key="suggestion.questionId || suggestion.displayText"
                class="starter-chip"
                @click="sendStarterPrompt(suggestion)"
              >
                {{ suggestion.displayText }}
              </button>
            </div>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Route</p>
            <h3>当前路由</h3>
            <div class="meta-stat">
              <span>技能</span>
              <strong>{{ currentRoute || '未选择' }}</strong>
            </div>
            <div class="meta-stat">
              <span>Skill</span>
              <strong>{{ currentSkillName || currentSkillId || '-' }}</strong>
            </div>
            <div class="meta-stat">
              <span>Run</span>
              <strong>{{ currentRunId || '-' }}</strong>
            </div>
            <div class="meta-stat">
              <span>Status</span>
              <strong>{{ runStatus || '-' }}</strong>
            </div>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Memory</p>
            <h3>长期记忆</h3>
            <p v-if="!hasMemorySummary" class="empty-text">完成至少一轮运行后，这里会展示当前会话的摘要、目标和后续待补充信息。</p>
            <template v-else>
              <div class="memory-block">
                <span class="memory-label">摘要</span>
                <p>{{ structuredMemory.summary }}</p>
              </div>
              <div v-if="structuredMemory.conversationGoal" class="memory-block">
                <span class="memory-label">当前目标</span>
                <p>{{ structuredMemory.conversationGoal }}</p>
              </div>
              <div v-if="structuredMemory.stableFacts.length" class="memory-block">
                <span class="memory-label">稳定事实</span>
                <ul class="detail-list">
                  <li v-for="fact in structuredMemory.stableFacts" :key="fact">{{ fact }}</li>
                </ul>
              </div>
              <div v-if="structuredMemory.pendingQuestions.length" class="memory-block">
                <span class="memory-label">待确认</span>
                <ul class="detail-list">
                  <li v-for="question in structuredMemory.pendingQuestions" :key="question">{{ question }}</li>
                </ul>
              </div>
              <div v-if="structuredMemory.retrievalHints.length" class="memory-block">
                <span class="memory-label">检索提示</span>
                <div class="pill-list">
                  <span v-for="hint in structuredMemory.retrievalHints" :key="hint" class="detail-pill">{{ hint }}</span>
                </div>
              </div>
              <div class="meta-footnote">
                <span>版本 {{ memorySummary?.summaryVersion || 1 }}</span>
                <span>压缩 {{ memorySummary?.compressionCount || 0 }} 次</span>
              </div>
            </template>
          </section>

          <section v-if="primarySkillItems.length" class="meta-card">
            <p class="eyebrow">Skills</p>
            <h3>客服主线 Skill</h3>
            <div class="skill-list">
              <button
                v-for="skill in primarySkillItems"
                :key="skill.skillId"
                class="skill-chip"
                :class="{ active: currentSkillId === skill.skillId }"
                @click="sendWithSkill(skill)"
              >
                <span>{{ skill.name }}</span>
                <strong>{{ skill.riskLevel }}</strong>
              </button>
            </div>
          </section>

          <div v-if="capabilities?.admin" class="workspace-group workspace-group--governance">
            <div class="workspace-group__header">
              <p class="eyebrow">Knowledge & Data Ops</p>
              <h3>知识与问数治理</h3>
              <p>只承载知识库质量、RAG 评测和 NL2SQL 合约，不干扰普通客服入口。</p>
            </div>

          <section v-if="qualityGate" class="meta-card">
            <p class="eyebrow">Quality Gate</p>
            <h3>治理门禁</h3>
            <div class="quality-status" :class="qualityGateClass">
              <strong>{{ qualityGate.status }}</strong>
              <span>RAG {{ qualityGate.latestRagRunId || '-' }} · NL2SQL {{ qualityGate.latestNl2SqlRunId || '-' }}</span>
            </div>
            <div class="detail-section">
              <div class="card-title-row">
                <span class="memory-label">Eval Control</span>
                <div class="rag-job-actions">
                  <button
                    class="ghost-button ghost-button--tiny"
                    :disabled="Boolean(evalSuiteRunning)"
                    @click="runEvalSuite('rag')"
                  >
                    Run RAG
                  </button>
                  <button
                    class="ghost-button ghost-button--tiny"
                    :disabled="Boolean(evalSuiteRunning)"
                    @click="runEvalSuite('nl2sql')"
                  >
                    Run NL2SQL
                  </button>
                  <button
                    class="ghost-button ghost-button--tiny"
                    :disabled="Boolean(evalSuiteRunning)"
                    @click="runEvalSuite('red-team')"
                  >
                    Run Red Team
                  </button>
                </div>
              </div>
              <div v-if="evalSuiteResult" class="comparison-summary">
                <strong>{{ evalSuiteResult.suite }} · {{ evalSuiteResult.status }}</strong>
                <p>{{ evalSuiteResult.evalRunId || evalSuiteResult.resultType }} · {{ evalSuiteProgressText }}</p>
                <div v-if="evalSuiteMetricItems.length" class="pill-list">
                  <span v-for="metric in evalSuiteMetricItems" :key="metric.name" class="detail-pill">
                    {{ metric.name }} {{ metric.value }}
                  </span>
                </div>
                <ul v-if="evalSuiteNextActions.length" class="detail-list">
                  <li v-for="action in evalSuiteNextActions" :key="action">{{ action }}</li>
                </ul>
                <button
                  v-if="evalSuiteResult.evalRunId"
                  class="ghost-button ghost-button--tiny"
                  @click="refreshEvalSuiteRun(evalSuiteResult.suite, evalSuiteResult.evalRunId)"
                >
                  Refresh Eval
                </button>
              </div>
            </div>
            <div v-if="qualityReleaseReadiness" class="readiness-panel">
              <span class="memory-label">Release</span>
              <strong>{{ qualityReleaseReadiness.status }}</strong>
              <p>{{ qualityReleaseReadiness.approvalHint }}</p>
            </div>
            <div v-if="qualityCoverageDomains.length" class="pill-list">
              <span v-for="domain in qualityCoverageDomains" :key="domain" class="detail-pill">{{ domain }}</span>
            </div>
            <div v-if="qualityRagClosure" class="detail-section">
              <span class="memory-label">RAG Closure</span>
              <div class="pill-list">
                <span class="detail-pill">{{ qualityRagClosure.status || 'UNKNOWN' }}</span>
                <span class="detail-pill">baseline {{ qualityRagClosure.baselineReady ? 'ready' : 'missing' }}</span>
                <span class="detail-pill">release {{ qualityRagClosure.releaseBlocked ? 'blocked' : 'ready' }}</span>
              </div>
              <ul v-if="qualityRagClosureActions.length" class="detail-list">
                <li v-for="action in qualityRagClosureActions" :key="action">{{ action }}</li>
              </ul>
            </div>
            <div v-if="qualityNl2SqlContract" class="detail-section">
              <span class="memory-label">NL2SQL Contract</span>
              <div class="pill-list">
                <span class="detail-pill">{{ qualityNl2SqlContract.status || 'UNKNOWN' }}</span>
                <span class="detail-pill">contract {{ qualityNl2SqlContract.contractReady ? 'ready' : 'missing' }}</span>
                <span class="detail-pill">unsafe {{ qualityNl2SqlUnsafeRate }}</span>
                <span class="detail-pill">schema {{ qualityNl2SqlSchemaRecall }}</span>
                <span class="detail-pill">cost {{ qualityNl2SqlCostGuard }}</span>
              </div>
              <p>{{ qualityNl2SqlContractFields }}</p>
            </div>
            <div v-if="qualityCapabilityEvidence.length" class="detail-section">
              <span class="memory-label">Capability Evidence</span>
              <ul class="detail-list">
                <li v-for="item in qualityCapabilityEvidence" :key="item.capability">
                  {{ item.capability }} · {{ item.evidence }}
                </li>
              </ul>
            </div>
            <ul class="gate-list">
              <li v-for="gate in qualityGateItems" :key="gate.name">
                <div>
                  <span>{{ gate.name }}</span>
                  <p v-if="gate.detailText">{{ gate.detailText }}</p>
                </div>
                <strong :class="gate.statusClass">{{ gate.status }}</strong>
              </li>
            </ul>
            <div v-if="qualityFailureSamples.length" class="detail-section">
              <span class="memory-label">Failure Samples</span>
              <ul class="detail-list">
                <li v-for="sample in qualityFailureSamples" :key="sample.gate">
                  {{ sample.gate }} · {{ sample.nextAction || sample.message }}
                </li>
              </ul>
            </div>
          </section>

          <section class="meta-card">
            <div class="card-title-row">
              <div>
                <p class="eyebrow">RAG EvalOps</p>
                <h3>评测闭环</h3>
              </div>
              <button class="ghost-button ghost-button--tiny" @click="loadAdminWorkspace">Refresh</button>
            </div>
            <div v-if="ragEvalReport" class="eval-summary">
              <div class="meta-stat">
                <span>Run</span>
                <strong>{{ ragEvalReport.evalRunId || qualityGate?.latestRagRunId || '-' }}</strong>
              </div>
              <div class="meta-stat">
                <span>Cases</span>
                <strong>{{ ragEvalReport.completedCases ?? ragEvalReport.totalCases ?? '-' }}</strong>
              </div>
            </div>
            <div v-if="qualityGate?.latestRagRunId" class="baseline-compare">
              <input v-model="ragBaselineRunId" placeholder="baseline run id" />
              <button class="ghost-button ghost-button--tiny" @click="compareRagEvalWithBaseline()">Compare</button>
            </div>
            <div v-if="ragEvalComparison" class="comparison-summary">
              <strong>{{ ragEvalComparison.baselineRunId }}</strong>
              <p>{{ comparisonSummaryText }}</p>
            </div>
            <div class="detail-section">
              <div class="card-title-row">
                <span class="memory-label">Reindex Jobs</span>
                <div class="rag-job-actions">
                  <button class="ghost-button ghost-button--tiny" @click="createRagReindexJob('full')">Full</button>
                  <button class="ghost-button ghost-button--tiny" @click="createRagReindexJob('incremental')">Incremental</button>
                </div>
              </div>
              <p v-if="ragLastReindexJob" class="trace-note">
                submitted {{ ragLastReindexJob.taskType || 'full' }} · {{ ragLastReindexJob.taskId }}
              </p>
              <p v-if="!ragIngestionTaskItems.length" class="empty-text">暂无索引任务。</p>
              <ul v-else class="tool-boundary-list">
                <li v-for="task in ragIngestionTaskItems" :key="task.taskId">
                  <div>
                    <strong>{{ task.taskId }}</strong>
                    <p>{{ task.taskType }} · {{ task.completedChunks || 0 }}/{{ task.totalChunks || 0 }} chunks</p>
                  </div>
                  <span class="trace-status" :class="ragTaskStatusClass(task)">
                    {{ task.taskStatus || 'UNKNOWN' }}
                  </span>
                </li>
              </ul>
            </div>
            <p v-if="!ragBadCaseItems.length" class="empty-text">当前没有待审核 bad case。</p>
            <ul v-else class="admin-action-list">
              <li v-for="badCase in ragBadCaseItems" :key="badCase.badCaseId">
                <div>
                  <strong>{{ badCase.question || badCase.badCaseId }}</strong>
                  <p>{{ badCase.failureType || 'RAG_BAD_CASE' }} · {{ badCase.reviewStatus || 'PENDING' }}</p>
                </div>
                <div class="bad-case-actions">
                  <button class="ghost-button ghost-button--tiny" @click="reviewBadCase(badCase, 'CONFIRMED', 'confirmed as valid RAG regression')">确认</button>
                  <button class="ghost-button ghost-button--tiny" @click="reviewBadCase(badCase, 'FIXED', 'fixed by prompt/retrieval update')">已修复</button>
                  <button class="ghost-button ghost-button--tiny" @click="reviewBadCase(badCase, 'IGNORED', 'not reproducible or not actionable')">忽略</button>
                  <button class="ghost-button ghost-button--tiny" @click="convertBadCaseToEval(badCase)">转 Eval</button>
                </div>
              </li>
            </ul>
          </section>
          </div>

          <div v-if="capabilities?.admin && hasExperimentalWorkspace" class="workspace-group workspace-group--experimental">
            <div class="workspace-group__header">
              <p class="eyebrow">Governance</p>
              <h3>AI 治理工作台</h3>
              <p>MCP 边界、Ops Evidence 和 Red Team 属于管理员治理能力，默认不进入客服主线。</p>
            </div>

          <section v-if="mcpGovernance" class="meta-card">
            <p class="eyebrow">MCP</p>
            <h3>协议边界</h3>
            <div class="quality-status" :class="mcpGovernanceClass">
              <strong>{{ mcpGovernance.status }}</strong>
              <span>tools {{ mcpGovernance.allowlistSize || 0 }} · resources {{ mcpGovernance.resourceAllowlistSize || 0 }} · prompts {{ mcpGovernance.promptAllowlistSize || 0 }}</span>
            </div>
            <div class="meta-stat">
              <span>Admin</span>
              <strong>{{ mcpGovernance.requireAdmin ? 'ON' : 'OFF' }}</strong>
            </div>
            <div class="meta-stat">
              <span>Confirm</span>
              <strong>{{ mcpGovernance.requireHighRiskConfirmation ? 'ON' : 'OFF' }}</strong>
            </div>
            <div class="meta-stat">
              <span>NL2SQL</span>
              <strong>{{ mcpGovernance.exposeNl2Sql ? 'EXPOSED' : 'BLOCKED' }}</strong>
            </div>
            <ul class="tool-boundary-list">
              <li v-for="tool in mcpAllowlistedTools" :key="tool.toolName">
                <div>
                  <strong>{{ tool.toolName }}</strong>
                  <p>{{ tool.scope }} · {{ tool.reason }}</p>
                </div>
                <span class="trace-status" :class="tool.riskLevel === 'HIGH' ? 'trace-status--error' : 'trace-status--ok'">
                  {{ tool.riskLevel }}
                </span>
              </li>
            </ul>
            <div v-if="mcpHighRiskTools.length" class="detail-section">
              <span class="memory-label">High Risk Tools</span>
              <div class="pill-list">
                <span v-for="tool in mcpHighRiskTools" :key="tool.toolName" class="detail-pill">
                  {{ tool.toolName }} · {{ tool.exposed ? 'exposed' : 'blocked' }}
                </span>
              </div>
            </div>
            <div v-if="mcpAllowlistedResources.length" class="detail-section">
              <span class="memory-label">Resources</span>
              <div class="pill-list">
                <span v-for="resource in mcpAllowlistedResources" :key="resource.name" class="detail-pill">
                  {{ resource.name }} · {{ resource.riskLevel }}
                </span>
              </div>
            </div>
            <div v-if="mcpAllowlistedPrompts.length" class="detail-section">
              <span class="memory-label">Prompts</span>
              <div class="pill-list">
                <span v-for="prompt in mcpAllowlistedPrompts" :key="prompt.name" class="detail-pill">
                  {{ prompt.name }} · {{ prompt.riskLevel }}
                </span>
              </div>
            </div>
            <div class="panel-actions panel-actions--compact">
              <button class="ghost-button ghost-button--tiny" @click="readMcpResource()">Read Resource</button>
              <button class="ghost-button ghost-button--tiny" @click="renderMcpPrompt()">Render Prompt</button>
            </div>
            <div v-if="mcpBoundaryResult" class="detail-section">
              <span class="memory-label">Boundary Result</span>
              <div class="pill-list">
                <span class="detail-pill">{{ mcpBoundaryResult.surface }} · {{ mcpBoundaryResult.name }}</span>
                <span class="detail-pill">{{ mcpBoundaryResult.policy }}</span>
              </div>
              <p class="empty-text">{{ mcpBoundaryPreview }}</p>
            </div>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Ops Evidence</p>
            <h3>运维证据工作台</h3>
            <div class="panel-actions panel-actions--compact">
              <button class="ghost-button ghost-button--tiny" @click="buildOpsRcaEvidence()">Build RCA</button>
            </div>
            <div v-if="opsProviderItems.length" class="detail-section">
              <span class="memory-label">Providers</span>
              <ul class="admin-action-list">
                <li v-for="provider in opsProviderItems" :key="provider.name || provider.providerType">
                  <div>
                    <strong>{{ provider.name || provider.providerType }}</strong>
                    <p>{{ provider.status || 'UNKNOWN' }} · {{ provider.reason || provider.message || 'read-only evidence provider' }}</p>
                  </div>
                  <span :class="['trace-status', provider.available ? 'trace-status--ok' : 'trace-status--error']">
                    {{ provider.available ? 'ready' : 'missing' }}
                  </span>
                </li>
              </ul>
            </div>
            <p v-else class="empty-text">暂无可用证据 Provider；RCA 将以缺证据状态降级。</p>
            <div v-if="opsEvidenceResult" class="evidence-result">
              <strong>{{ opsEvidenceResult.evidenceBundle?.rcaSummary || opsEvidenceResult.status }}</strong>
              <p>{{ opsEvidenceResult.assistantPrompt }}</p>
              <div class="detail-section">
                <span class="memory-label">Coverage</span>
                <div class="pill-list">
                  <span class="detail-pill">{{ opsEvidenceCoverage }}</span>
                  <span class="detail-pill">{{ opsEvidenceResult.evidenceBundle?.confidence || 'UNKNOWN' }}</span>
                  <span v-if="opsEvidenceResult.evidenceBundle?.humanReviewRequired" class="detail-pill">human review</span>
                </div>
                <p v-if="opsMissingProviders.length">Missing: {{ opsMissingProviders.join(' / ') }}</p>
              </div>
              <div v-if="opsLinkedSignals.length" class="detail-section">
                <span class="memory-label">Linked Signals</span>
                <ul class="detail-list">
                  <li v-for="signal in opsLinkedSignals" :key="signal">{{ signal }}</li>
                </ul>
              </div>
              <div v-if="opsRecommendedRunbooks.length" class="detail-section">
                <span class="memory-label">Recommended Runbooks</span>
                <ul class="detail-list">
                  <li v-for="runbook in opsRecommendedRunbooks" :key="runbook">{{ runbook }}</li>
                </ul>
              </div>
              <div v-if="opsSuggestedActions.length" class="detail-section">
                <span class="memory-label">Actions</span>
                <ul class="detail-list">
                  <li v-for="action in opsSuggestedActions" :key="action">{{ action }}</li>
                </ul>
              </div>
            </div>
          </section>

          <section class="meta-card">
            <p class="eyebrow">DataOps</p>
            <h3>数据问数治理</h3>
            <div class="panel-actions panel-actions--compact">
              <button class="ghost-button ghost-button--tiny" @click="reloadSemanticCatalog()">Reload Catalog</button>
              <button class="ghost-button ghost-button--tiny" @click="rebuildOpsMetrics()">Rebuild Metrics</button>
            </div>
            <div class="detail-section">
              <span class="memory-label">Semantic Catalog</span>
              <div class="pill-list">
                <span class="detail-pill">version {{ semanticCatalogVersion }}</span>
                <span class="detail-pill">{{ semanticCatalogItems.length }} items</span>
                <span class="detail-pill">{{ semanticCatalogStatus }}</span>
              </div>
            </div>
            <ul v-if="semanticCatalogItems.length" class="admin-action-list">
              <li v-for="item in semanticCatalogItems" :key="item.catalogId || item.viewName || item.metricName">
                <div>
                  <strong>{{ item.metricName || item.viewName || item.catalogId }}</strong>
                  <p>{{ item.viewName || item.datasetName || 'semantic view' }} · {{ item.securityLevel || 'NORMAL' }}</p>
                </div>
                <span class="detail-pill">{{ item.status || 'ACTIVE' }}</span>
              </li>
            </ul>
            <p v-else class="empty-text">暂无 active 语义目录；NL2SQL 仅生成并校验 SQL。</p>
            <div v-if="metricsRebuildResult" class="detail-section">
              <span class="memory-label">Metrics Rebuild</span>
              <div class="pill-list">
                <span class="detail-pill">{{ metricsRebuildResult.status || 'SUBMITTED' }}</span>
                <span class="detail-pill">{{ metricsRebuildResult.rebuiltRows ?? 0 }} rows</span>
              </div>
            </div>
          </section>
          </div>

          <section class="meta-card">
            <p class="eyebrow">Timeline</p>
            <h3>运行步骤</h3>
            <p v-if="!orderedTimeline.length" class="empty-text">发送首条消息后，这里会展示路由、检索、工具和完成事件。</p>
            <ol v-else class="step-list">
              <li v-for="item in orderedTimeline" :key="item.id">
                <span>{{ item.event }}</span>
                <strong>{{ item.data?.summary || item.data?.status || item.data?.routeType || '' }}</strong>
              </li>
            </ol>
          </section>

          <section class="meta-card">
            <div class="card-title-row">
              <div>
                <p class="eyebrow">RunGraph</p>
                <h3>图式运行</h3>
              </div>
              <button
                v-if="runGraph?.checkpointId"
                class="ghost-button ghost-button--tiny"
                :disabled="isStreaming"
                @click="replayRun"
              >
                Replay
              </button>
            </div>
            <p v-if="!graphNodeItems.length" class="empty-text">运行开始后，这里会展示 route、plan、skill、tool、approval 和 finalize 节点状态。</p>
            <template v-else>
              <div v-if="runGraphSummary" class="graph-summary">
                <div class="meta-stat">
                  <span>Replayable</span>
                  <strong>{{ runGraphSummary.checkpoint?.resumable ? 'YES' : 'NO' }}</strong>
                </div>
                <div class="meta-stat">
                  <span>Resume</span>
                  <strong>{{ runGraphSummary.checkpoint?.resumeCount || 0 }} / {{ runGraphSummary.checkpoint?.checkpointReplayEvents || 0 }}</strong>
                </div>
                <div class="meta-stat">
                  <span>Risk</span>
                  <strong>{{ runGraphHighRiskNodes.length }}</strong>
                </div>
                <div class="meta-stat">
                  <span>Cost</span>
                  <strong>{{ runGraphSummary.estimatedCost || '-' }}</strong>
                </div>
              </div>
              <div v-if="runGraph?.checkpointId" class="checkpoint-banner">
                <strong>{{ runGraph.checkpointStage }}</strong>
                <span>{{ runGraph.checkpointId }}</span>
              </div>
              <div v-if="runGraphRecoveryPlan" class="detail-section">
                <span class="memory-label">Recovery Plan</span>
                <div class="pill-list">
                  <span class="detail-pill">{{ runGraphRecoveryPlan.resumeFromStage || 'NO_CHECKPOINT' }}</span>
                  <span class="detail-pill">route {{ runGraphRecoveryPlan.skipRouting ? 'skip' : 'run' }}</span>
                  <span class="detail-pill">retrieval {{ runGraphRecoveryPlan.skipRetrieval ? 'skip' : 'run' }}</span>
                  <span class="detail-pill">{{ runGraphRecoveryPlan.riskHint || 'UNKNOWN' }}</span>
                </div>
                <div v-if="runGraphReplayContract.length" class="pill-list">
                  <span v-for="item in runGraphReplayContract" :key="item" class="detail-pill">{{ item }}</span>
                </div>
                <ul v-if="runGraphRecoveryActions.length" class="detail-list">
                  <li v-for="action in runGraphRecoveryActions" :key="action">{{ action }}</li>
                </ul>
              </div>
              <div v-if="runGraphAuditTrail.length" class="detail-section">
                <span class="memory-label">Replay Audit</span>
                <ul class="detail-list">
                  <li v-for="event in runGraphAuditTrail" :key="`${event.eventOrder}-${event.eventType}`">
                    {{ event.eventType }} · {{ event.checkpointStage || event.summary || event.eventCategory }}
                  </li>
                </ul>
              </div>
              <div v-if="runGraphHighRiskNodes.length" class="detail-section">
                <span class="memory-label">Risk Nodes</span>
                <div class="pill-list">
                  <span v-for="node in runGraphHighRiskNodes" :key="node.id" class="detail-pill">
                    {{ node.label || node.id }} · {{ node.riskLevel }}
                  </span>
                </div>
              </div>
              <ol class="graph-list">
                <li v-for="node in graphNodeItems" :key="node.id" class="graph-node">
                  <span class="graph-node__dot" :class="node.statusClass"></span>
                  <div>
                    <strong>{{ node.label }}</strong>
                    <p>{{ node.detailText }}</p>
                  </div>
                  <span class="trace-status" :class="node.statusClass">{{ node.status }}</span>
                </li>
              </ol>
            </template>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Stages</p>
            <h3>阶段观测</h3>
            <p v-if="!stageTraceItems.length" class="empty-text">这里会展示规划、检索、查询改写和答案生成各阶段的耗时与模型调用信息。</p>
            <ul v-else class="trace-list">
              <li v-for="trace in stageTraceItems" :key="trace.id" class="trace-item">
                <div class="trace-header">
                  <strong>{{ trace.label }}</strong>
                  <span class="trace-status" :class="trace.statusClass">{{ trace.statusText }}</span>
                </div>
                <div class="trace-meta">
                  <span>{{ trace.requestType || 'Runtime' }}</span>
                  <span>{{ trace.latencyText }}</span>
                </div>
                <div class="trace-meta">
                  <span>{{ trace.modelName || '未记录模型' }}</span>
                  <span v-if="trace.totalTokens">tokens {{ trace.totalTokens }}</span>
                  <span v-else-if="trace.promptTokens || trace.completionTokens">
                    {{ trace.promptTokens || 0 }}/{{ trace.completionTokens || 0 }}
                  </span>
                </div>
                <p v-if="trace.metadataSummary" class="trace-note">{{ trace.metadataSummary }}</p>
                <p v-if="trace.errorMessage" class="trace-note trace-note--error">{{ trace.errorMessage }}</p>
              </li>
            </ul>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Retrieval</p>
            <h3>检索细节</h3>
            <p v-if="!retrievalTraceItems.length" class="empty-text">知识问答运行后，这里会展示 shadow route、首轮检索、纠正检索和结果合并过程。</p>
            <ul v-else class="trace-list">
              <li v-for="trace in retrievalTraceItems" :key="trace.id" class="trace-item">
                <div class="trace-header">
                  <strong>{{ trace.label }}</strong>
                  <span class="trace-status trace-status--neutral">{{ trace.traceType || 'stage' }}</span>
                </div>
                <p v-if="trace.originalQuery" class="trace-query">Q: {{ trace.originalQuery }}</p>
                <p v-if="trace.rewrittenQuery && trace.rewrittenQuery !== trace.originalQuery" class="trace-query">R: {{ trace.rewrittenQuery }}</p>
                <div class="trace-meta">
                  <span>dense {{ trace.denseHitCount }}</span>
                  <span>sparse {{ trace.sparseHitCount }}</span>
                  <span>fused {{ trace.fusedHitCount }}</span>
                  <span>final {{ trace.finalHitCount }}</span>
                </div>
                <p v-if="trace.metadataSummary" class="trace-note">{{ trace.metadataSummary }}</p>
                <div v-if="trace.shadowRoute?.scopeCandidates?.length" class="memory-block">
                  <span class="memory-label">Scope</span>
                  <div class="pill-list">
                    <span v-for="candidate in trace.shadowRoute.scopeCandidates.slice(0, 3)" :key="`${trace.id}-scope-${candidate.name}`" class="detail-pill">
                      {{ candidate.name }} · {{ candidate.score }}
                    </span>
                  </div>
                </div>
                <div v-if="trace.shadowRoute?.topicCandidates?.length" class="memory-block">
                  <span class="memory-label">Topic</span>
                  <div class="pill-list">
                    <span v-for="candidate in trace.shadowRoute.topicCandidates.slice(0, 3)" :key="`${trace.id}-topic-${candidate.name}`" class="detail-pill">
                      {{ candidate.name }} · {{ candidate.score }}
                    </span>
                  </div>
                </div>
                <div v-if="trace.shadowRoute?.documentCandidates?.length" class="memory-block">
                  <span class="memory-label">Document</span>
                  <div class="pill-list">
                    <span v-for="candidate in trace.shadowRoute.documentCandidates.slice(0, 3)" :key="`${trace.id}-document-${candidate.name}`" class="detail-pill">
                      {{ candidate.name }} · {{ candidate.score }}
                    </span>
                  </div>
                </div>
              </li>
            </ul>
          </section>

          <section v-if="evidenceCards.length" class="meta-card">
            <p class="eyebrow">Evidence</p>
            <h3>来源证据</h3>
            <ul class="source-list">
              <li v-for="source in evidenceCards" :key="source.chunkId || source.title">
                <strong>{{ source.title || source.section || source.source }}</strong>
                <p>{{ source.snippet || source.content || '无摘要' }}</p>
              </li>
            </ul>
          </section>

          <section v-if="pendingAction" class="meta-card approval-card">
            <p class="eyebrow">Action</p>
            <h3>待审批动作</h3>
            <p class="approval-summary">{{ pendingAction.summary || pendingAction.previewText || '请确认是否执行该动作。' }}</p>
            <div class="approval-actions">
              <button class="primary-button" :disabled="pendingAction.processing" @click="approveAction">
                {{ pendingAction.processing ? '处理中' : '批准' }}
              </button>
              <button class="ghost-button ghost-button--soft" :disabled="pendingAction.processing" @click="rejectAction">拒绝</button>
            </div>
          </section>

          <section v-if="clarificationOptions.length" class="meta-card">
            <p class="eyebrow">Clarification</p>
            <h3>请选择意图</h3>
            <div class="clarification-list">
              <button
                v-for="option in clarificationOptions"
                :key="option"
                class="starter-chip"
                @click="sendMessage(option)"
              >
                {{ option }}
              </button>
            </div>
          </section>

          <section v-if="toolCalls.length" class="meta-card">
            <p class="eyebrow">Tools</p>
            <h3>最近工具</h3>
            <ul class="tool-list">
              <li v-for="tool in toolCalls.slice(0, 5)" :key="tool.id">
                <span>{{ tool.toolName }}</span>
                <strong>{{ tool.summary || tool.event }}</strong>
              </li>
            </ul>
          </section>

          <section v-if="refusalReason || errorMessage" class="meta-card error-card">
            <p class="eyebrow">Guardrail</p>
            <h3>拒答与错误</h3>
            <p v-if="refusalReason">{{ refusalReason }}</p>
            <p v-if="errorMessage">{{ errorMessage }}</p>
          </section>
        </aside>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import Chat from '../components/Chat.vue'
import { ensureAuthenticated } from '../api/api'
import { useAssistantRuntime } from '../composables/useAssistantRuntime'

const fallbackStarterPrompts = [
  { questionId: 'refund-rule', displayText: '退票规则', queryText: '节目开演前还能退票吗', intentCode: 'REFUND_RULE' },
  { questionId: 'real-name-entry', displayText: '实名入场', queryText: '实名入场要带什么证件', intentCode: 'REAL_NAME_RULE' },
  { questionId: 'ticket-category', displayText: '票档查询', queryText: '这个演出还有哪些票档', intentCode: 'TICKET_CATEGORY' },
  { questionId: 'ticket-format', displayText: '电子票/纸质票', queryText: '电子票和纸质票怎么取', intentCode: 'ORDER_AFTERSALE' },
  { questionId: 'order-aftersale', displayText: '订单售后', queryText: '订单售后怎么处理', intentCode: 'ORDER_AFTERSALE' },
  { questionId: 'human-handoff', displayText: '转人工', queryText: '我要转人工客服', intentCode: 'HUMAN_HANDOFF' }
]

const {
  messagesRef,
  inputRef,
  userInput,
  isStreaming,
  currentChatId,
  currentRunId,
  currentRoute,
  currentSkillId,
  currentSkillName,
  currentMessages,
  customerStarterPrompts,
  customerServiceCard,
  customerSuggestions,
  customerSentiment,
  customerWorkItem,
  conversations,
  orderedTimeline,
  evidenceCards,
  stageTraces,
  retrievalTraces,
  runGraph,
  memorySummary,
  pendingAction,
  toolCalls,
  clarificationOptions,
  errorMessage,
  refusalReason,
  runStatus,
  qualityGate,
  mcpGovernance,
  mcpBoundaryResult,
  evalSuiteResult,
  evalSuiteRunning,
  ragEvalReport,
  ragBaselineRunId,
  ragEvalComparison,
  ragBadCases,
  ragIngestionTasks,
  ragLastReindexJob,
  opsProviderStatus,
  opsRunbooks,
  opsEvidenceResult,
  semanticCatalog,
  metricsRebuildResult,
  hasMessages,
  capabilities,
  canUseOps,
  adjustTextareaHeight,
  loadCustomerStarterPrompts,
  loadCapabilities,
  loadAdminWorkspace,
  loadConversations,
  loadConversation,
  startNewChat,
  sendMessage,
  resumeRun,
  replayRun,
  compareRagEvalWithBaseline,
  runEvalSuite,
  refreshEvalSuiteRun,
  convertBadCaseToEval,
  reviewBadCase,
  createRagReindexJob,
  readMcpResource,
  renderMcpPrompt,
  buildOpsRcaEvidence,
  reloadSemanticCatalog,
  rebuildOpsMetrics,
  approveAction,
  rejectAction
} = useAssistantRuntime()

const stageLabels = {
  PLANNING: '运行规划',
  KNOWLEDGE_QUERY_REWRITE: '查询改写',
  KNOWLEDGE_SUB_QUESTION: '子问题拆解',
  KNOWLEDGE_HYDE: 'HyDE 假想文档',
  KNOWLEDGE_RETRIEVAL_FIRST_PASS: '首轮检索',
  KNOWLEDGE_RETRIEVAL_CORRECTIVE: '纠正检索',
  KNOWLEDGE_ANSWER: '答案生成'
}

const retrievalLabels = {
  'knowledge.shadow_route': 'Shadow Route',
  'knowledge.retrieval.first_pass': '首轮检索结果',
  'knowledge.retrieval.corrective_query': '纠正查询',
  'knowledge.retrieval.sub_question': '子问题检索',
  'knowledge.retrieval.hyde': 'HyDE 检索',
  'knowledge.retrieval.corrective_merged': '纠正结果合并'
}

const starterPrompts = computed(() => customerStarterPrompts.value.length ? customerStarterPrompts.value : fallbackStarterPrompts)

const primarySkillIds = new Set([
  'business.unified',
  'business.purchase.prepare',
  'knowledge.policy.qa',
  'general.web.search'
])

const primarySkillItems = computed(() => Array.isArray(capabilities.value?.skills)
  ? capabilities.value.skills.filter(skill => primarySkillIds.has(skill.skillId))
  : [])

const hasExperimentalWorkspace = computed(() => Array.isArray(capabilities.value?.experimentalWorkspaces)
  ? capabilities.value.experimentalWorkspaces.length > 0
  : false)

const sendStarterPrompt = (prompt) => {
  const queryText = prompt?.queryText || prompt?.displayText || String(prompt || '')
  return sendMessage(queryText, {
    scene: 'customer_service',
    intentHint: prompt?.intentCode,
    hotQuestionId: prompt?.questionId
  })
}

const structuredMemory = computed(() => memorySummary.value?.structuredMemory || {
  summary: '',
  conversationGoal: '',
  stableFacts: [],
  pendingQuestions: [],
  retrievalHints: []
})

const hasMemorySummary = computed(() => memorySummary.value?.hasContent === true)

const qualityGateItems = computed(() => Array.isArray(qualityGate.value?.gates)
  ? qualityGate.value.gates.map(gate => ({
    ...gate,
    statusClass: gate.status === 'FAIL' ? 'gate-status--fail' : (gate.status === 'WARN' ? 'gate-status--warn' : 'gate-status--pass'),
    detailText: qualityGateDetail(gate)
  }))
  : [])

const qualityGateDetail = (gate) => {
  const details = gate?.details || {}
  if (gate?.name === 'NL2SQL_EVAL') {
    return [
      details.executionAccuracy != null ? `exec ${Number(details.executionAccuracy).toFixed(2)}` : '',
      details.schemaLinkRecall != null ? `schema ${Number(details.schemaLinkRecall).toFixed(2)}` : '',
      details.unsafeRejectionRate != null ? `unsafe ${Number(details.unsafeRejectionRate).toFixed(2)}` : '',
      details.lowConfidenceClarificationRate != null ? `clarify ${Number(details.lowConfidenceClarificationRate).toFixed(2)}` : ''
    ].filter(Boolean).join(' · ')
  }
  if (gate?.name === 'RAG_EVAL') {
    return [
      details.avgRecall != null ? `recall ${Number(details.avgRecall).toFixed(2)}` : '',
      details.avgAnswerCorrectness != null ? `correct ${Number(details.avgAnswerCorrectness).toFixed(2)}` : ''
    ].filter(Boolean).join(' · ')
  }
  return gate?.message || ''
}

const qualityGateClass = computed(() => {
  const status = qualityGate.value?.status || 'UNKNOWN'
  return status === 'FAIL' ? 'quality-status--fail' : (status === 'WARN' ? 'quality-status--warn' : 'quality-status--pass')
})

const qualityReleaseReadiness = computed(() => qualityGate.value?.releaseReadiness || null)

const qualityCoverageDomains = computed(() => Array.isArray(qualityGate.value?.coverageSummary?.domains)
  ? qualityGate.value.coverageSummary.domains.slice(0, 8)
  : [])

const qualityRagClosure = computed(() => qualityGate.value?.ragClosure || null)

const qualityRagClosureActions = computed(() => Array.isArray(qualityRagClosure.value?.nextActions)
  ? qualityRagClosure.value.nextActions.slice(0, 3)
  : [])

const qualityNl2SqlContract = computed(() => qualityGate.value?.nl2SqlContract || null)

const qualityNl2SqlUnsafeRate = computed(() => {
  const value = qualityNl2SqlContract.value?.unsafeRejectionRate
  return value == null ? 'n/a' : Number(value).toFixed(2)
})

const qualityNl2SqlSchemaRecall = computed(() => {
  const value = qualityNl2SqlContract.value?.schemaLinkRecall
  return value == null ? 'n/a' : Number(value).toFixed(2)
})

const qualityNl2SqlContractFields = computed(() => Array.isArray(qualityNl2SqlContract.value?.responseFields)
  ? qualityNl2SqlContract.value.responseFields.slice(0, 8).join(' / ')
  : '')

const qualityNl2SqlCostGuard = computed(() => qualityNl2SqlContract.value?.costGuardPolicy || 'n/a')

const qualityCapabilityEvidence = computed(() => Array.isArray(qualityGate.value?.capabilityEvidence)
  ? qualityGate.value.capabilityEvidence.slice(0, 3)
  : [])

const qualityFailureSamples = computed(() => Array.isArray(qualityGate.value?.failureSamples)
  ? qualityGate.value.failureSamples.slice(0, 3)
  : [])

const evalSuiteNextActions = computed(() => Array.isArray(evalSuiteResult.value?.nextActions)
  ? evalSuiteResult.value.nextActions.slice(0, 3)
  : [])

const evalSuiteProgressText = computed(() => {
  const completed = evalSuiteResult.value?.completedCases
  const total = evalSuiteResult.value?.totalCases
  if (completed == null || total == null) {
    return evalSuiteResult.value?.resultType || 'snapshot'
  }
  const progress = evalSuiteResult.value?.progress == null
    ? 0
    : Math.round(Number(evalSuiteResult.value.progress) * 100)
  return `${completed}/${total} cases · ${progress}%`
})

const evalSuiteMetricItems = computed(() => {
  const metrics = evalSuiteResult.value?.metrics || {}
  return Object.entries(metrics)
    .slice(0, 5)
    .map(([name, rawValue]) => ({
      name,
      value: typeof rawValue === 'number' ? rawValue.toFixed(rawValue > 10 ? 0 : 2) : rawValue
    }))
})

const mcpGovernanceClass = computed(() => {
  const status = mcpGovernance.value?.status || 'UNKNOWN'
  return status === 'PASS' ? 'quality-status--pass' : 'quality-status--warn'
})

const mcpAllowlistedTools = computed(() => Array.isArray(mcpGovernance.value?.allowlistedTools)
  ? mcpGovernance.value.allowlistedTools.slice(0, 4)
  : [])

const mcpHighRiskTools = computed(() => Array.isArray(mcpGovernance.value?.highRiskTools)
  ? mcpGovernance.value.highRiskTools
  : [])

const mcpAllowlistedResources = computed(() => Array.isArray(mcpGovernance.value?.allowlistedResources)
  ? mcpGovernance.value.allowlistedResources.slice(0, 3)
  : [])

const mcpAllowlistedPrompts = computed(() => Array.isArray(mcpGovernance.value?.allowlistedPrompts)
  ? mcpGovernance.value.allowlistedPrompts.slice(0, 3)
  : [])

const mcpBoundaryPreview = computed(() => {
  if (!mcpBoundaryResult.value) {
    return ''
  }
  const source = mcpBoundaryResult.value.template || mcpBoundaryResult.value.content || mcpBoundaryResult.value.requiredEvidence || ''
  const text = typeof source === 'string' ? source : JSON.stringify(source)
  return text.length > 160 ? `${text.slice(0, 160)}...` : text
})

const ragBadCaseItems = computed(() => Array.isArray(ragBadCases.value)
  ? ragBadCases.value.slice(0, 3)
  : [])

const ragIngestionTaskItems = computed(() => Array.isArray(ragIngestionTasks.value)
  ? ragIngestionTasks.value.slice(0, 4)
  : [])

const ragTaskStatusClass = (task) => {
  const status = String(task?.taskStatus || '').toLowerCase()
  if (['completed', 'success', 'finished'].includes(status)) {
    return 'trace-status--ok'
  }
  if (['failed', 'error'].includes(status)) {
    return 'trace-status--error'
  }
  return 'trace-status--running'
}

const opsProviderItems = computed(() => {
  const providers = Array.isArray(opsProviderStatus.value) ? opsProviderStatus.value : []
  return providers.map(item => ({
    ...item,
    name: item.provider || item.name || item.signalType || item.providerType || 'provider',
    providerType: item.signalType || item.providerType || item.name || 'provider',
    available: item.available === true || item.healthy === true || item.status === 'ACTIVE'
  }))
})

const opsEvidenceBundle = computed(() => opsEvidenceResult.value?.evidenceBundle || null)

const opsMissingProviders = computed(() => Array.isArray(opsEvidenceBundle.value?.missingProviders)
  ? opsEvidenceBundle.value.missingProviders
  : [])

const opsEvidenceCoverage = computed(() => {
  const value = opsEvidenceBundle.value?.evidenceCoverage?.coverageRatio
    ?? opsEvidenceBundle.value?.evidenceCoverage
    ?? ''
  if (typeof value === 'number') {
    return `coverage ${Math.round(value * 100)}%`
  }
  return value || 'coverage n/a'
})

const opsLinkedSignals = computed(() => Array.isArray(opsEvidenceBundle.value?.linkedSignals)
  ? opsEvidenceBundle.value.linkedSignals.slice(0, 5)
  : [])

const opsRecommendedRunbooks = computed(() => {
  if (Array.isArray(opsEvidenceBundle.value?.recommendedRunbooks)) {
    return opsEvidenceBundle.value.recommendedRunbooks.slice(0, 4).map(item => item.title || item.runbookId || item)
  }
  return Array.isArray(opsRunbooks.value)
    ? opsRunbooks.value.slice(0, 4).map(item => item.runbookName || item.title || item.runbookId)
    : []
})

const opsSuggestedActions = computed(() => Array.isArray(opsEvidenceBundle.value?.suggestedActions)
  ? opsEvidenceBundle.value.suggestedActions.slice(0, 4)
  : [])

const semanticCatalogItems = computed(() => Array.isArray(semanticCatalog.value)
  ? semanticCatalog.value.slice(0, 6)
  : Array.isArray(semanticCatalog.value?.items)
    ? semanticCatalog.value.items.slice(0, 6)
    : [])

const semanticCatalogVersion = computed(() => semanticCatalog.value?.schemaVersion
  || semanticCatalogItems.value[0]?.schemaVersion
  || 'n/a')

const semanticCatalogStatus = computed(() => {
  if (Array.isArray(semanticCatalog.value)) {
    return semanticCatalog.value.length ? 'ACTIVE' : 'EMPTY'
  }
  return semanticCatalog.value?.status || 'UNKNOWN'
})

const comparisonSummaryText = computed(() => {
  const metricDiff = ragEvalComparison.value?.metricDiff || {}
  const recall = metricDiff.avgRecall
  const correctness = metricDiff.avgAnswerCorrectness
  return [
    recall != null ? `recall ${Number(recall).toFixed(3)}` : '',
    correctness != null ? `correctness ${Number(correctness).toFixed(3)}` : '',
    ragEvalComparison.value?.caseDiffs?.count != null ? `cases ${ragEvalComparison.value.caseDiffs.count}` : ''
  ].filter(Boolean).join(' · ') || 'baseline comparison ready'
})

const stageTraceItems = computed(() => [...stageTraces.value]
  .slice()
  .sort((left, right) => new Date(right.createTime || 0).getTime() - new Date(left.createTime || 0).getTime())
  .map(trace => {
    const metadata = trace.metadata || {}
    const metadataSummary = [
      metadata.action ? `action=${metadata.action}` : '',
      metadata.finalHitCount != null ? `final=${metadata.finalHitCount}` : '',
      metadata.mergedSourceCount != null ? `merged=${metadata.mergedSourceCount}` : '',
      metadata.streamed ? 'stream' : ''
    ].filter(Boolean).join(' · ')
    return {
      ...trace,
      label: stageLabels[trace.stepKey] || trace.stepKey || '未命名阶段',
      latencyText: trace.latencyMs != null ? `${trace.latencyMs} ms` : '进行中',
      statusText: trace.success === false ? '失败' : (trace.success === true ? '完成' : '运行中'),
      statusClass: trace.success === false ? 'trace-status--error' : (trace.success === true ? 'trace-status--ok' : 'trace-status--running'),
      metadataSummary
    }
  }))

const retrievalTraceItems = computed(() => [...retrievalTraces.value]
  .slice()
  .sort((left, right) => new Date(right.createTime || 0).getTime() - new Date(left.createTime || 0).getTime())
  .map(trace => {
    const metadata = trace.metadata || {}
    const shadowRoute = metadata.shadowRoute || null
    const metadataSummary = [
      metadata.action ? `action=${metadata.action}` : '',
      metadata.topK != null ? `topK=${metadata.topK}` : '',
      metadata.mergedSourceCount != null ? `merged=${metadata.mergedSourceCount}` : ''
    ].filter(Boolean).join(' · ')
    return {
      ...trace,
      label: retrievalLabels[trace.stepKey] || trace.stepKey || '检索步骤',
      metadataSummary,
      shadowRoute
    }
  }))

const graphNodeItems = computed(() => {
  const nodes = Array.isArray(runGraph.value?.nodes) ? runGraph.value.nodes : []
  return nodes
    .filter(node => ['route', 'plan', 'skill', 'tool', 'approval', 'final', 'guardrail'].includes(node.id || node.type))
    .map(node => {
      const status = node.status || 'PENDING'
      return {
        ...node,
        status,
        detailText: [
          node.eventCategory || node.type,
          node.outputSummary || node.eventType || '',
          node.totalTokens ? `tokens ${node.totalTokens}` : '',
          node.estimatedCost ? `cost ${node.estimatedCost}` : ''
        ].filter(Boolean).join(' · '),
        statusClass: status === 'FAILED'
          ? 'trace-status--error'
          : (status === 'COMPLETED' ? 'trace-status--ok' : (status === 'INTERRUPTED' ? 'trace-status--running' : 'trace-status--neutral'))
      }
    })
})

const runGraphSummary = computed(() => runGraph.value?.summary || null)

const runGraphRecoveryPlan = computed(() => runGraphSummary.value?.recoveryPlan || null)

const runGraphRecoveryActions = computed(() => Array.isArray(runGraphRecoveryPlan.value?.nextActions)
  ? runGraphRecoveryPlan.value.nextActions.slice(0, 3)
  : [])

const runGraphReplayContract = computed(() => {
  if (!runGraphRecoveryPlan.value) {
    return []
  }
  return [
    runGraphRecoveryPlan.value.checkpointFingerprint ? `fingerprint ${runGraphRecoveryPlan.value.checkpointFingerprint}` : '',
    runGraphRecoveryPlan.value.latestReplayAttemptId ? `attempt ${runGraphRecoveryPlan.value.latestReplayAttemptId}` : '',
    runGraphRecoveryPlan.value.idempotencyPolicy ? `idempotency ${runGraphRecoveryPlan.value.idempotencyPolicy}` : ''
  ].filter(Boolean).slice(0, 3)
})

const runGraphAuditTrail = computed(() => Array.isArray(runGraphSummary.value?.auditTrail)
  ? runGraphSummary.value.auditTrail.slice(0, 4)
  : [])

const runGraphHighRiskNodes = computed(() => Array.isArray(runGraphSummary.value?.highRiskNodes)
  ? runGraphSummary.value.highRiskNodes.slice(0, 4)
  : [])

const sendWithSkill = (skill) => {
  const message = userInput.value.trim() || skill.description || skill.name
  sendMessage(message, { skillHint: skill.skillId })
}

onMounted(async () => {
  if (!ensureAuthenticated()) {
    return
  }
  await loadCustomerStarterPrompts()
  await loadCapabilities()
  await loadConversations()
})
</script>

<style scoped lang="scss">
.workspace {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 18px;
  min-height: calc(100vh - 160px);
}

.history-pane,
.chat-pane,
.meta-card {
  border: 1px solid var(--border-color);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}

.history-pane {
  padding: 20px;
  background:
    linear-gradient(180deg, rgba(17, 28, 52, 0.95), rgba(17, 28, 52, 0.88)),
    linear-gradient(160deg, rgba(255, 90, 54, 0.18), transparent 36%);
  color: var(--text-inverse);
}

.history-pane__top,
.chat-hero,
.chat-layout,
.composer,
.approval-actions {
  display: flex;
}

.history-pane__top {
  flex-direction: column;
  gap: 10px;
}

.history-pane__top h2,
.meta-card h3,
.chat-hero h1 {
  margin: 0;
}

.history-pane__hint {
  margin: 18px 0 20px;
  padding: 14px 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(247, 248, 252, 0.74);
  font-size: 0.94rem;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.history-item {
  width: 100%;
  padding: 14px 16px;
  display: grid;
  gap: 6px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.06);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: 180ms ease;
}

.history-item.active,
.history-item:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.2);
}

.history-item__title {
  font-weight: 700;
}

.history-item__meta {
  font-size: 0.82rem;
  color: rgba(247, 248, 252, 0.68);
}

.eyebrow {
  margin: 0;
  font-size: 0.76rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--text-soft);
}

.history-pane .eyebrow {
  color: rgba(247, 248, 252, 0.56);
}

.chat-pane {
  padding: 22px;
  background: var(--surface-color);
}

.chat-hero {
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 18px;
}

.chat-hero__desc {
  max-width: 720px;
  margin: 10px 0 0;
  color: var(--text-soft);
}

.hero-badges {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.hero-badge {
  padding: 8px 12px;
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary-strong);
  font-size: 0.82rem;
  font-weight: 700;
}

.chat-layout {
  gap: 18px;
}

.chat-main {
  min-width: 0;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 18px;
  flex: 1 1 auto;
}

.messages {
  min-height: 560px;
  max-height: calc(100vh - 320px);
  overflow: auto;
  padding: 12px 4px 4px;
}

.empty-state {
  padding: 42px 28px;
  border: 1px dashed var(--border-color);
  border-radius: var(--radius-xl);
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.6), rgba(255, 255, 255, 0.32));
}

.empty-state h2 {
  margin: 10px 0 12px;
}

.starter-grid {
  display: grid;
  gap: 12px;
  margin-top: 22px;
}

.starter-chip {
  padding: 14px 16px;
  border: 1px solid var(--border-color);
  border-radius: 18px;
  background: var(--surface-strong);
  text-align: left;
  cursor: pointer;
  transition: 180ms ease;
}

.starter-chip:hover {
  border-color: var(--primary-color);
  box-shadow: 0 12px 28px rgba(255, 90, 54, 0.12);
}

.composer {
  align-items: flex-end;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-xl);
  background: var(--surface-strong);
}

.composer textarea {
  width: 100%;
  min-height: 56px;
  max-height: 220px;
  resize: none;
  border: none;
  background: transparent;
  color: var(--text-color);
  outline: none;
}

.send-button,
.ghost-button,
.primary-button {
  border: none;
  cursor: pointer;
}

.send-button,
.primary-button {
  padding: 12px 18px;
  border-radius: 16px;
  background: linear-gradient(135deg, var(--primary-color), var(--primary-strong));
  color: #fff;
  font-weight: 700;
}

.send-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.ghost-button {
  padding: 10px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.08);
  color: inherit;
}

.ghost-button--soft {
  background: rgba(18, 32, 63, 0.08);
  color: var(--text-color);
}

.ghost-button--link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
}

.meta-pane {
  width: 350px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.workspace-group {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-xl);
  background: rgba(255, 255, 255, 0.44);
}

.workspace-group--experimental {
  background: rgba(18, 32, 63, 0.04);
}

.workspace-group__header {
  display: grid;
  gap: 6px;
}

.workspace-group__header h3,
.workspace-group__header p {
  margin: 0;
}

.workspace-group__header p:last-child {
  color: var(--text-soft);
  font-size: 0.9rem;
}

.meta-card {
  padding: 18px;
  background: var(--surface-strong);
}

.meta-stat,
.tool-list li,
.step-list li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.meta-stat + .meta-stat {
  margin-top: 10px;
}

.step-list,
.tool-list,
.source-list,
.trace-list,
.detail-list {
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.step-list li,
.tool-list li {
  padding: 10px 0;
  border-bottom: 1px solid var(--border-color);
}

.source-list li {
  padding: 12px 0;
  border-bottom: 1px solid var(--border-color);
}

.source-list p {
  margin: 6px 0 0;
  color: var(--text-soft);
  font-size: 0.92rem;
}

.approval-summary,
.empty-text {
  color: var(--text-soft);
}

.memory-block + .memory-block {
  margin-top: 16px;
}

.memory-block p {
  margin: 6px 0 0;
  color: var(--text-soft);
}

.memory-label {
  display: block;
  font-size: 0.78rem;
  font-weight: 700;
  color: var(--text-color);
}

.detail-list {
  display: grid;
  gap: 8px;
}

.detail-list li {
  color: var(--text-soft);
}

.pill-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.detail-pill {
  padding: 6px 10px;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  background: rgba(255, 90, 54, 0.08);
  color: var(--text-color);
  font-size: 0.8rem;
}

.meta-footnote {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
  font-size: 0.78rem;
  color: var(--text-soft);
}

.trace-list {
  display: grid;
  gap: 12px;
}

.trace-item {
  padding: 14px 0 0;
  border-top: 1px solid var(--border-color);
}

.trace-item:first-child {
  padding-top: 0;
  border-top: none;
}

.trace-header,
.trace-meta,
.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ghost-button--tiny {
  min-height: 32px;
  padding: 6px 10px;
  font-size: 0.78rem;
}

.checkpoint-banner {
  display: grid;
  gap: 4px;
  margin: 12px 0;
  padding: 10px 12px;
  border: 1px solid rgba(47, 128, 237, 0.18);
  border-radius: 8px;
  background: rgba(47, 128, 237, 0.08);
}

.checkpoint-banner span {
  overflow-wrap: anywhere;
  color: var(--text-soft);
  font-size: 0.78rem;
}

.graph-list {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.graph-node {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-height: 44px;
}

.graph-node__dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--border-color);
}

.graph-node__dot.trace-status--ok {
  background: #12b76a;
}

.graph-node__dot.trace-status--running {
  background: #2f80ed;
}

.graph-node__dot.trace-status--error {
  background: #c93434;
}

.graph-node strong,
.graph-node p {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.graph-node p {
  margin: 4px 0 0;
  color: var(--text-soft);
  font-size: 0.82rem;
}

.trace-meta {
  margin-top: 8px;
  font-size: 0.82rem;
  color: var(--text-soft);
}

.trace-query,
.trace-note {
  margin: 8px 0 0;
  color: var(--text-soft);
  font-size: 0.9rem;
  line-height: 1.5;
}

.trace-note--error {
  color: #b42318;
}

.trace-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 0.76rem;
  font-weight: 700;
}

.trace-status--ok {
  background: rgba(18, 183, 106, 0.12);
  color: #027a48;
}

.trace-status--running {
  background: rgba(47, 128, 237, 0.12);
  color: #175cd3;
}

.trace-status--error {
  background: rgba(201, 52, 52, 0.12);
  color: #b42318;
}

.trace-status--neutral {
  background: rgba(18, 32, 63, 0.08);
  color: var(--text-soft);
}

.approval-actions {
  gap: 10px;
  margin-top: 16px;
}

.clarification-list {
  display: grid;
  gap: 8px;
}

.skill-list {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}

.skill-chip {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 10px 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-color);
  color: var(--text-color);
  cursor: pointer;
  text-align: left;
}

.skill-chip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-chip strong {
  font-size: 0.72rem;
  color: var(--text-soft);
}

.skill-chip.active,
.skill-chip:hover {
  border-color: var(--primary-color);
}

.quality-status {
  display: grid;
  gap: 6px;
  margin-top: 10px;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.quality-status span {
  color: var(--text-soft);
  font-size: 0.8rem;
}

.quality-status--pass {
  background: rgba(18, 183, 106, 0.08);
  border-color: rgba(18, 183, 106, 0.2);
}

.quality-status--warn {
  background: rgba(247, 144, 9, 0.08);
  border-color: rgba(247, 144, 9, 0.22);
}

.quality-status--fail {
  background: rgba(201, 52, 52, 0.08);
  border-color: rgba(201, 52, 52, 0.22);
}

.gate-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.gate-list li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 0.86rem;
}

.gate-list p {
  margin: 4px 0 0;
  color: var(--text-soft);
  font-size: 0.78rem;
}

.gate-status--pass {
  color: #027a48;
}

.gate-status--warn {
  color: #b54708;
}

.gate-status--fail {
  color: #b42318;
}

.readiness-panel {
  display: grid;
  gap: 4px;
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(18, 183, 106, 0.18);
  border-radius: 8px;
  background: rgba(18, 183, 106, 0.08);
}

.readiness-panel p {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.8rem;
}

.tool-boundary-list {
  display: grid;
  gap: 8px;
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
}

.tool-boundary-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid var(--border-color);
}

.tool-boundary-list li:first-child {
  border-top: none;
}

.tool-boundary-list strong,
.tool-boundary-list p {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-boundary-list p {
  margin: 4px 0 0;
  color: var(--text-soft);
  font-size: 0.78rem;
}

.eval-summary {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.baseline-compare {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  margin-top: 12px;
}

.baseline-compare input {
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-color);
  color: var(--text-color);
}

.comparison-summary {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid rgba(18, 183, 106, 0.18);
  border-radius: 8px;
  background: rgba(18, 183, 106, 0.08);
}

.comparison-summary p {
  margin: 4px 0 0;
  color: var(--text-soft);
  font-size: 0.82rem;
}

.admin-action-list {
  display: grid;
  gap: 10px;
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
}

.admin-action-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--border-color);
}

.admin-action-list li:first-child {
  border-top: none;
}

.admin-action-list strong,
.admin-action-list p {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-action-list p,
.evidence-result p {
  margin: 4px 0 0;
  color: var(--text-soft);
  font-size: 0.82rem;
}

.bad-case-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  max-width: 190px;
}

.ops-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  max-width: 150px;
}

.evidence-result {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid rgba(47, 128, 237, 0.18);
  border-radius: 8px;
  background: rgba(47, 128, 237, 0.08);
}

.detail-section {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid rgba(47, 128, 237, 0.16);
}

.error-card {
  border-color: rgba(201, 52, 52, 0.24);
  background: rgba(201, 52, 52, 0.06);
}

@media (max-width: 1180px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .chat-layout {
    flex-direction: column;
  }

  .meta-pane {
    width: 100%;
  }
}

@media (max-width: 780px) {
  .chat-pane {
    padding: 16px;
  }

  .chat-hero {
    flex-direction: column;
  }
}
</style>

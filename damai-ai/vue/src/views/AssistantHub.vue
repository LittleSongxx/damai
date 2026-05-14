<template>
  <div class="workspace workspace--assistant-hub">
    <aside class="history-pane">
      <div class="history-pane__top">
        <p class="eyebrow">Unified Runtime</p>
        <h2>统一助手</h2>
        <button class="ghost-button" @click="startNewChat">新建会话</button>
        <RouterLink v-if="capabilities?.admin" class="ghost-button ghost-button--link" to="/assistant/skills">Skill 管理</RouterLink>
      </div>

      <div class="history-pane__hint">
        一个入口承接购票、规则、通用问答和运维问题，后台按技能路由并保留完整运行轨迹。
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
          <p class="eyebrow">Single Entry</p>
          <h1>大麦统一助手</h1>
          <p class="chat-hero__desc">
            统一处理购票、规则问答、通用联网搜索和运维诊断，检索、工具和审批都通过同一条运行时链路输出。
          </p>
        </div>
        <div class="hero-badges">
          <span class="hero-badge">Business</span>
          <span class="hero-badge">Knowledge</span>
          <span class="hero-badge">General</span>
          <span v-if="canUseOps" class="hero-badge">Ops</span>
        </div>
      </header>

      <div class="chat-layout">
        <div class="chat-main">
          <div class="messages" ref="messagesRef">
            <div v-if="!hasMessages" class="empty-state">
              <p class="eyebrow">Starter</p>
              <h2>直接说你的目标</h2>
              <p>我会先路由到业务、规则、通用或运维技能，再按统一事件流返回答案、证据和动作。</p>
              <div class="starter-grid">
                <button
                  v-for="prompt in starterPrompts"
                  :key="prompt"
                  class="starter-chip"
                  @click="sendMessage(prompt)"
                >
                  {{ prompt }}
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
              placeholder="例如：帮我找本周上海的脱口秀；退票规则是什么；介绍一下这个歌手；查一下 gateway 最近 15 分钟 CPU 和错误日志。"
              @input="adjustTextareaHeight"
              @keydown.enter.prevent="sendMessage()"
            />
            <button class="send-button" :disabled="isStreaming || !userInput.trim()" @click="sendMessage()">
              {{ isStreaming ? '运行中' : '发送' }}
            </button>
          </div>
        </div>

        <aside class="meta-pane">
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

          <section v-if="capabilities?.skills?.length" class="meta-card">
            <p class="eyebrow">Skills</p>
            <h3>直选 Skill</h3>
            <div class="skill-list">
              <button
                v-for="skill in capabilities?.skills"
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

const allStarterPrompts = [
  '帮我找这周北京的演唱会并推荐两场',
  '节目开演前一天还能退票吗',
  '介绍一下我刚看到的这个歌手',
  '帮我查一下某位歌手的代表作和近期动态',
  '查一下 gateway 最近 15 分钟 CPU 和错误日志',
  '我想买上海周末两张 300 左右的脱口秀票'
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
  conversations,
  orderedTimeline,
  evidenceCards,
  stageTraces,
  retrievalTraces,
  memorySummary,
  pendingAction,
  toolCalls,
  clarificationOptions,
  errorMessage,
  refusalReason,
  runStatus,
  hasMessages,
  capabilities,
  canUseOps,
  adjustTextareaHeight,
  loadCapabilities,
  loadConversations,
  loadConversation,
  startNewChat,
  sendMessage,
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

const starterPrompts = computed(() => allStarterPrompts.filter(prompt => canUseOps.value || !/gateway|cpu|错误日志|trace|监控|运维/i.test(prompt)))

const structuredMemory = computed(() => memorySummary.value?.structuredMemory || {
  summary: '',
  conversationGoal: '',
  stableFacts: [],
  pendingQuestions: [],
  retrievalHints: []
})

const hasMemorySummary = computed(() => memorySummary.value?.hasContent === true)

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

const sendWithSkill = (skill) => {
  const message = userInput.value.trim() || skill.description || skill.name
  sendMessage(message, { skillHint: skill.skillId })
}

onMounted(async () => {
  if (!ensureAuthenticated()) {
    return
  }
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
.trace-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
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

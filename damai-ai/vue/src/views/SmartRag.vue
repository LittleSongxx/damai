<template>
  <div class="workspace workspace--rag">
    <aside class="history-pane">
      <div class="history-pane__top">
        <p class="eyebrow">Policy Retrieval</p>
        <h2>规则会话</h2>
        <button class="ghost-button" @click="startNewChat">新建问答</button>
      </div>

      <div class="history-pane__hint">
        这里会保留每一次规则问答的检索链路、引用片段和改写后的查询结果。
      </div>

      <div class="history-list">
        <div v-for="chat in chatHistory" :key="chat.id" class="history-row">
          <button
            class="history-item"
            :class="{ active: currentChatId === chat.id }"
            @click="loadChat(chat.id)"
          >
            <span class="history-item__title">{{ chat.title || '新的规则问答' }}</span>
            <span class="history-item__meta">{{ chat.workflowStatus || '未开始' }}</span>
          </button>
          <button class="delete-button" title="删除对话" @click="deleteCurrentChat(chat.id)">删</button>
        </div>
      </div>
    </aside>

    <section class="chat-pane">
      <header class="chat-hero">
        <div>
          <p class="eyebrow">Grounded Answers</p>
          <h1>规则助手</h1>
          <p class="chat-hero__desc">先做查询改写和混合检索，再带上引用来源回答，不直接无依据输出规则结论。</p>
        </div>
        <div class="hero-actions">
          <span class="hero-badge">Dense + BM25</span>
          <span class="hero-badge">RRF</span>
          <button class="ghost-button ghost-button--light" @click="refreshRagIndex">重建索引</button>
        </div>
      </header>

      <div class="chat-layout">
        <div class="chat-main">
          <div class="messages" ref="messagesRef">
            <div v-if="!currentMessages.length" class="empty-state">
              <p class="eyebrow">推荐提问</p>
              <h2>问退票、实名制、转赠或入场规则</h2>
              <p>回答会优先引用命中的 FAQ 片段，你可以直接看到这轮检索是否足够扎实。</p>
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
              v-for="(message, index) in currentMessages"
              :key="`${message.role}-${index}`"
              :message="message"
            />
          </div>

          <div class="composer">
            <textarea
              ref="inputRef"
              v-model="userInput"
              rows="1"
              placeholder="例如：退票规则是什么？实名票可以转赠吗？"
              @input="adjustTextareaHeight"
              @keydown.enter.prevent="sendMessage()"
            />
            <button class="send-button" :disabled="isStreaming || !userInput.trim()" @click="sendMessage()">
              {{ isStreaming ? '检索中' : '发送' }}
            </button>
          </div>
        </div>

        <aside class="meta-pane">
          <section class="meta-card">
            <p class="eyebrow">Retrieval Chain</p>
            <h3>检索步骤</h3>
            <p v-if="!workflowSteps.length" class="empty-text">还没有检索流程记录，先发起一次规则问答。</p>
            <ol v-else class="step-list">
              <li v-for="step in workflowSteps" :key="step.id">
                <span>{{ step.stepKey }}</span>
                <strong>{{ step.stepStatus }}</strong>
              </li>
            </ol>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Trace Meta</p>
            <h3>查询元数据</h3>
            <div class="meta-stat">
              <span>Trace ID</span>
              <strong>{{ retrievalMeta?.traceId || '-' }}</strong>
            </div>
            <div class="meta-stat meta-stat--wrap">
              <span>改写 Query</span>
              <strong>{{ retrievalMeta?.rewrittenQuery || '-' }}</strong>
            </div>
            <div class="meta-stat">
              <span>Run ID</span>
              <strong>{{ currentRunId || '-' }}</strong>
            </div>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Citations</p>
            <h3>引用来源</h3>
            <p v-if="!retrievalSources.length" class="empty-text">本轮回答还没有引用片段。</p>
            <ul v-else class="source-list">
              <li v-for="source in retrievalSources" :key="source.chunkId" class="source-item">
                <div class="source-head">
                  <strong>{{ source.title || source.source || '未命名来源' }}</strong>
                  <span>{{ source.section || '未分段' }}</span>
                </div>
                <p class="source-meta">
                  {{ source.source || '-' }}
                  <span v-if="source.score !== undefined && source.score !== null"> · {{ formatScore(source.score) }}</span>
                </p>
                <p class="source-content">{{ source.snippet || '没有可展示的片段内容。' }}</p>
              </li>
            </ul>
          </section>

          <section v-if="errorMessage" class="meta-card error-card">
            <p class="eyebrow">Problem</p>
            <h3>错误信息</h3>
            <p>{{ errorMessage }}</p>
          </section>
        </aside>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import Chat from '../components/Chat.vue'
import { chatAPI, ensureAuthenticated } from '../api/api'
import { useAiChat } from '../composables/useAiChat'

const starterPrompts = [
  '退票规则是什么？',
  '实名票可以转赠给别人吗？',
  '电子票和纸质票的取票规则有什么区别？',
  '入场时需要带哪些证件？'
]

const {
  messagesRef,
  inputRef,
  userInput,
  isStreaming,
  currentChatId,
  currentMessages,
  chatHistory,
  workflowSteps,
  retrievalSources,
  retrievalMeta,
  currentRunId,
  errorMessage,
  adjustTextareaHeight,
  loadChatHistory,
  loadChat,
  startNewChat,
  deleteChat,
  sendMessage
} = useAiChat({
  chatType: 3,
  sendMessageApi: chatAPI.sendRagMessage,
  newChatTitle: '新的规则问答'
})

const deleteCurrentChat = async (chatId) => {
  if (!window.confirm('确定删除这条规则问答记录吗？')) {
    return
  }
  await deleteChat(chatId)
}

const refreshRagIndex = async () => {
  await chatAPI.reindexFaq()
  window.alert('知识库索引重建请求已提交。')
}

const formatScore = (score) => Number(score).toFixed(3)

onMounted(async () => {
  if (!ensureAuthenticated()) {
    return
  }
  await loadChatHistory()
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
    linear-gradient(180deg, rgba(19, 44, 67, 0.96), rgba(22, 49, 70, 0.9)),
    linear-gradient(155deg, rgba(50, 179, 147, 0.18), transparent 38%);
  color: var(--text-inverse);
}

.history-pane__top,
.history-list,
.meta-pane {
  display: flex;
  flex-direction: column;
}

.history-pane__top {
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
  color: rgba(247, 248, 252, 0.76);
  font-size: 0.94rem;
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

.history-list {
  gap: 10px;
}

.history-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 36px;
  gap: 8px;
}

.history-item {
  width: 100%;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.04);
  color: inherit;
  text-align: left;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  cursor: pointer;
  transition: 180ms ease;
}

.history-item:hover,
.history-item.active {
  transform: translateY(-1px);
  border-color: rgba(255, 255, 255, 0.2);
  background: rgba(255, 255, 255, 0.1);
}

.history-item__title {
  font-weight: 700;
}

.history-item__meta {
  font-size: 0.82rem;
  color: rgba(247, 248, 252, 0.68);
}

.chat-pane {
  padding: 20px;
  background: var(--surface-color);
  backdrop-filter: blur(18px);
}

.chat-hero {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: 22px 24px;
  margin-bottom: 18px;
  border: 1px solid rgba(50, 179, 147, 0.18);
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgba(50, 179, 147, 0.18), rgba(14, 121, 178, 0.08)),
    var(--surface-strong);
}

.chat-hero__desc {
  margin: 8px 0 0;
  max-width: 700px;
  color: var(--text-soft);
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  justify-content: flex-end;
  gap: 8px;
}

.hero-badge {
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.76);
  color: #0e7ab2;
  font-size: 0.86rem;
  font-weight: 700;
  border: 1px solid rgba(18, 32, 63, 0.08);
}

.chat-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 18px;
}

.chat-main,
.meta-pane {
  display: flex;
  flex-direction: column;
}

.messages {
  min-height: 600px;
  max-height: calc(100vh - 380px);
  overflow-y: auto;
  padding: 18px;
  border: 1px solid var(--border-color);
  border-radius: 24px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.84), rgba(255, 255, 255, 0.95)),
    radial-gradient(circle at top right, rgba(50, 179, 147, 0.08), transparent 32%);
}

.empty-state {
  max-width: 760px;
  padding: 14px 4px;
}

.empty-state h2 {
  margin: 6px 0 8px;
  font-size: clamp(1.8rem, 2vw, 2.4rem);
}

.empty-state p:not(.eyebrow) {
  margin: 0;
  color: var(--text-soft);
}

.starter-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 20px;
}

.starter-chip {
  padding: 16px 18px;
  text-align: left;
  border: 1px solid rgba(18, 32, 63, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.84);
  color: var(--text-color);
  cursor: pointer;
  transition: 180ms ease;
}

.starter-chip:hover {
  transform: translateY(-1px);
  border-color: rgba(50, 179, 147, 0.24);
  box-shadow: 0 14px 30px rgba(17, 28, 52, 0.08);
}

.composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: flex-end;
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--border-color);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.82);
}

.composer textarea {
  min-height: 62px;
  max-height: 180px;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-color);
}

.send-button,
.ghost-button,
.delete-button {
  border: none;
  cursor: pointer;
  transition: 180ms ease;
}

.send-button {
  padding: 14px 20px;
  border-radius: 16px;
  background: linear-gradient(135deg, #13b28e, #0f8ed1);
  color: #fff;
  font-weight: 800;
  box-shadow: 0 18px 30px rgba(15, 142, 209, 0.2);
}

.send-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
}

.ghost-button {
  padding: 11px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.74);
  color: var(--text-color);
  border: 1px solid var(--border-color);
}

.ghost-button--light {
  background: rgba(255, 255, 255, 0.9);
}

.ghost-button:hover,
.delete-button:hover {
  transform: translateY(-1px);
}

.delete-button {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(247, 248, 252, 0.82);
}

.meta-pane {
  gap: 14px;
}

.meta-card {
  padding: 18px;
  background: var(--surface-strong);
}

.meta-card .eyebrow {
  margin-bottom: 6px;
}

.empty-text,
.meta-card p,
.source-meta {
  color: var(--text-soft);
}

.step-list,
.source-list {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.step-list li {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--border-color);
}

.step-list li:last-child {
  border-bottom: none;
}

.meta-stat {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--border-color);
}

.meta-stat:last-child {
  border-bottom: none;
}

.meta-stat strong {
  text-align: right;
}

.meta-stat--wrap {
  align-items: flex-start;
}

.meta-stat--wrap strong {
  max-width: 180px;
  white-space: pre-wrap;
  word-break: break-word;
}

.source-item + .source-item {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--border-color);
}

.source-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.source-head span {
  color: var(--text-soft);
  font-size: 0.82rem;
}

.source-content {
  margin: 8px 0 0;
  color: var(--text-color);
  line-height: 1.65;
  white-space: pre-wrap;
}

.error-card {
  border-color: rgba(220, 73, 64, 0.24);
  background: linear-gradient(180deg, rgba(255, 244, 243, 0.92), rgba(255, 255, 255, 0.96));
}

@media (max-width: 1180px) {
  .workspace,
  .chat-layout {
    grid-template-columns: 1fr;
  }

  .history-pane {
    order: 2;
  }

  .messages {
    max-height: none;
  }
}

@media (max-width: 720px) {
  .chat-hero,
  .hero-actions,
  .starter-grid,
  .composer {
    grid-template-columns: 1fr;
  }

  .chat-hero,
  .hero-actions {
    display: grid;
  }

  .history-pane,
  .chat-pane {
    padding: 16px;
  }
}
</style>

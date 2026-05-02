<template>
  <div class="workspace workspace--analysis">
    <aside class="history-pane">
      <div class="history-pane__top">
        <p class="eyebrow">Ops Copilot</p>
        <h2>运维会话</h2>
        <button class="ghost-button" @click="startNewChat">新建分析</button>
      </div>

      <div class="history-pane__hint">
        这里会保留每一轮日志、指标和排障总结，便于回看每次运维判断链路。
      </div>

      <div class="history-list">
        <div v-for="chat in chatHistory" :key="chat.id" class="history-row">
          <button
            class="history-item"
            :class="{ active: currentChatId === chat.id }"
            @click="loadChat(chat.id)"
          >
            <span class="history-item__title">{{ chat.title || '新的运维分析' }}</span>
            <span class="history-item__meta">{{ chat.workflowStatus || '未开始' }}</span>
          </button>
          <button class="delete-button" title="删除对话" @click="deleteCurrentChat(chat.id)">删</button>
        </div>
      </div>
    </aside>

    <section class="chat-pane">
      <header class="chat-hero">
        <div>
          <p class="eyebrow">MCP Diagnostics</p>
          <h1>运维助手</h1>
          <p class="chat-hero__desc">适合按现象、服务名、时间范围或 traceId 追查，日志和指标会按步骤串起来再给结论。</p>
        </div>
        <div class="hero-badges">
          <span class="hero-badge">日志</span>
          <span class="hero-badge">指标</span>
          <span class="hero-badge">诊断建议</span>
        </div>
      </header>

      <div class="chat-layout">
        <div class="chat-main">
          <div class="messages" ref="messagesRef">
            <div v-if="!currentMessages.length" class="empty-state">
              <p class="eyebrow">建议输入</p>
              <h2>先描述异常，再补服务名或 traceId</h2>
              <p>我会优先拉取证据，再做推断，而不是直接输出结论。</p>
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
              placeholder="例如：订单服务 12:00 之后超时明显增多，帮我看下可能原因。"
              @input="adjustTextareaHeight"
              @keydown.enter.prevent="sendMessage()"
            />
            <button class="send-button" :disabled="isStreaming || !userInput.trim()" @click="sendMessage()">
              {{ isStreaming ? '分析中' : '发送' }}
            </button>
          </div>
        </div>

        <aside class="meta-pane">
          <section class="meta-card">
            <p class="eyebrow">Workflow</p>
            <h3>分析步骤</h3>
            <p v-if="!workflowSteps.length" class="empty-text">还没有分析步骤，先发起一轮排障提问。</p>
            <ol v-else class="step-list">
              <li v-for="step in workflowSteps" :key="step.id">
                <span>{{ step.stepKey }}</span>
                <strong>{{ step.stepStatus }}</strong>
              </li>
            </ol>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Run State</p>
            <h3>会话状态</h3>
            <div class="meta-stat">
              <span>当前 Chat ID</span>
              <strong>{{ currentChatId || '-' }}</strong>
            </div>
            <div class="meta-stat">
              <span>当前 Run ID</span>
              <strong>{{ currentRunId || '-' }}</strong>
            </div>
            <div class="meta-stat">
              <span>步骤数</span>
              <strong>{{ workflowSteps.length }}</strong>
            </div>
          </section>

          <section class="meta-card">
            <p class="eyebrow">How To Ask</p>
            <h3>输入建议</h3>
            <ul class="tips-list">
              <li>带上服务名，比如 `order-service`、`gateway-service`。</li>
              <li>尽量补时间范围或错误关键字，减少无关日志噪声。</li>
              <li>如果有 traceId，直接给 traceId，定位会更快。</li>
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
  'gateway-service 最近 10 分钟的错误日志有哪些？',
  'order-service 从 12:00 开始 RT 飙高，帮我判断可能原因。',
  '给我查 traceId=trace-demo-001 的日志链路。',
  '帮我看一下当前 JVM 堆内存和 GC 是否异常。'
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
  currentRunId,
  errorMessage,
  adjustTextareaHeight,
  loadChatHistory,
  loadChat,
  startNewChat,
  deleteChat,
  sendMessage
} = useAiChat({
  chatType: 4,
  sendMessageApi: chatAPI.sendAnalysisMessage,
  newChatTitle: '新的运维分析'
})

const deleteCurrentChat = async (chatId) => {
  if (!window.confirm('确定删除这条运维分析记录吗？')) {
    return
  }
  await deleteChat(chatId)
}

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
    linear-gradient(180deg, rgba(32, 27, 58, 0.96), rgba(26, 29, 68, 0.9)),
    linear-gradient(155deg, rgba(104, 93, 224, 0.2), transparent 36%);
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
  border: 1px solid rgba(104, 93, 224, 0.18);
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgba(104, 93, 224, 0.18), rgba(47, 167, 212, 0.08)),
    var(--surface-strong);
}

.chat-hero__desc {
  margin: 8px 0 0;
  max-width: 700px;
  color: var(--text-soft);
}

.hero-badges {
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
  color: #5f53d8;
  font-size: 0.86rem;
  font-weight: 700;
  border: 1px solid rgba(18, 32, 63, 0.08);
}

.chat-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
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
    radial-gradient(circle at top right, rgba(104, 93, 224, 0.08), transparent 32%);
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
  border-color: rgba(104, 93, 224, 0.24);
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
  background: linear-gradient(135deg, #655ae0, #2f8fdb);
  color: #fff;
  font-weight: 800;
  box-shadow: 0 18px 30px rgba(101, 90, 224, 0.2);
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
.meta-card p {
  color: var(--text-soft);
}

.step-list,
.tips-list {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.step-list li,
.tips-list li {
  padding: 10px 0;
  border-bottom: 1px dashed var(--border-color);
}

.step-list li {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}

.step-list li:last-child,
.tips-list li:last-child {
  border-bottom: none;
}

.tips-list li {
  color: var(--text-soft);
  line-height: 1.6;
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
  .hero-badges,
  .starter-grid,
  .composer {
    grid-template-columns: 1fr;
  }

  .chat-hero,
  .hero-badges {
    display: grid;
  }

  .history-pane,
  .chat-pane {
    padding: 16px;
  }
}
</style>

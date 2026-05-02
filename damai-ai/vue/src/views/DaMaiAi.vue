<template>
  <div class="workspace workspace--assistant">
    <aside class="history-pane">
      <div class="history-pane__top">
        <p class="eyebrow">Assistant Flow</p>
        <h2>购票会话</h2>
        <button class="ghost-button" @click="startNewChat">新建咨询</button>
      </div>

      <div class="history-pane__hint">
        从节目检索、票档确认到审批下单，这里会保留每一轮购票链路。
      </div>

      <div class="history-list">
        <div v-for="chat in chatHistory" :key="chat.id" class="history-row">
          <button
            class="history-item"
            :class="{ active: currentChatId === chat.id }"
            @click="loadChat(chat.id)"
          >
            <span class="history-item__title">{{ chat.title || '新的购票咨询' }}</span>
            <span class="history-item__meta">{{ chat.workflowStatus || '未开始' }}</span>
          </button>
          <button class="delete-button" title="删除对话" @click="deleteCurrentChat(chat.id)">删</button>
        </div>
      </div>
    </aside>

    <section class="chat-pane">
      <header class="chat-hero">
        <div>
          <p class="eyebrow">Ticket Concierge</p>
          <h1>麦小蜜</h1>
          <p class="chat-hero__desc">先帮你缩小节目范围，再进入票档、购票人和审批下单流程。</p>
        </div>
        <div class="hero-badges">
          <span class="hero-badge">推荐</span>
          <span class="hero-badge">详情</span>
          <span class="hero-badge">审批下单</span>
        </div>
      </header>

      <div class="chat-layout">
        <div class="chat-main">
          <div class="messages" ref="messagesRef">
            <div v-if="!currentMessages.length" class="empty-state">
              <p class="eyebrow">怎么开始</p>
              <h2>告诉我城市、艺人、时间或预算</h2>
              <p>我会优先用节目检索和票档工具回答，不直接编造节目数据。</p>
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
              placeholder="例如：这周北京有哪些演唱会？帮我看于文文北京场的票档。"
              @input="adjustTextareaHeight"
              @keydown.enter.prevent="sendMessage()"
            />
            <button class="send-button" :disabled="isStreaming || !userInput.trim()" @click="sendMessage()">
              {{ isStreaming ? '发送中' : '发送' }}
            </button>
          </div>
        </div>

        <aside class="meta-pane">
          <section class="meta-card">
            <p class="eyebrow">Workflow</p>
            <h3>当前进度</h3>
            <p v-if="!workflowSteps.length" class="empty-text">还没有步骤记录，先发起一轮购票咨询。</p>
            <ol v-else class="step-list">
              <li v-for="step in workflowSteps" :key="step.id">
                <span>{{ step.stepKey }}</span>
                <strong>{{ step.stepStatus }}</strong>
              </li>
            </ol>
          </section>

          <section v-if="pendingApproval" class="meta-card approval-card">
            <p class="eyebrow">Approval Gate</p>
            <h3>待确认订单</h3>
            <p class="approval-summary">{{ approvalSummary }}</p>
            <div class="approval-actions">
              <button class="primary-button" :disabled="approvalBusy" @click="approvePending">确认下单</button>
              <button class="ghost-button ghost-button--soft" :disabled="approvalBusy" @click="rejectPending">取消</button>
            </div>
          </section>

          <section class="meta-card">
            <p class="eyebrow">Run State</p>
            <h3>会话状态</h3>
            <div class="meta-stat">
              <span>当前 Chat ID</span>
              <strong>{{ currentChatId || '-' }}</strong>
            </div>
            <div class="meta-stat">
              <span>最近 Run ID</span>
              <strong>{{ currentRunId || '-' }}</strong>
            </div>
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
import { computed, onMounted } from 'vue'
import Chat from '../components/Chat.vue'
import { chatAPI, ensureAuthenticated } from '../api/api'
import { useAiChat } from '../composables/useAiChat'

const starterPrompts = [
  '最近北京有哪些演唱会？',
  '帮我查于文文北京场的票档',
  '上海本周适合情侣看的演出推荐',
  '我想买 2 张 300 左右的北京演唱会门票'
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
  pendingApproval,
  currentRunId,
  approvalBusy,
  errorMessage,
  adjustTextareaHeight,
  loadChatHistory,
  loadChat,
  startNewChat,
  deleteChat,
  sendMessage,
  approvePending,
  rejectPending
} = useAiChat({
  chatType: 2,
  sendMessageApi: chatAPI.sendAssistantMessage,
  newChatTitle: '新的购票咨询'
})

const deleteCurrentChat = async (chatId) => {
  if (!window.confirm('确定删除这条购票咨询记录吗？')) {
    return
  }
  await deleteChat(chatId)
}

const approvalSummary = computed(() => {
  if (!pendingApproval.value?.previewJson) {
    return '订单预览已生成，请确认是否正式下单。'
  }
  try {
    const preview = JSON.parse(pendingApproval.value.previewJson)
    const names = Array.isArray(preview.ticketUsers) ? preview.ticketUsers.join('、') : ''
    return `节目《${preview.programTitle || ''}》 ${preview.ticketCategoryPrice || ''} 元 x ${preview.ticketCount || ''}，购票人：${names || '未识别'}。`
  } catch (error) {
    return '订单预览已生成，请确认是否正式下单。'
  }
})

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
    linear-gradient(180deg, rgba(17, 28, 52, 0.95), rgba(17, 28, 52, 0.88)),
    linear-gradient(160deg, rgba(255, 90, 54, 0.18), transparent 36%);
  color: var(--text-inverse);
}

.history-pane__top h2,
.meta-card h3,
.chat-hero h1 {
  margin: 0;
}

.history-pane__top {
  display: grid;
  gap: 10px;
}

.history-pane__hint {
  margin: 18px 0 20px;
  padding: 14px 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(247, 248, 252, 0.74);
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
  display: flex;
  flex-direction: column;
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
  border: 1px solid rgba(255, 90, 54, 0.16);
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgba(255, 90, 54, 0.15), rgba(37, 80, 200, 0.08)),
    var(--surface-strong);
}

.chat-hero__desc {
  margin: 8px 0 0;
  max-width: 680px;
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
  background: rgba(255, 255, 255, 0.72);
  color: var(--accent-color);
  font-size: 0.86rem;
  font-weight: 700;
  border: 1px solid rgba(18, 32, 63, 0.08);
}

.chat-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 18px;
}

.chat-main {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.messages {
  min-height: 600px;
  max-height: calc(100vh - 380px);
  overflow-y: auto;
  padding: 18px;
  border: 1px solid var(--border-color);
  border-radius: 24px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.94)),
    repeating-linear-gradient(
      180deg,
      rgba(18, 32, 63, 0.018) 0,
      rgba(18, 32, 63, 0.018) 1px,
      transparent 1px,
      transparent 26px
    );
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
  background: rgba(255, 255, 255, 0.82);
  color: var(--text-color);
  cursor: pointer;
  transition: 180ms ease;
}

.starter-chip:hover {
  transform: translateY(-1px);
  border-color: rgba(255, 90, 54, 0.2);
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
.primary-button,
.ghost-button,
.delete-button {
  border: none;
  cursor: pointer;
  transition: 180ms ease;
}

.send-button,
.primary-button {
  padding: 14px 20px;
  border-radius: 16px;
  background: linear-gradient(135deg, var(--primary-color), var(--primary-strong));
  color: #fff;
  font-weight: 800;
  box-shadow: 0 18px 30px rgba(255, 90, 54, 0.22);
}

.send-button:disabled,
.primary-button:disabled {
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

.ghost-button--soft {
  background: transparent;
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
  display: flex;
  flex-direction: column;
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
.approval-summary,
.meta-card p {
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

.step-list strong,
.meta-stat strong {
  color: var(--accent-color);
}

.approval-card {
  border-color: rgba(255, 90, 54, 0.18);
  background:
    linear-gradient(180deg, rgba(255, 90, 54, 0.08), rgba(255, 255, 255, 0.96)),
    var(--surface-strong);
}

.approval-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.meta-stat {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--border-color);
}

.meta-stat:last-child {
  border-bottom: none;
}

.error-card {
  border-color: rgba(214, 48, 49, 0.18);
}

@media (max-width: 1200px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .chat-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .chat-pane,
  .history-pane {
    padding: 14px;
  }

  .chat-hero {
    flex-direction: column;
    padding: 18px;
  }

  .starter-grid {
    grid-template-columns: 1fr;
  }

  .composer {
    grid-template-columns: 1fr;
  }
}
</style>

<template>
  <div class="ai-customer-widget" :class="{ 'is-open': open }">
    <button class="ai-customer-widget__launcher" type="button" @click="toggle">
      <span>AI</span>
    </button>

    <section v-if="open" class="ai-customer-widget__panel">
      <header class="ai-customer-widget__header">
        <div>
          <strong>麦小蜜</strong>
          <span>票务智能客服</span>
        </div>
        <button type="button" @click="toggle">×</button>
      </header>

      <div class="ai-customer-widget__context" v-if="contextLabel">
        {{ contextLabel }}
      </div>

      <main ref="scrollRef" class="ai-customer-widget__messages">
        <div
          v-for="message in messages"
          :key="message.id"
          class="ai-customer-widget__message"
          :class="`ai-customer-widget__message--${message.role}`"
        >
          <p>{{ message.content }}</p>
        </div>

        <div v-if="evidenceCards.length" class="ai-customer-widget__evidence">
          <span>来源</span>
          <button v-for="source in evidenceCards" :key="source.title || source.source || source.faqId" type="button">
            {{ source.title || source.source || source.faqId }}
          </button>
        </div>

        <div v-if="pendingAction" class="ai-customer-widget__action">
          <strong>下单确认</strong>
          <p>{{ pendingAction.previewSummary || '请确认是否按当前信息创建订单。' }}</p>
          <div>
            <button type="button" :disabled="busy" @click="resolvePurchase('approve')">确认下单</button>
            <button type="button" :disabled="busy" @click="resolvePurchase('reject')">取消</button>
          </div>
        </div>

        <div v-if="workItem" class="ai-customer-widget__work-item">
          <span>工单 {{ workItem.workItemId }}</span>
          <strong>{{ workItem.workStatus || 'OPEN' }}</strong>
        </div>
      </main>

      <div class="ai-customer-widget__prompts" v-if="starterPrompts.length">
        <button
          v-for="prompt in starterPrompts"
          :key="prompt.questionId"
          type="button"
          @click="send(prompt.queryText || prompt.displayText, prompt)"
        >
          {{ prompt.displayText }}
        </button>
      </div>

      <footer class="ai-customer-widget__footer">
        <input
          v-model="input"
          :disabled="busy"
          placeholder="问退票、实名入场、票档、订单售后..."
          @keydown.enter.prevent="send()"
        />
        <button type="button" :disabled="busy || !input.trim()" @click="send()">发送</button>
      </footer>

      <div class="ai-customer-widget__feedback" v-if="lastRunId || currentChatId">
        <button type="button" @click="feedback('up')">已解决</button>
        <button type="button" @click="feedback('down')">没解决</button>
        <button type="button" @click="handoff">转人工</button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  approveAction,
  createRun,
  createWorkItem,
  getRun,
  getStarterPrompts,
  quickAnswer,
  rejectAction,
  streamRun,
  submitFeedback
} from '@/api/aiCustomerService'
import { getToken } from '@/utils/auth'

const route = useRoute()
const open = ref(false)
const busy = ref(false)
const input = ref('')
const currentChatId = ref('')
const lastRunId = ref('')
const pendingAction = ref(null)
const workItem = ref(null)
const evidenceCards = ref([])
const starterPrompts = ref([])
const scrollRef = ref(null)
const messages = ref([
  {
    id: 'welcome',
    role: 'assistant',
    content: '你好，我是麦小蜜。可以帮你查演出、问规则、确认购票信息，也可以把复杂售后转给人工客服。'
  }
])

const pageContext = computed(() => {
  const context = {
    scene: 'customer_service',
    entry: 'damai-pro-widget',
    routeName: route.name || '',
    routePath: route.path
  }
  if (route.name === 'detial' && route.params.id) {
    context.programId = String(route.params.id)
  }
  if (route.params.orderNumber) {
    context.orderNo = String(route.params.orderNumber)
  }
  if (history.state?.ticketCategoryId) {
    context.categoryId = String(history.state.ticketCategoryId)
  }
  return context
})

const contextLabel = computed(() => {
  const context = pageContext.value
  if (context.orderNo) {
    return `已带入订单 ${context.orderNo}`
  }
  if (context.programId) {
    return `已带入演出 ${context.programId}`
  }
  return ''
})

watch(open, async (visible) => {
  if (visible && starterPrompts.value.length === 0) {
    await loadStarterPrompts()
  }
})

onMounted(loadStarterPrompts)

function toggle() {
  open.value = !open.value
}

async function loadStarterPrompts() {
  if (!getToken()) {
    return
  }
  try {
    const response = await getStarterPrompts()
    starterPrompts.value = response.data || []
  } catch (error) {
    starterPrompts.value = []
  }
}

async function send(presetMessage, prompt = null) {
  const content = (presetMessage || input.value).trim()
  if (!content || busy.value) {
    return
  }
  if (!getToken()) {
    ElMessage.warning('请先登录后使用智能客服')
    return
  }
  input.value = ''
  pushMessage('user', content)
  busy.value = true
  pendingAction.value = null
  evidenceCards.value = []
  try {
    const quick = await quickAnswer({
      chatId: currentChatId.value || null,
      message: content,
      hotQuestionId: prompt?.questionId,
      intentHint: prompt?.intentCode,
      programId: pageContext.value.programId,
      orderNo: pageContext.value.orderNo,
      categoryId: pageContext.value.categoryId,
      clientContext: pageContext.value
    })
    const quickData = quick.data || null
    if (quickData?.workItem) {
      workItem.value = quickData.workItem
    }
    if (quickData?.hit && quickData.answerMode === 'CACHED_ANSWER') {
      evidenceCards.value = quickData.sourceRefs || []
      pushMessage('assistant', quickData.directAnswer || '已命中客服高频问题。')
      return
    }
    await runAssistant(content, quickData?.clientContext || {})
  } catch (error) {
    pushMessage('assistant', error.message || '智能客服暂时不可用，请稍后再试。')
  } finally {
    busy.value = false
  }
}

async function runAssistant(content, quickContext = {}) {
  const created = await createRun({
    chatId: currentChatId.value || null,
    message: content,
    clientContext: {
      ...pageContext.value,
      ...quickContext
    }
  })
  const run = created.data || {}
  currentChatId.value = run.chatId || currentChatId.value
  lastRunId.value = run.runId || lastRunId.value
  const assistantMessage = pushMessage('assistant', '')
  await streamRun(run.eventStreamPath, async ({ event, data }) => {
    if (event === 'message.delta') {
      assistantMessage.content += data?.delta || ''
      await scrollToBottom()
    } else if (event === 'message.replaced') {
      assistantMessage.content = data?.content || assistantMessage.content
    } else if (event === 'action.required') {
      pendingAction.value = data
    } else if (event === 'retrieval.completed' && Array.isArray(data?.sources)) {
      evidenceCards.value = data.sources
    } else if (event === 'customer.work_item.created') {
      workItem.value = data?.workItem || data?.ticket || data
    } else if (event === 'run.failed') {
      assistantMessage.content = data?.message || '本次请求处理失败。'
    }
  })
  if (!assistantMessage.content.trim()) {
    const detail = await getRun(lastRunId.value)
    assistantMessage.content = detail?.data?.run?.responseSummary || '已完成处理。'
    pendingAction.value = detail?.data?.pendingAction || pendingAction.value
  }
}

async function resolvePurchase(decision) {
  if (!pendingAction.value || busy.value) {
    return
  }
  busy.value = true
  try {
    const response = decision === 'approve'
      ? await approveAction(pendingAction.value.runId, pendingAction.value.actionId)
      : await rejectAction(pendingAction.value.runId, pendingAction.value.actionId)
    pendingAction.value = null
    pushMessage('assistant', response?.data?.message || (decision === 'approve' ? '订单已创建。' : '已取消本次下单请求。'))
  } catch (error) {
    pushMessage('assistant', error.message || '操作失败，请稍后再试。')
  } finally {
    busy.value = false
  }
}

async function feedback(rating) {
  try {
    await submitFeedback({
      runId: lastRunId.value || null,
      conversationId: currentChatId.value || null,
      rating,
      comment: rating === 'down' ? '客户侧浮窗反馈未解决' : '客户侧浮窗反馈已解决'
    })
    ElMessage.success('反馈已记录')
  } catch (error) {
    ElMessage.warning(error.message || '反馈提交失败')
  }
}

async function handoff() {
  if (busy.value) {
    return
  }
  busy.value = true
  try {
    const latestUserMessage = [...messages.value].reverse().find(item => item.role === 'user')?.content || input.value
    const latestAiMessage = [...messages.value].reverse().find(item => item.role === 'assistant')?.content || ''
    const response = await createWorkItem({
      runId: lastRunId.value || null,
      conversationId: currentChatId.value || null,
      userQuestion: latestUserMessage,
      aiAnswer: latestAiMessage,
      intentCode: 'HUMAN_HANDOFF',
      reason: '用户在主站 AI 浮窗请求转人工',
      businessContext: pageContext.value,
      suggestedReply: '请结合页面上下文、订单号和用户最近问题继续处理。'
    })
    workItem.value = response.data
    pushMessage('assistant', `已创建人工工单 ${response.data?.workItemId || ''}，客服会结合上下文继续处理。`)
  } catch (error) {
    pushMessage('assistant', error.message || '转人工失败，请稍后再试。')
  } finally {
    busy.value = false
  }
}

function pushMessage(role, content) {
  const message = {
    id: `${role}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    role,
    content
  }
  messages.value.push(message)
  scrollToBottom()
  return message
}

async function scrollToBottom() {
  await nextTick()
  if (scrollRef.value) {
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  }
}
</script>

<style scoped lang="scss">
.ai-customer-widget {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 3000;
  font-family: inherit;
}

.ai-customer-widget__launcher {
  width: 56px;
  height: 56px;
  border: 0;
  border-radius: 50%;
  background: #ff371d;
  color: #fff;
  box-shadow: 0 10px 28px rgba(255, 55, 29, 0.32);
  cursor: pointer;
  font-weight: 700;
}

.ai-customer-widget__panel {
  position: absolute;
  right: 0;
  bottom: 72px;
  width: 380px;
  height: 590px;
  display: grid;
  grid-template-rows: auto auto 1fr auto auto auto;
  background: #fff;
  border: 1px solid #ebecef;
  border-radius: 8px;
  box-shadow: 0 18px 48px rgba(20, 24, 31, 0.18);
  overflow: hidden;
}

.ai-customer-widget__header {
  height: 60px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #f0f1f4;
  background: #ffffff;

  strong {
    display: block;
    color: #1f2329;
    font-size: 16px;
  }

  span {
    color: #646a73;
    font-size: 12px;
  }

  button {
    width: 28px;
    height: 28px;
    border: 0;
    background: transparent;
    color: #646a73;
    cursor: pointer;
    font-size: 22px;
    line-height: 1;
  }
}

.ai-customer-widget__context {
  margin: 10px 14px 0;
  padding: 8px 10px;
  border-radius: 6px;
  color: #8a3f00;
  background: #fff6e8;
  font-size: 12px;
}

.ai-customer-widget__messages {
  padding: 14px;
  overflow-y: auto;
  min-height: 0;
}

.ai-customer-widget__message {
  display: flex;
  margin-bottom: 10px;

  p {
    max-width: 82%;
    margin: 0;
    padding: 9px 11px;
    border-radius: 8px;
    line-height: 1.55;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 13px;
  }
}

.ai-customer-widget__message--assistant p {
  background: #f5f6f8;
  color: #1f2329;
}

.ai-customer-widget__message--user {
  justify-content: flex-end;

  p {
    color: #fff;
    background: #ff5a45;
  }
}

.ai-customer-widget__evidence,
.ai-customer-widget__work-item,
.ai-customer-widget__action {
  margin: 10px 0;
  padding: 10px;
  border: 1px solid #edf0f4;
  border-radius: 8px;
  background: #fbfcfd;
  font-size: 12px;
}

.ai-customer-widget__evidence {
  span {
    display: block;
    margin-bottom: 8px;
    color: #646a73;
  }

  button {
    margin: 0 6px 6px 0;
    padding: 4px 8px;
    border: 1px solid #d9dde4;
    border-radius: 999px;
    background: #fff;
    color: #4e5969;
  }
}

.ai-customer-widget__action {
  strong {
    display: block;
    margin-bottom: 6px;
    color: #1f2329;
  }

  p {
    margin: 0 0 10px;
    line-height: 1.5;
    color: #4e5969;
  }

  button {
    height: 30px;
    margin-right: 8px;
    padding: 0 12px;
    border: 1px solid #ff5a45;
    border-radius: 6px;
    color: #ff371d;
    background: #fff;
    cursor: pointer;

    &:first-child {
      color: #fff;
      background: #ff371d;
    }
  }
}

.ai-customer-widget__work-item {
  display: flex;
  justify-content: space-between;
  color: #4e5969;

  strong {
    color: #ff371d;
  }
}

.ai-customer-widget__prompts {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  overflow-x: auto;
  border-top: 1px solid #f0f1f4;

  button {
    flex: 0 0 auto;
    height: 28px;
    padding: 0 10px;
    border: 1px solid #e3e6eb;
    border-radius: 999px;
    background: #fff;
    color: #4e5969;
    cursor: pointer;
    font-size: 12px;
  }
}

.ai-customer-widget__footer {
  display: grid;
  grid-template-columns: 1fr 64px;
  gap: 8px;
  padding: 12px 14px;
  border-top: 1px solid #f0f1f4;

  input {
    height: 36px;
    padding: 0 10px;
    border: 1px solid #d9dde4;
    border-radius: 6px;
    outline: none;
  }

  button {
    height: 36px;
    border: 0;
    border-radius: 6px;
    color: #fff;
    background: #ff371d;
    cursor: pointer;

    &:disabled {
      cursor: not-allowed;
      background: #c9cdd4;
    }
  }
}

.ai-customer-widget__feedback {
  display: flex;
  gap: 8px;
  padding: 0 14px 12px;

  button {
    flex: 1;
    height: 28px;
    border: 1px solid #e3e6eb;
    border-radius: 6px;
    color: #4e5969;
    background: #fff;
    cursor: pointer;
    font-size: 12px;
  }
}

@media (max-width: 520px) {
  .ai-customer-widget {
    right: 14px;
    bottom: 14px;
  }

  .ai-customer-widget__panel {
    right: -4px;
    width: calc(100vw - 28px);
    height: min(620px, calc(100vh - 104px));
  }
}
</style>

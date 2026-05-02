import { computed, nextTick, ref } from 'vue'
import { assistantAPI } from '../api/api'

function normalizeMessage(message, index) {
  return {
    id: message.id || `${message.role || 'assistant'}-${index}`,
    role: message.role || 'assistant',
    content: message.content || '',
    timestamp: message.createdAt || message.timestamp || new Date().toISOString()
  }
}

function normalizeConversation(chat) {
  return {
    id: chat.chatId,
    title: chat.title || '新的助手会话',
    latestRunId: chat.latestRunId || '',
    routeType: chat.routeType || '',
    workflowStatus: chat.latestStatus || chat.workflowStatus || ''
  }
}

function parseJsonSafely(value, fallback) {
  if (!value) {
    return fallback
  }
  if (typeof value === 'object') {
    return value
  }
  try {
    return JSON.parse(value)
  } catch (error) {
    return fallback
  }
}

export function useAssistantRuntime() {
  const messagesRef = ref(null)
  const inputRef = ref(null)
  const userInput = ref('')
  const isStreaming = ref(false)
  const currentChatId = ref('')
  const currentRunId = ref('')
  const currentRoute = ref('')
  const currentMessages = ref([])
  const conversations = ref([])
  const runTimeline = ref([])
  const evidenceCards = ref([])
  const pendingAction = ref(null)
  const toolCalls = ref([])
  const clarificationOptions = ref([])
  const errorMessage = ref('')
  const refusalReason = ref('')
  const runStatus = ref('')
  const capabilities = ref({ admin: false, allowedRoutes: ['business', 'knowledge', 'general'] })

  const orderedTimeline = computed(() => [...runTimeline.value].reverse())
  const hasMessages = computed(() => currentMessages.value.length > 0)
  const canUseOps = computed(() => capabilities.value.allowedRoutes?.includes('ops') === true)

  const scrollToBottom = async () => {
    await nextTick()
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  }

  const adjustTextareaHeight = () => {
    if (!inputRef.value) {
      return
    }
    inputRef.value.style.height = 'auto'
    inputRef.value.style.height = `${Math.min(inputRef.value.scrollHeight, 220)}px`
  }

  const resetRunState = () => {
    runTimeline.value = []
    evidenceCards.value = []
    pendingAction.value = null
    toolCalls.value = []
    clarificationOptions.value = []
    refusalReason.value = ''
    errorMessage.value = ''
    runStatus.value = ''
    currentRoute.value = ''
  }

  const appendAssistantDelta = async (delta) => {
    if (!delta) {
      return
    }
    const lastMessage = currentMessages.value[currentMessages.value.length - 1]
    if (lastMessage?.role === 'assistant') {
      lastMessage.content += delta
    } else {
      currentMessages.value.push({
        id: `assistant-${Date.now()}`,
        role: 'assistant',
        content: delta,
        timestamp: new Date().toISOString()
      })
    }
    await scrollToBottom()
  }

  const recordTimeline = (event, data) => {
    runTimeline.value.unshift({
      id: `${event}-${runTimeline.value.length}-${Date.now()}`,
      event,
      data,
      at: new Date().toISOString()
    })
  }

  const updateConversationTitle = (chatId, title) => {
    if (!chatId || !title) {
      return
    }
    const target = conversations.value.find(item => item.id === chatId)
    if (target) {
      target.title = title
    }
  }

  const loadConversations = async () => {
    const result = await assistantAPI.listConversations()
    conversations.value = Array.isArray(result?.data)
      ? result.data.map(normalizeConversation)
      : []
  }

  const loadCapabilities = async () => {
    const result = await assistantAPI.getCapabilities()
    capabilities.value = {
      admin: result?.data?.admin === true,
      allowedRoutes: Array.isArray(result?.data?.allowedRoutes) ? result.data.allowedRoutes : ['business', 'knowledge', 'general']
    }
  }

  const loadConversation = async (chatId) => {
    const result = await assistantAPI.listMessages(chatId)
    const conversation = conversations.value.find(item => item.id === chatId)
    currentChatId.value = chatId
    currentMessages.value = Array.isArray(result?.data)
      ? result.data.map(normalizeMessage)
      : []
    resetRunState()
    currentRunId.value = conversation?.latestRunId || ''
    currentRoute.value = conversation?.routeType || ''
    runStatus.value = conversation?.workflowStatus || ''

    if (conversation?.latestRunId) {
      const runDetail = await assistantAPI.getRun(conversation.latestRunId)
      const detail = runDetail?.data
      pendingAction.value = detail?.pendingAction || null
      if (detail?.retrieval?.finalHitsJson) {
        evidenceCards.value = parseJsonSafely(detail.retrieval.finalHitsJson, [])
      }
      if (Array.isArray(detail?.events)) {
        runTimeline.value = [...detail.events]
          .map((event, index) => ({
            id: event.eventId || `evt-${index}`,
            event: event.eventType,
            data: parseJsonSafely(event.payloadJson, {}),
            at: event.createTime || new Date().toISOString()
          }))
          .reverse()
      }
    }
    await scrollToBottom()
  }

  const startNewChat = () => {
    currentChatId.value = ''
    currentRunId.value = ''
    currentMessages.value = []
    resetRunState()
    userInput.value = ''
  }

  const handleRunEvent = async (event, data) => {
    switch (event) {
      case 'run.started':
        currentRunId.value = data?.runId || currentRunId.value
        currentChatId.value = data?.chatId || currentChatId.value
        runStatus.value = data?.status || 'RUNNING'
        recordTimeline(event, data)
        break
      case 'route.selected':
        currentRoute.value = data?.routeType || ''
        updateConversationTitle(currentChatId.value, data?.conversationTitle)
        recordTimeline(event, data)
        break
      case 'clarification.required':
        clarificationOptions.value = Array.isArray(data?.options)
          ? data.options.filter(option => canUseOps.value || !/日志|trace|cpu|jvm|监控|运维/i.test(option))
          : []
        recordTimeline(event, data)
        break
      case 'skill.started':
      case 'skill.completed':
        recordTimeline(event, data)
        break
      case 'retrieval.started':
      case 'retrieval.completed':
        if (Array.isArray(data?.sources)) {
          evidenceCards.value = data.sources
        }
        if (data?.refusalReason) {
          refusalReason.value = data.refusalReason
        }
        recordTimeline(event, data)
        break
      case 'tool.started':
      case 'tool.completed':
        toolCalls.value.unshift({
          id: `${event}-${Date.now()}`,
          event,
          toolName: data?.toolName || data?.name || 'tool',
          summary: data?.summary || data?.status || ''
        })
        recordTimeline(event, data)
        break
      case 'action.required':
        pendingAction.value = data
        recordTimeline(event, data)
        break
      case 'message.delta':
        await appendAssistantDelta(data?.delta || '')
        break
      case 'message.completed':
        runStatus.value = data?.status || 'COMPLETED'
        recordTimeline(event, data)
        if (data?.conversationTitle) {
          updateConversationTitle(currentChatId.value, data.conversationTitle)
        }
        break
      case 'run.completed':
        runStatus.value = data?.status || 'COMPLETED'
        recordTimeline(event, data)
        break
      case 'run.failed':
        runStatus.value = data?.status || 'FAILED'
        errorMessage.value = data?.message || '统一助手执行失败'
        recordTimeline(event, data)
        break
      default:
        recordTimeline(event, data)
        break
    }
  }

  const sendMessage = async (presetMessage) => {
    const message = (presetMessage ?? userInput.value).trim()
    if (!message || isStreaming.value) {
      return
    }

    currentMessages.value.push({
      id: `user-${Date.now()}`,
      role: 'user',
      content: message,
      timestamp: new Date().toISOString()
    })
    userInput.value = ''
    adjustTextareaHeight()
    resetRunState()
    isStreaming.value = true
    await scrollToBottom()

    try {
      const stream = await assistantAPI.sendMessage(message, currentChatId.value || null, {
        entry: 'assistant-hub'
      })
      for await (const item of stream) {
        if (!item) {
          continue
        }
        await handleRunEvent(item.event, item.data)
      }
      await loadConversations()
    } catch (error) {
      errorMessage.value = error.message || '统一助手执行失败'
    } finally {
      isStreaming.value = false
    }
  }

  const resolveAction = async (action, decision) => {
    if (!action?.runId || !action?.actionId) {
      return
    }
    const result = decision === 'approve'
      ? await assistantAPI.approveAction(action.runId, action.actionId)
      : await assistantAPI.rejectAction(action.runId, action.actionId)

    pendingAction.value = null
    currentMessages.value.push({
      id: `assistant-action-${Date.now()}`,
      role: 'assistant',
      content: result?.data?.message || (decision === 'approve' ? '审批已通过。' : '审批已拒绝。'),
      timestamp: new Date().toISOString()
    })
    await scrollToBottom()
  }

  return {
    messagesRef,
    inputRef,
    userInput,
    isStreaming,
    currentChatId,
    currentRunId,
    currentRoute,
    currentMessages,
    conversations,
    runTimeline,
    orderedTimeline,
    evidenceCards,
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
    approveAction: () => resolveAction(pendingAction.value, 'approve'),
    rejectAction: () => resolveAction(pendingAction.value, 'reject')
  }
}

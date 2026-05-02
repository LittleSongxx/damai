import { nextTick, ref } from 'vue'
import { chatAPI } from '../api/api'

export function useAiChat({ chatType, sendMessageApi, newChatTitle = '新的对话' }) {
  const messagesRef = ref(null)
  const inputRef = ref(null)
  const userInput = ref('')
  const isStreaming = ref(false)
  const currentChatId = ref(null)
  const currentMessages = ref([])
  const chatHistory = ref([])
  const workflowSteps = ref([])
  const pendingApproval = ref(null)
  const retrievalSources = ref([])
  const retrievalMeta = ref(null)
  const currentRunId = ref(null)
  const approvalBusy = ref(false)
  const errorMessage = ref('')

  const adjustTextareaHeight = () => {
    const textarea = inputRef.value
    if (textarea) {
      textarea.style.height = 'auto'
      textarea.style.height = `${textarea.scrollHeight}px`
    }
  }

  const scrollToBottom = async () => {
    await nextTick()
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  }

  const startNewChat = async () => {
    const newChatId = Date.now().toString()
    currentChatId.value = newChatId
    currentMessages.value = []
    workflowSteps.value = []
    pendingApproval.value = null
    retrievalSources.value = []
    retrievalMeta.value = null
    currentRunId.value = null
    const newChat = {
      id: newChatId,
      title: newChatTitle
    }
    chatHistory.value = [newChat, ...chatHistory.value.filter(chat => chat.id !== newChatId)]
  }

  const loadChat = async (chatId) => {
    currentChatId.value = chatId
    currentMessages.value = await chatAPI.chatHistoryMessageList(chatId, chatType)
    const targetChat = chatHistory.value.find(chat => chat.id === chatId)
    currentRunId.value = targetChat?.latestRunId || null
    if (currentRunId.value) {
      await refreshWorkflow(currentRunId.value)
    } else {
      workflowSteps.value = []
      pendingApproval.value = null
      retrievalSources.value = []
      retrievalMeta.value = null
    }
  }

  const loadChatHistory = async () => {
    chatHistory.value = await chatAPI.chatTypeHistoryList(chatType)
    if (chatHistory.value.length > 0) {
      await loadChat(chatHistory.value[0].id)
    } else {
      await startNewChat()
    }
  }

  const refreshHistory = async () => {
    chatHistory.value = await chatAPI.chatTypeHistoryList(chatType)
  }

  const refreshWorkflow = async (runId = currentRunId.value) => {
    if (!runId) {
      return
    }
    const workflow = await chatAPI.getWorkflow(runId)
    workflowSteps.value = workflow?.steps || []
    pendingApproval.value = workflow?.pendingApproval || null
  }

  const deleteChat = async (chatId) => {
    await chatAPI.deleteChat(chatId, chatType)
    chatHistory.value = chatHistory.value.filter(chat => chat.id !== chatId)
    if (currentChatId.value === chatId) {
      await startNewChat()
    }
  }

  const handleEvent = async (event, assistantMessage) => {
    const { event: type, data } = event
    if (type === 'message.delta') {
      assistantMessage.content += data?.delta || ''
    } else if (type === 'workflow.step') {
      workflowSteps.value = Array.isArray(data) ? data : (data?.steps || [])
    } else if (type === 'approval.required') {
      pendingApproval.value = data
      currentRunId.value = data?.runId || currentRunId.value
    } else if (type === 'retrieval.sources') {
      retrievalSources.value = data?.sources || []
      retrievalMeta.value = {
        traceId: data?.traceId,
        rewrittenQuery: data?.rewrittenQuery
      }
      currentRunId.value = data?.runId || currentRunId.value
    } else if (type === 'message.done') {
      currentRunId.value = data?.runId || currentRunId.value
      if (currentRunId.value) {
        await refreshWorkflow(currentRunId.value)
      }
      await refreshHistory()
    } else if (type === 'error') {
      errorMessage.value = data?.message || '请求失败'
      assistantMessage.content = '抱歉，发生了错误，请稍后重试。'
    }
  }

  const sendMessage = async (content) => {
    if (isStreaming.value || (!content && !userInput.value.trim())) {
      return
    }

    const messageContent = content || userInput.value.trim()
    const userMessage = {
      role: 'user',
      content: messageContent,
      timestamp: new Date()
    }
    currentMessages.value.push(userMessage)

    if (!content) {
      userInput.value = ''
      adjustTextareaHeight()
    }

    const assistantMessage = {
      role: 'assistant',
      content: '',
      timestamp: new Date()
    }
    currentMessages.value.push(assistantMessage)
    isStreaming.value = true
    errorMessage.value = ''
    await scrollToBottom()

    try {
      const stream = await sendMessageApi(messageContent, currentChatId.value)
      for await (const event of stream) {
        await handleEvent(event, assistantMessage)
        await nextTick()
        await scrollToBottom()
      }
    } catch (error) {
      console.error('sendMessage error', error)
      assistantMessage.content = '抱歉，发生了错误，请稍后重试。'
      errorMessage.value = error.message || '请求失败'
    } finally {
      isStreaming.value = false
      await scrollToBottom()
    }
  }

  const approvePending = async () => {
    if (!currentRunId.value || approvalBusy.value) {
      return
    }
    approvalBusy.value = true
    try {
      const result = await chatAPI.approveWorkflow(currentRunId.value)
      pendingApproval.value = null
      currentMessages.value.push({
        role: 'assistant',
        content: `订单已创建成功，订单编号：${result.orderNumber}\n\n请前往 ${result.orderListAddress} 完成支付。`,
        timestamp: new Date()
      })
      await refreshWorkflow(currentRunId.value)
      await refreshHistory()
      await scrollToBottom()
    } finally {
      approvalBusy.value = false
    }
  }

  const rejectPending = async () => {
    if (!currentRunId.value || approvalBusy.value) {
      return
    }
    approvalBusy.value = true
    try {
      await chatAPI.rejectWorkflow(currentRunId.value)
      pendingApproval.value = null
      currentMessages.value.push({
        role: 'assistant',
        content: '已取消本次下单请求。如需重新购票，请重新确认节目和票档信息。',
        timestamp: new Date()
      })
      await refreshWorkflow(currentRunId.value)
      await refreshHistory()
      await scrollToBottom()
    } finally {
      approvalBusy.value = false
    }
  }

  return {
    messagesRef,
    inputRef,
    userInput,
    isStreaming,
    currentChatId,
    currentMessages,
    chatHistory,
    workflowSteps,
    pendingApproval,
    retrievalSources,
    retrievalMeta,
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
  }
}

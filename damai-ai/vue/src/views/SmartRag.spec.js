import { shallowMount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { reindexFaq, loadChatHistory } = vi.hoisted(() => ({
  reindexFaq: vi.fn().mockResolvedValue(true),
  loadChatHistory: vi.fn()
}))

vi.mock('../api/api', () => ({
  chatAPI: {
    sendRagMessage: vi.fn(),
    reindexFaq
  },
  ensureAuthenticated: vi.fn(() => true)
}))

vi.mock('../composables/useAiChat', () => ({
  useAiChat: () => ({
    messagesRef: ref(null),
    inputRef: ref(null),
    userInput: ref(''),
    isStreaming: ref(false),
    currentChatId: ref('chat-rag-1'),
    currentMessages: ref([]),
    chatHistory: ref([{ id: 'chat-rag-1', title: '退票规则', workflowStatus: 'COMPLETED' }]),
    workflowSteps: ref([{ id: 1, stepKey: 'RERANK', stepStatus: 'COMPLETED' }]),
    retrievalSources: ref([{
      chunkId: 'faq-1',
      title: '退票规则 FAQ',
      source: 'refund.md',
      section: '退票说明',
      snippet: '开演前可根据票档规则申请退票。',
      score: 0.9123
    }]),
    retrievalMeta: ref({
      traceId: 'trace-1',
      rewrittenQuery: '退票 退款 取消订单'
    }),
    errorMessage: ref(''),
    adjustTextareaHeight: vi.fn(),
    loadChatHistory,
    loadChat: vi.fn(),
    startNewChat: vi.fn(),
    deleteChat: vi.fn(),
    sendMessage: vi.fn()
  })
}))

import SmartRag from './SmartRag.vue'

describe('SmartRag', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(window, 'alert').mockImplementation(() => {})
  })

  it('renders citations and rewritten query', async () => {
    const wrapper = shallowMount(SmartRag, {
      global: {
        stubs: {
          Chat: true
        }
      }
    })

    await Promise.resolve()
    expect(loadChatHistory).toHaveBeenCalled()
    expect(wrapper.text()).toContain('退票 退款 取消订单')
    expect(wrapper.text()).toContain('退票规则 FAQ')
    expect(wrapper.text()).toContain('开演前可根据票档规则申请退票。')
    expect(wrapper.text()).toContain('RERANK')

    await wrapper.findAll('.ghost-button')[1].trigger('click')
    expect(reindexFaq).toHaveBeenCalled()
  })
})

import { shallowMount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { approvePending, rejectPending, loadChatHistory } = vi.hoisted(() => ({
  approvePending: vi.fn(),
  rejectPending: vi.fn(),
  loadChatHistory: vi.fn()
}))

vi.mock('../api/api', () => ({
  chatAPI: {
    sendAssistantMessage: vi.fn()
  },
  ensureAuthenticated: vi.fn(() => true)
}))

vi.mock('../composables/useAiChat', () => ({
  useAiChat: () => ({
    messagesRef: ref(null),
    inputRef: ref(null),
    userInput: ref(''),
    isStreaming: ref(false),
    currentChatId: ref('chat-1'),
    currentMessages: ref([]),
    chatHistory: ref([{ id: 'chat-1', title: '新的购票咨询', workflowStatus: 'WAITING_APPROVAL' }]),
    workflowSteps: ref([{ id: 1, stepKey: 'WAIT_APPROVAL', stepStatus: 'COMPLETED' }]),
    pendingApproval: ref({
      previewJson: JSON.stringify({
        programTitle: '周杰伦演唱会',
        ticketCategoryPrice: 1280,
        ticketCount: 2,
        ticketUsers: ['张三', '李四']
      })
    }),
    approvalBusy: ref(false),
    errorMessage: ref(''),
    adjustTextareaHeight: vi.fn(),
    loadChatHistory,
    loadChat: vi.fn(),
    startNewChat: vi.fn(),
    sendMessage: vi.fn(),
    approvePending,
    rejectPending
  })
}))

import DaMaiAi from './DaMaiAi.vue'

describe('DaMaiAi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders approval summary and workflow step', async () => {
    const wrapper = shallowMount(DaMaiAi, {
      global: {
        stubs: {
          Chat: true
        }
      }
    })

    await Promise.resolve()
    expect(loadChatHistory).toHaveBeenCalled()
    expect(wrapper.text()).toContain('周杰伦演唱会')
    expect(wrapper.text()).toContain('张三、李四')
    expect(wrapper.text()).toContain('WAIT_APPROVAL')

    await wrapper.get('.primary-button').trigger('click')
    expect(approvePending).toHaveBeenCalled()
  })
})

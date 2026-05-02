import { createAbsoluteUrl, createSseIterator, getAuthState, parseSseChunk } from './api'

function createReader(chunks) {
  const encoder = new TextEncoder()
  let index = 0
  return {
    async read() {
      if (index >= chunks.length) {
        return { done: true, value: undefined }
      }
      return {
        done: false,
        value: encoder.encode(chunks[index++])
      }
    }
  }
}

describe('api sse helpers', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('parses structured SSE chunks', () => {
    const event = parseSseChunk('event: retrieval.sources\ndata: {"traceId":"trace-1","rewrittenQuery":"退票 退款"}')
    expect(event).toEqual({
      event: 'retrieval.sources',
      data: {
        traceId: 'trace-1',
        rewrittenQuery: '退票 退款'
      }
    })
  })

  it('iterates across multi-chunk SSE payloads', async () => {
    const reader = createReader([
      'event: message.delta\ndata: {"delta":"你好"}\n\n',
      'event: workflow.step\ndata: {"steps":[{"id":1,"stepKey":"ANSWER"}]}\n\n'
    ])

    const iterator = createSseIterator(reader)
    const events = []
    for await (const event of iterator) {
      events.push(event)
    }

    expect(events).toHaveLength(2)
    expect(events[0]).toEqual({
      event: 'message.delta',
      data: {
        delta: '你好'
      }
    })
    expect(events[1].event).toBe('workflow.step')
    expect(events[1].data.steps[0].stepKey).toBe('ANSWER')
  })

  it('normalizes relative API URLs against the current origin', () => {
    const url = createAbsoluteUrl('/damai-ai-dev/chat/type/history/list?type=3')
    expect(url.toString()).toBe(
      new URL('/damai-ai-dev/chat/type/history/list?type=3', window.location.origin).toString()
    )
  })

  it('reads auth state from shared cookies', () => {
    Object.defineProperty(document, 'cookie', {
      value: 'Admin-Token=test-token; userId=5',
      configurable: true
    })

    expect(getAuthState()).toEqual({
      token: 'test-token',
      userId: '5',
      isAuthenticated: true
    })
  })
})

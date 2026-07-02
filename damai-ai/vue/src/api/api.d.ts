export interface AuthState {
  token: string
  userId: string
  isAuthenticated: boolean
}

export interface SseEvent<T = unknown> {
  id: string
  event: string
  data: T
}

export function getAuthState(): AuthState
export function ensureAuthenticated(): boolean
export function createAbsoluteUrl(rawUrl: string): URL
export function createSseIterator<T = unknown>(reader: ReadableStreamDefaultReader<Uint8Array>): AsyncIterable<SseEvent<T> | null>
export function parseSseChunk<T = unknown>(chunk: string): SseEvent<T> | null

export const assistantAPI: Record<string, (...args: any[]) => Promise<any>>
export const customerServiceAPI: Record<string, (...args: any[]) => Promise<any>>
export const ragEvalAPI: Record<string, (...args: any[]) => Promise<any>>
export const aiOpsAdminAPI: Record<string, (...args: any[]) => Promise<any>>
export const promptVersionAPI: Record<string, (...args: any[]) => Promise<any>>

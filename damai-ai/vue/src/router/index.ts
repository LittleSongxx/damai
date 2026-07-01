import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ensureAuthenticated, getAuthState } from '../api/api'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/assistant'
  },
  {
    path: '/assistant',
    name: 'AssistantHub',
    component: () => import('../views/AssistantHub.vue')
  },
  {
    path: '/assistant/skills',
    name: 'SkillManagement',
    component: () => import('../views/SkillManagement.vue')
  },
  {
    path: '/assistant/prompts',
    name: 'PromptGovernance',
    component: () => import('../views/PromptGovernance.vue')
  },
  {
    path: '/damai-ai',
    redirect: '/assistant'
  },
  {
    path: '/damai-rag',
    redirect: '/assistant'
  },
  {
    path: '/ai-observability',
    name: 'AiObservability',
    component: () => import('../views/AiObservability.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(() => {
  if (!getAuthState().isAuthenticated) {
    ensureAuthenticated()
    return false
  }
  return true
})

export default router

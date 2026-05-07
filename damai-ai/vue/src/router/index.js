import { createRouter, createWebHistory } from 'vue-router'
import { ensureAuthenticated, getAuthState } from '../api/api'

const routes = [
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
    path: '/legacy',
    name: 'Home',
    component: () => import('../views/Home.vue')
  },
  {
    path: '/damai-ai',
    name: 'DaMaiAI',
    component: () => import('../views/DaMaiAi.vue')
  },
  {
    path: '/damai-rag',
    name: 'SmartRag',
    component: () => import('../views/SmartRag.vue')
  },
  {
    path: '/damai-analysis',
    name: 'DaMaiAnalysis',
    component: () => import('../views/DaMaiAnalysis.vue')
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

router.beforeEach((to, from, next) => {
  if (!getAuthState().isAuthenticated) {
    ensureAuthenticated()
    return
  }
  next()
})

export default router 

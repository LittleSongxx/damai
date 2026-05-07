import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
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
      path: '/damai-ai',
      name: 'DaMaiAI',
      component: () => import('../views/DaMaiAi.vue')
    },
    {
      path: '/damai-rag',
      name: 'SmartRag',
      component: () => import('../views/SmartRag.vue')
    }
  ],
})

export default router

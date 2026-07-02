<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { useDark, useToggle } from '@vueuse/core'
import { SunIcon, MoonIcon } from '@heroicons/vue/24/outline'
import { assistantAPI, ensureAuthenticated } from './api/api'

const route = useRoute()
const isDark = useDark()
const toggleDark = useToggle(isDark)
const capabilities = ref({ admin: false, allowedRoutes: ['business', 'knowledge', 'general'] })

const navItems = [
  { label: '统一助手', to: '/assistant' }
]

const activePath = computed(() => route.path)
const visibleNavItems = computed(() => navItems.filter(item => !item.adminOnly || capabilities.value.admin))

onMounted(async () => {
  if (!ensureAuthenticated()) {
    return
  }
  try {
    const result = await assistantAPI.getCapabilities()
    capabilities.value = {
      admin: result?.data?.admin === true,
      allowedRoutes: Array.isArray(result?.data?.allowedRoutes) ? result.data.allowedRoutes : ['business', 'knowledge', 'general']
    }
  } catch (error) {
    capabilities.value = { admin: false, allowedRoutes: ['business', 'knowledge', 'general'] }
  }
})
</script>

<template>
  <div class="app-shell" :class="{ dark: isDark }">
    <div class="app-shell__backdrop"></div>
    <header class="topbar">
      <div class="brand">
        <div class="brand__mark">AI</div>
        <div>
          <p class="brand__eyebrow">Javaup Ticket Ops</p>
          <router-link to="/assistant" class="brand__title">大麦 AI 控制台</router-link>
        </div>
      </div>

      <nav class="topbar__nav">
        <router-link
          v-for="item in visibleNavItems"
          :key="item.to"
          :to="item.to"
          class="nav-pill"
          :class="{ 'nav-pill--active': activePath === item.to }"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <button @click="toggleDark()" class="theme-toggle" aria-label="切换主题">
        <SunIcon v-if="isDark" class="icon" />
        <MoonIcon v-else class="icon" />
      </button>
    </header>

    <main class="main-stage">
      <RouterView v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </RouterView>
    </main>
  </div>
</template>

<style lang="scss">
:root {
  --primary-color: #ff5a36;
  --primary-strong: #ff4319;
  --primary-soft: rgba(255, 90, 54, 0.14);
  --secondary-color: #2550c8;
  --accent-color: #12203f;
  --bg-color: #f7efe8;
  --bg-strong: #fffaf6;
  --surface-color: rgba(255, 255, 255, 0.84);
  --surface-strong: rgba(255, 255, 255, 0.96);
  --surface-dark: #17253f;
  --text-color: #162033;
  --text-soft: #627089;
  --text-inverse: #f7f8fc;
  --border-color: rgba(18, 32, 63, 0.12);
  --border-strong: rgba(18, 32, 63, 0.18);
  --shadow-sm: 0 16px 38px rgba(17, 28, 52, 0.08);
  --shadow-md: 0 26px 60px rgba(17, 28, 52, 0.14);
  --radius-xl: 28px;
  --radius-lg: 20px;
  --radius-md: 16px;
  --radius-sm: 12px;
}

.dark {
  --bg-color: #0d1424;
  --bg-strong: #121c31;
  --surface-color: rgba(18, 29, 50, 0.86);
  --surface-strong: rgba(18, 29, 50, 0.96);
  --surface-dark: #0a1020;
  --text-color: #f3f6ff;
  --text-soft: #93a3c2;
  --text-inverse: #f7f8fc;
  --border-color: rgba(147, 163, 194, 0.15);
  --border-strong: rgba(147, 163, 194, 0.24);
  --shadow-sm: 0 18px 40px rgba(0, 0, 0, 0.26);
  --shadow-md: 0 28px 68px rgba(0, 0, 0, 0.34);
}

* {
  box-sizing: border-box;
}

html,
body,
#app {
  min-height: 100%;
}

body {
  margin: 0;
  color: var(--text-color);
  background:
    radial-gradient(circle at top left, rgba(255, 117, 82, 0.2), transparent 30%),
    radial-gradient(circle at top right, rgba(37, 80, 200, 0.16), transparent 28%),
    linear-gradient(180deg, var(--bg-strong), var(--bg-color));
  font-family: 'Avenir Next', 'Segoe UI Variable', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  line-height: 1.6;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

a {
  color: inherit;
}

button,
input,
textarea {
  font: inherit;
}

.app-shell {
  position: relative;
  min-height: 100vh;
  padding: 22px 24px 28px;
}

.app-shell__backdrop {
  position: fixed;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(circle at 18% 20%, rgba(255, 90, 54, 0.12), transparent 0 24%),
    radial-gradient(circle at 82% 18%, rgba(37, 80, 200, 0.12), transparent 0 22%),
    radial-gradient(circle at 50% 100%, rgba(255, 220, 193, 0.16), transparent 0 32%);
}

.topbar {
  position: sticky;
  top: 18px;
  z-index: 50;
  max-width: 1480px;
  margin: 0 auto 18px;
  padding: 14px 18px;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 18px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-xl);
  background: var(--surface-color);
  backdrop-filter: blur(18px);
  box-shadow: var(--shadow-sm);
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand__mark {
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  background: linear-gradient(145deg, var(--primary-color), #ff875e);
  color: #fff;
  font-weight: 900;
  letter-spacing: 0.08em;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.3);
}

.brand__eyebrow {
  margin: 0 0 2px;
  color: var(--text-soft);
  font-size: 0.75rem;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.brand__title {
  text-decoration: none;
  font-size: 1.45rem;
  font-weight: 900;
  letter-spacing: 0.02em;
}

.topbar__nav {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.nav-pill {
  padding: 10px 16px;
  border-radius: 999px;
  text-decoration: none;
  color: var(--text-soft);
  border: 1px solid transparent;
  transition: 180ms ease;
}

.nav-pill:hover {
  color: var(--text-color);
  border-color: var(--border-color);
  background: rgba(255, 255, 255, 0.36);
}

.nav-pill--active {
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--accent-color), var(--secondary-color));
  box-shadow: 0 14px 34px rgba(37, 80, 200, 0.22);
}

.theme-toggle {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border-color);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.55);
  color: var(--primary-color);
  cursor: pointer;
  transition: 180ms ease;
}

.theme-toggle:hover {
  transform: translateY(-1px);
  border-color: rgba(255, 90, 54, 0.32);
  background: var(--primary-soft);
}

.theme-toggle .icon {
  width: 22px;
  height: 22px;
}

.main-stage {
  position: relative;
  max-width: 1480px;
  margin: 0 auto;
}

.page-enter-active,
.page-leave-active {
  transition: opacity 0.24s ease, transform 0.24s ease;
}

.page-enter-from,
.page-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (max-width: 1120px) {
  .topbar {
    grid-template-columns: 1fr auto;
  }

  .topbar__nav {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
}

@media (max-width: 768px) {
  .app-shell {
    padding: 14px 12px 20px;
  }

  .topbar {
    top: 8px;
    padding: 12px;
    gap: 12px;
  }

  .brand__title {
    font-size: 1.2rem;
  }

  .brand__eyebrow {
    font-size: 0.68rem;
  }

  .nav-pill {
    padding: 8px 12px;
    font-size: 0.92rem;
  }
}
</style>

<template>
  <div class="program-poster" :class="[`program-poster--${tone}`]">
    <img
      v-if="imageSrc && !loadFailed"
      class="program-poster__image"
      :src="imageSrc"
      :alt="altText"
      :loading="loading"
      :referrerpolicy="referrerPolicy"
      :style="{ objectFit: fit }"
      @error="handleError"
      @load="handleLoad"
    >
    <div v-else class="program-poster__fallback">
      <span class="program-poster__badge">{{ badgeText }}</span>
      <strong class="program-poster__title">{{ titleText }}</strong>
      <small class="program-poster__subtitle">{{ subtitleText }}</small>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  src: {
    type: String,
    default: ''
  },
  alt: {
    type: String,
    default: ''
  },
  title: {
    type: String,
    default: ''
  },
  subtitle: {
    type: String,
    default: ''
  },
  badge: {
    type: String,
    default: ''
  },
  fit: {
    type: String,
    default: 'cover'
  },
  loading: {
    type: String,
    default: 'lazy'
  },
  referrerPolicy: {
    type: String,
    default: 'no-referrer'
  },
  tone: {
    type: String,
    default: 'warm'
  }
})

const loadFailed = ref(false)

const imageSrc = computed(() => props.src?.trim?.() || '')
const altText = computed(() => props.alt || props.title || '节目海报')
const badgeText = computed(() => props.badge || 'Javaup Live')
const titleText = computed(() => props.title || '热门演出')
const subtitleText = computed(() => props.subtitle || '现场演出推荐')

watch(() => props.src, () => {
  loadFailed.value = false
})

const handleError = () => {
  loadFailed.value = true
}

const handleLoad = () => {
  loadFailed.value = false
}
</script>

<style scoped lang="scss">
.program-poster {
  width: 100%;
  height: 100%;
  overflow: hidden;
  position: relative;
  display: block;
  background:
    radial-gradient(circle at top right, rgba(255, 255, 255, 0.2), transparent 34%),
    linear-gradient(155deg, rgba(255, 96, 61, 0.95), rgba(25, 58, 130, 0.96));

  &--cool {
    background:
      radial-gradient(circle at top right, rgba(255, 255, 255, 0.18), transparent 34%),
      linear-gradient(155deg, rgba(39, 155, 215, 0.95), rgba(33, 44, 98, 0.98));
  }
}

.program-poster__image {
  width: 100%;
  height: 100%;
  display: block;
}

.program-poster__fallback {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 8px;
  padding: 18px;
  color: #fff7f2;
  background:
    linear-gradient(180deg, rgba(12, 22, 46, 0.06), rgba(12, 22, 46, 0.78)),
    linear-gradient(155deg, rgba(255, 96, 61, 0.92), rgba(25, 58, 130, 0.94));
}

.program-poster--cool .program-poster__fallback {
  background:
    linear-gradient(180deg, rgba(12, 22, 46, 0.06), rgba(12, 22, 46, 0.78)),
    linear-gradient(155deg, rgba(39, 155, 215, 0.92), rgba(33, 44, 98, 0.96));
}

.program-poster__badge {
  width: fit-content;
  max-width: 100%;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.2);
  font-size: 12px;
  letter-spacing: 0.06em;
}

.program-poster__title {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-height: 1.4;
  font-size: 1rem;
}

.program-poster__subtitle {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  color: rgba(255, 247, 242, 0.86);
  line-height: 1.45;
}
</style>

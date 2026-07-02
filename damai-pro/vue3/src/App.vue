<template>
  <router-view :key="route.fullPath"/>
  <AiCustomerWidget />
</template>
<script setup  >
import {useRoute} from 'vue-router'
import AiCustomerWidget from '@/components/aiCustomerWidget/index.vue'
const route = useRoute();
</script>

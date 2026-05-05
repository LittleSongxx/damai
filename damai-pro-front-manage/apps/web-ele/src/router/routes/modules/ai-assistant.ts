import type { RouteRecordRaw } from 'vue-router';

import { IFrameView } from '#/layouts';

const aiAssistantUrl = import.meta.env.VITE_DAMAI_AI_WEB_URL || 'http://127.0.0.1:15174/assistant';

const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:bot-message-square',
      order: 5,
      title: 'AI智能助手',
    },
    name: 'AiAssistantRoot',
    path: '/aiAssistantRoot',
    children: [
      {
        path: '/aiAssistant',
        name: 'AiAssistant',
        meta: {
          iframeSrc: aiAssistantUrl,
          keepAlive: true,
          title: 'AI助手',
        },
        component: IFrameView,
      },
    ],
  },
];

export default routes;

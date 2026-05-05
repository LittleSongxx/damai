# damai-pro User Frontend

`damai-pro/vue3` is the customer-facing frontend of `damai-pro`. It covers program browsing, search, program details, ticket category selection, seat selection, login, order pages, and payment entry points.

## Positioning

This frontend handles user interaction and API calls only. Inventory deduction, seat locking, order creation, payment status, and authentication checks are implemented by the `damai-pro` backend services.

## Tech Stack

| Category | Technology |
| --- | --- |
| Framework | Vue 3.2, Vite 3 |
| UI | Element Plus, `@element-plus/icons-vue` |
| State and Routing | Pinia, Vue Router |
| Request and Security | Axios, CryptoJS, JSEncrypt, jsrsasign |
| Charts and Editor | ECharts, Vue Quill |

## Requirements

- Node.js and npm
- `damai-pro` backend is running, especially `damai-gateway-service:6085`
- Local infrastructure ports are prepared in `damai-pro/.env`

## Development

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 15173 --strictPort
```

When started by the workspace script, the port is controlled by `DAMAI_PRO_FRONTEND_PORT` in `damai-pro/.env`. The recommended local value is `15173`.

## Build

```bash
npm run build
npm run preview
```

## Development Proxy

`.env.development` provides the frontend proxy settings:

| Variable | Meaning |
| --- | --- |
| `VITE_APP_BASE_API` | Vite proxy prefix, currently `/barley-dev` |
| `VITE_APP_URL` | Gateway target, currently `http://127.0.0.1:6085` |
| `VITE_SIGN_FLAG` | Whether signed requests are enabled |
| `VITE_CODE` | Channel code, currently `0001` |
| `VITE_CREATE_ORDER_VERSION` | Order creation version, currently `4` for the async order flow |

`vite.config.js` strips `/barley-dev` and forwards requests to the gateway, so backend calls finally enter `/damai/**` routes of `damai-gateway-service`.

## Backend Dependencies

Make sure these services are healthy before local development:

- `damai-gateway-service:6085`
- `damai-user-service:6082`
- `damai-program-service:6086`
- `damai-order-service:8081`
- `damai-pay-service:6087`

## Troubleshooting

- **404 from APIs**: Check `VITE_APP_BASE_API`, proxy rewrite rules, and gateway routes.
- **Order creation fails**: Check `VITE_CREATE_ORDER_VERSION`, program service, order service, and RabbitMQ consumers.
- **Authentication fails**: Check login state, signature switch, channel code, and gateway filters.
- **Port conflict**: Use `--port 15173 --strictPort` or update `DAMAI_PRO_FRONTEND_PORT`.

## Related Docs

- [`../README.md`](../README.md): `damai-pro` backend overview.
- [`../docs/local-dev.md`](../docs/local-dev.md): Local development and validation guide.


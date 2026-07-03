# damai-ai Agent Runtime Architecture

## Boundary

`damai-ai` is organized as an Agent Runtime platform, not a single service package that owns every capability.

| Module | Responsibility |
| --- | --- |
| `damai-ai-domain` | Shared entities, DTOs, VOs, enums, request context, response contracts, domain events and artifact types. |
| `damai-ai-infra` | MySQL, MyBatis-Plus, Easy-ES, Redis/Redisson, MQ, tracing and `damai-pro` gateway adapters. |
| `damai-ai-runtime` | Runtime contracts, run/event/state/checkpoint vocabulary, capability registry SPI and policy SPI. |
| `damai-ai-business` | User purchase agent capabilities: show query, ticket-grade query, preview, approval, reservation and confirm. |
| `damai-ai-knowledge` | FAQ, RAG, evidence gating, citation and refusal paths for service knowledge. |
| `damai-ai-customer` | Customer-service orchestration: emotion handling, clarification, handoff, tickets and user-level ticket queries. |
| `damai-ai-ops` | Admin-only ops/data agent capabilities, RCA, metrics/log/trace and read-only NL2SQL. |
| `damai-ai-governance` | Evaluation, benchmark, prompt governance, quality gates, red-team and bad-case management. |
| `damai-core-service` | Spring Boot application, HTTP/SSE facade, wiring and aggregated configuration. |

## Dependency Direction

The allowed direction is:

`domain -> infra/runtime -> business/knowledge/customer/ops/governance -> core-service`

Business modules must not directly depend on each other. Cross-domain execution goes through runtime contracts, capability descriptors, artifacts and domain events.

## Runtime Core

The Runtime Core is responsible for input normalization, context assembly, model routing, action interpretation, state and artifact management, scheduling, reliability, security policy and replayable observability.

The shared runtime vocabulary is:

- `AgentRequest`: user, scene, input, context, budget, SLA and risk hint.
- `AgentRun`: run identity, route, status, stage and checkpoint pointer.
- `AgentEvent`: SSE, audit and replay event.
- `CapabilityDescriptor`: Function Tool, MCP Server, A2A Remote Agent, Skill and Plugin metadata.
- `ActionArtifact`: approval/replay artifact for purchase preview, SQL query, RAG evidence and ops diagnosis.
- `PolicyDecision`: allow, require approval or deny.

## Control Plane And Data Plane

Data plane APIs serve normal user interaction: assistant chat, purchase preview/approval and customer-service answers.

Control plane APIs live under `/assistant/admin/**`: knowledge governance, prompt versions, eval, benchmark, red-team, bad cases, ops diagnosis and NL2SQL. Experimental strategies such as general AgentLoop, MultiAgent, A2A and advanced RAG variants should be opt-in by profile and kept out of the normal user path.

## CAP Policy

- Purchase agent: prefer consistency and partition tolerance. Unknown confirm result enters `UNKNOWN` and must not duplicate orders.
- Customer-service agent: prefer availability and partition tolerance. RAG outage degrades to FAQ, cache, clarification or handoff.
- Ops/data agent: prefer consistency and partition tolerance. Missing permission, unsafe SQL or untrusted data results in refusal.

All queue messages, checkpoints, approvals and action artifacts need idempotency keys and replayable events.

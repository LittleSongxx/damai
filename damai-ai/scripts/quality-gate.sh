#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT_DIR/target"
REPORT_FILE="$REPORT_DIR/quality-gate-summary.json"
PREVIOUS_REPORT_FILE="$REPORT_DIR/quality-gate-summary.previous.json"
LOG_DIR="$REPORT_DIR/quality-gate-logs"

BACKEND_TESTS="${DAMAI_AI_BACKEND_TESTS:-}"
FRONTEND_TESTS="${DAMAI_AI_FRONTEND_TESTS:-}"
BACKEND_TEST_SCOPE="all"
FRONTEND_TEST_SCOPE="all"
BACKEND_TEST_SELECTION_COUNT="null"
FRONTEND_TEST_SELECTION_COUNT="null"

if [[ -n "$BACKEND_TESTS" ]]; then
  BACKEND_TEST_SCOPE="env-selected"
  BACKEND_TEST_SELECTION_COUNT="$(python3 - <<PY
print(len([item for item in "$BACKEND_TESTS".split(",") if item.strip()]))
PY
)"
fi

if [[ -n "$FRONTEND_TESTS" ]]; then
  FRONTEND_TEST_SCOPE="env-selected"
  FRONTEND_TEST_SELECTION_COUNT="$(python3 - <<PY
print(len([item for item in "$FRONTEND_TESTS".split(" ") if item.strip()]))
PY
)"
fi

mkdir -p "$REPORT_DIR" "$LOG_DIR"
if [[ -f "$REPORT_FILE" ]]; then
  cp "$REPORT_FILE" "$PREVIOUS_REPORT_FILE"
fi

started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
previous_status="NONE"
if [[ -f "$PREVIOUS_REPORT_FILE" ]]; then
  previous_status="$(python3 - <<PY
import json
try:
    print(json.load(open("$PREVIOUS_REPORT_FILE")).get("status", "UNKNOWN"))
except Exception:
    print("UNKNOWN")
PY
)"
fi
backend_tests_status="NOT_RUN"
backend_compile_status="NOT_RUN"
frontend_tests_status="NOT_RUN"
overall_status="PASS"
CHECK_RESULTS=()

run_backend_tests() {
  if [[ -n "$BACKEND_TESTS" ]]; then
    mvn -pl damai-core-service -Dtest="$BACKEND_TESTS" test
    return
  fi
  mvn -pl damai-core-service test
}

run_frontend_tests() {
  if [[ -n "$FRONTEND_TESTS" ]]; then
    npm test -- $FRONTEND_TESTS
    return
  fi
  npm test
}

run_step() {
  local name="$1"
  local key="$2"
  local log_file="$3"
  shift
  shift
  shift
  echo "==> $name"
  local step_start step_end duration_ms status
  step_start="$(date +%s%3N)"
  if "$@" >"$log_file" 2>&1; then
    cat "$log_file"
    echo "PASS: $name"
    step_end="$(date +%s%3N)"
    duration_ms=$((step_end - step_start))
    CHECK_RESULTS+=("{\"key\":\"$key\",\"name\":\"$name\",\"status\":\"PASS\",\"durationMs\":$duration_ms,\"log\":\"$log_file\"}")
    return 0
  fi
  cat "$log_file" >&2
  echo "FAIL: $name" >&2
  overall_status="FAIL"
  step_end="$(date +%s%3N)"
  duration_ms=$((step_end - step_start))
  CHECK_RESULTS+=("{\"key\":\"$key\",\"name\":\"$name\",\"status\":\"FAIL\",\"durationMs\":$duration_ms,\"log\":\"$log_file\"}")
  return 1
}

cd "$ROOT_DIR"

if run_step "backend $BACKEND_TEST_SCOPE test suite" "backendTests" "$LOG_DIR/backend-tests.log" \
  run_backend_tests; then
  backend_tests_status="PASS"
else
  backend_tests_status="FAIL"
fi

if run_step "backend compile" "backendCompile" "$LOG_DIR/backend-compile.log" \
  mvn -pl damai-core-service -DskipTests compile; then
  backend_compile_status="PASS"
else
  backend_compile_status="FAIL"
fi

cd "$ROOT_DIR/vue"
if run_step "frontend $FRONTEND_TEST_SCOPE test suite" "frontendTests" "$LOG_DIR/frontend-tests.log" \
  run_frontend_tests; then
  frontend_tests_status="PASS"
else
  frontend_tests_status="FAIL"
fi

completed_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
backend_test_classes_json="$(BACKEND_TESTS_ENV="$BACKEND_TESTS" python3 - <<'PY'
import json
import os

tests = [item.strip() for item in os.environ.get("BACKEND_TESTS_ENV", "").split(",") if item.strip()]
print(json.dumps(tests, ensure_ascii=False) if tests else "null")
PY
)"
frontend_test_files_json="$(FRONTEND_TESTS_ENV="$FRONTEND_TESTS" python3 - <<'PY'
import json
import os

tests = [item.strip() for item in os.environ.get("FRONTEND_TESTS_ENV", "").split(" ") if item.strip()]
print(json.dumps(tests, ensure_ascii=False) if tests else "null")
PY
)"
checks_json="$(IFS=,; echo "[${CHECK_RESULTS[*]}]")"

cat > "$REPORT_FILE" <<JSON
{
  "status": "$overall_status",
  "previousStatus": "$previous_status",
  "startedAt": "$started_at",
  "completedAt": "$completed_at",
  "backendTests": "$backend_tests_status",
  "backendCompile": "$backend_compile_status",
  "frontendTests": "$frontend_tests_status",
  "backendTestScope": "$BACKEND_TEST_SCOPE",
  "frontendTestScope": "$FRONTEND_TEST_SCOPE",
  "backendTestClasses": $backend_test_classes_json,
  "frontendTestFiles": $frontend_test_files_json,
  "coverageSummary": {
    "backendTestSelectionCount": $BACKEND_TEST_SELECTION_COUNT,
    "frontendTestSelectionCount": $FRONTEND_TEST_SELECTION_COUNT,
    "domains": [
      "auth",
      "run-graph",
      "mcp-governance",
      "mcp-boundary",
      "prompt-governance",
      "prompt-release-plan",
      "assistant-eval-control-plane",
      "rag-evalops",
      "rag-retrieval-boundary",
      "rag-ingestion-quality",
      "nl2sql-safety",
      "aiops-rca",
      "red-team"
    ]
  },
  "capabilityEvidence": [
    {
      "capability": "assistant-eval-control-plane",
      "evidence": "Unified EvalOps start/status interface for RAG, NL2SQL, red-team, and quality-gate suites",
      "interfaces": ["POST /assistant/evals/{suite}/run", "GET /assistant/evals/{suite}/runs/{evalRunId}"],
      "tests": ["AssistantEvalRunServiceTest", "AssistantHub.spec.js", "api.spec.js"]
    },
    {
      "capability": "rag-reindex-jobs",
      "evidence": "Async knowledge-base reindex jobs with task history surfaced in the admin workspace",
      "interfaces": ["POST /ai/rag/reindex-jobs", "GET /ai/rag/ingestion/tasks"],
      "tests": ["DocumentLifecycleServiceTest", "RagIngestionConsumerTest", "AssistantHub.spec.js"]
    },
    {
      "capability": "rag-retrieval-boundary",
      "evidence": "Knowledge retrieval, RAG evaluation fallback, and ingestion coverage checks call a single RagRetrievalFacade that owns MultiChannelRetrievalEngine access, document resolution, and retrieval metadata",
      "interfaces": ["KnowledgeRetrievalOrchestrator.retrieve", "RagEvalService.retrieveEvalEvidence", "IngestionQualityService.runQualityReport", "RagRetrievalFacade.retrieve"],
      "tests": ["RagRetrievalFacadeTest", "KnowledgeRetrievalOrchestratorTest", "RagEvalServiceTest", "IngestionQualityServiceTest"]
    },
    {
      "capability": "rag-closure-plan",
      "evidence": "RAG EvalOps exposes baseline readiness, release blocking, candidate eval cases, and bad-case closure workflow",
      "interfaces": ["GET /api/rag-eval/runs/{evalRunId}/report", "GET /assistant/admin/quality-gates/latest"],
      "tests": ["RagEvalOpsServicesTest", "AiQualityGateServiceTest", "AssistantHub.spec.js"]
    },
    {
      "capability": "nl2sql-response-contract",
      "evidence": "NL2SQL returns a stable status/sql/evidence/safetyReport/executionPlan/resultPreview/maskedColumns/repairTrace contract with EXPLAIN cost guard, timeout, row limit, and cost metrics surfaced",
      "interfaces": ["POST /assistant/evals/nl2sql/run", "GET /assistant/admin/quality-gates/latest"],
      "tests": ["Nl2SqlOrchestratorPolicyTest", "Nl2SqlEvalServiceTest", "AiQualityGateServiceTest"]
    },
    {
      "capability": "run-graph-checkpoint",
      "evidence": "Run graph, checkpoint replay, risk nodes, and audit timeline for recoverable agent runs",
      "interfaces": ["GET /assistant/runs/{runId}/graph", "POST /assistant/runs/{runId}/resume", "POST /assistant/runs/{runId}/replay"],
      "tests": ["AssistantRunGraphServiceTest", "AssistantHub.spec.js"]
    },
    {
      "capability": "mcp-protocol-governance",
      "evidence": "MCP tools/resources/prompts are separated into explicit allowlists; resource reads and prompt rendering require admin, high-risk confirmation, and audit records",
      "interfaces": ["GET /assistant/admin/mcp/governance", "POST /assistant/admin/mcp/resources/read", "POST /assistant/admin/mcp/prompts/render"],
      "tests": ["McpToolGovernanceServiceTest", "McpBoundaryServiceTest", "AiQualityGateServiceTest", "AssistantHub.spec.js", "api.spec.js"]
    },
    {
      "capability": "prompt-release-plan",
      "evidence": "Prompt/config releases generate a quality-gate evidence plan before publish and persist baseline, rollout, rollback, and gate evidence in release records",
      "interfaces": ["POST /api/prompt-versions/release-plan", "POST /api/prompt-versions/publish"],
      "tests": ["PromptVersionServiceTest", "PromptGovernance.spec.js", "api.spec.js"]
    },
    {
      "capability": "security-red-team",
      "evidence": "Admin-gated governance endpoints and regression coverage for prompt injection, NL2SQL injection, and tool overreach",
      "interfaces": ["/api/rag-eval/**", "/api/nl2sql-eval/**", "/assistant/evals/**", "/ai/rag/**"],
      "tests": ["AiAuthenticationInterceptorTest", "RedTeamRegressionTest"]
    },
    {
      "capability": "aiops-rca",
      "evidence": "Fault-injection scenarios and RCA evidence bundles correlate logs, metrics, traces, spanId, topology, changes, SLO, and suggested actions",
      "interfaces": ["POST /assistant/admin/aiops/rca-evidence", "POST /assistant/admin/aiops/fault-scenarios/{scenarioId}/inject"],
      "tests": ["OpsRcaEvidenceServiceTest", "AiOpsFaultInjectionServiceTest", "AssistantHub.spec.js"]
    }
  ],
  "checks": $checks_json
}
JSON

echo "Quality gate report: $REPORT_FILE"
test "$overall_status" = "PASS"

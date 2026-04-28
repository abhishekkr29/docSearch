#!/usr/bin/env bash
# Walks the full docSearch API surface with curl. Mirrors the Postman
# collection at postman/docsearch.postman_collection.json.
#
# Usage:  BASE_URL=http://localhost:8080 ./scripts/api-examples.sh
#
# Run scripts/seed.sh first if you want there to be data to find.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_A="${TENANT_A:-tenantId}"
TENANT_B="${TENANT_B:-globex}"

hr() { printf '\n\033[1m── %s ──\033[0m\n' "$1"; }

hr "1. Health (drills into OpenSearch / Redis / Postgres)"
curl -sS "$BASE_URL/actuator/health" | jq .

hr "2. Index a document under tenant A — capture the id"
DOC_ID=$(curl -sS -X POST "$BASE_URL/documents" \
	-H 'Content-Type: application/json' -H "X-Tenant-ID: $TENANT_A" \
	-d '{
		"title":   "Distributed Document Search Service",
		"content": "A prototype of a distributed document search service capable of searching through millions of documents with sub-second response times. This service demonstrate enterprise-grade architectural patterns including multi-tenancy, fault tolerance, and horizontal scalability.",
		"author":  "ezio",
		"tags":    "distributed,systems,primer"
	}' | jq -r '.id')
echo "indexed id=$DOC_ID"

hr "3. Index a document under tenant B (different content)"
curl -sS -X POST "$BASE_URL/documents" \
	-H 'Content-Type: application/json' -H "X-Tenant-ID: $TENANT_B" \
	-d '{
		"title":   "Internal Confidential Memo",
		"content": "This document belongs only to globex and must never be returned in a search by tenant '"$TENANT_A"'.",
		"author":  "dave",
		"tags":    "confidential,memo"
	}' | jq '{id, title, tenant}'

hr "4. Search as tenant A"
curl -sS "$BASE_URL/search?q=distributed&page=0&size=10" \
	-H "X-Tenant-ID: $TENANT_A" | jq '{total, hits: (.hits | length)}'

hr "5. Pagination — size=2, page=1"
curl -sS "$BASE_URL/search?q=distributed&page=1&size=2" \
	-H "X-Tenant-ID: $TENANT_A" | jq '{total, page: .page, size: .size, hits: (.hits | length)}'

hr "6. Tenant isolation — A queries for a term only present in B"
curl -sS "$BASE_URL/search?q=confidential" \
	-H "X-Tenant-ID: $TENANT_A" | jq '{total, hits: [.hits[]?.title]}'

hr "7. Same query as tenant B — sees its own doc"
curl -sS "$BASE_URL/search?q=confidential" \
	-H "X-Tenant-ID: $TENANT_B" | jq '{total, hits: [.hits[]?.title]}'

hr "8. Get document by id (cache hit on second call within 5 min)"
curl -sS "$BASE_URL/documents/$DOC_ID" -H "X-Tenant-ID: $TENANT_A" | jq .

hr "9. Error: missing X-Tenant-ID → 400"
curl -sS -o /tmp/docsearch_err.json -w 'HTTP %{http_code}\n' \
	"$BASE_URL/search?q=anything" || true
jq . /tmp/docsearch_err.json || cat /tmp/docsearch_err.json

hr "10. Error: empty q → 400"
curl -sS -o /tmp/docsearch_err.json -w 'HTTP %{http_code}\n' \
	-H "X-Tenant-ID: $TENANT_A" "$BASE_URL/search?q=" || true
jq . /tmp/docsearch_err.json || cat /tmp/docsearch_err.json

hr "11. Error: get a nonexistent id → 404"
curl -sS -o /tmp/docsearch_err.json -w 'HTTP %{http_code}\n' \
	-H "X-Tenant-ID: $TENANT_A" \
	"$BASE_URL/documents/00000000-0000-0000-0000-000000000000" || true
jq . /tmp/docsearch_err.json || cat /tmp/docsearch_err.json

hr "12. Error: index with empty title → 400 (bean validation)"
curl -sS -o /tmp/docsearch_err.json -w 'HTTP %{http_code}\n' \
	-X POST "$BASE_URL/documents" \
	-H 'Content-Type: application/json' -H "X-Tenant-ID: $TENANT_A" \
	-d '{"title":"","content":"missing title should be rejected by @NotBlank"}' || true
jq . /tmp/docsearch_err.json || cat /tmp/docsearch_err.json

hr "13. Rate limit demo — burst 110 requests, expect 429s with headers"
hits_200=0; hits_429=0
for i in $(seq 1 110); do
	code=$(curl -sS -o /dev/null -w '%{http_code}' \
		"$BASE_URL/search?q=distributed" -H "X-Tenant-ID: $TENANT_A")
	if [ "$code" = "200" ]; then hits_200=$((hits_200+1));
	elif [ "$code" = "429" ]; then hits_429=$((hits_429+1)); fi
done
echo "200=$hits_200  429=$hits_429"
echo "Inspect 429 headers explicitly:"
curl -sS -D - -o /dev/null "$BASE_URL/search?q=distributed" \
	-H "X-Tenant-ID: $TENANT_A" | grep -iE '^(http|retry-after|x-ratelimit)'

hr "14. Cleanup — delete the indexed doc"
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' \
	-X DELETE -H "X-Tenant-ID: $TENANT_A" "$BASE_URL/documents/$DOC_ID"

hr "Done"

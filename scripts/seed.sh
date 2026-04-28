#!/usr/bin/env bash
# Seeds the running API with sample documents across two tenants.
#
# Usage:  BASE_URL=http://localhost:8080 ./scripts/seed.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "Seeding tenant=tenantId ..."
curl -fsS -X POST "$BASE_URL/documents" \
	-H "Content-Type: application/json" -H "X-Tenant-ID: tenantId" \
	-d '{"title":"Distributed Document Search Service","content":"A prototype of a distributed document search service capable of searching through millions of documents with sub-second response times. This service demonstrate enterprise-grade architectural patterns including multi-tenancy, fault tolerance, and horizontal scalability.","author":"ezio","tags":"distributed,systems,primer"}' >/dev/null
curl -fsS -X POST "$BASE_URL/documents" \
	-H "Content-Type: application/json" -H "X-Tenant-ID: tenantId" \
	-d '{"title":"Search Engine Internals","content":"Inverted indices, BM25, term frequency, and query planning explained from the ground up.","author":"bob","tags":"search,engines,bm25"}' >/dev/null
curl -fsS -X POST "$BASE_URL/documents" \
	-H "Content-Type: application/json" -H "X-Tenant-ID: tenantId" \
	-d '{"title":"Caching Strategies","content":"Patterns for read-through, write-through, and write-behind caching using Redis and CDNs.","author":"carol","tags":"cache,redis,cdn"}' >/dev/null

echo "Seeding tenant=globex ..."
curl -fsS -X POST "$BASE_URL/documents" \
	-H "Content-Type: application/json" -H "X-Tenant-ID: globex" \
	-d '{"title":"Internal Confidential Memo","content":"This document belongs only to globex and must never be returned in a search by tenant tenantId.","author":"dave","tags":"confidential,memo"}' >/dev/null

echo "Done. Try:"
echo "  curl '$BASE_URL/search?q=distributed' -H 'X-Tenant-ID: tenantId' | jq ."
echo "  curl '$BASE_URL/search?q=memo'        -H 'X-Tenant-ID: globex' | jq ."

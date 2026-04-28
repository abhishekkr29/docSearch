# Enterprise Experience Showcase

> The sections below draw on my work at **WIOM** (Mar 2022 – Jun 2025,
> Senior Backend Engineer / founding engineer) and earlier at
> **Samsung R&D Institute, Noida**. Stack at WIOM was primarily Java + Spring Boot +
> .NET Core + C# on SQL Server + DynamoDB, with Azure Service Bus, SQS for
> async messaging.

## 1. A similar distributed system I've built

At **WIOM** I was the founding backend engineer who took the platform
from zero to MVP to **150K+ customers**. The architecture deliberately
started life as a **modular monolith** so a small team could ship
quickly with one deploy unit and a single transactional database.
Once growth justified independent deploys and team autonomy, I led the
**migration to microservices**, owning the design and rollout of three
core services:

- **Customer Service** — full customer lifecycle: onboarding, plan
  creation, coupons, recharges, and juspay-backed mobile payments.
  Outbound communications (FCM push, SMS, WhatsApp via Gupshup) were
  fan-out from **Azure Service Bus** topics, decoupling the customer
  flow from third-party latency and failure modes.
- **Booking & Installation Service** — lead intake plus location-based
  field-partner allocation, choreographing installation tasks and
  automated account/user provisioning across multiple downstream
  services.
- **Ticketing Service** — support workflow with task tracking,
  comments, resolver assignment, SLA-based resolution timelines, and
  partner incentive/bonus computation tied to those SLAs.

The same patterns the docSearch prototype uses — a stateless service
tier, async messaging for fan-out, per-tenant isolation, and a clean
separation between source-of-truth storage and a denormalised
read-side — are the patterns I shipped at WIOM at multi-tenant scale.

## 2. A performance optimisation that mattered

> *Anchored to a real WIOM workload but I want to confirm the exact
> numbers from internal dashboards before quoting them.*

The **autopay renewal pipeline** at WIOM contributed roughly **70%** of
company revenue, so its throughput and latency were business-critical.
The original implementation processed renewals one customer at a time:
a scheduler enqueued individual messages onto **Azure Service Bus**, a
worker fan-out picked them up, called juspay per customer, and wrote the
result back to **SQL Server** in a single-row transaction. This was
fine at MVP scale but degraded as the renewal cohort grew — payment
window pressure, SQL Server lock contention, and juspay rate limiting
all started to compound.

The fix had three parts:

1. **Batched the writeback path.** Result rows were grouped by
   `(plan_id, status)` and committed in batches per transaction, eliminating the per-row
   transaction overhead.
2. **Throttled the upstream fan-out** so juspay saw a smoothed request
   rate (capped to its documented limit + a small jitter), removing
   the retry storm that had been masking the real win.
3. **Pre-staged eligible customers** the night before the renewal
   window, so the morning job did the comparatively cheap work of
   *executing* renewals rather than discovering them.

End-to-end renewal-window time dropped from ~2 hrs to
~30 mins, the autopay success rate moved up by
4%, and the alarms stopped firing on the renewal
window entirely.

## 3. A critical production incident I resolved

A deploy went out with a **misconfigured producer**
that started publishing **bulk-batched messages** onto the async queue
feeding the renewal and notification fan-out workers, instead of one
message per unit of work. The fan-out workers weren't shaped to
process bulk envelopes at the rate they were arriving, so consumption
fell behind production almost immediately. Queue depth spiked, the
fan-out fleet saturated, and renewals plus downstream notifications
ran roughly ~30 mins behind real time across the affected
window.

The chain was:

1. **Detection** — alert fired on queue depth and consumer lag
   breaching their thresholds. From the dashboards it was clear the
   producers were healthy but the consumer fleet was saturated, and
   that the *shape* of messages on the queue was off — bulk batches
   instead of the per-item envelopes the workers expected.
2. **Containment** — a straight rollback would have been slow (the
   bad deploy had already buried a large number of in-flight
   messages), so I split the response in two parallel tracks:
   - **Drain side:** scaled the fan-out worker fleet out
     horizontally to add raw consumption capacity.
   - **Reshape side:** deployed a small **Lambda** that consumed the
     choked queue, unpacked each bulk envelope into its constituent
     items, and republished them onto a separate queue that the
     (now-scaled-out) fan-out workers could process at their normal
     per-message rate. This bridged the bad shape without losing
     messages.
3. **Remediation** — fixed the producer configuration and redeployed
   in parallel with the drain. Once the reshape Lambda's source queue
   drained to zero and the producer was emitting the correct shape
   again, traffic cut back over to the original fan-out path and the
   worker fleet was scaled back down. No customer-facing replay or
   backfill was needed — the Lambda had carried the entire affected
   window end-to-end.
4. **Prevention** — added alerts at the **producer** on
   message-size and message-count-per-publish (the leading indicator
   of this class of misconfig, hours earlier than the consumer-side
   lag signal); made the relevant config flag default-safe, so a
   missing or empty value can't silently flip the producer into bulk
   mode; promoted the "reshape via Lambda" approach into the runbook,
   since translating a bad-shape queue into a good-shape queue turned
   out to be a generally useful incident-response pattern.

The sharpest lesson was that the fastest path back to green wasn't a
rollback — it was treating the bad queue as *data* and writing a
small consumer to translate it into the shape the rest of the system
already understood. Rollback would have been slower, would have lost
visibility into messages already accepted from upstream, and would
have spent its time fighting the deploy system instead of the
backlog.

## 4. An architectural decision that balanced competing concerns

The clearest example is the **modular monolith → microservices**
trajectory at WIOM itself.

When we started, the obvious-from-the-outside answer would have been
"build microservices day one — it's a startup, set the right
foundations." I argued the opposite, and the team agreed: a small
founding team optimising for *time-to-MVP* should ship a **modular
monolith** with strong internal module boundaries, not a distributed
system with weak ones. The monolith gave us:

- one deploy pipeline to keep alive, not seven;
- ACID transactions across what would later become service
  boundaries — invaluable while the domain model was still moving;
- a single database to back-up, restore, and reason about.

We paid for that with the *known* future cost of having to split
later, and the discipline of keeping module boundaries clean enough
that the split would actually be possible. Concretely: each future
service was a separate package with its own data tables, and
inter-module calls went through interfaces, not direct repository
access.

When growth justified the cost — multiple teams, deploy contention,
blast-radius concerns — we extracted the services in the order that
matched the actual pain (Customer first, Booking & Installation
second, Ticketing third). Async communication moved to **Azure
Service Bus**, and each extraction was a separate, reversible step
rather than a big-bang rewrite.

Two years on, the trade-off has aged well: we got to 150K+ customers
without distributed-system tax during the period when product-market
fit was the only thing that mattered, and we paid the
microservices-migration tax exactly when the business benefits
(independent deploys, team autonomy) had become real and measurable.

---

## AI tool usage note

I used Claude (Anthropic's CLI) to scaffold the Spring Boot project
layout (Gradle build, application.yml, base packages, the Spring
Boot Web slice test), and to draft boilerplate DTOs, the
GlobalExceptionHandler, and the OpenSearch client config. I then
reviewed and adjusted the generated code, made the architectural
choices myself (multi-tenancy approach, per-tenant index strategy,
Redis-backed Lua-scripted rate limiter, sync-now/async-later
indexing), and wrote the architecture and production-readiness
analysis to reflect my own opinions and prior experience. The AI
accelerated the typing — not the thinking.

# AEM Bulk Schema.org Enrichment Tool

## Overview

The Bulk Schema.org Enrichment Tool allows AEM authors and administrators to apply schema.org JSON-LD structured data to an entire content tree in a single operation — without opening each page's properties manually.

It builds on the per-page schema.org system described in [SCHEMA_ORG_JSONLD_IMPLEMENTATION.md](./SCHEMA_ORG_JSONLD_IMPLEMENTATION.md).

---

## Access

Navigate to:
```
http://<author-host>/apps/wknd/tools/seoBulkManager.html
```

The page appears as a standard AEM shell UI (same chrome as the rest of AEM's Tools console).

---

## How It Works

1. Author fills in the form (root path, action, options) and clicks **Start Enrichment**.
2. The browser POSTs to `POST /bin/wknd/seo/bulk.json`, which queues an async **Sling Job** and returns a `jobId`.
3. The browser begins polling `GET /bin/wknd/seo/bulk.json?jobId=<id>` every 3 seconds.
4. The Sling Job (`BulkSchemaJobConsumer`) runs in the background:
   - BFS-traverses `cq:Page` resources under the chosen root path.
   - For each page, applies the chosen action.
   - Writes progress to `/var/wknd/seo/jobs/<jobId>` in the JCR.
5. The browser renders a live progress bar and error log; polling stops when the job finishes.

---

## Form Options

| Field | Values | Description |
|-------|--------|-------------|
| Root Path | `/content/…` | Subtree to process. All `cq:Page` descendants are targeted. |
| Action | `Apply defaults` / `AI-generate` | How schema is applied (see below). |
| Schema Type | Auto / WebPage / Article / BlogPosting / … | For AI action: type to generate. "Auto" uses the existing page setting or falls back to WebPage. |
| AI Provider | Default / openai / gemini / ollama | Which AI provider to use (for AI action only). |
| Skip existing | checkbox (default: on) | When checked, pages that already have `seo/enabled=true` are skipped. |

---

## Actions

### Apply Defaults

Sets two JCR properties on every matching page's `jcr:content/seo` node:
```
seo/enabled = true   (Boolean)
seo/mode    = merge  (String)
```

The page then inherits all schema values from its **template policy** at runtime — no AI call needed. This is the fastest option and works without an AI provider configured.

Use this to "switch on" schema.org for an entire site or language branch in seconds, relying on template-level defaults already authored.

### AI-Generate

For each page:
1. Extracts visible text from the page content tree (up to 12,000 characters).
2. Builds a `GenerationRequest` with the page's title, description, locale, and body text.
3. Calls the configured AI provider (OpenAI / Gemini / Ollama).
4. Writes the generated JSON-LD to `seo/jsonLd`, plus sets `seo/enabled=true` and `seo/mode=merge`.

The generated JSON-LD takes precedence over structured fields at render time (same priority rule as manual per-page authoring).

> **Note**: For large trees, AI generation makes one API call per page. A 500-page run may take considerable time depending on provider latency. A configurable delay between calls (`aiRequestDelayMs`, default 200 ms) respects API rate limits. The hard limit `maxPagesPerRun` (default 5,000) prevents runaway jobs.

---

## Skip Existing Behaviour

| `skipExisting` | Page state | Result |
|----------------|------------|--------|
| `true` (default) | `seo/enabled = true` already | Skipped — existing schema preserved |
| `true` | No `seo` node / `enabled = false` | Processed |
| `false` | Any | Always processed (overwrites existing schema) |

---

## Job Progress & History

While a job runs, the UI shows:
- Status badge: `QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED`
- Progress bar: processed / total pages
- Summary: succeeded, failed, total
- Collapsible error list (up to 50 entries, format: `pagePath: error message`)

Recent jobs are listed at `GET /bin/wknd/seo/bulk.json?action=list` (last 20, sorted by start time).

Raw progress data is stored in the JCR at `/var/wknd/seo/jobs/<jobId>` on the author instance. This path is not replicated to publish.

---

## Architecture

```
Browser (seoBulkManager.html)
  POST /bin/wknd/seo/bulk.json  →  BulkSchemaServlet
                                     JobManager.addJob("wknd/seo/bulk", props)
  GET  /bin/wknd/seo/bulk.json?jobId=…  →  BulkSchemaServlet
                                             reads /var/wknd/seo/jobs/{jobId}

Sling Job Worker Thread
  BulkSchemaJobConsumer (topic: wknd/seo/bulk)
    service resolver: wknd-seo-bulk-service
    PageTextExtractor.extractBodyText()   ← shared with SchemaAiServlet
    SchemaAiProvider.generate()           ← existing AI provider SPI
    writes jcr:content/seo/*             ← same nodes as per-page dialog
    writes /var/wknd/seo/jobs/{jobId}     ← progress tracking
```

---

## Key Files

| Component | Path |
|-----------|------|
| Job Consumer | `core/src/main/java/…/seo/bulk/BulkSchemaJobConsumer.java` |
| REST Servlet | `core/src/main/java/…/seo/servlets/BulkSchemaServlet.java` |
| Job DTO | `core/src/main/java/…/seo/bulk/BulkJobRequest.java` |
| Text Extractor | `core/src/main/java/…/seo/PageTextExtractor.java` |
| Shell Page | `ui.apps/…/apps/wknd/tools/seoBulkManager/jcr:content/.content.xml` |
| Client Library | `ui.apps/…/clientlibs/clientlib-seo-bulk/js/bulk-manager.js` |
| Service User | `ui.config/…/config.author/org.apache.sling.jcr.repoinit.RepositoryInitializer~wknd-seo-bulk.cfg.json` |
| Job Config | `ui.config/…/config.author/com.adobe.aem.guides.wknd.core.seo.bulk.BulkSchemaJobConsumer.cfg.json` |

---

## OSGi Configuration

`BulkSchemaJobConsumer` is configurable via OSGi (`config.author`):

| Property | Default | Description |
|----------|---------|-------------|
| `maxPagesPerRun` | 5000 | Hard cap to prevent runaway jobs. |
| `maxErrorsBeforeAbort` | 100 | Abort the job after this many consecutive errors. |
| `progressSaveIntervalPages` | 10 | How often to persist progress to JCR. |
| `aiRequestDelayMs` | 200 | Delay (ms) between AI provider calls. |

---

## After Enrichment

After a bulk run completes, pages are updated on the **author** instance only. To make schema.org visible on publish:

1. Replicate the affected pages via the AEM Replication queue, or
2. Use a Bulk Activation (from the Sites console) on the processed subtree.

The JSON-LD is rendered at request time by `SeoSchemaModelImpl` — no further steps are needed for correct rendering once properties are replicated.

---

## Validation Checklist

1. Bundle `aem-guides-wknd.core` is **Active** in `/system/console/bundles`.
2. Service user `wknd-seo-bulk-service` exists in `/system/console/serviceusers`.
3. Navigate to `http://localhost:4502/apps/wknd/tools/seoBulkManager.html` — form renders.
4. Submit `defaults` action on a small subtree → progress reaches `COMPLETED`.
5. Inspect a processed page in CRX DE: `jcr:content/seo/enabled = true`, `mode = merge`.
6. View page source in preview — `<script type="application/ld+json">` present.
7. Submit `ai` action on a single page — `jcr:content/seo/jsonLd` populated with valid JSON-LD.

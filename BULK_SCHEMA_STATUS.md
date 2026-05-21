# Bulk Schema.org Enrichment — Implementation Status

## Status: COMPLETE ✓

All implementation files have been written. Ready to build and deploy.

---

## Files Created

| File | Description |
|------|-------------|
| `BULK_SCHEMA_ENRICHMENT.md` | User-facing feature documentation |
| `core/…/seo/PageTextExtractor.java` | New `@ProviderType` interface for page text extraction |
| `core/…/seo/impl/PageTextExtractorImpl.java` | OSGi `@Component` implementation (logic extracted from `SchemaAiServlet`) |
| `core/…/seo/servlets/SchemaAiServlet.java` | **Refactored**: removed 3 private text methods; now delegates to `PageTextExtractor` via `@Reference` |
| `ui.config/…/config.author/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~wknd-seo-bulk.cfg.json` | Maps subservice `wknd-seo-bulk` to JCR service user `wknd-seo-bulk-service` |
| `ui.config/…/config.author/org.apache.sling.jcr.repoinit.RepositoryInitializer~wknd-seo-bulk.cfg.json` | Creates `wknd-seo-bulk-service` user; grants read on `/content`, write on `/content/wknd` and `/var/wknd/seo/jobs`; creates `/var/wknd/seo/jobs` path |
| `core/…/seo/bulk/BulkJobRequest.java` | Shared constants DTO: job topic, property keys, status values, progress node names |

| `core/…/seo/bulk/BulkSchemaJobConsumer.java` | Sling Job consumer: BFS traversal, defaults/AI action, progress writes |
| `core/…/seo/servlets/BulkSchemaServlet.java` | REST API: POST submit job, GET poll progress, GET list jobs |
| `ui.config/…/config.author/com.adobe.aem.guides.wknd.core.seo.bulk.BulkSchemaJobConsumer.cfg.json` | OSGi defaults for the consumer |
| `ui.apps/…/apps/wknd/tools/seoBulkManager/.content.xml` | cq:Page wrapper |
| `ui.apps/…/apps/wknd/tools/seoBulkManager/jcr:content/.content.xml` | Granite UI page shell + full form tree inline |
| `ui.apps/…/clientlibs/clientlib-seo-bulk/.content.xml` | Clientlib folder (category: wknd.seo.bulk) |
| `ui.apps/…/clientlibs/clientlib-seo-bulk/js/js.txt` | JS manifest |
| `ui.apps/…/clientlibs/clientlib-seo-bulk/js/bulk-manager.js` | Submit handler, CSRF fetch, polling loop, progress UI |
| `ui.apps/…/META-INF/vault/filter.xml` | Added `/apps/wknd/tools` filter |

---

## Remaining (none)

### ~~1. `BulkSchemaJobConsumer.java`~~ — DONE
**Path**: `core/src/main/java/com/adobe/aem/guides/wknd/core/seo/bulk/BulkSchemaJobConsumer.java`

- `@Component(service = JobConsumer.class, property = { "job.topics=wknd/seo/bulk" })`
- `@Designate(ocd = BulkSchemaJobConsumer.Config.class)`
- References: `ResourceResolverFactory`, `PageTextExtractor`, dynamic multi `SchemaAiProvider[]`
- Config properties: `maxPagesPerRun` (5000), `maxErrorsBeforeAbort` (100), `progressSaveIntervalPages` (10), `aiRequestDelayMs` (200)
- Algorithm:
  1. Open service resolver (`subservice = "wknd-seo-bulk"`)
  2. Write `/var/wknd/seo/jobs/{jobId}` with `status=RUNNING`
  3. BFS-traverse `cq:Page` resources from `rootPath`
  4. Per page: check `skipExisting`; apply `defaults` or `ai` action; write `jcr:content/seo/*`
  5. Check `job.isStopped()` each iteration
  6. Save progress every `progressSaveIntervalPages` pages
  7. Write `status=COMPLETED/FAILED/CANCELLED` on exit; close resolver in `finally`

### 2. `BulkSchemaJobConsumer.cfg.json`
**Path**: `ui.config/…/config.author/com.adobe.aem.guides.wknd.core.seo.bulk.BulkSchemaJobConsumer.cfg.json`

```json
{ "maxPagesPerRun": 5000, "maxErrorsBeforeAbort": 100, "progressSaveIntervalPages": 10, "aiRequestDelayMs": 200 }
```

### 3. `BulkSchemaServlet.java`
**Path**: `core/src/main/java/com/adobe/aem/guides/wknd/core/seo/servlets/BulkSchemaServlet.java`

- `sling.servlet.paths=/bin/wknd/seo/bulk`, methods GET + POST, extension json
- References: `JobManager`, `ResourceResolverFactory`
- `POST`: validate params → pre-generate `jobId=UUID` → `jobManager.addJob()` → return `{success, jobId, pollUrl}`
- `GET ?jobId=xxx`: read `/var/wknd/seo/jobs/{jobId}` → return progress JSON
- `GET ?action=list`: return last 20 jobs sorted by `startedAt` descending

### 4. Granite UI Shell Page
**Paths**:
- `ui.apps/…/apps/wknd/tools/seoBulkManager/.content.xml` — `jcr:primaryType="cq:Page"`
- `ui.apps/…/apps/wknd/tools/seoBulkManager/jcr:content/.content.xml` — `sling:resourceType="granite/ui/components/coral/foundation/page"`

Form fields: `rootPath` (pathfield), `action` (select), `schemaType` (select), `providerId` (select), `skipExisting` (checkbox), submit button `.wknd-bulk-submit`; hidden progress container `.wknd-bulk-progress`.

Also add `<filter root="/apps/wknd/tools"/>` to `ui.apps/src/main/content/META-INF/vault/filter.xml`.

### 5. `clientlib-seo-bulk`
**Path**: `ui.apps/…/clientlibs/clientlib-seo-bulk/`

Files:
- `.content.xml` — `categories="[wknd.seo.bulk]"`, `allowProxy=true`
- `js/js.txt` — manifest
- `js/bulk-manager.js` — IIFE; on submit click: fetch CSRF token → POST → receive `jobId`; `setInterval` poll every 3s → render `<coral-tag>` status, `<coral-progressbar>`, summary, errors; stop on terminal status; `clearInterval` on `beforeunload`

---

## Key Reuse Points

| Pattern | Source | Consumer |
|---------|--------|----------|
| Dynamic `SchemaAiProvider[]` binding | `SchemaAiServlet` | `BulkSchemaJobConsumer` |
| `GenerationRequest` builder | `SchemaAiServlet.buildRequest()` | `BulkSchemaJobConsumer` |
| JCR node names: `seo`, `enabled`, `mode`, `jsonLd`, `type` | `SeoSchemaModelImpl` constants | `BulkSchemaJobConsumer` |
| CSRF token fetch + POST pattern | `seo-dialog.js` | `bulk-manager.js` |
| `Granite.UI.Foundation.Utils.notifyUser` | `seo-dialog.js` | `bulk-manager.js` |

---

## Verification Steps (once all files are complete)

1. `mvn clean install -PautoInstallSinglePackage` — bundle Active in `/system/console/bundles`
2. Navigate to `http://localhost:4502/apps/wknd/tools/seoBulkManager.html`
3. Submit `defaults` action on `/content/wknd/us/en/adventures` — verify `COMPLETED` and `seo/enabled=true` in CRX DE
4. Submit `ai` action on a small subtree — verify `seo/jsonLd` populated
5. Verify page source: `<script type="application/ld+json">` present
6. Test `skipExisting=true` preserves existing schema; `false` overwrites

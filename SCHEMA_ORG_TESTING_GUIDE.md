# Schema.org / JSON-LD — Complete Testing Guide

This guide covers every layer of the WKND Schema.org implementation: unit tests, OSGi health checks, manual authoring, REST API tests via curl, bulk enrichment, output validation, and common failure modes.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Build & Deploy](#2-build--deploy)
3. [OSGi Health Checks](#3-osgi-health-checks)
4. [Unit Tests](#4-unit-tests)
5. [Manual Authoring — Page Properties](#5-manual-authoring--page-properties)
6. [Manual Authoring — Template Policy](#6-manual-authoring--template-policy)
7. [Merge / Override / Disable Modes](#7-merge--override--disable-modes)
8. [AI Generation — Servlet API](#8-ai-generation--servlet-api)
9. [AI Providers — Provider Selection](#9-ai-providers--provider-selection)
10. [Bulk Enrichment — REST API](#10-bulk-enrichment--rest-api)
11. [Bulk Enrichment — Tool UI](#11-bulk-enrichment--tool-ui)
12. [Output Validation](#12-output-validation)
13. [Article-Specific Fields](#13-article-specific-fields)
14. [Regression Checklist](#14-regression-checklist)
15. [Debug Reference](#15-debug-reference)

---

## 1. Prerequisites

| Requirement | Detail |
|---|---|
| AEM Author | `http://localhost:4502` running, bundle `aem-guides-wknd.core` Active |
| Admin credentials | `admin / admin` (local dev) |
| `curl` | Any version; examples use `-u admin:admin` |
| `jq` | Optional but recommended for JSON pretty-printing |
| OpenAI API key | Only needed for AI generation tests |
| WKND content | `/content/wknd` deployed with at least one page |

Set a shell variable to avoid repeating credentials:

```bash
AEM="http://localhost:4502"
AUTH="-u admin:admin"
```

---

## 2. Build & Deploy

```bash
# Full build + deploy to local author
mvn clean install -PautoInstallSinglePackage

# Deploy only the core bundle (faster for Java changes)
mvn clean install -pl core -PautoInstallBundle

# Deploy only ui.apps (dialog / clientlib / tool page changes)
mvn clean install -pl ui.apps -PautoInstallPackage
```

Confirm the bundle is active before testing:

```bash
curl $AUTH "$AEM/system/console/bundles/aem-guides-wknd.core.json" | jq '.data[0].state'
# Expected: "Active"
```

---

## 3. OSGi Health Checks

### 3.1 Bundle active

Navigate to `http://localhost:4502/system/console/bundles` and search for `aem-guides-wknd.core`. State must be **Active**.

### 3.2 SEO components registered

```bash
curl $AUTH "$AEM/system/console/components.json" \
  | jq '[.data[] | select(.name | startswith("com.adobe.aem.guides.wknd.core.seo")) | {name, state}]'
```

Expected components and states:

| Component | Expected state |
|---|---|
| `…seo.models.impl.SeoSchemaModelImpl` | active |
| `…seo.servlets.SchemaAiServlet` | active |
| `…seo.servlets.BulkSchemaServlet` | active |
| `…seo.bulk.BulkSchemaJobConsumer` | active |
| `…seo.impl.PageTextExtractorImpl` | active |
| `…seo.ai.impl.OpenAiSchemaProvider` | active (if API key configured) |

### 3.3 Sling Job topic registered

```bash
curl $AUTH "$AEM/system/console/jmx/org.apache.sling:type=JobManager.json" 2>/dev/null \
  || echo "Check via Felix console: /system/console/slingevent"
```

Navigate to `http://localhost:4502/system/console/slingevent` and confirm `wknd/seo/bulk` appears under **Job Queues**.

### 3.4 Repoinit: jobs root created

```bash
curl $AUTH "$AEM/crx/de/index.jsp#/var/wknd/seo/jobs"
# Or via API:
curl $AUTH "$AEM/api/assets/wknd/seo.json" 2>/dev/null
curl $AUTH "$AEM/bin/querybuilder.json?path=/var/wknd/seo&type=nt:unstructured&p.limit=1" | jq '.total'
```

Path `/var/wknd/seo/jobs` must exist. Create manually if missing:

```bash
curl $AUTH -X POST "$AEM/crx/de/commands/node?cmd=createNode&name=jobs&type=nt:unstructured&parentPath=/var/wknd/seo"
```

---

## 4. Unit Tests

Run the full test suite:

```bash
mvn test -pl core
```

Run only SEO tests (once test files are added under `core/src/test/…/seo/`):

```bash
mvn test -pl core -Dtest="Seo*,Bulk*,SchemaAi*"
```

### 4.1 What to test — SeoSchemaModelImpl

The model is a Sling Model; use `io.wcm.testing.mock.aem` or `org.apache.sling.testing.mock.sling`:

```java
// Minimal fixture — page with seo/enabled=true and seo/type=Article
context.load().json("/com/…/seo/SeoSchemaModelImplTest.json", "/content/test");
MockSlingHttpServletRequest request = context.request();
request.setResource(context.resourceResolver().getResource("/content/test/jcr:content"));

SeoSchemaModel model = request.adaptTo(SeoSchemaModel.class);
assertTrue(model.isEnabled());
assertTrue(model.getRenderedJsonLd().contains("\"@type\":\"Article\""));
```

Key scenarios to cover:

| Scenario | Fixture property | Expected |
|---|---|---|
| `enabled=false` | `seo/enabled=false` | `isEnabled() == false`, no JSON-LD |
| `mode=disable` | `seo/mode=disable` | `isEnabled() == false` |
| Raw JSON-LD wins | `seo/jsonLd={"@type":"X"}` | Output equals the raw value verbatim |
| Merge fallback | page has no `headline`, policy has `headline=foo` | `"headline":"foo"` in output |
| Override ignores policy | `seo/mode=override` | Policy values absent from output |
| Article fields | `seo/type=Article`, `seo/publisherName=WKND` | `"publisher":{"name":"WKND"}` in output |
| Script injection blocked | `seo/jsonLd=</script><script>alert(1)` | `</script>` escaped in output |

### 4.2 What to test — GenerationRequest / GenerationResult

These are plain Java; no AEM mocks needed:

```java
GenerationRequest req = GenerationRequest.builder()
    .pagePath("/content/wknd/en/test")
    .schemaType("Article")
    .title("Test Page")
    .build();
assertEquals("Article", req.getSchemaType());
assertEquals("WebPage", GenerationRequest.builder().pagePath("/x").build().getSchemaType());

GenerationResult ok = GenerationResult.ok("{}", "openai");
assertTrue(ok.isSuccess());
assertNull(ok.getErrorMessage());

GenerationResult err = GenerationResult.error("timeout", "openai");
assertFalse(err.isSuccess());
assertNull(err.getJsonLd());
```

### 4.3 What to test — PageTextExtractorImpl

```java
// Mock a page whose jcr:content has a text property
// Verify truncation at maxChars
// Verify whitespace collapse
```

---

## 5. Manual Authoring — Page Properties

### 5.1 Open the SEO tab

1. Navigate to `http://localhost:4502/sites.html/content/wknd`.
2. Select any page → **Properties** (toolbar) → **Schema.org** tab.
3. Confirm the tab renders without JS errors (check browser console).

### 5.2 Enable and save structured fields

| Field | Test value |
|---|---|
| Enable Schema.org | ✓ checked |
| @type | `Article` |
| Headline | `Test Headline` |
| Description | `Test description for schema.` |
| Author | `WKND Editorial` |
| Date Published | `2026-01-01` |
| Keywords | `adventure, travel` |

Save → open the page in a new tab → **View Source** → search for `application/ld+json`.

Expected output (abridged):

```json
{
  "@context": "https://schema.org",
  "@type": "Article",
  "headline": "Test Headline",
  "description": "Test description for schema.",
  "author": { "@type": "Person", "name": "WKND Editorial" },
  "keywords": ["adventure", "travel"]
}
```

### 5.3 Raw JSON-LD textarea takes priority

1. Paste any valid JSON-LD into the **Raw JSON-LD** textarea.
2. Save.
3. View source — confirm the raw value is emitted verbatim (structured fields are ignored).
4. Clear the textarea, save — structured fields render again.

### 5.4 Disable schema on a page

Set **Mode** to `disable` (or uncheck **Enable**) → save → view source → confirm no `<script type="application/ld+json">` tag is present.

---

## 6. Manual Authoring — Template Policy

### 6.1 Open template policy

1. Navigate to `http://localhost:4502/libs/wcm/core/content/sites/templates.html/conf/wknd`.
2. Open the **WKND Page** template → **Edit** → **Page Policy** (page icon, top right).
3. Confirm the **Schema.org Defaults** tab is present.

### 6.2 Set template defaults

| Field | Value |
|---|---|
| @type | `WebPage` |
| Publisher Name | `WKND Magazine` |
| Publisher Logo | `/content/dam/wknd/en/magazine/wknd-magazine-logo.png` |

Save the policy.

### 6.3 Verify merge behaviour

Open a page that has **no** SEO tab data saved:

- View source → JSON-LD should include `"publisher":{"name":"WKND Magazine"}` from the policy default.
- Open page properties → SEO tab → set a different `headline` → save.
- View source → page `headline` appears; publisher still comes from the policy (merge).

### 6.4 Verify override mode blocks policy

On the page, set **Mode** to `override`. View source — publisher from policy should be absent.

---

## 7. Merge / Override / Disable Modes

Quick curl reference — read current SEO node:

```bash
PAGE="/content/wknd/us/en/adventures/napa-wine-tasting"

curl $AUTH "$AEM$PAGE/jcr:content/seo.json"
```

Write mode via CURL (Sling POST):

```bash
# Set mode=disable
curl $AUTH -X POST "$AEM$PAGE/jcr:content/seo" \
  -d "mode=disable" -d "_charset_=utf-8"

# Set mode=merge (default)
curl $AUTH -X POST "$AEM$PAGE/jcr:content/seo" \
  -d "mode=merge" -d "_charset_=utf-8"
```

Verify in page source after each change.

---

## 8. AI Generation — Servlet API

Base URL: `POST /bin/wknd/seo/generate.json`

### 8.1 Health check (no AI key required)

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json" \
  -d "pagePath=/content/wknd/us/en/adventures/napa-wine-tasting"
```

**Expected when no provider configured:**
```json
{"success":false,"error":"No AI provider is available. Check OSGi configuration."}
```

### 8.2 Missing pagePath → 400

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json"
# Expected: {"success":false,"error":"Missing required parameter: pagePath"}
```

### 8.3 Non-existent page → 404

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json" \
  -d "pagePath=/content/wknd/does-not-exist"
# Expected: {"success":false,"error":"Page not found: /content/wknd/does-not-exist"}
```

### 8.4 Successful generation (requires OpenAI key)

Configure the provider first:

```bash
curl $AUTH -X POST \
  "$AEM/system/console/configMgr/com.adobe.aem.guides.wknd.core.seo.ai.impl.OpenAiSchemaProvider" \
  -d "apply=true" \
  -d "apiKey=sk-..." \
  -d "model=gpt-4o-mini" \
  -d "maxBodyChars=8000"
```

Then generate:

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json" \
  -d "pagePath=/content/wknd/us/en/adventures/napa-wine-tasting" \
  -d "schemaType=Article" \
  -d "providerId=openai" | jq .
```

**Expected:**
```json
{
  "success": true,
  "providerId": "openai",
  "jsonLd": "{\n  \"@context\": \"https://schema.org\",\n  \"@type\": \"Article\",\n  ..."
}
```

### 8.5 Author prompt override

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json" \
  -d "pagePath=/content/wknd/us/en/adventures/napa-wine-tasting" \
  -d "schemaType=Article" \
  -d "authorPrompt=Focus on the wine region and include sameAs links to Wikidata."
```

Confirm the AI output reflects the extra instruction.

### 8.6 Dialog button test (browser)

1. Open page properties → Schema.org tab.
2. Select `@type = Article`.
3. Click **Generate with AI**.
4. Button should show busy state; after ~5s the **Raw JSON-LD** textarea should populate.
5. Open browser DevTools → Network → confirm POST to `/bin/wknd/seo/generate` with `CSRF-Token` header.

---

## 9. AI Providers — Provider Selection

### 9.1 Explicit provider routing

```bash
# Ollama (local)
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json" \
  -d "pagePath=/content/wknd/us/en" \
  -d "providerId=ollama"

# Gemini
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json" \
  -d "pagePath=/content/wknd/us/en" \
  -d "providerId=gemini"
```

### 9.2 Fallback to first available

Disable OpenAI (delete config) and confirm the next registered provider with `isAvailable()=true` is selected when `providerId` is not supplied.

### 9.3 isAvailable() = false behaviour

Remove the API key from the OSGi config. The servlet must skip that provider and either use the next available one or return 503.

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/generate.json" \
  -d "pagePath=/content/wknd/us/en"
# Expected: 503 with {"success":false,"error":"No AI provider is available…"}
```

---

## 10. Bulk Enrichment — REST API

Base URL: `POST|GET /bin/wknd/seo/bulk.json`

### 10.1 Submit a defaults job

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/bulk.json" \
  -d "rootPath=/content/wknd/us/en/adventures" \
  -d "action=defaults" \
  -d "schemaType=auto" \
  -d "skipExisting=false" | jq .
```

**Expected:**
```json
{
  "success": true,
  "jobId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "pollUrl": "/bin/wknd/seo/bulk.json?jobId=f47ac10b-..."
}
```

Save the `jobId` for polling:

```bash
JOB_ID="<value from above>"
```

### 10.2 Poll for progress

```bash
curl $AUTH "$AEM/bin/wknd/seo/bulk.json?jobId=$JOB_ID" | jq .
```

**Expected while running:**
```json
{
  "jobId": "f47ac10b-...",
  "status": "RUNNING",
  "startedAt": 1748000000000,
  "finishedAt": 0,
  "processedPages": 14,
  "successPages": 14,
  "failedPages": 0,
  "errors": []
}
```

Poll repeatedly until `status` is `COMPLETED`, `FAILED`, or `CANCELLED`.

Convenience loop:

```bash
while true; do
  STATUS=$(curl -s $AUTH "$AEM/bin/wknd/seo/bulk.json?jobId=$JOB_ID" | jq -r '.status')
  echo "$(date +%H:%M:%S) $STATUS"
  [[ "$STATUS" == "COMPLETED" || "$STATUS" == "FAILED" || "$STATUS" == "CANCELLED" ]] && break
  sleep 3
done
```

### 10.3 Verify JCR writes after completion

Pick one page from the subtree:

```bash
PAGE="/content/wknd/us/en/adventures/napa-wine-tasting"
curl $AUTH "$AEM$PAGE/jcr:content/seo.json" | jq '{enabled, type, mode}'
```

**Expected:**
```json
{
  "enabled": true,
  "type": "WebPage",
  "mode": "merge"
}
```

### 10.4 skipExisting=true skips already-enabled pages

1. Run the job once (`skipExisting=false`) on a subtree.
2. Run it again with `skipExisting=true` and a different `schemaType=Article`.
3. After the second run, pages should still have the original `type` (not overwritten).

```bash
# Run 2 — should skip everything
curl $AUTH -X POST "$AEM/bin/wknd/seo/bulk.json" \
  -d "rootPath=/content/wknd/us/en/adventures" \
  -d "action=defaults" \
  -d "schemaType=Article" \
  -d "skipExisting=true" | jq '.jobId'
```

Poll to completion → verify `processedPages` is 0 (all skipped).

### 10.5 Submit an AI job

Requires an AI provider to be configured and available.

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/bulk.json" \
  -d "rootPath=/content/wknd/us/en/adventures/napa-wine-tasting" \
  -d "action=ai" \
  -d "schemaType=Article" \
  -d "providerId=openai" \
  -d "skipExisting=false" | jq .
```

After completion:

```bash
curl $AUTH "$AEM$PAGE/jcr:content/seo.json" | jq '{enabled, type, jsonLd: .jsonLd[:80]}'
```

The `jsonLd` property should contain AI-generated JSON-LD.

### 10.6 Missing rootPath → 400

```bash
curl $AUTH -X POST "$AEM/bin/wknd/seo/bulk.json" \
  -d "action=defaults"
# Expected: {"success":false,"error":"rootPath is required"}
```

### 10.7 Job not found → 404

```bash
curl $AUTH "$AEM/bin/wknd/seo/bulk.json?jobId=does-not-exist"
# Expected: {"success":false,"error":"Job not found: does-not-exist"}
```

### 10.8 List recent jobs

```bash
curl $AUTH "$AEM/bin/wknd/seo/bulk.json?action=list" | jq '.jobs | length'
# Expected: number of jobs submitted (up to 20)
```

### 10.9 Missing parameter — GET with no params

```bash
curl $AUTH "$AEM/bin/wknd/seo/bulk.json"
# Expected: 400 {"success":false,"error":"Provide jobId= or action=list"}
```

---

## 11. Bulk Enrichment — Tool UI

URL: `http://localhost:4502/apps/wknd/tools/seoBulkManager.html`

### 11.1 Page renders correctly

- Open the URL as an admin author.
- Confirm the Granite UI page shell loads (WKND navigation header, Coral UI styling).
- Confirm all form fields are visible: Root Path, Action, Schema Type, Skip Existing checkbox, Start button.

### 11.2 Form validation — empty root path

Click **Start Bulk Enrichment** with Root Path empty.
Expected: Granite UI validation error on the pathfield; no network request to `/bin/wknd/seo/bulk.json`.

### 11.3 Successful job submission

1. Set Root Path to `/content/wknd/us/en/adventures`.
2. Set Action to `Template Defaults (fast)`.
3. Click **Start Bulk Enrichment**.
4. Confirm:
   - Button becomes disabled.
   - Progress panel appears with status `Queued` or `Running`.
   - Progress bar updates every 3 seconds.
   - On completion status changes to `Completed` (green tag).
   - Button re-enables.

### 11.4 Error display

Submit a job to a path with many pages and a deliberately broken provider (wrong API key).
After failures exceed threshold, confirm error list appears in the progress panel under a `<details>` element.

### 11.5 Browser network audit

Open DevTools → Network before clicking submit.

- Confirm `POST /bin/wknd/seo/bulk.json` includes `CSRF-Token` header.
- Confirm `Content-Type: application/x-www-form-urlencoded`.
- Confirm polling GETs to `/bin/wknd/seo/bulk.json?jobId=…` fire every ~3 seconds.

---

## 12. Output Validation

### 12.1 View page source

```bash
# Fetch rendered page HTML and grep for JSON-LD
curl -s $AUTH "$AEM/content/wknd/us/en/adventures/napa-wine-tasting.html" \
  | grep -A 20 'application/ld+json'
```

### 12.2 Extract and validate JSON-LD

```bash
curl -s $AUTH "$AEM/content/wknd/us/en/adventures/napa-wine-tasting.html" \
  | grep -oP '(?<=<script type="application/ld\+json">).*?(?=</script>)' \
  | jq .
```

Minimum expected fields:

```json
{
  "@context": "https://schema.org",
  "@type": "WebPage",
  "url": "...",
  "name": "..."
}
```

### 12.3 Google Rich Results Test

1. Deploy to a publicly accessible environment (or use ngrok for local).
2. Visit [search.google.com/test/rich-results](https://search.google.com/test/rich-results).
3. Enter the page URL → confirm no errors, structured data is detected.

### 12.4 Schema.org validator

1. Visit [validator.schema.org](https://validator.schema.org).
2. Paste the JSON-LD or enter the page URL.
3. Confirm zero errors; review warnings.

### 12.5 Confirm no double-output

A page must contain **at most one** `<script type="application/ld+json">` block:

```bash
curl -s $AUTH "$AEM/content/wknd/us/en.html" \
  | grep -c 'application/ld+json'
# Expected: 1
```

### 12.6 mode=disable removes the tag entirely

```bash
curl $AUTH -X POST "/content/wknd/us/en/adventures/napa-wine-tasting/jcr:content/seo" \
  -d "mode=disable"
curl -s $AUTH "$AEM/content/wknd/us/en/adventures/napa-wine-tasting.html" \
  | grep -c 'application/ld+json'
# Expected: 0
```

---

## 13. Article-Specific Fields

These fields only render when `@type` is `Article`, `NewsArticle`, or `BlogPosting`.

### 13.1 Dialog conditional visibility

1. Open page properties → Schema.org tab.
2. Select `@type = WebPage` → confirm **Article Section**, **Word Count**, **Publisher** fields are **hidden**.
3. Change to `@type = Article` → confirm those fields **appear**.
4. Change back to `WebPage` → confirm they **hide again** without page reload.

### 13.2 Publisher object in output

Set:
- `@type = Article`
- `publisherName = WKND Magazine`
- `publisherLogo = /content/dam/wknd/logo.png`

Save → view source → confirm:

```json
"publisher": {
  "@type": "Organization",
  "name": "WKND Magazine",
  "logo": { "@type": "ImageObject", "url": "/content/dam/wknd/logo.png" }
}
```

### 13.3 Author object variations

| `authorType` | Expected JSON-LD |
|---|---|
| `Person` | `"author":{"@type":"Person","name":"…","url":"…"}` |
| `Organization` | `"author":{"@type":"Organization","name":"…"}` |

---

## 14. Regression Checklist

Run this after any change to the SEO layer:

- [ ] `mvn test -pl core` passes
- [ ] Bundle active in OSGi console
- [ ] SEO tab appears in page properties and policy dialog
- [ ] `mode=disable` removes JSON-LD from page source
- [ ] Raw JSON-LD textarea overrides structured fields
- [ ] Policy defaults appear when page fields are empty (`mode=merge`)
- [ ] `mode=override` suppresses policy values
- [ ] AI generation returns valid JSON in dialog and via curl
- [ ] Bulk `defaults` job completes; `seo/enabled=true` written to JCR
- [ ] Bulk `ai` job completes; `seo/jsonLd` written to JCR
- [ ] `skipExisting=true` produces zero processed pages on a pre-enriched subtree
- [ ] Tool UI page loads at `/apps/wknd/tools/seoBulkManager.html`
- [ ] Progress bar updates during a running job
- [ ] Google Rich Results Test returns no errors on a production-deployed page
- [ ] No JS errors in browser console on any authoring dialog

---

## 15. Debug Reference

### Bundle won't activate

```bash
curl $AUTH "$AEM/system/console/bundles/aem-guides-wknd.core.json" \
  | jq '.data[0] | {state, stateReason}'
```

Common causes: missing `@Reference` target, compile error, missing package import. Check:
`http://localhost:4502/system/console/bundles` → click bundle name → check **Unsatisfied References**.

### JSON-LD not appearing in page source

1. Confirm `seo/enabled = true` in CRX DE: `/crx/de/index.jsp#/content/…/jcr:content/seo`
2. Confirm `mode != disable`
3. Confirm `customheaderlibs.html` includes the Sling Model call:

```bash
find . -name "customheaderlibs.html" | xargs grep -l "SeoSchemaModel"
```

4. Check the error log for `SeoSchemaModelImpl` warnings:

```bash
curl $AUTH "$AEM/system/console/slinglog/tailer.txt?tail=100&name=%2Flogs%2Ferror.log" \
  | grep -i "SeoSchema"
```

### AI servlet returns 503

1. Confirm the provider OSGi component is **active**: `/system/console/components`.
2. Confirm `isAvailable()` returns true (API key present and non-blank).
3. Check error log for provider class name.

### Bulk job stuck in QUEUED

1. Confirm `BulkSchemaJobConsumer` component is **active**.
2. Confirm Sling Job queue is running: `/system/console/slingevent`.
3. Check progress node in CRX DE: `/var/wknd/seo/jobs/{jobId}`.
4. Check error log:

```bash
curl $AUTH "$AEM/system/console/slinglog/tailer.txt?tail=200&name=%2Flogs%2Ferror.log" \
  | grep -i "BulkSchema"
```

### Bulk job fails immediately

Likely causes:
- Service user `wknd-seo-bulk-service` not created (check repoinit config deployed).
- No write permission on `/content/wknd` — verify in Security console.
- `PageManager.getPage(rootPath)` returns null — confirm path exists.

### Progress node missing after job submission

`/var/wknd/seo/jobs` path was not created by repoinit. Create manually:

```bash
curl $AUTH -X POST "$AEM/crx/de/commands/node" \
  -d "cmd=createNode&name=jobs&type=nt:unstructured&parentPath=/var/wknd/seo"
```

Or redeploy `ui.config` to re-run repoinit.

### CSRF token rejected (403 on POST)

The `seo-dialog.js` and `bulk-manager.js` both fetch `/libs/granite/csrf/token.json` before posting. A 403 on the token endpoint means the session expired. Reload the page and retry.

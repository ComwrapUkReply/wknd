# Schema.org / JSON-LD Structured Data — Implementation Summary

**Branch:** `multi-schema`
**Target platform:** AEM as a Cloud Service (SDK `2025.6.21193`)
**Purpose:** Add Schema.org structured data (JSON-LD) to every WKND page to improve SEO rich results eligibility and AI search engine citability (GEO — Generative Engine Optimisation).

---

## 1. What Was Built

A page-level Schema.org system that:

- Emits a single `<script type="application/ld+json">` block in every page `<head>`
- Supports 14 schema types (Article, BlogPosting, Organization, Person, FAQPage, etc.)
- Merges three tiers of configuration: page-level author input → template policy defaults → built-in JCR fallbacks
- Auto-injects `Organization`, `BreadcrumbList`, and `WebSite` schemas alongside the primary author-chosen type, wrapped in a `@graph` array
- Provides an AI-assisted generation feature (OpenAI / Google Gemini / Ollama) that drafts JSON-LD from page content
- Is controlled entirely through the existing AEM Page Properties dialog (new "Schema.org" tab) and a template policy dialog — no new component placement required

---

## 2. Files Added

### Java — `core` module

| File | Description |
|---|---|
| `core/src/main/java/.../seo/models/SeoSchemaModel.java` | Sling Model interface; exposes `isEnabled()` and `getJsonLd()` to HTL |
| `core/src/main/java/.../seo/models/impl/SeoSchemaModelImpl.java` | Core engine — reads page/policy JCR properties, applies fallback chain, builds JSON-LD with `javax.json`, merges auto-schemas into `@graph` |
| `core/src/main/java/.../seo/models/package-info.java` | OSGi package export declaration |
| `core/src/main/java/.../seo/servlets/SchemaAiServlet.java` | Author-only POST endpoint (`/bin/wknd/seo/generate`) — accepts page path and schema type, calls the selected AI provider, returns generated JSON-LD |
| `core/src/main/java/.../seo/ai/SchemaAiProvider.java` | OSGi SPI interface for AI providers |
| `core/src/main/java/.../seo/ai/GenerationRequest.java` | Value object passed to AI providers (page path, type, title, description, body text, locale, author prompt) |
| `core/src/main/java/.../seo/ai/GenerationResult.java` | Value object returned by AI providers (JSON-LD string or error) |
| `core/src/main/java/.../seo/ai/impl/OpenAiSchemaProvider.java` | OpenAI Chat Completions integration (`gpt-4o-mini` by default) |
| `core/src/main/java/.../seo/ai/impl/GeminiSchemaProvider.java` | Google Gemini integration |
| `core/src/main/java/.../seo/ai/impl/OllamaSchemaProvider.java` | Ollama (local LLM) integration |
| `core/src/main/java/.../seo/ai/package-info.java` | OSGi package export declaration |
| `core/src/main/java/.../seo/config/SeoGlobalConfig.java` | OSGi `@ObjectClassDefinition` — site-wide config: organization name, URL, logo, sameAs, auto-schema toggle defaults |
| `core/src/main/java/.../seo/config/SeoGlobalConfigService.java` | OSGi `@Component` that activates the global config and exposes it to Sling Models via `@OSGiService` injection |

### Java — Tests

| File | Description |
|---|---|
| `core/src/test/java/.../seo/models/impl/SeoSchemaModelImplTest.java` | Unit tests using AEM Mocks (`io.wcm`): date fallbacks, `cq:tags` → keywords/about, `@id` / `jcr:uuid`, enabled/disabled gate, `@graph` output, per-page toggle overrides |

### UI — `ui.apps` module

| File | Description |
|---|---|
| `ui.apps/.../components/page/_cq_dialog/.content.xml` | Extends the WKND Page Properties dialog — adds a "Schema.org" tab via `granite:include` |
| `ui.apps/.../components/page/_cq_design_dialog/.content.xml` | Extends the Template Policy dialog — adds "Schema.org Defaults" tab for template-level defaults |
| `ui.apps/.../components/seo/.content.xml` | Component node definition for the `seo` component group (dialog fragment container) |
| `ui.apps/.../components/seo/dialog-tab/.content.xml` | Reusable Granite UI fragment — full Schema.org authoring form: enabled toggle, mode selector, primary type, article/organisation field wells, auto-schema inclusions well, common fields, AI generation panel, raw JSON-LD textarea |
| `ui.apps/.../components/seo/dialog-tab-template/.content.xml` | Template-policy variant of the dialog tab (same structure, fewer fields, used as default values for all pages on a template) |
| `ui.apps/.../clientlibs/clientlib-seo-authoring/.content.xml` | Client library definition (`categories: wknd.seo.authoring`, `channel: authoring`) |
| `ui.apps/.../clientlibs/clientlib-seo-authoring/js.txt` | Client library JS manifest |
| `ui.apps/.../clientlibs/clientlib-seo-authoring/js/seo-dialog.js` | Author-side JavaScript — wires the "Generate with AI" button (POST to servlet, populates JSON-LD textarea), shows/hides type-conditional field wells (article, organisation) |

### Configuration — `ui.config` module

| File | Description |
|---|---|
| `ui.config/.../osgiconfig/config.author/com...OpenAiSchemaProvider.cfg.json` | OpenAI provider OSGi config (API key placeholder, model, timeout) — author runmode only |
| `ui.config/.../osgiconfig/config.author/com...GeminiSchemaProvider.cfg.json` | Gemini provider OSGi config — author runmode only |
| `ui.config/.../osgiconfig/config.author/com...OllamaSchemaProvider.cfg.json` | Ollama provider OSGi config — author runmode only |
| `ui.config/.../osgiconfig/config/com...SeoGlobalConfigService.cfg.json` | Global site config defaults: org name `WKND`, org URL, logo path, all auto-schemas enabled, search URL template empty |

---

## 3. Files Modified

| File | What Changed |
|---|---|
| `core/pom.xml` | Added `javax.json` and `org.apache.httpcomponents` dependencies for JSON building and HTTP calls to AI provider APIs |
| `ui.apps/.../components/page/customheaderlibs.html` | Added the JSON-LD injection block — `data-sly-use` binding to `SeoSchemaModel`, conditional `<script type="application/ld+json">` output |
| `ui.apps/.../components/seo/dialog-tab/.content.xml` | Extended across three commits: added article-specific fields (articleSection, wordCount, publisher, authorType), then Organisation fields (legalName, logo, telephone, email, sameAs, address), then multi-schema auto-injection override selects (Organization / BreadcrumbList / WebSite: Inherit / Include / Exclude) |
| `ui.apps/.../components/seo/dialog-tab-template/.content.xml` | Same sequence of additions as dialog-tab — policy dialog kept in sync |
| `core/src/main/java/.../seo/models/impl/SeoSchemaModelImpl.java` | Built iteratively across four commits: initial JSON-LD builder → article-type fields → organisation fields → date/tag fallbacks and `cq:tags` integration → `@graph` multi-schema with `SeoGlobalConfigService` injection |
| `core/src/test/java/.../seo/models/impl/SeoSchemaModelImplTest.java` | Started empty, grew to 16 tests covering all fallback paths and multi-schema graph output |

---

## 4. How the System Works End-to-End

```
Author fills Page Properties → Schema.org tab
          │
          ▼
  JCR stored at: /content/<site>/<page>/jcr:content/seo/
  (enabled, mode, type, headline, description, author, …)
          │
          ▼
  SeoSchemaModelImpl (@PostConstruct)
    1. Read page seo/ node
    2. Read template policy seo/ node (ContentPolicyManager)
    3. Merge: page value → policy value → JCR fallback (jcr:title, cq:lastModified, etc.)
    4. Build primary JsonObject from merged fields
    5. Ask SeoGlobalConfigService for auto-schema toggles
    6. Build Organization, BreadcrumbList, WebSite nodes
    7. If total nodes > 1 → wrap in @graph; else output single node
    8. Sanitize (escape </) → store as renderedJsonLd
          │
          ▼
  customheaderlibs.html
    <sly data-sly-use.seo="…SeoSchemaModel" data-sly-test="${seo.enabled}">
      <script type="application/ld+json">${seo.jsonLd @ context='unsafe'}</script>
    </sly>
          │
          ▼
  Page <head> — one <script type="application/ld+json"> block
```

### Three-tier precedence (per field)

```
Page-level seo/ value  →  Template policy seo/ value  →  JCR fallback
     (authored)               (template default)         (jcr:title, cq:lastModified, etc.)
```

### Auto-schema toggle logic (per schema type)

```
Page toggle = "yes"  →  always include
Page toggle = "no"   →  always exclude
Page toggle = ""     →  follow OSGi global config (includeOrganization / includeBreadcrumb / includeWebSite)
```

---

## 5. Supported Schema Types

`WebPage` · `Organization` · `Article` · `NewsArticle` · `BlogPosting` · `Product` · `Event` · `FAQPage` · `HowTo` · `Person` · `Place` · `Recipe` · `VideoObject` · `BreadcrumbList`

Article-like types (`Article`, `NewsArticle`, `BlogPosting`) expose extra fields: `articleSection`, `wordCount`, `publisher` (name + logo), `author` (name, type, URL).

`Organization` type exposes: `legalName`, `logo`, `telephone`, `email`, `foundingDate`, `sameAs[]`, `address` (PostalAddress).

---

## 6. AEM as a Cloud Service Compatibility

### What works ✅

| Area | Status |
|---|---|
| Target SDK | `2025.6.21193` — latest Cloud SDK |
| Java version | 21 ✅ |
| All `com.day.cq.*` APIs | Cloud-safe ✅ |
| All `org.apache.sling.*` APIs | Cloud-safe ✅ |
| OSGi annotations | Modern `org.osgi.service.component.annotations` ✅ |
| Sling Model injection (`@Self`, `@ScriptVariable`, `@OSGiService`) | Cloud-safe ✅ |
| HTL rendering via `data-sly-use` | Cloud-safe ✅ |
| OSGi configs split by runmode (`config.author`, `config`) | Cloud pattern ✅ |
| AI providers (author runmode only) | Cloud-safe ✅ |

### Known issue requiring fix ⚠️

**`SchemaAiServlet` uses a path-based servlet registration:**

```java
// CURRENT — BLOCKED on AEM Cloud
"sling.servlet.paths=/bin/wknd/seo/generate"
```

AEM as a Cloud Service blocks all path-based servlets (`/bin/*`) for security. The "Generate with AI" button in Page Properties will return **403 Forbidden** when deployed to Cloud.

**Required fix — switch to resource-type based registration:**

```java
// NEEDS TO CHANGE TO THIS
"sling.servlet.resourceTypes=wknd/servlets/seo/generate",
"sling.servlet.methods=POST",
"sling.servlet.extensions=json"
```

This requires:
1. Updating the `@Component` properties in `SchemaAiServlet.java`
2. Creating a dummy resource node at `ui.apps/.../apps/wknd/servlets/seo/generate/.content.xml`
3. Updating `seo-dialog.js` to POST to the resource-type URL instead of `/bin/wknd/seo/generate`

> All other parts of the implementation — JSON-LD rendering, auto-schemas, OSGi config, dialogs — are fully compatible with AEM as a Cloud Service as-is.

---

## 7. Can This Be Deployed to an Existing AEM Cloud Project?

### Short answer

Yes — but not as a traditional installable ZIP package. AEM as a Cloud Service does not support ad-hoc package installation via Package Manager for code. All deployments must go through Cloud Manager.

### Two routes

#### Route A — Merge into the target project (recommended)

Copy the implementation files into the target project's Maven module structure and deploy via Cloud Manager pipeline. This is the standard approach for AEM Cloud.

**What to copy:**

| Source | Target project equivalent |
|---|---|
| `core/src/main/java/.../seo/` | `core/src/main/java/<your-package>/seo/` |
| `core/src/test/java/.../seo/` | `core/src/test/java/<your-package>/seo/` |
| `ui.apps/.../components/seo/` | `ui.apps/.../components/seo/` |
| `ui.apps/.../components/page/_cq_dialog/` | Merge the Schema.org tab include into existing dialog |
| `ui.apps/.../components/page/_cq_design_dialog/` | Merge the Schema.org tab include into existing policy dialog |
| `ui.apps/.../components/page/customheaderlibs.html` | Add the JSON-LD block to existing file |
| `ui.apps/.../clientlibs/clientlib-seo-authoring/` | Copy as-is |
| `ui.config/.../osgiconfig/config.author/` AI configs | Copy and fill in real API keys |
| `ui.config/.../osgiconfig/config/` global config | Copy and adjust values |

**Changes needed before copying:**
- Update all Java package names from `com.adobe.aem.guides.wknd.core.seo` to your project's base package
- Update the `sling:resourceSuperType` references in dialog `.content.xml` files if your page component path differs
- Fix `SchemaAiServlet` servlet registration (path → resource-type, as described in Section 6)

#### Route B — Distribute as a Maven artifact

The `core` module can be published to a Maven repository (Nexus, Artifactory, or GitHub Packages) as a standalone OSGi bundle. The target project adds it as a Maven dependency in its `all/pom.xml` and includes the bundle in the `all` content package.

The `ui.apps` content (dialogs, clientlibs, component fragment) still needs to be merged manually into the target project, as AEM content overlays cannot be distributed purely as a Maven dependency without a content package.

This route is better suited to distributing the feature across multiple projects.

### What cannot be reused without changes

- **Package names** — all Java classes use the WKND base package; must be renamed for a different project
- **Component resource types** — dialogs reference `wknd/components/seo/dialog-tab`; these paths must match the target project's app root
- **Page component overlay** — the `_cq_dialog` and `_cq_design_dialog` additions assume the page component is at `apps/wknd/components/page`; this differs per project
- **OSGi config values** — organisation name, logo path, and AI API keys must be updated per environment

---

## 8. File Count Summary

| Category | New files | Modified files |
|---|---|---|
| Java (main) | 13 | 1 |
| Java (test) | 1 | — |
| Granite UI dialogs (ui.apps) | 5 | 2 |
| Client library (ui.apps) | 3 | — |
| HTL template (ui.apps) | — | 1 |
| OSGi configs (ui.config) | 4 | — |
| **Total** | **26** | **4** |

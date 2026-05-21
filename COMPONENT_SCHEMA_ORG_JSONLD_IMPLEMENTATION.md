# Component-Level Schema.org JSON-LD Enhancement

## Context

The WKND project already has a solid **page-level** Schema.org JSON-LD system: one `<script type="application/ld+json">` block per page, authored via Page Properties → Schema.org tab. It supports 14 schema types, policy defaults, merge/override strategies, and AI generation (OpenAI / Gemini / Ollama).

The gap: **individual components have no structured data**. A page about an adventure with a Teaser, a Byline author, an embedded video, and an Accordion FAQ produces exactly one JSON-LD block — the page-level one. Search engines and AI engines cannot distinguish the FAQ from the author bio from the video at the component level.

This plan enhances the system to emit **component-scoped JSON-LD**, dramatically improving rich result eligibility, AI citability, and entity disambiguation.

---

## Why This Matters — SEO, GEO & AEO

### SEO (Search Engine Optimization)
- **Rich results eligibility**: FAQPage → "People Also Ask" accordion, BreadcrumbList → sitelinks, NewsArticle → news carousel, VideoObject → video thumbnail in SERP
- **Entity clarity**: Component-level schema clarifies *which part* of the page is an Article vs. a Product vs. a Person — reducing ambiguity for crawlers
- **Incremental coverage**: Each component independently validates in Rich Results Test

### GEO (Generative Engine Optimization)
- AI search engines (ChatGPT, Perplexity, Gemini) parse structured data to extract entity relationships
- A `Byline` component with `Person` schema → AI can cite "Written by Jane Doe, Senior Editor" with confidence
- `ImageObject` with `caption` + `creditText` → AI can attribute images correctly
- Component-level schema creates a **semantic map** of the page that LLMs use to structure their understanding

### AEO (Answer Engine Optimization)
- **FAQPage on Accordion** is the highest-value target: Google surfaces FAQ answers directly in AI Overviews
- **HowTo on step-based content** gets featured snippet treatment
- **BreadcrumbList on Breadcrumb** (WCM Core already generates this natively — just needs surfacing)
- **VideoObject on Embed** (YouTube/Vimeo): AI Overview video carousels
- Component-level AEO means *any page with an Accordion* can get FAQ rich results without authors manually describing content at page level

---

## Architecture Decision: Inline JSON-LD (Recommended)

### Option A — Inline per component (CHOSEN)
Each component emits its own `<script type="application/ld+json">` block immediately after its HTML. Google fully supports multiple JSON-LD blocks anywhere in the document (head or body). Simple, independent, no cross-component wiring.

### Option B — Aggregated @graph
Components register structured data via a request-scoped OSGi service; page aggregates into one `@graph` in `<head>`. Cleaner output but significantly more complex (requires Sling request cycle coordination).

**Decision**: Start with Option A (inline). It delivers 95% of the value with 20% of the complexity. Option B can be layered later.

---

## Components to Target (Priority Order)

### P0 — Highest AEO/GEO Impact
| Component | Schema Type | Why |
|-----------|------------|-----|
| **Accordion** | `FAQPage` + `Question`/`Answer` | Direct FAQ rich results. Each panel = one Q&A pair |
| **Breadcrumb** | `BreadcrumbList` | WCM Core may already generate this; just needs exposure |
| **Byline** | `Person` | Author entity = trust signal for AI; already has name + image |

### P1 — High SEO Impact
| Component | Schema Type | Why |
|-----------|------------|-----|
| **Teaser** | `NewsArticle` / `BlogPosting` / `Event` | Headline + image + description + link maps naturally |
| **Embed** | `VideoObject` | YouTube/Vimeo embeds get video rich results |
| **Image** | `ImageObject` | Caption, alt, credit → AI image attribution |

### P2 — Useful but Lower Urgency
| Component | Schema Type | Why |
|-----------|------------|-----|
| **List** | `ItemList` | Collections of linked items → sitelinks |
| **Download** | `DataDownload` | File assets with size/format metadata |
| **Content Fragment** | `Article` | Rich structured content |

---

## Author Experience Design

### Pattern: Optional "Schema.org" Tab in Each Component Dialog

Authors see a new **Schema.org** tab when editing a component. The tab is:
- **Off by default** — zero impact unless author opts in
- **Type pre-selected** based on component (e.g., Accordion always defaults to FAQPage)
- **Minimal fields** — component-specific, not the full page-level form
- **AI Generate button** — reusing the existing `SchemaAiProvider` / `SchemaAiServlet` infrastructure

#### Accordion Schema Tab (example)
```
[✓] Emit Schema.org structured data
Schema Type: [FAQPage]  (locked — only sensible type for Accordion)
─────────────────────────────────────────────
[AI Generate]  [Raw JSON-LD override...]
```
*FAQ content is read automatically from accordion items — author does not re-enter it.*

#### Teaser Schema Tab (example)
```
[✓] Emit Schema.org structured data
Schema Type: [NewsArticle ▼]  (NewsArticle | BlogPosting | Event | Product | Thing)
Date Published: [date picker]
─────────────────────────────────────────────
[AI Generate]  [Raw JSON-LD override...]
```

#### Byline Schema Tab (example)
```
[✓] Emit Schema.org structured data
Schema Type: [Person ▼]  (Person | Organization)
Job Title: [______________]  (defaults to first occupation)
Profile URL: [______________]
─────────────────────────────────────────────
[AI Generate]
```

### Properties Storage
All component-level schema stored under `./schema/*` within the component node:
```
/content/wknd/en/.../jcr:content/root/container/accordion
└── schema/
    ├── enabled (Boolean)
    ├── type    (String)   → "FAQPage"
    └── jsonLd  (String)   → raw override (optional)
```

---

## Implementation Plan

### Phase 1: Infrastructure (shared across all components)

**1a. Reusable Granite UI fragment for component schema tabs**
- New: `ui.apps/.../components/seo/component-schema-tab/.content.xml`
- Fields: `enabled` toggle, `type` select (component-specific datasource), `jsonLd` override textarea, AI Generate button
- Reuses `clientlib-seo-authoring` JS (extend for component dialog context)

**1b. Extend `SchemaAiServlet`**
- Add optional `componentPath` param to `POST /bin/wknd/seo/generate`
- When present, servlet reads component resource + its text children for body context
- No new servlet needed — just add a parameter branch

**1c. Create `ComponentSchemaModel` base Sling Model**
```java
// core/.../seo/models/ComponentSchemaModel.java
public interface ComponentSchemaModel {
    boolean isEnabled();
    String getJsonLd();   // rendered JSON-LD for this component
}
```
Base impl reads `./schema/enabled` and `./schema/jsonLd`; specific component impls extend it.

### Phase 2: Accordion → FAQPage (P0, highest value)

**Files to create:**
- `ui.apps/.../components/accordion/_cq_dialog/.content.xml` — extend core dialog via sling:resourceMerge
- `core/.../seo/component/impl/AccordionSchemaModelImpl.java` — reads accordion panel titles/text, builds FAQPage JSON-LD
- `ui.apps/.../components/accordion/accordion.html` — wraps core rendering + appends JSON-LD block

**JSON-LD output:**
```json
{
  "@context": "https://schema.org",
  "@type": "FAQPage",
  "mainEntity": [
    {
      "@type": "Question",
      "name": "Panel title",
      "acceptedAnswer": { "@type": "Answer", "text": "Panel body text" }
    }
  ]
}
```

### Phase 3: Breadcrumb → BreadcrumbList (P0, easy win)

WCM Core Breadcrumb already generates `BreadcrumbList` JSON-LD via its own model. In WKND it is a pure proxy — need to verify if Core already exposes it. May be zero-code.

**Files to check first:**
- Core component's `breadcrumb.html` — does it already emit JSON-LD?
- If not: `ui.apps/.../components/breadcrumb/breadcrumb.html` + `BreadcrumbSchemaModelImpl.java`

### Phase 4: Byline → Person (P0, teaches cross-component pattern)

**Files to modify/create:**
- `ui.apps/.../components/byline/_cq_dialog/.content.xml` — add Schema.org tab
- `core/.../seo/component/impl/BylineSchemaModelImpl.java` — adapts `BylineImpl` data into Person JSON-LD
- `ui.apps/.../components/byline/byline.html` — append JSON-LD block

**JSON-LD output:**
```json
{
  "@context": "https://schema.org",
  "@type": "Person",
  "name": "Stacey Roswells",
  "jobTitle": "Photographer",
  "image": "https://example.com/dam/authors/stacey.jpg"
}
```

### Phase 5: Teaser + Embed + Image (P1)

Same pattern applied to Teaser (NewsArticle/BlogPosting), Embed (VideoObject), Image (ImageObject).

---

## HTL Rendering Pattern (per component)

For each component, append at the bottom of its `.html` file:

```html
<sly data-sly-use.schema="com.adobe.aem.guides.wknd.core.seo.component.AccordionSchemaModel"
     data-sly-test="${schema.enabled}">
    <script type="application/ld+json">${schema.jsonLd @ context='unsafe'}</script>
</sly>
```

For **proxy components** (no existing HTL in WKND), create a minimal `componentname.html` that:
1. Delegates rendering to core via `<sly data-sly-resource="${@ resourceType='core/wcm/components/...'}">`
2. Appends the schema block after

---

## Key Files

### New Files
| File | Purpose |
|------|---------|
| `ui.apps/.../components/seo/component-schema-tab/.content.xml` | Reusable dialog tab fragment |
| `core/.../seo/component/ComponentSchemaModel.java` | Base interface |
| `core/.../seo/component/impl/AccordionSchemaModelImpl.java` | FAQPage builder |
| `core/.../seo/component/impl/BylineSchemaModelImpl.java` | Person builder |
| `core/.../seo/component/impl/TeaserSchemaModelImpl.java` | NewsArticle/BlogPosting builder |
| `core/.../seo/component/impl/BreadcrumbSchemaModelImpl.java` | BreadcrumbList builder |
| `ui.apps/.../components/accordion/_cq_dialog/.content.xml` | Dialog extending core |
| `ui.apps/.../components/accordion/accordion.html` | HTL with JSON-LD append |

### Modified Files
| File | Change |
|------|--------|
| `ui.apps/.../components/byline/_cq_dialog/.content.xml` | Add Schema.org tab |
| `ui.apps/.../components/byline/byline.html` | Add JSON-LD append |
| `core/.../seo/servlets/SchemaAiServlet.java` | Add componentPath param |
| `ui.apps/.../clientlibs/clientlib-seo-authoring/js/seo-dialog.js` | Support component dialog context |

---

## Effort Estimate

| Phase | Effort | Risk | Value |
|-------|--------|------|-------|
| Infrastructure (base model + dialog fragment) | Medium (3h) | Low | Enables all below |
| Accordion → FAQPage | Medium (3h) | Low | Very High (AEO) |
| Breadcrumb → BreadcrumbList | Low (1h) | Very Low | High (SEO) |
| Byline → Person | Low (2h) | Very Low | High (GEO trust) |
| Teaser → NewsArticle | Medium (3h) | Low | High (SEO rich results) |
| Embed → VideoObject | Medium (2h) | Low | High (video SERP) |
| Image → ImageObject | Low (1h) | Very Low | Medium |

---

## Verification

For each component:
1. Author a page with the component, enable Schema.org tab, save
2. View page source → confirm `<script type="application/ld+json">` adjacent to component HTML
3. Paste URL into Google Rich Results Test
4. Verify: detected type matches, no validation errors
5. For Accordion/FAQ: confirm "FAQPage" detected with all Q&A pairs
6. For Byline/Person: confirm "Person" entity with name + jobTitle
7. `mvn test -pl core` passes
8. Bundle `aem-guides-wknd.core` is Active in `/system/console/bundles`

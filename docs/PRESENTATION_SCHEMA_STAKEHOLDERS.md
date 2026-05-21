# Presentation: Structured Data (Schema.org) on WKND — Stakeholder briefing

**Audience:** Business stakeholders, marketing, content leadership  
**Goal:** Explain what we built, why it matters for **SEO**, **GEO**, and **AEO**, and how demos show value  
**Tone:** Plain language, minimal technical depth

---

## Why this matters in one sentence

We give every page a **clear, machine-readable “ID card”** (who/what/when, topic, publisher, language) so **search engines and AI systems** can understand and use our content more confidently.

---

## Agenda (with demo moments)

| Time | Topic | What you say (angle) |
|------|--------|----------------------|
| 0–3 min | **Hook** | “People used to optimize for 10 blue links; today they optimize for answers and AI summaries.” |
| 3–8 min | **What we built** | “A reusable layer: template defaults + page overrides + optional AI draft + safe output in the page.” |
| 8–12 min | **Business outcomes** | SEO (rich results, clarity), AEO (direct answers), GEO (trust and citability in AI). |
| 12–20 min | **Demo 1 — Page properties** | Open a page → SEO tab → show fields or raw JSON-LD → save → view page source for `application/ld+json`. *Context: “This is what Google and assistants read.”* |
| 20–25 min | **Demo 2 — Template policy** | Open template policy → show defaults for a content type. *Context: “Scale and consistency without copy-paste.”* |
| 25–30 min | **Demo 3 — Merge / override / off** | Toggle mode: merge vs page-only vs disable. *Context: “Control and governance.”* |
| 30–35 min | **Demo 4 — AI assist (if configured)** | “Generate with AI” → review → edit. *Context: “Speed for authors, humans stay in control.”* |
| 35–40 min | **Q&A** | Reinforce: consistency, speed, risk controls (disable, manual override). |

---

## Glossary (simple)

- **Structured data / JSON-LD:** A small block of data in the page that says, in a standard format, “this is an article,” “this is the title,” “this is the author,” etc.
- **SEO (Search Engine Optimization):** Helping Google/Bing understand and rank our pages; structured data supports clearer indexing and eligible enhancements (where Google allows).
- **AEO (Answer Engine Optimization):** Optimizing so systems can **extract a correct short answer** or fact from our page (dates, authorship, entity, topic).
- **GEO (Generative Engine Optimization):** Optimizing so **AI-driven answers** that draw from the web are more likely to **reflect accurate facts about us** and **cite us** when citation matters.

---

# Version 1 — High-level introduction (speaker transcript)

*Use this for executives or a short slot.*

---

**Opening**

“Today I’ll show how we’re making our content easier for both **traditional search** and **new AI-style discovery**. The idea is simple: we’re adding a **standard ‘content passport’** to each page—structured information machines can trust.”

**The problem**

“When pages only have visible text and basic metadata, machines have to **guess**. Guessing leads to **weaker snippets, wrong assumptions, and missed opportunities** in search and in AI summaries.”

**What we delivered**

“We implemented a **structured data layer** on our AEM site. Content teams can:

- Set **sensible defaults at the template** level—for example, all articles behave consistently.
- **Adjust each page** when something is unique.
- Optionally use **AI to draft** a first version, then **edit and approve**.
- **Turn structured data off** on a page if we ever need to.

The output is a single, clean **JSON-LD** block in the page—**invisible to visitors**, **visible to trustworthy parsers**.”

**Why the business should care**

“Three labels you hear a lot are **SEO**, **AEO**, and **GEO**. You can think of them as one ladder:

1. **SEO** — *Can finders **find** us and understand what the page is?*  
2. **AEO** — *Can answer engines **quote the right facts** (who, when, what topic)?*  
3. **GEO** — *When AI generates an answer, is our brand **represented accurately** and are we in a **strong position to be referenced**?*

This feature **raises the floor** on all three by making our **entities and facts explicit**.”

**Risk and control**

“We designed for **governance**: authors can **merge** template + page, **ignore** template on a page, or **disable** output. Raw JSON can be used when experts need full control.”

**Closing**

“This isn’t magic—it’s **discipline at scale**. It makes us **clearer to machines**, which makes us **more competitive** wherever discovery happens.”

---

# Version 2 — Deep dive (speaker transcript)

*Use this for marketing ops, SEO leads, or a longer workshop.*

---

**1. Where authoring happens**

“We extended **Page properties** with an SEO tab. Authors store structured data under the page’s content.  
We also extended the **template policy (design dialog)** so we can define **defaults** for every page that uses that template—think ‘all blog posts start as `BlogPosting` with a default publisher.’”

**2. How defaults and pages combine**

“We support a **mode** on the page:

- **Merge (default):** Page fields **win** when filled; anything left empty **falls back** to the template. That’s how we balance **scale** and **flexibility**.
- **Override:** Use **only** what’s on the page—useful for special campaigns or one-offs.
- **Disable:** **No** structured data on that page—useful for tests, sensitive pages, or when we’re not ready.

This matches how real teams work: **templates for standards**, **pages for exceptions**.”

**3. What actually gets published**

“When someone visits the page, the system **builds one JSON-LD payload** and injects it in the `<head>` as `application/ld+json`. If something goes wrong, the code is written so **the page still loads**—SEO must not break the experience.”

Reference (runtime output in `customheaderlibs.html`):

```33:41:ui.apps/src/main/content/jcr_root/apps/wknd/components/page/customheaderlibs.html
<!--/*
    Schema.org / JSON-LD structured data (AEO / GEO).
    Merges template (policy) defaults with page-level values via
    com.adobe.aem.guides.wknd.core.seo.models.SeoSchemaModel. Safe no-op when disabled.
*/-->
<sly data-sly-use.seo="com.adobe.aem.guides.wknd.core.seo.models.SeoSchemaModel"
     data-sly-test="${seo.enabled}">
    <script type="application/ld+json">${seo.jsonLd @ context='unsafe'}</script>
</sly>
```

**4. Manual vs “smart defaults” vs AI**

“Authors can work in two ways:

- **Guided fields**: type (e.g. `WebPage`, `Article`, `NewsArticle`, `BlogPosting`), headline, description, author, dates, image, keywords—and for articles, extras like section, word count, publisher, author URL.
- **Raw JSON-LD textarea**: for advanced cases.

**Priority rule that matters for governance:** if **raw JSON-LD** is present, that’s treated as the **final word** for what we emit (still with basic safety). Otherwise we **build** JSON from the fields. So experts can always **override** generated or field-based output.”

**5. AI-assisted authoring (author environment)**

“When configured, authors can click **‘Generate with AI’**. A **server endpoint** reads **page context** (path, titles, descriptions, sampled text) and returns a **draft** JSON-LD. **Humans review**—we’re not blindly trusting the model. AI controls are **intentionally not** on the template tab so **defaults stay human-governed**.”

(See: `POST /bin/wknd/seo/generate`, `SchemaAiServlet`, clientlib `seo-dialog.js`, and `SCHEMA_ORG_JSONLD_IMPLEMENTATION.md`.)

**6. Article-specific richness**

“For article-like types, we output richer objects—for example **structured author** (person vs organization + URL), **publisher** with logo, **article section**, **word count**. That’s the kind of detail **answer engines and AI systems** use to judge **trust and context**.”

**7. SEO, AEO, GEO — how this implementation maps**

- **SEO:** Clear `@type`, titles, descriptions, URLs, images, dates → better **machine understanding** and eligibility for **enhanced understanding** where Google supports it; fewer ambiguous pages.
- **AEO:** Explicit facts (dates, authorship, publisher, language) → easier **correct extraction** for featured/answer-style surfaces.
- **GEO:** Stronger **entity signals** (who published, what it is, topical keywords, language) → better alignment when models synthesize from many sources; **citability** improves when our facts are **unambiguous and complete**.

**8. Operational notes for stakeholders**

- **Keys and AI:** Provider config uses API settings suitable for cloud secrets—not hardcoded keys.
- **Safety:** Invalid raw JSON is skipped with logging; **sanitization** avoids breaking the HTML script tag.
- **Measurement:** Track **coverage** (% pages with valid JSON-LD), **correctness** (spot checks), and **rich result / search console** trends where applicable—not only rankings.

**Closing**

“What you’re seeing is **industrialized metadata**: fast for authors, consistent by design, flexible when needed, and aligned with **how discovery is evolving**.”

---

## Demo script (step-by-step, with “say this” context)

**Demo A — Prove it ships (2 min)**  

1. Open a published page.  
2. **View page source** (or devtools Elements).  
3. Find `<script type="application/ld+json">`.  
**Say:** “This is the machine-readable summary. Humans don’t see it; search and AI parsers do.”

**Demo B — Page-level control (3 min)**  

1. Open **Page properties** → **SEO** tab.  
2. Fill headline/description or paste a small JSON-LD draft.  
3. Save, refresh published view.  
**Say:** “We can tune one page without changing the whole site.”

**Demo C — Template scale (3 min)**  

1. Open **template policy** → SEO defaults.  
2. Show a default type or publisher.  
**Say:** “Hundreds of pages inherit quality defaults; we’re not asking authors to repeat work.”

**Demo D — Modes (2 min)**  

1. Show **merge** vs **override** vs **disable**.  
**Say:** “This is our governance dial—standard, exception, or off.”

**Demo E — AI draft (optional, 3 min)**  

1. Click **Generate with AI**, show draft in textarea.  
2. Edit one field manually.  
**Say:** “Speed plus human review. Marketing keeps control.”

---

## Backup slide — “What we did NOT promise”

- Structured data **does not guarantee** a specific ranking or a rich result **badge**.  
- AI systems **don’t always cite** sources; we **improve odds** with clarity and completeness.  
- Quality of **on-page content** still drives reputation; metadata **supports** it.

---

## Internal reference

Implementation summary: `SCHEMA_ORG_JSONLD_IMPLEMENTATION.md`  
Core model: `core/.../SeoSchemaModel.java`, `SeoSchemaModelImpl.java`  
Rendering: `ui.apps/.../page/customheaderlibs.html`

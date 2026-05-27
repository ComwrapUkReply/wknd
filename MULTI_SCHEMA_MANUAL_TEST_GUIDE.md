# Multi-Schema JSON-LD — Manual Test Guide

This guide walks through verifying that the multi-schema auto-injection works end-to-end on a local AEM instance after deploying the `multi-schema` branch.

---

## Prerequisites

- AEM SDK running at `http://localhost:4502` (admin/admin)
- Project built and deployed: `mvn clean install -PautoInstallSinglePackage -Denforcer.skip=true`
- WKND sample content installed (included in the default build via `ui.content.sample`)

---

## Step 1 — Verify the OSGi config is active

Open the Felix console and confirm the `SeoGlobalConfigService` is registered with the expected values.

1. Go to: `http://localhost:4502/system/console/configMgr`
2. Search for **"WKND SEO"**
3. You should see **"WKND SEO – Global Site Config"** listed
4. Click it and confirm the default values:

| Field | Expected default |
|---|---|
| Organization name | `WKND` |
| Organization URL | `https://www.wknd.site` |
| Logo DAM path / URL | `/content/dam/wknd/en/logos/wknd-logo.png` |
| Include Organization schema | `true` |
| Include BreadcrumbList | `true` |
| Include WebSite schema | `true` |
| Search URL template | *(empty)* |

If the entry is missing, check `http://localhost:4502/system/console/bundles` — the `aem-guides-wknd.core` bundle must be **Active**.

---

## Step 2 — Enable Schema.org on a WKND page

Navigate to a WKND article page and enable the JSON-LD output via Page Properties.

1. Go to the WKND Sites console: `http://localhost:4502/sites.html/content/wknd`
2. Navigate into any article, e.g. `/content/wknd/us/en/magazine/guide-la-skateparks`
3. Click **Page Properties → "Schema.org"** tab (last tab)
4. Ensure **"Emit Schema.org / JSON-LD on this page"** is checked (it defaults to `true`)
5. Set **Primary @type** to `Article`
6. Fill in **Headline** (or leave blank to fall back to `jcr:title`)
7. Under **Auto-injected schemas**, leave all three dropdowns on **"Inherit from global config (default)"**
8. Click **Save & Close**

---

## Step 3 — Inspect the rendered JSON-LD in page source

Open the published page source and look for the JSON-LD block.

1. Open the page in preview mode: `http://localhost:4502/content/wknd/us/en/magazine/guide-la-skateparks.html?wcmmode=disabled`
2. Right-click → **View Page Source** (or `Cmd+U` / `Ctrl+U`)
3. Search for `application/ld+json`

You should find **exactly one** `<script type="application/ld+json">` block containing a `@graph` array with three nodes:

```json
{
  "@context": "https://schema.org",
  "@graph": [
    {
      "@context": "https://schema.org",
      "@type": "Article",
      "headline": "...",
      ...
    },
    {
      "@type": "Organization",
      "name": "WKND",
      "url": "https://www.wknd.site",
      "@id": "https://www.wknd.site#organization",
      "logo": { "@type": "ImageObject", "url": "/content/dam/wknd/en/logos/wknd-logo.png" }
    },
    {
      "@type": "BreadcrumbList",
      "itemListElement": [
        { "@type": "ListItem", "position": 1, "name": "WKND", "item": "/content/wknd.html" },
        { "@type": "ListItem", "position": 2, "name": "US", "item": "/content/wknd/us.html" },
        ...
        { "@type": "ListItem", "position": N, "name": "Guide La Skateparks", "item": "/content/wknd/us/en/magazine/guide-la-skateparks.html" }
      ]
    },
    {
      "@type": "WebSite",
      "@id": "https://www.wknd.site#website",
      "url": "https://www.wknd.site",
      "name": "WKND"
    }
  ]
}
```

**What to verify:**
- `@graph` array is present (not a single flat object)
- The `Article` node has `headline`, `@id`, `url`, `inLanguage`
- The `Organization` node has `name: "WKND"` and a `logo` sub-object
- The `BreadcrumbList` has `ListItem` entries from the site root down to the current page, each with `position`, `name`, and `item`
- The `WebSite` node has `@id` ending in `#website`
- There is **only one** `<script type="application/ld+json">` block (not multiple)

---

## Step 4 — Validate with Google Rich Results Test

Copy the entire `<script type="application/ld+json">` content (just the JSON, without the tags) and paste it into Google's validator.

1. Go to: `https://search.google.com/test/rich-results`
2. Click the **"Code snippet"** tab
3. Paste the JSON
4. Click **"Test code"**

**Expected result:** No errors. The validator should detect at minimum:
- **Article** (eligible for Article rich result)
- **Breadcrumbs** (eligible for Breadcrumb rich result)

---

## Step 5 — Validate with Schema.org Validator

1. Go to: `https://validator.schema.org/`
2. Click the **"Code Snippet"** tab
3. Paste the same JSON
4. Click **Validate**

**Expected result:** All entities resolve cleanly with no errors. Each `@type` should be recognized.

---

## Step 6 — Test the OSGi config override (add a search URL)

Test that changing the OSGi config is reflected immediately without redeploying code.

1. Go to `http://localhost:4502/system/console/configMgr`
2. Open **"WKND SEO – Global Site Config"**
3. Set **Search URL template** to: `https://www.wknd.site/search?q={search_term_string}`
4. Click **Save**
5. Reload the page source (Step 3)

**Expected result:** The `WebSite` node now includes a `potentialAction` block:

```json
{
  "@type": "WebSite",
  ...
  "potentialAction": {
    "@type": "SearchAction",
    "target": {
      "@type": "EntryPoint",
      "urlTemplate": "https://www.wknd.site/search?q={search_term_string}"
    },
    "query-input": "required name=search_term_string"
  }
}
```

Revert the field to empty before continuing.

---

## Step 7 — Test per-page override: suppress Organization

Verify that an individual page can opt out of an auto-schema even when the global config has it enabled.

1. Return to Page Properties → **Schema.org** tab on the same article
2. Under **Auto-injected schemas**, change **Organization schema** to **"Exclude from this page"**
3. Click **Save & Close**
4. Reload the page source

**Expected result:**
- `@graph` still present (Breadcrumb + WebSite remain)
- **No** `"@type": "Organization"` node in the array

5. Change the dropdown back to **"Inherit from global config (default)"** and save when done.

---

## Step 8 — Test per-page override: force include on a page where global is off

Verify the "Include on this page" override works.

1. In the Felix console (`/system/console/configMgr`), set **Include BreadcrumbList** to `false` and Save.
2. Reload the page source — the `BreadcrumbList` node should disappear.
3. Open Page Properties → Schema.org on the article page.
4. Set **BreadcrumbList schema** to **"Include on this page"** and Save.
5. Reload the page source.

**Expected result:** `BreadcrumbList` is back **only** on this page.

6. Reset the OSGi config (set **Include BreadcrumbList** back to `true`) and the page dialog back to **"Inherit"** when done.

---

## Step 9 — Test that a top-level page has no BreadcrumbList

The breadcrumb builder skips pages that have fewer than two ancestors above `/content`.

1. Open any first-level WKND page, e.g. `http://localhost:4502/content/wknd.html?wcmmode=disabled`
2. View source

**Expected result:** No `BreadcrumbList` node in the `@graph` (the page sits at depth 2 — there is nothing above it to form a trail).

---

## Step 10 — Test that disabling Schema.org emits nothing

1. Open Page Properties → Schema.org on any page
2. Set **Strategy vs. Template Defaults** to **"Disable (no JSON-LD)"** and Save
3. View page source

**Expected result:** No `<script type="application/ld+json">` block anywhere on the page.

4. Change it back to **"Merge with template defaults"** and Save.

---

## Quick checklist

| # | Test | Pass |
|---|---|---|
| 1 | OSGi config visible in Felix console with correct defaults | ☐ |
| 2 | Page Properties → Schema.org tab saves without error | ☐ |
| 3 | Page source contains exactly one `<script type="application/ld+json">` | ☐ |
| 4 | Output is a `@graph` array (not a flat object) | ☐ |
| 5 | `Article` / `WebPage` primary node present | ☐ |
| 6 | `Organization` node present with name + logo | ☐ |
| 7 | `BreadcrumbList` present with correct page hierarchy | ☐ |
| 8 | `WebSite` node present | ☐ |
| 9 | Google Rich Results Test: no errors | ☐ |
| 10 | SearchAction appears after setting search URL in OSGi config | ☐ |
| 11 | Per-page "Exclude" suppresses the relevant auto-schema | ☐ |
| 12 | Per-page "Include" overrides a globally disabled auto-schema | ☐ |
| 13 | Top-level page has no `BreadcrumbList` | ☐ |
| 14 | "Disable" mode emits no JSON-LD at all | ☐ |

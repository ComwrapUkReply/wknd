# Schema.org Structured Data — Author User Manual

This manual is written for content authors and site administrators who manage
WKND pages. No coding knowledge is required.

---

## What is Schema.org and why does it matter?

Schema.org structured data is hidden metadata that search engines (Google,
Bing, Perplexity, ChatGPT) read alongside your visible page content. When it
is present and correct:

- Google may show **Rich Results** — star ratings, article dates, breadcrumbs,
  FAQ accordions — directly in search results.
- AI search assistants (ChatGPT, Perplexity, Google AI Overviews) can cite
  and summarise your pages more accurately.
- Bing Copilot can surface the right content in conversational answers.

The structured data is injected automatically into every page's `<head>` as
JSON-LD — authors never touch HTML directly.

---

## How it works — the two layers

| Layer | Where you edit it | Who should set it |
|---|---|---|
| **Template defaults** | Template → Policy dialog | Site administrator or lead author, once per template |
| **Page overrides** | Page → Properties → Schema.org tab | Author, per page |

At render time the two layers are merged. A page value always wins over a
template default for the same field. Fields left blank on the page fall back
to the template default automatically.

```
Template default:  publisher = "WKND Magazine"   type = "WebPage"
Page override:     type = "Article"               (publisher not set)
                   ─────────────────────────────────────────────────
Final output:      type = "Article"   publisher = "WKND Magazine"
```

---

## Part 1 — Template-level defaults

Template defaults are set once and apply to every page that uses that
template. They save authors from repeating the same publisher name, logo, and
base type on hundreds of pages.

### Step 1 — Open the template editor

1. Go to **Sites** → navigate to your site root.
2. In the top toolbar click **Templates** (or navigate to
   `http://localhost:4502/libs/wcm/core/content/sites/templates.html/conf/wknd`).
3. Find the **WKND Page** template and click to open it.

### Step 2 — Open the Page Policy

1. In the template editor toolbar, click the **Page Information** icon
   (the page with a settings cog, top-right area).
2. Select **Page Policy**.
3. In the dialog that opens, click the **Schema.org Defaults** tab.

### Step 3 — Configure the defaults

| Field | Recommended value | Notes |
|---|---|---|
| Emit Schema.org / JSON-LD on pages using this template | ✓ checked | Turn off only if you want no structured data site-wide |
| Default Strategy | `Merge with template defaults (recommended)` | Pages add to what you set here |
| Default Primary @type | `WebPage` | Good starting point; authors can override per page |
| Default Headline | *(leave blank)* | Falls back to `jcr:title` automatically |
| Default Description | *(leave blank)* | Falls back to `jcr:description` automatically |
| Default Author | `WKND Editorial` | Used when pages don't specify an author |
| Default Publisher Name | `WKND Magazine` | Appears in every Article JSON-LD |
| Default Publisher Logo | `/content/dam/wknd/en/magazine/logo.png` | DAM path to your logo |
| Default Keywords | `adventure`, `travel`, `outdoors` | Shared baseline keywords |

> **Tip — Article templates:** If you have a dedicated Article template, set
> Default Primary @type to `Article` and fill in Publisher Name and Publisher
> Logo here. Every article page inherits those values without authors having to
> repeat them.

### Step 4 — Save and confirm

Click **Done** in the policy dialog. The policy is saved immediately — no
page republish is needed at this stage.

---

## Part 2 — Page-level Schema.org

### Step 1 — Open Page Properties

1. In **Sites**, select a page (one click to highlight it).
2. In the toolbar click **Properties**, or right-click → **Properties**.
3. Click the **Schema.org** tab.

### Step 2 — Enable structured data

Make sure **Emit Schema.org / JSON-LD on this page** is checked. If unchecked,
no JSON-LD is emitted regardless of template settings.

### Step 3 — Choose the strategy

The **Strategy vs. Template Defaults** dropdown controls how page values
combine with the template policy:

| Strategy | What it does |
|---|---|
| **Merge with template defaults** *(recommended)* | Your page values fill in specific fields; anything you leave blank falls back to the template default. |
| **Override template defaults** | Only what you enter on this page appears. Template defaults are ignored entirely. |
| **Disable (no JSON-LD)** | No structured data on this page, even if the template has it enabled. Useful for utility pages (login, search, 404). |

### Step 4 — Choose the schema type

The **Primary @type** dropdown selects the main Schema.org type for this page.
Choose the type that best matches the content:

| Type | Use for |
|---|---|
| `WebPage` | Generic pages, homepages, landing pages |
| `Article` | Long-form editorial content |
| `NewsArticle` | News and press releases |
| `BlogPosting` | Blog posts |
| `Product` | Product detail pages |
| `Event` | Events and experiences |
| `FAQPage` | Pages built around questions and answers |
| `HowTo` | Step-by-step instructional content |
| `Recipe` | Recipe pages |
| `VideoObject` | Pages whose primary content is a video |
| `Place` | Location pages |
| `Organization` | Brand or company pages |
| `Person` | Profile and bio pages |
| `BreadcrumbList` | Navigation trail pages |

### Step 5 — Fill in descriptive fields

Leave any field blank to inherit the template default.

| Field | What it controls | Tip |
|---|---|---|
| **Headline** | `schema:headline` | Leave blank — falls back to the page title |
| **Description** | `schema:description` | Leave blank — falls back to the meta description |
| **Author** | `schema:author.name` | The person or organisation who wrote the content |
| **Date Published** | `schema:datePublished` | When the content was first published |
| **Date Modified** | `schema:dateModified` | Last significant edit date |
| **Primary Image** | `schema:image` | Pick a DAM image — used by Google for article cards |
| **Keywords** | `schema:keywords` | Click **+** to add keywords; separate them as individual entries |

### Step 6 — Article-specific fields

These fields appear automatically when you select `Article`, `NewsArticle`,
or `BlogPosting` as the type. They are hidden for all other types.

| Field | What it controls |
|---|---|
| **Article Section** | Topical category, e.g. `Travel`, `Culture`, `Food` |
| **Word Count** | Number of words in the article body |
| **Publisher Name** | Usually set once in the template; override here if needed |
| **Publisher Logo** | DAM path to the publisher's logo |
| **Author Type** | `Person` for an individual, `Organization` for a brand |
| **Author URL** | Link to the author's profile page |

### Step 7 — Generate with AI (optional)

If an AI provider is configured on the site, the **AI-assisted generation**
panel offers a one-click way to draft all the JSON-LD from your page content.

1. Optionally enter **Additional instructions** to guide the AI, for example:
   - `Emphasise the local region and include sameAs links.`
   - `Focus on the event date and location.`
2. Choose a **Provider** (leave on `Default` unless directed otherwise).
3. Click **Generate with AI**.
4. The **JSON-LD** textarea below fills with a draft — review it carefully.
5. Edit the raw JSON-LD if anything looks wrong.
6. Click **Save** to store it.

> **Important:** The AI reads your published page body to produce the draft.
> Add your page content first, then come back to generate the schema.
> The AI output is a draft — always review it before saving.

### Step 8 — Raw JSON-LD (advanced)

The **JSON-LD** textarea at the bottom accepts any valid Schema.org JSON-LD.
When this field contains a value it is emitted verbatim and all the individual
fields above it are ignored.

Use cases:
- Paste AI-generated output and refine it manually.
- Enter complex nested schemas not covered by the form fields.
- Paste output from the [Schema.org Markup Generator](https://technicalseo.com/tools/schema-markup-generator/).

To go back to using the form fields, clear this textarea and save.

### Step 9 — Save

Click **Save & Close**. The structured data is live immediately on the next
page render — no workflow or republish needed.

---

## Part 3 — Verify a single page

### In the browser

1. Open the live page URL.
2. Right-click anywhere → **View Page Source**.
3. Press **Ctrl+F** / **Cmd+F** and search for `application/ld+json`.
4. You should find a block like:

```html
<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "Article",
  "headline": "Napa Wine Tasting",
  "author": { "@type": "Person", "name": "WKND Editorial" },
  "publisher": { "@type": "Organization", "name": "WKND Magazine" }
}
</script>
```

5. If no block appears, the **Emit Schema.org** checkbox is off or
   **Strategy** is set to `Disable`.

### With Google's Rich Results Test

1. Publish the page to a publicly accessible URL.
2. Go to [search.google.com/test/rich-results](https://search.google.com/test/rich-results).
3. Paste the page URL and click **Test URL**.
4. A green result with your schema type means Google can read it correctly.
5. Errors shown here must be fixed before Google will show rich results.

### With Schema.org Validator

1. Go to [validator.schema.org](https://validator.schema.org).
2. Paste the JSON-LD block directly (without the `<script>` tags) or enter
   the page URL.
3. Check for errors (red) and warnings (yellow). Errors must be fixed;
   warnings are advisories.

---

## Part 4 — Bulk Schema Enrichment

The Bulk Enrichment tool lets an administrator apply Schema.org to an entire
subtree of pages in one operation — without opening each page individually.

### When to use it

- You have hundreds of existing pages with no Schema.org data and want to
  enable it quickly.
- You want to regenerate AI-powered JSON-LD for a whole section after
  significant content updates.
- You are launching a new country or language site and need baseline
  structured data before go-live.

### Access the tool

Navigate to:

```
http://localhost:4502/apps/wknd/tools/seoBulkManager.html
```

You must be logged in as an administrator or have the `wknd-schema-admin`
permission.

---

### The form fields explained

| Field | What it controls |
|---|---|
| **Root Path** | The top-level JCR path to start from. All pages beneath this path are processed. Example: `/content/wknd/us/en/adventures` |
| **Action** | See table below |
| **Schema Type** | Which `@type` to assign to each page |
| **Skip pages that already have Schema.org enabled** | When checked, pages that already have `Emit Schema.org` turned on are left untouched. Uncheck to overwrite everything. |

#### Action options

| Action | What it does | Speed | Requires AI |
|---|---|---|---|
| **Template Defaults (fast)** | Sets `Emit Schema.org = on` and `Strategy = Merge` on every page. No fields are filled — pages inherit everything from the template policy. | Very fast | No |
| **AI Generation** | Calls the AI provider once per page. Reads the page body, generates full JSON-LD, and saves it to the page's Raw JSON-LD field. | Slower (1 API call per page) | Yes |

#### Schema Type options

| Option | What it does |
|---|---|
| **Auto-detect** | Uses the type already saved on each page, or falls back to `WebPage` |
| `WebPage`, `Article`, `BlogPosting`, etc. | Forces that type on every page in the run |

---

### Running a bulk job — step by step

#### Option A — Template Defaults (recommended first run)

This is the fastest way to get baseline Schema.org onto all pages.

1. Open the Bulk Enrichment tool.
2. In **Root Path**, type or browse to the content subtree, for example
   `/content/wknd/us/en`.
3. Set **Action** to `Template Defaults (fast — no AI)`.
4. Set **Schema Type** to `Auto-detect`.
5. Leave **Skip pages that already have Schema.org enabled** unchecked for
   a first run (or check it to be safe on a mixed site).
6. Click **Start Bulk Enrichment**.

The button disables and a progress panel appears below:

```
[ Running ]   Elapsed: 12s

████████████░░░░░░░░░░░░   ~60%

Processed: 42  |  Success: 42  |  Failed: 0
```

7. Wait for the status to change to `Completed` (green).
8. The button re-enables.

> **Tip:** A typical WKND adventure subtree of 50 pages finishes in under
> 10 seconds with the Defaults action.

#### Option B — AI Generation

Use this after running Option A if you want richer, page-specific JSON-LD.

1. Ensure an AI provider is configured (ask your developer if unsure).
2. Set **Action** to `AI Generation (calls provider per page)`.
3. Set **Schema Type** to the appropriate type (e.g. `Article` for a blog
   section).
4. Check **Skip pages that already have Schema.org enabled** to avoid
   overwriting pages you have already manually curated.
5. Click **Start Bulk Enrichment**.

Progress updates every 3 seconds. AI jobs take longer — expect roughly
1–3 seconds per page depending on the provider.

> **Note:** If the AI provider is not reachable the job fails immediately
> with an error in the progress panel. The Defaults action is always
> available as a fallback.

---

### Reading the progress panel

| Status | Colour | Meaning |
|---|---|---|
| Queued | Blue | Job is waiting to start |
| Running | Blue | Actively processing pages |
| Completed | Green | All pages processed successfully |
| Failed | Red | Job encountered too many errors or a fatal problem |
| Cancelled | Yellow | Job was stopped |

The numbers show:
- **Processed** — total pages visited (includes skipped pages)
- **Success** — pages where Schema.org was written successfully
- **Failed** — pages that produced an error (expand the Errors list to see which ones and why)

---

### Verifying the bulk job results

#### Check a sample page

After the job completes, open a page from the subtree:

1. Go to Sites → select one of the pages in the subtree.
2. Open **Properties** → **Schema.org** tab.
3. **Emit Schema.org** should be checked.
4. **Strategy** should be `Merge with template defaults`.
5. If you ran AI Generation: the **JSON-LD** textarea should contain
   AI-generated content.

#### Check the live output

1. Open the page in a browser tab.
2. View source → search for `application/ld+json`.
3. Confirm the block is present and contains the expected `@type`.

#### Check multiple pages quickly

Open several pages in sequence using Sites list view and verify the Schema.org
tab on each. Alternatively, use the **List recent jobs** feature:

```
http://localhost:4502/bin/wknd/seo/bulk.json?action=list
```

This returns the last 20 jobs with their status and page counts.

---

## Part 5 — Strategy combinations reference

This table shows what appears in the final page output for every combination
of page and template settings.

| Template: Enabled | Page: Enabled | Page: Strategy | Output |
|---|---|---|---|
| ✓ | ✓ | Merge | Page fields + template fields for blanks |
| ✓ | ✓ | Override | Page fields only; template ignored |
| ✓ | ✓ | Disable | **No JSON-LD** |
| ✓ | ✗ | *(any)* | **No JSON-LD** |
| ✗ | ✓ | Merge | Page fields only (nothing to merge from template) |
| ✗ | ✗ | *(any)* | **No JSON-LD** |

> **Recommendation:** Leave the template set to Enabled + Merge. Use
> `Disable` on individual pages (login, utility, error pages) rather than
> turning off the template default.

---

## Part 6 — Field priority rules

When the same field is set at both levels, this is the order of precedence
(highest wins):

```
1. Raw JSON-LD textarea (page)       ← if populated, all other fields ignored
2. Structured fields (page)
3. Structured fields (template policy)
4. Automatic fallbacks:
     headline   → jcr:title
     description → jcr:description
```

Practical example:

- Template has `author = "WKND Editorial"`.
- Page has `author = "Jane Smith"`.
- Final output: `"author": "Jane Smith"` — page wins.

If you clear the page author field and save, the template's `"WKND Editorial"`
reappears in the next render.

---

## Part 7 — Supported Schema.org types quick reference

| Type | Key fields populated automatically |
|---|---|
| `WebPage` | `name`, `description`, `url`, `image` |
| `Article` | All WebPage fields + `headline`, `author`, `datePublished`, `dateModified`, `publisher`, `articleSection`, `wordCount`, `keywords` |
| `NewsArticle` | Same as Article |
| `BlogPosting` | Same as Article |
| `Product` | `name`, `description`, `image` |
| `Event` | `name`, `description`, `image`, `startDate` (from datePublished) |
| `FAQPage` | `name`, `description` (add FAQ `mainEntity` via Raw JSON-LD) |
| `HowTo` | `name`, `description` (add steps via Raw JSON-LD) |
| `Recipe` | `name`, `description`, `image` |
| `VideoObject` | `name`, `description`, `thumbnailUrl` (from image) |
| `Place` | `name`, `description` |
| `Organization` | `name`, `description`, `logo` (from image) |
| `Person` | `name`, `description`, `image` |

---

## Part 8 — Common mistakes and how to fix them

### "I saved but I see no JSON-LD in the page source"

- Is **Emit Schema.org** checked? Open Page Properties → Schema.org tab and
  confirm.
- Is **Strategy** set to `Disable`? Change it to `Merge`.
- Did you check the template policy too? If the template has `Enabled = off`,
  it doesn't block page-level output but it removes template defaults.
- Is the page published? View source on the author preview URL
  (`?wcmmode=disabled`) to test without publishing.

### "The AI button does nothing / spins forever"

- The AI provider may not be configured. Ask your developer to check the
  OSGi configuration.
- The page may have no content yet. Add text components, save, then try again.
- Check the browser console for a network error on `/bin/wknd/seo/generate`.

### "AI generated text that looks wrong"

- Add specific **Additional instructions** to guide the AI, for example:
  `This is a wine tasting experience page. Include region and varietal details.`
- Edit the Raw JSON-LD textarea directly after generation.
- If the output is completely wrong, switch to filling in the structured
  fields manually instead.

### "Template defaults are not showing up on the page"

- Confirm the page **Strategy** is `Merge` (not `Override` or `Disable`).
- Confirm the template policy was saved correctly:
  open the template editor → Page Policy → Schema.org Defaults → verify the
  values are there.

### "After a bulk job, some pages still show no JSON-LD"

- Those pages may have had `Emit Schema.org = false` explicitly set before
  the bulk job. Open the page and check — the bulk job sets `enabled = true`
  only on the `seo` node; it does not change an explicitly disabled flag.
- Run the bulk job again with **Skip pages that already have Schema.org
  enabled** unchecked to force-overwrite.

### "The bulk job shows Failed errors for some pages"

- Expand the **Errors** list in the progress panel to see which page paths
  failed and why.
- Common reasons: page has no `jcr:content` (structural page, not a content
  page), or the AI provider returned an error for that page's content.
- Failed pages can be fixed individually via Page Properties.

---

## Quick-start checklist

Use this before a site launch to ensure Schema.org is correctly configured:

- [ ] Template policy has **Emit Schema.org** checked
- [ ] Template policy has a **Default Publisher Name** and **Publisher Logo**
- [ ] Template policy **Default @type** is `WebPage` (or `Article` for article templates)
- [ ] Bulk enrichment run with **Template Defaults** action on all content sections
- [ ] Sample pages verified in browser source — JSON-LD block present
- [ ] Sample pages verified in Google Rich Results Test — no errors
- [ ] High-value pages have page-level overrides for `Article` type with dates
- [ ] Utility pages (login, search, error) have **Strategy = Disable**

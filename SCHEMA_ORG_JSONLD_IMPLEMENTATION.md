# AEM Schema.org / JSON-LD Implementation Summary

## Overview

This implementation adds a reusable Structured Data layer to WKND on AEM as a Cloud Service.
It supports:

- Manual authoring of Schema.org metadata
- AI-assisted generation of JSON-LD
- Template-level defaults (policy)
- Page-level overrides
- Runtime JSON-LD rendering in the page `<head>`

The feature is designed for SEO, AEO (Answer Engine Optimization), and GEO use cases.

## Is It Page Level or Template Level?

It is **both**:

- **Template/Policy level**: author sets reusable defaults for pages using that template.
- **Page level**: author can override or extend those defaults per page.

The final output is resolved at request time by the Sling Model.

## Authoring Locations

### 1) Page Properties

- Dialog extension: `ui.apps/src/main/content/jcr_root/apps/wknd/components/page/_cq_dialog/.content.xml`
- Reusable tab fragment: `ui.apps/src/main/content/jcr_root/apps/wknd/components/seo/dialog-tab/.content.xml`
- Stored on page under: `jcr:content/seo/*`

### 2) Template Policy (Design Dialog)

- Dialog extension: `ui.apps/src/main/content/jcr_root/apps/wknd/components/page/_cq_design_dialog/.content.xml`
- Uses dedicated manual-defaults tab fragment:
  `ui.apps/src/main/content/jcr_root/apps/wknd/components/seo/dialog-tab-template/.content.xml`
- Stored on policy node under: `.../policies/.../seo/*`
- AI generation controls are intentionally hidden at template level.

## Merge / Override Behavior

The field `./seo/mode` controls page-vs-template behavior:

- `merge` (default): page values override template values per field; missing fields fall back to template defaults.
- `override`: use page values only (ignore template defaults).
- `disable`: do not emit JSON-LD.

## Runtime Rendering Flow

1. Request hits a page.
2. `SeoSchemaModelImpl` reads:
   - page-level values from `jcr:content/seo`
   - policy defaults from `ContentPolicyManager`
3. Model resolves merge strategy and builds final JSON-LD.
4. `customheaderlibs.html` renders:
   - `<script type="application/ld+json">...</script>`

Key files:

- Model API: `core/src/main/java/com/adobe/aem/guides/wknd/core/seo/models/SeoSchemaModel.java`
- Model impl: `core/src/main/java/com/adobe/aem/guides/wknd/core/seo/models/impl/SeoSchemaModelImpl.java`
- HTL render point: `ui.apps/src/main/content/jcr_root/apps/wknd/components/page/customheaderlibs.html`

## Manual vs AI Authoring

### Manual Authoring

Authors can fully manage Schema data manually using:

- structured form fields (type, headline, description, author, dates, image, keywords)
- raw JSON-LD textarea (`./seo/jsonLd`)

### AI-Assisted Authoring

- Button in dialog: "Generate with AI"
- Client-side wiring: `ui.apps/src/main/content/jcr_root/apps/wknd/clientlibs/clientlib-seo-authoring/js/seo-dialog.js`
- Backend endpoint: `POST /bin/wknd/seo/generate`
- Servlet: `core/src/main/java/com/adobe/aem/guides/wknd/core/seo/servlets/SchemaAiServlet.java`
- Provider SPI:
  - `SchemaAiProvider`
  - `OpenAiSchemaProvider`

## Priority Rules (Important)

1. If raw JSON-LD (`./seo/jsonLd`) is populated, it is emitted as final payload.
2. Otherwise JSON-LD is built from structured fields.
3. Strategy (`merge` / `override` / `disable`) still applies.

This means authors can always replace AI output with manual edits.

## OSGi Configuration (AI Provider)

Config file:

- `ui.config/src/main/content/jcr_root/apps/wknd/osgiconfig/config.author/com.adobe.aem.guides.wknd.core.seo.ai.impl.OpenAiSchemaProvider.cfg.json`

Typical fields:

- `apiKey`
- `baseUrl`
- `model`
- `timeoutSeconds`
- `maxBodyChars`

For cloud environments, use secret/env substitution (do not hardcode keys).

## Validation Checklist

After deployment:

1. Bundle `aem-guides-wknd.core` is **Active** in `/system/console/bundles`.
2. SEO components are visible in `/system/console/components`.
3. SEO tab appears in Page Properties and Template Policy dialogs.
4. Saved values produce JSON-LD in page source.
5. `mode=disable` removes JSON-LD.
6. AI generation populates textarea when provider is configured.


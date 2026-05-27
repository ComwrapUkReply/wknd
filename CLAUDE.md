# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Requirements

- Java 21
- Maven 3.9.4+
- Node.js (for `ui.frontend`)
- A running AEM instance at `localhost:4502` (author) / `localhost:4503` (publish) for deployment

## Build & Deploy Commands

```bash
# Full build + deploy to local AEM (AEM as a Cloud Service SDK)
mvn clean install -PautoInstallSinglePackage

# AEM 6.5 variant
mvn clean install -PautoInstallSinglePackage -Pclassic

# Deploy only the OSGi bundle (faster Java-only iteration)
mvn clean install -PautoInstallBundle -pl core

# Deploy to publish instance
mvn clean install -PautoInstallSinglePackagePublish
```

## Tests

```bash
# Run all unit tests
mvn test

# Run a single test class
mvn test -pl core -Dtest=SeoSchemaModelImplTest

# Run a single test method
mvn test -pl core -Dtest=SeoSchemaModelImplTest#testBuildJsonLd
```

## Frontend (ui.frontend)

```bash
cd ui.frontend
npm ci

npm run dev    # build with source maps, no optimization
npm run prod   # optimized production build
npm run start  # webpack-dev-server proxied to localhost:4502
```

Compiled output goes to `ui.frontend/dist/` and is then copied into `ui.apps/…/clientlibs/` by `aem-clientlib-generator`. The only files AEM consumes are `site.js`, `site.css`, `dependencies.js`, and `dependencies.css`.

## Module Structure

| Module | Purpose |
|---|---|
| `core` | OSGi bundle: Sling Models, servlets, OSGi services |
| `ui.apps` | Component HTL templates, client libraries, dialog XML, overlay configs |
| `ui.apps.structure` | Repository structure package (required for Cloud Service) |
| `ui.config` | OSGi configurations per run mode (`config`, `config.author`, `config.publish`, `config.prod`, etc.) |
| `ui.content` | Editable templates, policies, i18n, basic site structure |
| `ui.content.sample` | Full pre-authored WKND reference site (overwrites `/content/wknd` on each build — set `mode="merge"` in its `filter.xml` to prevent this) |
| `ui.frontend` | Webpack/TypeScript/SCSS build; output is synced into `ui.apps` clientlibs |
| `all` | Content package aggregating all modules for single-package deployment |
| `it.tests` | AEM integration tests (run against a live AEM instance) |
| `ui.tests` | Selenium/Cypress UI tests |

## Architecture: Key Patterns

### Sling Models
Java models live in `core/src/main/java/…/core/models/` (interface) and `…/models/impl/` (implementation). Models are annotated with `@Model(adaptables = SlingHttpServletRequest.class, ...)`. Tests use the AEM Mocks framework (`AemContext`).

### Components
Each AEM component is a folder under `ui.apps/…/components/<name>/` containing:
- `.content.xml` — component metadata + `sling:resourceType`
- `<name>.html` — HTL rendering template (uses `data-sly-use` for model binding)
- `_cq_dialog/.content.xml` — author dialog
- `_cq_design_dialog/.content.xml` — template policy dialog (optional)

Components extend AEM Core Components via `sling:resourceSuperType`. Custom components: `byline`, `helloworld`, `image-list`, `seo`.

### Schema.org / JSON-LD (custom feature on `multi-schema` branch)

This project adds a full Schema.org structured data system for SEO/GEO. Entry point: `customheaderlibs.html` calls `SeoSchemaModel` and emits a `<script type="application/ld+json">` block when enabled.

**Three-tier precedence:** page-level JCR properties (`jcr:content/seo/…`) override template policy defaults, which override built-in fallbacks (e.g. `jcr:title` → `headline`, `cq:tags` → `keywords`).

**Key Java classes:**
- `SeoSchemaModelImpl` — builds JSON-LD; merges policy + page values; handles `@graph` when multiple schemas are combined
- `SchemaAiServlet` — POST `/bin/wknd/seo/generate`; delegates to a `SchemaAiProvider`
- `SchemaAiProvider` (SPI) — pluggable AI providers: `OpenAiSchemaProvider`, `GeminiSchemaProvider`, `OllamaSchemaProvider`

**OSGi configs** for AI providers live in `ui.config/…/config.author/`:
- `com.adobe.aem.guides.wknd.core.seo.ai.impl.OpenAiSchemaProvider.cfg.json`
- `…GeminiSchemaProvider.cfg.json`
- `…OllamaSchemaProvider.cfg.json`

**Author dialog** fragments are under `ui.apps/…/components/seo/dialog-tab/` (page dialog) and `dialog-tab-template/` (policy dialog), included via `granite:include` into `page/_cq_dialog/` and `page/_cq_design_dialog/`.

Supported schema types: `WebPage`, `Organization`, `Article`, `NewsArticle`, `BlogPosting`, `Product`, `Event`, `FAQPage`, `HowTo`, `Person`, `Place`, `Recipe`, `VideoObject`, `BreadcrumbList`.

### OSGi Configuration Convention
Configs are split by run mode under `ui.config/…/osgiconfig/`:
- `config/` — all environments
- `config.author/` — author only
- `config.publish/` — publish only
- `config.prod/`, `config.stage/` — environment-specific overrides

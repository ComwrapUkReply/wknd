/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.adobe.aem.guides.wknd.core.seo.models;

import org.osgi.annotation.versioning.ProviderType;

/**
 * View-model consumed by {@code customheaderlibs.html} to render a single
 * {@code <script type="application/ld+json">} block per page.
 */
@ProviderType
public interface SeoSchemaModel {

    /**
     * True when there is content to emit (enabled AND merged payload non-empty).
     * HTL should short-circuit on this.
     */
    boolean isEnabled();

    /**
     * Merged/resolved JSON-LD as a ready-to-embed string. Empty when disabled.
     * Already HTML-safe (no closing {@code </script>} sequences).
     */
    String getJsonLd();
}

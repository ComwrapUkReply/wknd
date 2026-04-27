/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo.ai;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Immutable, defensive-copy input to {@link SchemaAiProvider#generate}.
 *
 * Kept deliberately simple (primitives + a context map) to avoid leaking
 * AEM types into the SPI so that providers can be unit-tested in isolation.
 */
@ProviderType
public final class GenerationRequest {

    /** Absolute JCR path of the page being authored, e.g. /content/wknd/en/adventures/foo. */
    private final String pagePath;

    /** Primary schema.org type (e.g. "Article", "Product"). Never null; defaults to "WebPage". */
    private final String schemaType;

    /** Page headline / jcr:title fallback. May be null. */
    private final String title;

    /** Page description / jcr:description fallback. May be null. */
    private final String description;

    /** Plain-text body extracted from the page. May be null. */
    private final String bodyText;

    /** Absolute URL of the page as it will be served (canonical). May be null. */
    private final String canonicalUrl;

    /** Optional author-provided extra instructions to the AI. May be null. */
    private final String authorPrompt;

    /** Locale tag (e.g. "en-US"). May be null. */
    private final String locale;

    /** Additional, provider-specific context. Never null. Immutable. */
    private final Map<String, Object> context;

    private GenerationRequest(final Builder b) {
        this.pagePath = b.pagePath;
        this.schemaType = (b.schemaType == null || b.schemaType.isEmpty()) ? "WebPage" : b.schemaType;
        this.title = b.title;
        this.description = b.description;
        this.bodyText = b.bodyText;
        this.canonicalUrl = b.canonicalUrl;
        this.authorPrompt = b.authorPrompt;
        this.locale = b.locale;
        this.context = (b.context == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(b.context);
    }

    public String getPagePath()     { return pagePath; }
    public String getSchemaType()   { return schemaType; }
    public String getTitle()        { return title; }
    public String getDescription()  { return description; }
    public String getBodyText()     { return bodyText; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public String getAuthorPrompt() { return authorPrompt; }
    public String getLocale()       { return locale; }
    public Map<String, Object> getContext() { return context; }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder - preferred construction path. */
    public static final class Builder {
        private String pagePath;
        private String schemaType;
        private String title;
        private String description;
        private String bodyText;
        private String canonicalUrl;
        private String authorPrompt;
        private String locale;
        private Map<String, Object> context;

        public Builder pagePath(final String v)     { this.pagePath = v; return this; }
        public Builder schemaType(final String v)   { this.schemaType = v; return this; }
        public Builder title(final String v)        { this.title = v; return this; }
        public Builder description(final String v)  { this.description = v; return this; }
        public Builder bodyText(final String v)     { this.bodyText = v; return this; }
        public Builder canonicalUrl(final String v) { this.canonicalUrl = v; return this; }
        public Builder authorPrompt(final String v) { this.authorPrompt = v; return this; }
        public Builder locale(final String v)       { this.locale = v; return this; }
        public Builder context(final Map<String, Object> v) { this.context = v; return this; }

        public GenerationRequest build() {
            Objects.requireNonNull(pagePath, "pagePath is required");
            return new GenerationRequest(this);
        }
    }
}

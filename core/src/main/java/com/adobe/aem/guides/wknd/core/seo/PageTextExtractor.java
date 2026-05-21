/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo;

import org.osgi.annotation.versioning.ProviderType;

import com.day.cq.wcm.api.Page;

/**
 * Extracts readable plain text from a CQ {@link Page}'s JCR content tree.
 * Shared between the per-page AI servlet and the bulk enrichment job.
 */
@ProviderType
public interface PageTextExtractor {

    /**
     * Walk the content resource tree of {@code page}, collect text from known
     * text properties, strip HTML, and return the result capped at
     * {@code maxChars} characters.
     *
     * @param page     the page whose content to extract; must not be {@code null}
     * @param maxChars maximum number of characters to return; values &lt;= 0
     *                 are treated as the default cap (12,000)
     * @return plain-text body, never {@code null}, empty when the page has no
     *         content resource
     */
    String extractBodyText(Page page, int maxChars);
}

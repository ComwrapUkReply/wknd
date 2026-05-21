/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;

import com.adobe.aem.guides.wknd.core.seo.PageTextExtractor;
import com.day.cq.wcm.api.Page;

/**
 * Default implementation of {@link PageTextExtractor}.
 * Logic extracted verbatim from {@code SchemaAiServlet}.
 */
@Component(service = PageTextExtractor.class)
public class PageTextExtractorImpl implements PageTextExtractor {

    private static final int DEFAULT_MAX_CHARS = 12_000;

    private static final String[] TEXT_PROPERTY_NAMES = {
            "text", "jcr:title", "title", "headline",
            "description", "jcr:description", "cq:panelTitle"
    };

    @Override
    public String extractBodyText(final Page page, final int maxChars) {
        if (page == null) { return ""; }
        final Resource content = page.getContentResource();
        if (content == null) { return ""; }
        final int cap = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        final StringBuilder sb = new StringBuilder(4096);
        collectText(content, sb, cap);
        return sb.length() > cap ? sb.substring(0, cap) : sb.toString();
    }

    private void collectText(final Resource res, final StringBuilder sb, final int cap) {
        if (res == null || sb.length() >= cap) { return; }
        final ValueMap vm = res.getValueMap();
        for (final String pn : TEXT_PROPERTY_NAMES) {
            final String v = vm.get(pn, String.class);
            if (StringUtils.isNotBlank(v)) {
                sb.append(stripHtml(v)).append('\n');
                if (sb.length() >= cap) { return; }
            }
        }
        for (final Resource child : res.getChildren()) {
            collectText(child, sb, cap);
        }
    }

    private String stripHtml(final String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}

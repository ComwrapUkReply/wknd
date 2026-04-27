/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.adobe.aem.guides.wknd.core.seo.ai.GenerationRequest;
import com.adobe.aem.guides.wknd.core.seo.ai.GenerationResult;
import com.adobe.aem.guides.wknd.core.seo.ai.SchemaAiProvider;

/**
 * Author-only endpoint that drafts JSON-LD for a page via a
 * {@link SchemaAiProvider}. Wired from the Granite UI dialog button
 * "Generate with AI".
 *
 * POST /bin/wknd/seo/generate
 *   pagePath=   (required) absolute JCR path of the page
 *   schemaType= (optional) schema.org @type, defaults to WebPage
 *   providerId= (optional) explicit provider id, e.g. "openai"
 *   authorPrompt= (optional) extra instructions for the AI
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/wknd/seo/generate",
                "sling.servlet.methods=POST",
                "sling.servlet.extensions=json"
        })
public class SchemaAiServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final String PARAM_PAGE_PATH    = "pagePath";
    private static final String PARAM_SCHEMA_TYPE  = "schemaType";
    private static final String PARAM_PROVIDER_ID  = "providerId";
    private static final String PARAM_AUTHOR_PROMPT = "authorPrompt";
    private static final String PN_JCR_TITLE       = "jcr:title";
    private static final String PN_JCR_DESCRIPTION = "jcr:description";
    private static final int    MAX_BODY_CHARS      = 12000;

    private static final String[] TEXT_PROPERTY_NAMES = {
            "text", "jcr:title", "title", "headline", "description", "jcr:description", "cq:panelTitle"
    };

    private static final Logger LOG = LoggerFactory.getLogger(SchemaAiServlet.class);

    // ---- Dynamic provider list --------------------------------------------

    private final List<SchemaAiProvider> providers = new CopyOnWriteArrayList<>();

    @Reference(service = SchemaAiProvider.class,
               cardinality = ReferenceCardinality.MULTIPLE,
               policy = ReferencePolicy.DYNAMIC)
    protected void bindProvider(final SchemaAiProvider p) {
        if (p != null) { providers.add(p); }
    }

    protected void unbindProvider(final SchemaAiProvider p) {
        providers.remove(p);
    }

    // ---- Handler ----------------------------------------------------------

    @Override
    protected void doPost(final SlingHttpServletRequest request,
                          final SlingHttpServletResponse response) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        final String pagePath = StringUtils.trimToNull(request.getParameter(PARAM_PAGE_PATH));
        if (pagePath == null) {
            writeError(response, 400, "Missing required parameter: " + PARAM_PAGE_PATH);
            return;
        }

        final ResourceResolver resolver = request.getResourceResolver();
        final PageManager pm = resolver.adaptTo(PageManager.class);
        final Page page = (pm == null) ? null : pm.getPage(pagePath);
        if (page == null) {
            writeError(response, 404, "Page not found: " + pagePath);
            return;
        }

        final SchemaAiProvider provider = selectProvider(request.getParameter(PARAM_PROVIDER_ID));
        if (provider == null) {
            writeError(response, 503, "No AI provider is available. Check OSGi configuration.");
            return;
        }

        final GenerationResult result = provider.generate(buildRequest(request, page));
        if (!result.isSuccess()) {
            writeError(response, 502, result.getErrorMessage());
            return;
        }

        final JsonObjectBuilder body = Json.createObjectBuilder()
                .add("success", true)
                .add("providerId", nonNull(result.getProviderId()))
                .add("jsonLd", nonNull(result.getJsonLd()));

        try (PrintWriter w = response.getWriter()) {
            w.write(body.build().toString());
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private SchemaAiProvider selectProvider(final String requestedId) {
        if (StringUtils.isNotBlank(requestedId)) {
            for (final SchemaAiProvider p : providers) {
                if (requestedId.equalsIgnoreCase(p.getId()) && p.isAvailable()) { return p; }
            }
        }
        for (final SchemaAiProvider p : providers) {
            if (p.isAvailable()) { return p; }
        }
        return null;
    }

    private GenerationRequest buildRequest(final SlingHttpServletRequest request, final Page page) {
        final ValueMap props    = page.getProperties();
        final String schemaType = StringUtils.defaultIfBlank(request.getParameter(PARAM_SCHEMA_TYPE), "WebPage");
        return GenerationRequest.builder()
                .pagePath(page.getPath())
                .schemaType(schemaType)
                .title(props == null ? null : props.get(PN_JCR_TITLE, String.class))
                .description(props == null ? null : props.get(PN_JCR_DESCRIPTION, String.class))
                .bodyText(extractBodyText(page))
                .locale(page.getLanguage(false) != null ? page.getLanguage(false).toString() : null)
                .authorPrompt(StringUtils.trimToNull(request.getParameter(PARAM_AUTHOR_PROMPT)))
                .build();
    }

    private String extractBodyText(final Page page) {
        final Resource content = page.getContentResource();
        if (content == null) { return ""; }
        final StringBuilder sb = new StringBuilder(4096);
        collectText(content, sb);
        return sb.length() > MAX_BODY_CHARS ? sb.substring(0, MAX_BODY_CHARS) : sb.toString();
    }

    private void collectText(final Resource res, final StringBuilder sb) {
        if (res == null || sb.length() >= MAX_BODY_CHARS) { return; }
        final ValueMap vm = res.getValueMap();
        for (final String pn : TEXT_PROPERTY_NAMES) {
            final String v = vm.get(pn, String.class);
            if (StringUtils.isNotBlank(v)) {
                sb.append(stripHtml(v)).append('\n');
                if (sb.length() >= MAX_BODY_CHARS) { return; }
            }
        }
        for (final Resource child : res.getChildren()) {
            collectText(child, sb);
        }
    }

    private String stripHtml(final String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private String nonNull(final String s) { return s == null ? "" : s; }

    private void writeError(final SlingHttpServletResponse response, final int status,
                            final String message) throws IOException {
        response.setStatus(status);
        final JsonObject body = Json.createObjectBuilder()
                .add("success", false)
                .add("error", message == null ? "" : message)
                .build();
        try (PrintWriter w = response.getWriter()) {
            w.write(body.toString());
        }
    }
}

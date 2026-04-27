/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.adobe.aem.guides.wknd.core.seo.models.impl;

import java.io.StringReader;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.JsonWriter;
import javax.json.stream.JsonGenerator;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ScriptVariable;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;
import com.adobe.aem.guides.wknd.core.seo.models.SeoSchemaModel;

/**
 * Merges template (policy) defaults with page-level Schema.org properties
 * and renders a single JSON-LD document.
 *
 * Precedence per field: page value → template default.
 * When the author pastes raw JSON-LD (./seo/jsonLd) it is trusted and
 * emitted verbatim (after safety sanitization).
 */
@Model(
        adaptables = SlingHttpServletRequest.class,
        adapters = SeoSchemaModel.class,
        defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SeoSchemaModelImpl implements SeoSchemaModel {

    // ---- Property names ---------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(SeoSchemaModelImpl.class);

    private static final String SEO_NODE          = "seo";
    private static final String PN_ENABLED        = "enabled";
    private static final String PN_MODE           = "mode";
    private static final String PN_TYPE           = "type";
    private static final String PN_HEADLINE       = "headline";
    private static final String PN_DESCRIPTION    = "description";
    private static final String PN_AUTHOR         = "author";
    private static final String PN_DATE_PUBLISHED = "datePublished";
    private static final String PN_DATE_MODIFIED  = "dateModified";
    private static final String PN_IMAGE          = "image";
    private static final String PN_KEYWORDS       = "keywords";
    private static final String PN_JSON_LD        = "jsonLd";

    private static final String MODE_DISABLE  = "disable";
    private static final String MODE_OVERRIDE = "override";
    private static final String DEFAULT_TYPE  = "WebPage";
    private static final String SCHEMA_CONTEXT = "https://schema.org";

    // ---- Injections -------------------------------------------------------

    @Self
    private SlingHttpServletRequest request;

    @ScriptVariable
    private com.day.cq.wcm.api.Page currentPage;

    // ---- Derived state ----------------------------------------------------

    private boolean enabled;
    private String renderedJsonLd = "";

    // ---- Lifecycle --------------------------------------------------------

    @PostConstruct
    protected void init() {
        try {
            final ValueMap pageSeo   = readPageSeo();
            final ValueMap policySeo = readPolicySeo();

            final String  mode        = StringUtils.defaultIfBlank(pageSeo.get(PN_MODE, String.class), "merge");
            final boolean pageEnabled = pageSeo.get(PN_ENABLED, Boolean.TRUE);

            if (MODE_DISABLE.equalsIgnoreCase(mode) || !pageEnabled) {
                this.enabled = false;
                return;
            }

            final String rendered = buildJsonLd(pageSeo, policySeo, mode);
            if (StringUtils.isBlank(rendered)) {
                this.enabled = false;
                return;
            }
            this.renderedJsonLd = sanitize(rendered);
            this.enabled        = StringUtils.isNotBlank(this.renderedJsonLd);

        } catch (final RuntimeException re) {
            // SEO must never break page rendering.
            LOG.warn("Failed to build JSON-LD for {}: {}",
                    currentPage != null ? currentPage.getPath() : "?", re.toString());
            this.enabled      = false;
            this.renderedJsonLd = "";
        }
    }

    // ---- API --------------------------------------------------------------

    @Override public boolean isEnabled() { return enabled; }
    @Override public String  getJsonLd() { return renderedJsonLd; }

    // ---- JCR readers ------------------------------------------------------

    private ValueMap readPageSeo() {
        if (currentPage == null) { return emptyMap(); }
        final Resource content = currentPage.getContentResource();
        if (content == null)    { return emptyMap(); }
        final Resource seo = content.getChild(SEO_NODE);
        return (seo != null) ? seo.getValueMap() : emptyMap();
    }

    private ValueMap readPolicySeo() {
        if (request == null || currentPage == null) { return emptyMap(); }
        final ContentPolicyManager cpm = request.getResourceResolver().adaptTo(ContentPolicyManager.class);
        final Resource content = currentPage.getContentResource();
        if (cpm == null || content == null)         { return emptyMap(); }
        final ContentPolicy policy = cpm.getPolicy(content);
        if (policy == null)                         { return emptyMap(); }
        final Resource policyRes = policy.adaptTo(Resource.class);
        if (policyRes == null)                      { return emptyMap(); }
        final Resource seo = policyRes.getChild(SEO_NODE);
        return (seo != null) ? seo.getValueMap() : emptyMap();
    }

    private ValueMap emptyMap() {
        return new ValueMapDecorator(Collections.emptyMap());
    }

    // ---- JSON-LD builder --------------------------------------------------

    private String buildJsonLd(final ValueMap page, final ValueMap policy, final String mode) {
        final boolean override = MODE_OVERRIDE.equalsIgnoreCase(mode);
        final Map<String, JsonObject> graph = new LinkedHashMap<>();

        addRaw(graph, policy.get(PN_JSON_LD, String.class), "policy");
        if (!override) {
            // page raw overrides policy raw
        } else {
            graph.clear();
        }
        addRaw(graph, page.get(PN_JSON_LD, String.class), "page");

        if (graph.isEmpty()) {
            final JsonObject built = buildFromFields(page, policy, override);
            return (built != null) ? pretty(built) : "";
        }
        if (graph.size() == 1) {
            return pretty(graph.values().iterator().next());
        }
        return pretty(wrapGraph(graph.values()));
    }

    private void addRaw(final Map<String, JsonObject> graph, final String raw, final String origin) {
        if (StringUtils.isBlank(raw)) { return; }
        try (JsonReader r = Json.createReader(new StringReader(raw.trim()))) {
            final JsonValue v = r.readValue();
            if (v.getValueType() == JsonValue.ValueType.OBJECT) {
                final JsonObject obj = v.asJsonObject();
                if (obj.containsKey("@graph") && obj.get("@graph").getValueType() == JsonValue.ValueType.ARRAY) {
                    for (final JsonValue item : obj.getJsonArray("@graph")) {
                        if (item.getValueType() == JsonValue.ValueType.OBJECT) {
                            graph.put(origin + ":" + graph.size(), item.asJsonObject());
                        }
                    }
                } else {
                    graph.put(origin + ":" + graph.size(), obj);
                }
            }
        } catch (final RuntimeException re) {
            LOG.debug("Skipping invalid JSON-LD ({}): {}", origin, re.toString());
        }
    }

    private JsonObject wrapGraph(final Iterable<JsonObject> nodes) {
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        for (final JsonObject n : nodes) { arr.add(n); }
        return Json.createObjectBuilder()
                .add("@context", SCHEMA_CONTEXT)
                .add("@graph", arr)
                .build();
    }

    private JsonObject buildFromFields(final ValueMap page, final ValueMap policy, final boolean override) {
        final String type        = StringUtils.defaultIfBlank(pickString(page, override ? null : policy, PN_TYPE), DEFAULT_TYPE);
        final String headline    = StringUtils.defaultIfBlank(pickString(page, override ? null : policy, PN_HEADLINE), pageTitle());
        final String description = StringUtils.defaultIfBlank(pickString(page, override ? null : policy, PN_DESCRIPTION), pageDescription());
        final String author      = pickString(page, override ? null : policy, PN_AUTHOR);
        final String image       = pickString(page, override ? null : policy, PN_IMAGE);
        final String datePub     = pickDate(page, override ? null : policy, PN_DATE_PUBLISHED);
        final String dateMod     = pickDate(page, override ? null : policy, PN_DATE_MODIFIED);
        final List<String> keywords = pickList(page, override ? null : policy, PN_KEYWORDS);

        if (StringUtils.isBlank(headline) && StringUtils.isBlank(description)
                && StringUtils.isBlank(author) && keywords.isEmpty()) {
            return null;
        }

        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("@context", SCHEMA_CONTEXT)
                .add("@type", type);

        final String url = canonicalUrl();
        if (StringUtils.isNotBlank(url)) {
            b.add("url", url);
            b.add("mainEntityOfPage", Json.createObjectBuilder().add("@id", url));
        }
        if (StringUtils.isNotBlank(headline))    { b.add("headline", headline); b.add("name", headline); }
        if (StringUtils.isNotBlank(description)) { b.add("description", description); }
        if (StringUtils.isNotBlank(image))       { b.add("image", image); }
        if (StringUtils.isNotBlank(author)) {
            b.add("author", Json.createObjectBuilder().add("@type", "Person").add("name", author));
        }
        if (StringUtils.isNotBlank(datePub)) { b.add("datePublished", datePub); }
        if (StringUtils.isNotBlank(dateMod)) { b.add("dateModified", dateMod); }
        if (!keywords.isEmpty()) {
            final JsonArrayBuilder kw = Json.createArrayBuilder();
            keywords.forEach(kw::add);
            b.add("keywords", kw);
        }
        final String lang = pageLanguage();
        if (StringUtils.isNotBlank(lang)) { b.add("inLanguage", lang); }

        return b.build();
    }

    // ---- Field pickers ----------------------------------------------------

    private String pickString(final ValueMap page, final ValueMap policy, final String key) {
        final String p = (page != null) ? page.get(key, String.class) : null;
        if (StringUtils.isNotBlank(p)) { return p; }
        return (policy != null) ? policy.get(key, String.class) : null;
    }

    private String pickDate(final ValueMap page, final ValueMap policy, final String key) {
        final String s = pickString(page, policy, key);
        if (StringUtils.isNotBlank(s)) { return s; }
        final java.util.Calendar cal = (page != null) ? page.get(key, java.util.Calendar.class) : null;
        if (cal != null) { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX").format(cal.getTime()); }
        final Date d = (page != null) ? page.get(key, Date.class) : null;
        if (d != null)   { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX").format(d); }
        return null;
    }

    private List<String> pickList(final ValueMap page, final ValueMap policy, final String key) {
        final String[] p = (page != null) ? page.get(key, String[].class) : null;
        if (p != null && p.length > 0) { return new ArrayList<>(Arrays.asList(p)); }
        final String[] t = (policy != null) ? policy.get(key, String[].class) : null;
        if (t != null && t.length > 0) { return new ArrayList<>(Arrays.asList(t)); }
        return Collections.emptyList();
    }

    // ---- Page metadata helpers --------------------------------------------

    private String pageTitle() {
        if (currentPage == null) { return null; }
        return StringUtils.defaultIfBlank(currentPage.getPageTitle(),
                StringUtils.defaultIfBlank(currentPage.getTitle(), currentPage.getName()));
    }

    private String pageDescription() {
        return (currentPage != null) ? currentPage.getDescription() : null;
    }

    private String pageLanguage() {
        if (currentPage == null || currentPage.getLanguage(false) == null) { return null; }
        return currentPage.getLanguage(false).toLanguageTag();
    }

    private String canonicalUrl() {
        if (currentPage == null) { return null; }
        final String vanity = currentPage.getVanityUrl();
        return StringUtils.isNotBlank(vanity) ? vanity : currentPage.getPath() + ".html";
    }

    // ---- Rendering helpers ------------------------------------------------

    private String pretty(final JsonObject obj) {
        final StringWriter sw = new StringWriter();
        final Map<String, Object> cfg = new HashMap<>();
        cfg.put(JsonGenerator.PRETTY_PRINTING, Boolean.TRUE);
        try (JsonWriter w = Json.createWriterFactory(cfg).createWriter(sw)) {
            w.writeObject(obj);
        }
        return sw.toString();
    }

    private String sanitize(final String json) {
        return json == null ? "" : json.replace("</", "<\\/");
    }
}

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
import java.util.Locale;
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
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.ScriptVariable;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.aem.guides.wknd.core.seo.config.SeoGlobalConfigService;
import com.adobe.aem.guides.wknd.core.seo.models.SeoSchemaModel;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;

/**
 * Merges template (policy) defaults with page-level Schema.org properties
 * and renders a single JSON-LD document.
 *
 * Precedence per field: page value → template default.
 * When the author pastes raw JSON-LD (./seo/jsonLd) it is trusted and
 * emitted verbatim (after safety sanitization).
 *
 * In addition to the author-configured primary schema, three auto-schemas
 * are injected (controlled by {@link SeoGlobalConfigService} and per-page toggles):
 *   - Organization  — from OSGi config
 *   - BreadcrumbList — derived from the AEM page hierarchy
 *   - WebSite        — from OSGi config, with optional SearchAction
 *
 * When more than one schema node is present the output is wrapped in a
 * single {@code @graph} array inside one {@code <script type="application/ld+json">}.
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
    private static final String PN_ARTICLE_SECTION   = "articleSection";
    private static final String PN_WORD_COUNT        = "wordCount";
    private static final String PN_PUBLISHER_NAME    = "publisherName";
    private static final String PN_PUBLISHER_LOGO    = "publisherLogo";
    private static final String PN_AUTHOR_TYPE       = "authorType";
    private static final String PN_AUTHOR_URL        = "authorUrl";
    private static final String PN_ORG_LEGAL_NAME    = "orgLegalName";
    private static final String PN_ORG_LOGO          = "orgLogo";
    private static final String PN_ORG_TELEPHONE     = "orgTelephone";
    private static final String PN_ORG_EMAIL         = "orgEmail";
    private static final String PN_ORG_SAME_AS       = "orgSameAs";
    private static final String PN_ORG_FOUNDING_DATE = "orgFoundingDate";
    private static final String PN_ORG_STREET        = "orgStreetAddress";
    private static final String PN_ORG_LOCALITY      = "orgAddressLocality";
    private static final String PN_ORG_REGION        = "orgAddressRegion";
    private static final String PN_ORG_POSTAL_CODE   = "orgPostalCode";
    private static final String PN_ORG_COUNTRY       = "orgAddressCountry";

    // Per-page multi-schema override toggles (stored under ./seo/)
    private static final String PN_INCLUDE_ORGANIZATION = "includeOrganization";
    private static final String PN_INCLUDE_BREADCRUMB   = "includeBreadcrumb";
    private static final String PN_INCLUDE_WEBSITE      = "includeWebSite";

    // Toggle values
    private static final String TOGGLE_YES = "yes";
    private static final String TOGGLE_NO  = "no";

    private static final String PN_CQ_LAST_MODIFIED      = "cq:lastModified";
    private static final String PN_CQ_LAST_REPLICATED    = "cq:lastReplicated";
    private static final String PN_CQ_REPLICATION_ACTION = "cq:lastReplicationAction";
    private static final String PN_CQ_TAGS               = "cq:tags";
    private static final String REPLICATION_ACTIVATE     = "Activate";

    private static final String MODE_DISABLE    = "disable";
    private static final String MODE_OVERRIDE   = "override";
    private static final String DEFAULT_TYPE    = "WebPage";
    private static final String SCHEMA_CONTEXT  = "https://schema.org";
    private static final String TYPE_ARTICLE      = "Article";
    private static final String TYPE_NEWS_ARTICLE  = "NewsArticle";
    private static final String TYPE_BLOG_POSTING  = "BlogPosting";
    private static final String TYPE_ORGANIZATION  = "Organization";

    // ---- Injections -------------------------------------------------------

    @Self
    private SlingHttpServletRequest request;

    @ScriptVariable
    private Page currentPage;

    @OSGiService
    private SeoGlobalConfigService globalConfig;

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

        // 1. Resolve the primary schema (raw JSON-LD or built from fields)
        addRaw(graph, policy.get(PN_JSON_LD, String.class), "policy");
        if (override) { graph.clear(); }
        addRaw(graph, page.get(PN_JSON_LD, String.class), "page");

        if (graph.isEmpty()) {
            final JsonObject built = buildFromFields(page, policy, override);
            if (built == null) { return ""; }
            graph.put("primary", built);
        }

        // 2. Add auto-schemas when the global service is available
        if (globalConfig != null) {
            if (shouldInclude(page, PN_INCLUDE_ORGANIZATION, globalConfig.includeOrganization())) {
                final JsonObject org = buildOrganizationSchema();
                if (org != null) { graph.put("auto:org", org); }
            }
            if (shouldInclude(page, PN_INCLUDE_BREADCRUMB, globalConfig.includeBreadcrumb())) {
                final JsonObject bc = buildBreadcrumbSchema();
                if (bc != null) { graph.put("auto:breadcrumb", bc); }
            }
            if (shouldInclude(page, PN_INCLUDE_WEBSITE, globalConfig.includeWebSite())) {
                final JsonObject ws = buildWebSiteSchema();
                if (ws != null) { graph.put("auto:website", ws); }
            }
        }

        // 3. Single node → output without @graph wrapper; multiple → wrap
        if (graph.size() == 1) {
            return pretty(graph.values().iterator().next());
        }
        return pretty(wrapGraph(graph.values()));
    }

    /**
     * Returns true when this auto-schema should be included on the current page.
     * Page toggle ("yes"/"no") takes precedence over the global OSGi default.
     */
    private boolean shouldInclude(final ValueMap page, final String key, final boolean globalDefault) {
        final String pageVal = page.get(key, String.class);
        if (TOGGLE_YES.equals(pageVal)) { return true; }
        if (TOGGLE_NO.equals(pageVal))  { return false; }
        return globalDefault;
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

    // ---- Auto-schema builders ---------------------------------------------

    /**
     * Builds an Organization node from the OSGi global config.
     * Returns null when organizationName is blank (nothing useful to emit).
     */
    private JsonObject buildOrganizationSchema() {
        final String name = globalConfig.organizationName();
        if (StringUtils.isBlank(name)) { return null; }

        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("@type", "Organization")
                .add("name", name);

        final String url = globalConfig.organizationUrl();
        if (StringUtils.isNotBlank(url)) {
            b.add("@id", url + "#organization");
            b.add("url", url);
        }
        final String logo = globalConfig.logoPath();
        if (StringUtils.isNotBlank(logo)) {
            b.add("logo", Json.createObjectBuilder()
                    .add("@type", "ImageObject")
                    .add("url", logo));
        }
        final String[] sameAs = globalConfig.sameAs();
        if (sameAs != null && sameAs.length > 0) {
            final JsonArrayBuilder arr = Json.createArrayBuilder();
            for (final String s : sameAs) {
                if (StringUtils.isNotBlank(s)) { arr.add(s); }
            }
            b.add("sameAs", arr);
        }
        return b.build();
    }

    /**
     * Builds a BreadcrumbList by walking the AEM page hierarchy from the site
     * root (depth 2, i.e. one level below /content) to the current page.
     * Returns null for top-level pages that have no meaningful breadcrumb.
     */
    private JsonObject buildBreadcrumbSchema() {
        if (currentPage == null) { return null; }

        final List<Page> crumbs = new ArrayList<>();
        Page p = currentPage;
        // Walk up; stop when we've reached depth 1 (the /content node itself)
        while (p != null && p.getDepth() > 1) {
            crumbs.add(0, p);
            p = p.getParent();
        }
        if (crumbs.size() < 2) { return null; }

        final JsonArrayBuilder items = Json.createArrayBuilder();
        int position = 1;
        for (final Page crumb : crumbs) {
            final String name = StringUtils.defaultIfBlank(crumb.getNavigationTitle(),
                    StringUtils.defaultIfBlank(crumb.getTitle(), crumb.getName()));
            final String url = crumb.getPath() + ".html";
            items.add(Json.createObjectBuilder()
                    .add("@type", "ListItem")
                    .add("position", position++)
                    .add("name", name)
                    .add("item", url));
        }

        return Json.createObjectBuilder()
                .add("@type", "BreadcrumbList")
                .add("itemListElement", items)
                .build();
    }

    /**
     * Builds a WebSite node from the OSGi global config.
     * Optionally includes a SearchAction if searchUrlTemplate is configured.
     * Returns null when no URL can be determined.
     */
    private JsonObject buildWebSiteSchema() {
        final String url = StringUtils.defaultIfBlank(
                globalConfig.organizationUrl(),
                currentPage != null ? currentPage.getPath() + ".html" : "");
        if (StringUtils.isBlank(url)) { return null; }

        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("@type", "WebSite")
                .add("@id", url + "#website")
                .add("url", url);

        final String name = globalConfig.organizationName();
        if (StringUtils.isNotBlank(name)) {
            b.add("name", name);
        }

        final String searchTemplate = globalConfig.searchUrlTemplate();
        if (StringUtils.isNotBlank(searchTemplate)) {
            b.add("potentialAction", Json.createObjectBuilder()
                    .add("@type", "SearchAction")
                    .add("target", Json.createObjectBuilder()
                            .add("@type", "EntryPoint")
                            .add("urlTemplate", searchTemplate))
                    .add("query-input", "required name=search_term_string"));
        }
        return b.build();
    }

    // ---- Field-based primary schema builder -------------------------------

    private JsonObject buildFromFields(final ValueMap page, final ValueMap policy, final boolean override) {
        final String type        = StringUtils.defaultIfBlank(pickString(page, override ? null : policy, PN_TYPE), DEFAULT_TYPE);
        final String headline    = StringUtils.defaultIfBlank(pickString(page, override ? null : policy, PN_HEADLINE), pageTitle());
        final String description = StringUtils.defaultIfBlank(pickString(page, override ? null : policy, PN_DESCRIPTION), pageDescription());
        final String author      = pickString(page, override ? null : policy, PN_AUTHOR);
        final String image       = pickString(page, override ? null : policy, PN_IMAGE);
        final String datePub     = StringUtils.defaultIfBlank(
                pickDate(page, override ? null : policy, PN_DATE_PUBLISHED), replicationDate());
        final String dateMod     = StringUtils.defaultIfBlank(
                pickDate(page, override ? null : policy, PN_DATE_MODIFIED), pageLastModified());
        final String articleSection = pickString(page, override ? null : policy, PN_ARTICLE_SECTION);
        final String publisherName  = pickString(page, override ? null : policy, PN_PUBLISHER_NAME);
        final String publisherLogo  = pickString(page, override ? null : policy, PN_PUBLISHER_LOGO);
        final String authorType     = StringUtils.defaultIfBlank(
                pickString(page, override ? null : policy, PN_AUTHOR_TYPE), "Person");
        final String authorUrl      = pickString(page, override ? null : policy, PN_AUTHOR_URL);
        final Long wordCount        = pickLong(page, override ? null : policy, PN_WORD_COUNT);
        final List<String> tagTitles = pageTags();
        List<String> keywords = pickList(page, override ? null : policy, PN_KEYWORDS);
        if (keywords.isEmpty()) { keywords = tagTitles; }

        if (StringUtils.isBlank(headline) && StringUtils.isBlank(description)
                && StringUtils.isBlank(author) && keywords.isEmpty()
                && StringUtils.isBlank(datePub) && StringUtils.isBlank(dateMod)
                && tagTitles.isEmpty()) {
            return null;
        }

        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("@context", SCHEMA_CONTEXT)
                .add("@type", type);

        final String url = canonicalUrl();
        if (StringUtils.isNotBlank(url)) {
            b.add("@id", url);
            b.add("url", url);
            b.add("mainEntityOfPage", Json.createObjectBuilder().add("@id", url));
        }
        final String uuid = pageUuid();
        if (StringUtils.isNotBlank(uuid)) {
            b.add("identifier", Json.createObjectBuilder()
                    .add("@type", "PropertyValue")
                    .add("name", "jcr:uuid")
                    .add("value", uuid));
        }
        if (StringUtils.isNotBlank(headline))    { b.add("headline", headline); b.add("name", headline); }
        if (StringUtils.isNotBlank(description)) { b.add("description", description); }
        if (StringUtils.isNotBlank(image))       { b.add("image", image); }
        if (StringUtils.isNotBlank(author)) {
            if (isArticleLikeType(type)) {
                final JsonObjectBuilder authorObject = Json.createObjectBuilder()
                        .add("@type", sanitizeAuthorType(authorType))
                        .add("name", author);
                if (StringUtils.isNotBlank(authorUrl)) {
                    authorObject.add("url", authorUrl);
                }
                b.add("author", authorObject);
            } else {
                b.add("author", Json.createObjectBuilder().add("@type", "Person").add("name", author));
            }
        }
        if (StringUtils.isNotBlank(datePub)) { b.add("datePublished", datePub); }
        if (StringUtils.isNotBlank(dateMod)) { b.add("dateModified", dateMod); }
        if (!keywords.isEmpty()) {
            final JsonArrayBuilder kw = Json.createArrayBuilder();
            keywords.forEach(kw::add);
            b.add("keywords", kw);
        }
        if (!tagTitles.isEmpty()) {
            final JsonArrayBuilder aboutArr = Json.createArrayBuilder();
            tagTitles.forEach(t -> aboutArr.add(
                    Json.createObjectBuilder().add("@type", "Thing").add("name", t)));
            b.add("about", aboutArr);
        }
        final String lang = pageLanguage();
        if (StringUtils.isNotBlank(lang)) { b.add("inLanguage", lang); }

        if (isArticleLikeType(type)) {
            if (StringUtils.isNotBlank(articleSection)) {
                b.add("articleSection", articleSection);
            }
            if (wordCount != null && wordCount.longValue() >= 0L) {
                b.add("wordCount", wordCount.longValue());
            }
            if (StringUtils.isNotBlank(publisherName) || StringUtils.isNotBlank(publisherLogo)) {
                final JsonObjectBuilder publisherObject = Json.createObjectBuilder()
                        .add("@type", "Organization");
                if (StringUtils.isNotBlank(publisherName)) {
                    publisherObject.add("name", publisherName);
                }
                if (StringUtils.isNotBlank(publisherLogo)) {
                    publisherObject.add("logo", publisherLogo);
                }
                b.add("publisher", publisherObject);
            }
        }

        if (isOrganizationType(type)) {
            addOrganizationFields(b, page, override ? null : policy);
        }

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

    private Long pickLong(final ValueMap page, final ValueMap policy, final String key) {
        final Long pLong = (page != null) ? page.get(key, Long.class) : null;
        if (pLong != null) { return pLong; }
        final Integer pInt = (page != null) ? page.get(key, Integer.class) : null;
        if (pInt != null) { return Long.valueOf(pInt.longValue()); }
        final String pStr = (page != null) ? page.get(key, String.class) : null;
        final Long parsedPage = parseLongSafe(pStr);
        if (parsedPage != null) { return parsedPage; }

        final Long tLong = (policy != null) ? policy.get(key, Long.class) : null;
        if (tLong != null) { return tLong; }
        final Integer tInt = (policy != null) ? policy.get(key, Integer.class) : null;
        if (tInt != null) { return Long.valueOf(tInt.longValue()); }
        final String tStr = (policy != null) ? policy.get(key, String.class) : null;
        return parseLongSafe(tStr);
    }

    private Long parseLongSafe(final String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (final NumberFormatException nfe) {
            return null;
        }
    }

    private boolean isArticleLikeType(final String type) {
        return TYPE_ARTICLE.equals(type) || TYPE_NEWS_ARTICLE.equals(type) || TYPE_BLOG_POSTING.equals(type);
    }

    private boolean isOrganizationType(final String type) {
        return TYPE_ORGANIZATION.equals(type);
    }

    private void addOrganizationFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        final String legalName    = pickString(page, policy, PN_ORG_LEGAL_NAME);
        final String logo         = pickString(page, policy, PN_ORG_LOGO);
        final String telephone    = pickString(page, policy, PN_ORG_TELEPHONE);
        final String email        = pickString(page, policy, PN_ORG_EMAIL);
        final String foundingDate = pickString(page, policy, PN_ORG_FOUNDING_DATE);
        final List<String> sameAs = pickList(page, policy, PN_ORG_SAME_AS);
        final String street       = pickString(page, policy, PN_ORG_STREET);
        final String locality     = pickString(page, policy, PN_ORG_LOCALITY);
        final String region       = pickString(page, policy, PN_ORG_REGION);
        final String postalCode   = pickString(page, policy, PN_ORG_POSTAL_CODE);
        final String country      = pickString(page, policy, PN_ORG_COUNTRY);

        if (StringUtils.isNotBlank(legalName))    { b.add("legalName", legalName); }
        if (StringUtils.isNotBlank(logo)) {
            b.add("logo", Json.createObjectBuilder()
                    .add("@type", "ImageObject")
                    .add("url", logo));
        }
        if (StringUtils.isNotBlank(telephone))    { b.add("telephone", telephone); }
        if (StringUtils.isNotBlank(email))        { b.add("email", email); }
        if (StringUtils.isNotBlank(foundingDate)) { b.add("foundingDate", foundingDate); }
        if (!sameAs.isEmpty()) {
            final JsonArrayBuilder arr = Json.createArrayBuilder();
            sameAs.forEach(arr::add);
            b.add("sameAs", arr);
        }
        final boolean hasAddress = StringUtils.isNotBlank(street) || StringUtils.isNotBlank(locality)
                || StringUtils.isNotBlank(region) || StringUtils.isNotBlank(postalCode)
                || StringUtils.isNotBlank(country);
        if (hasAddress) {
            final JsonObjectBuilder addr = Json.createObjectBuilder().add("@type", "PostalAddress");
            if (StringUtils.isNotBlank(street))     { addr.add("streetAddress", street); }
            if (StringUtils.isNotBlank(locality))   { addr.add("addressLocality", locality); }
            if (StringUtils.isNotBlank(region))     { addr.add("addressRegion", region); }
            if (StringUtils.isNotBlank(postalCode)) { addr.add("postalCode", postalCode); }
            if (StringUtils.isNotBlank(country))    { addr.add("addressCountry", country); }
            b.add("address", addr);
        }
    }

    private String sanitizeAuthorType(final String authorType) {
        return "Organization".equals(authorType) ? "Organization" : "Person";
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
        if (currentPage == null) { return null; }
        try {
            final java.util.Locale locale = currentPage.getLanguage(false);
            return (locale != null) ? locale.toLanguageTag() : null;
        } catch (final Throwable t) {
            // ICU4J (used by Page.getLanguage) may be absent in restricted environments.
            LOG.debug("Cannot determine page language: {}", t.getMessage());
            return null;
        }
    }

    private String canonicalUrl() {
        if (currentPage == null) { return null; }
        final String vanity = currentPage.getVanityUrl();
        return StringUtils.isNotBlank(vanity) ? vanity : currentPage.getPath() + ".html";
    }

    private ValueMap pageContentProps() {
        if (currentPage == null) { return emptyMap(); }
        final Resource content = currentPage.getContentResource();
        return (content != null) ? content.getValueMap() : emptyMap();
    }

    private String formatCalendar(final java.util.Calendar cal) {
        if (cal == null) { return null; }
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX").format(cal.getTime());
    }

    private String pageLastModified() {
        return formatCalendar(pageContentProps().get(PN_CQ_LAST_MODIFIED, java.util.Calendar.class));
    }

    private String replicationDate() {
        final ValueMap props = pageContentProps();
        if (!REPLICATION_ACTIVATE.equals(props.get(PN_CQ_REPLICATION_ACTION, String.class))) {
            return null;
        }
        return formatCalendar(props.get(PN_CQ_LAST_REPLICATED, java.util.Calendar.class));
    }

    private String pageUuid() {
        return pageContentProps().get("jcr:uuid", String.class);
    }

    private List<String> pageTags() {
        final String[] tagIds = pageContentProps().get(PN_CQ_TAGS, String[].class);
        if (tagIds == null || tagIds.length == 0) { return Collections.emptyList(); }
        final TagManager tm = (request != null)
                ? request.getResourceResolver().adaptTo(TagManager.class) : null;
        if (tm == null) { return Collections.emptyList(); }
        Locale locale = Locale.ENGLISH;
        if (currentPage != null) {
            try {
                final Locale pageLocale = currentPage.getLanguage(false);
                if (pageLocale != null) { locale = pageLocale; }
            } catch (final Throwable t) {
                LOG.debug("Cannot determine page language for tags: {}", t.getMessage());
            }
        }
        final List<String> titles = new ArrayList<>();
        for (final String tagId : tagIds) {
            final Tag tag = tm.resolve(tagId);
            if (tag != null) {
                final String title = StringUtils.defaultIfBlank(tag.getTitle(locale), tag.getTitle());
                if (StringUtils.isNotBlank(title)) { titles.add(title); }
            }
        }
        return Collections.unmodifiableList(titles);
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

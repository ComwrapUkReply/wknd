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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;
import com.adobe.aem.guides.wknd.core.seo.models.SeoSchemaModel;

/**
 * Builds a single JSON-LD document describing the current page.
 *
 * <p>Scope strategy (SEO / GEO / AEO):</p>
 * <ul>
 *   <li><b>Homepage (language root)</b> → {@code Organization} + {@code WebSite}
 *       — the brand-entity anchor for the whole site.</li>
 *   <li><b>Inner pages</b> → the authored page-level type (if any) +
 *       {@code BreadcrumbList}. Inner pages do not re-emit the Organization /
 *       WebSite nodes; instead the primary node references them by {@code @id}
 *       ({@code isPartOf} → WebSite, {@code publisher} → Organization) so that
 *       generative / answer engines resolve a single brand entity site-wide.</li>
 * </ul>
 *
 * <p>Per-field precedence: page {@code jcr:content/seo/*} → template policy →
 * native AEM page metadata fallback. When the author pastes raw JSON-LD
 * ({@code ./seo/jsonLd}) it is trusted and emitted verbatim on inner pages.</p>
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

    // ---- Native AEM page metadata (Core Components page v3) ---------------

    private static final String PN_CQ_LAST_MODIFIED      = "cq:lastModified";
    private static final String PN_CQ_LAST_REPLICATED    = "cq:lastReplicated";
    private static final String PN_CQ_REPLICATION_ACTION = "cq:lastReplicationAction";
    private static final String PN_CQ_TAGS               = "cq:tags";
    private static final String PN_CQ_CANONICAL_URL      = "cq:canonicalUrl";
    private static final String PN_CQ_ROBOTS_TAGS        = "cq:robotsTags";
    private static final String PN_CQ_FEATURED_IMAGE     = "cq:featuredimage";
    private static final String PN_FILE_REFERENCE        = "fileReference";
    private static final String PN_ON_TIME               = "onTime";
    private static final String PN_OFF_TIME              = "offTime";
    private static final String PN_SUBTITLE              = "subtitle";
    private static final String REPLICATION_ACTIVATE     = "Activate";
    private static final String ROBOTS_NOINDEX           = "noindex";

    private static final String MODE_DISABLE    = "disable";
    private static final String MODE_OVERRIDE   = "override";
    private static final String SCHEMA_CONTEXT  = "https://schema.org";

    private static final String TYPE_ARTICLE      = "Article";
    private static final String TYPE_NEWS_ARTICLE = "NewsArticle";
    private static final String TYPE_BLOG_POSTING = "BlogPosting";
    private static final String TYPE_FAQ_PAGE     = "FAQPage";
    private static final String TYPE_HOW_TO       = "HowTo";
    private static final String TYPE_PERSON       = "Person";
    private static final String TYPE_PLACE        = "Place";
    private static final String TYPE_EVENT        = "Event";
    private static final String TYPE_JOB_POSTING  = "JobPosting";
    private static final String TYPE_AUDIO_OBJECT = "AudioObject";
    private static final String TYPE_VIDEO_OBJECT = "VideoObject";

    /** Page-level types the model knows how to build. Anything else (incl. the
     *  parked WebPage and compliance-hold types) yields no primary node. */
    private static final Set<String> PAGE_LEVEL_TYPES = new HashSet<>(Arrays.asList(
            TYPE_ARTICLE, TYPE_NEWS_ARTICLE, TYPE_BLOG_POSTING, TYPE_AUDIO_OBJECT,
            TYPE_EVENT, TYPE_FAQ_PAGE, TYPE_PLACE, TYPE_VIDEO_OBJECT,
            TYPE_JOB_POSTING, TYPE_HOW_TO, TYPE_PERSON));

    /** CreativeWork subtypes — only these carry CreativeWork-scoped properties
     *  (headline, datePublished, keywords, isPartOf, publisher, …). */
    private static final Set<String> CREATIVE_WORK_TYPES = new HashSet<>(Arrays.asList(
            TYPE_ARTICLE, TYPE_NEWS_ARTICLE, TYPE_BLOG_POSTING,
            TYPE_FAQ_PAGE, TYPE_HOW_TO, TYPE_AUDIO_OBJECT, TYPE_VIDEO_OBJECT));

    private static final String ISO_DATETIME = "yyyy-MM-dd'T'HH:mmXXX";

    /** ISO-639 two-letter language codes (JDK source, no ICU4J dependency). */
    private static final Set<String> ISO_LANGUAGES =
            new HashSet<>(Arrays.asList(Locale.getISOLanguages()));

    // ---- Site-wide Organisation / WebSite constants ----------------------
    // These describe the brand and never differ between pages or environments.
    // Update here when the site is rebranded or social profiles change.

    private static final String ORG_NAME  = "WKND";
    private static final String ORG_URL   = "https://www.wknd.site";
    private static final String ORG_LOGO  = "/content/dam/wknd/en/logos/wknd-logo.png";
    private static final String[] ORG_SAME_AS = {
        // Add authoritative profile URLs as the brand grows, e.g.:
        // "https://www.linkedin.com/company/wknd",
        // "https://en.wikipedia.org/wiki/WKND"
    };
    /** WebSite SearchAction target. Set blank to omit the sitelinks search box. */
    private static final String ORG_SEARCH_URL_TEMPLATE = ORG_URL + "/search?keyword={q}";

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

            if (MODE_DISABLE.equalsIgnoreCase(mode) || !pageEnabled || isNoindex()) {
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

    private Resource pageSeoResource() {
        if (currentPage == null) { return null; }
        final Resource content = currentPage.getContentResource();
        return (content != null) ? content.getChild(SEO_NODE) : null;
    }

    private Resource policySeoResource() {
        if (request == null || currentPage == null) { return null; }
        final ContentPolicyManager cpm = request.getResourceResolver().adaptTo(ContentPolicyManager.class);
        final Resource content = currentPage.getContentResource();
        if (cpm == null || content == null)         { return null; }
        final ContentPolicy policy = cpm.getPolicy(content);
        if (policy == null)                         { return null; }
        final Resource policyRes = policy.adaptTo(Resource.class);
        return (policyRes != null) ? policyRes.getChild(SEO_NODE) : null;
    }

    private ValueMap readPageSeo() {
        final Resource seo = pageSeoResource();
        return (seo != null) ? seo.getValueMap() : emptyMap();
    }

    private ValueMap readPolicySeo() {
        final Resource seo = policySeoResource();
        return (seo != null) ? seo.getValueMap() : emptyMap();
    }

    private ValueMap emptyMap() {
        return new ValueMapDecorator(Collections.emptyMap());
    }

    // ---- Page classification ----------------------------------------------

    /**
     * A page is the "homepage" when it is its own language root
     * (e.g. {@code /content/wknd/us/en}). Detected from the path by locating the
     * language segment — pure JDK (no ICU4J), so it works in restricted
     * environments where {@code LanguageUtil}/{@code Page.getLanguage} are absent.
     */
    private boolean isHomepage() {
        if (currentPage == null) { return false; }
        final String root = languageRootPath(currentPage.getPath());
        return root != null && root.equals(currentPage.getPath());
    }

    /** Path up to and including the first ISO-639 language segment, or null. */
    private String languageRootPath(final String path) {
        if (StringUtils.isBlank(path)) { return null; }
        final StringBuilder sb = new StringBuilder();
        for (final String segment : path.split("/")) {
            if (segment.isEmpty()) { continue; }
            sb.append('/').append(segment);
            if (isLanguageSegment(segment)) { return sb.toString(); }
        }
        return null;
    }

    private boolean isLanguageSegment(final String segment) {
        String lang = segment;
        final int sep = StringUtils.indexOfAny(segment, "_-");
        if (sep > 0) { lang = segment.substring(0, sep); }
        return lang.length() == 2 && ISO_LANGUAGES.contains(lang.toLowerCase(Locale.ROOT));
    }

    private boolean isNoindex() {
        final String[] robots = pageContentProps().get(PN_CQ_ROBOTS_TAGS, String[].class);
        if (robots == null) { return false; }
        for (final String r : robots) {
            if (r != null && r.toLowerCase(Locale.ROOT).contains(ROBOTS_NOINDEX)) { return true; }
        }
        return false;
    }

    // ---- JSON-LD builder --------------------------------------------------

    private String buildJsonLd(final ValueMap page, final ValueMap policy, final String mode) {
        final boolean override = MODE_OVERRIDE.equalsIgnoreCase(mode);
        final Map<String, JsonObject> graph = new LinkedHashMap<>();

        if (isHomepage()) {
            // Homepage anchors the brand: Organization + WebSite only.
            final JsonObject org = buildOrganizationSchema();
            if (org != null) { graph.put("auto:org", org); }
            final JsonObject ws = buildWebSiteSchema();
            if (ws  != null) { graph.put("auto:website", ws); }
        } else {
            // Inner page: authored primary (raw or built) + breadcrumb trail.
            addRaw(graph, policy.get(PN_JSON_LD, String.class), "policy");
            if (override) { graph.clear(); }
            addRaw(graph, page.get(PN_JSON_LD, String.class), "page");

            if (graph.isEmpty()) {
                final JsonObject built = buildFromFields(page, policy, override);
                if (built != null) { graph.put("primary", built); }
            }

            final JsonObject bc = buildBreadcrumbSchema();
            if (bc != null) { graph.put("auto:breadcrumb", bc); }
        }

        if (graph.isEmpty())   { return ""; }
        if (graph.size() == 1) { return pretty(graph.values().iterator().next()); }
        return pretty(wrapGraph(graph.values()));
    }

    // ---- Site-wide auto-schema builders -----------------------------------

    /**
     * Builds a fixed Organization node from the site-wide constants, enriched
     * with the homepage's own description. Returns null when ORG_NAME is blank.
     */
    private JsonObject buildOrganizationSchema() {
        if (StringUtils.isBlank(ORG_NAME)) { return null; }

        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("@type", "Organization")
                .add("name",  ORG_NAME);

        if (StringUtils.isNotBlank(ORG_URL)) {
            b.add("@id", ORG_URL + "#organization");
            b.add("url",  ORG_URL);
        }
        final String description = pageDescription();
        if (StringUtils.isNotBlank(description)) {
            b.add("description", description);
        }
        if (StringUtils.isNotBlank(ORG_LOGO)) {
            b.add("logo", Json.createObjectBuilder()
                    .add("@type", "ImageObject")
                    .add("url",   ORG_LOGO));
        }
        if (ORG_SAME_AS != null && ORG_SAME_AS.length > 0) {
            final JsonArrayBuilder arr = Json.createArrayBuilder();
            for (final String s : ORG_SAME_AS) {
                if (StringUtils.isNotBlank(s)) { arr.add(s); }
            }
            b.add("sameAs", arr);
        }
        return b.build();
    }

    /**
     * Builds a WebSite node from the site-wide constants, including a
     * SearchAction (sitelinks search box) when a search URL is configured.
     */
    private JsonObject buildWebSiteSchema() {
        if (StringUtils.isBlank(ORG_URL)) { return null; }

        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("@type", "WebSite")
                .add("@id",   ORG_URL + "#website")
                .add("url",   ORG_URL);

        if (StringUtils.isNotBlank(ORG_NAME)) {
            b.add("name", ORG_NAME);
        }
        if (StringUtils.isNotBlank(ORG_SEARCH_URL_TEMPLATE)) {
            b.add("potentialAction", Json.createObjectBuilder()
                    .add("@type", "SearchAction")
                    .add("target", Json.createObjectBuilder()
                            .add("@type", "EntryPoint")
                            .add("urlTemplate", ORG_SEARCH_URL_TEMPLATE))
                    .add("query-input", "required name=q"));
        }
        return b.build();
    }

    /**
     * Builds a BreadcrumbList by walking the AEM page hierarchy from the
     * site root (depth 2) to the current page. Null for top-level pages.
     */
    private JsonObject buildBreadcrumbSchema() {
        if (currentPage == null) { return null; }

        final List<com.day.cq.wcm.api.Page> crumbs = new ArrayList<>();
        com.day.cq.wcm.api.Page p = currentPage;
        while (p != null && p.getDepth() > 1) {
            crumbs.add(0, p);
            p = p.getParent();
        }
        if (crumbs.size() < 2) { return null; }

        final JsonArrayBuilder items = Json.createArrayBuilder();
        int position = 1;
        for (final com.day.cq.wcm.api.Page crumb : crumbs) {
            final String name = StringUtils.defaultIfBlank(crumb.getNavigationTitle(),
                    StringUtils.defaultIfBlank(crumb.getTitle(), crumb.getName()));
            items.add(Json.createObjectBuilder()
                    .add("@type",    "ListItem")
                    .add("position", position++)
                    .add("name",     name)
                    .add("item",     crumb.getPath() + ".html"));
        }
        return Json.createObjectBuilder()
                .add("@type",           "BreadcrumbList")
                .add("itemListElement", items)
                .build();
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

    // ---- Primary node from dialog fields ----------------------------------

    private JsonObject buildFromFields(final ValueMap page, final ValueMap policy, final boolean override) {
        final ValueMap pol = override ? null : policy;
        final String type = pickString(page, pol, PN_TYPE);

        // WebPage is parked and compliance-hold types are not implemented:
        // only emit a primary node for a supported page-level type.
        if (StringUtils.isBlank(type) || !PAGE_LEVEL_TYPES.contains(type)) {
            return null;
        }

        final boolean cw = CREATIVE_WORK_TYPES.contains(type);

        final String name        = StringUtils.defaultIfBlank(pickString(page, pol, PN_HEADLINE), pageTitle());
        final String description = StringUtils.defaultIfBlank(pickString(page, pol, PN_DESCRIPTION), pageDescription());
        final String image       = StringUtils.defaultIfBlank(pickString(page, pol, PN_IMAGE), pageFeaturedImage());

        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("@context", SCHEMA_CONTEXT)
                .add("@type", type);

        // Universal Thing properties (valid on every type).
        final String url = canonicalUrl();
        if (StringUtils.isNotBlank(url)) {
            b.add("@id", url);
            b.add("url", url);
        }
        final String uuid = pageUuid();
        if (StringUtils.isNotBlank(uuid)) {
            b.add("identifier", Json.createObjectBuilder()
                    .add("@type", "PropertyValue")
                    .add("name", "jcr:uuid")
                    .add("value", uuid));
        }
        if (StringUtils.isNotBlank(name))        { b.add("name", name); }
        if (StringUtils.isNotBlank(description)) { b.add("description", description); }
        if (StringUtils.isNotBlank(image))       { b.add("image", image); }

        // CreativeWork-scoped properties + brand entity references.
        if (cw) {
            addCreativeWorkFields(b, page, pol, type, name, url);
        }

        // Type-specific dedicated properties.
        addTypeFields(b, page, pol, type);

        return b.build();
    }

    /** Adds CreativeWork-only fields shared by Article/FAQ/HowTo/Audio/Video. */
    private void addCreativeWorkFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy,
                                       final String type, final String name, final String url) {
        if (StringUtils.isNotBlank(name)) { b.add("headline", name); }
        addIfPresent(b, "alternativeHeadline", pageSubtitle());
        if (StringUtils.isNotBlank(url)) {
            b.add("mainEntityOfPage", Json.createObjectBuilder().add("@id", url));
        }

        String datePub = pickDate(page, policy, PN_DATE_PUBLISHED);
        if (StringUtils.isBlank(datePub)) { datePub = replicationDate(); }
        if (StringUtils.isBlank(datePub)) { datePub = pageOnTime(); }
        if (StringUtils.isNotBlank(datePub)) { b.add("datePublished", datePub); }

        final String dateMod = StringUtils.defaultIfBlank(
                pickDate(page, policy, PN_DATE_MODIFIED), pageLastModified());
        if (StringUtils.isNotBlank(dateMod)) { b.add("dateModified", dateMod); }

        List<String> keywords = pickList(page, policy, PN_KEYWORDS);
        final List<String> tagTitles = pageTags();
        if (keywords.isEmpty()) { keywords = tagTitles; }
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

        // Brand entity references — single source of truth lives on the homepage.
        if (StringUtils.isNotBlank(ORG_URL)) {
            b.add("isPartOf", Json.createObjectBuilder().add("@id", ORG_URL + "#website"));
        }
        final JsonObjectBuilder publisher = resolvePublisher(page, policy, type);
        if (publisher != null) { b.add("publisher", publisher); }
    }

    /** Article-like pages may override the publisher; otherwise reference the brand. */
    private JsonObjectBuilder resolvePublisher(final ValueMap page, final ValueMap policy, final String type) {
        if (isArticleLikeType(type)) {
            final String pubName = pickString(page, policy, PN_PUBLISHER_NAME);
            final String pubLogo = pickString(page, policy, PN_PUBLISHER_LOGO);
            if (StringUtils.isNotBlank(pubName) || StringUtils.isNotBlank(pubLogo)) {
                final JsonObjectBuilder pub = Json.createObjectBuilder().add("@type", "Organization");
                if (StringUtils.isNotBlank(pubName)) { pub.add("name", pubName); }
                if (StringUtils.isNotBlank(pubLogo)) { pub.add("logo", pubLogo); }
                return pub;
            }
        }
        return StringUtils.isNotBlank(ORG_URL)
                ? Json.createObjectBuilder().add("@id", ORG_URL + "#organization")
                : null;
    }

    private void addTypeFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy,
                               final String type) {
        switch (type) {
            case TYPE_ARTICLE:
            case TYPE_NEWS_ARTICLE:
            case TYPE_BLOG_POSTING:
                addArticleFields(b, page, policy);
                break;
            case TYPE_EVENT:
                addEventFields(b, page, policy);
                break;
            case TYPE_FAQ_PAGE:
                addFaqFields(b);
                break;
            case TYPE_HOW_TO:
                addHowToFields(b, page, policy);
                break;
            case TYPE_PERSON:
                addPersonFields(b, page, policy);
                break;
            case TYPE_PLACE:
                addPlaceFields(b, page, policy);
                break;
            case TYPE_JOB_POSTING:
                addJobPostingFields(b, page, policy);
                break;
            case TYPE_AUDIO_OBJECT:
                addAudioFields(b, page, policy);
                break;
            case TYPE_VIDEO_OBJECT:
                addVideoFields(b, page, policy);
                break;
            default:
                break;
        }
    }

    // ---- Type-specific builders -------------------------------------------

    private void addArticleFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        final String author = pickString(page, policy, PN_AUTHOR);
        if (StringUtils.isNotBlank(author)) {
            final String authorType = StringUtils.defaultIfBlank(
                    pickString(page, policy, PN_AUTHOR_TYPE), "Person");
            final JsonObjectBuilder authorObject = Json.createObjectBuilder()
                    .add("@type", sanitizeAuthorType(authorType))
                    .add("name", author);
            final String authorUrl = pickString(page, policy, PN_AUTHOR_URL);
            if (StringUtils.isNotBlank(authorUrl)) { authorObject.add("url", authorUrl); }
            b.add("author", authorObject);
        }
        addIfPresent(b, "articleSection", pickString(page, policy, PN_ARTICLE_SECTION));
        final Long wordCount = pickLong(page, policy, PN_WORD_COUNT);
        if (wordCount != null && wordCount.longValue() >= 0L) {
            b.add("wordCount", wordCount.longValue());
        }
        addIfPresent(b, "expires", pageOffTime());
    }

    private void addEventFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        addIfPresent(b, "startDate", pickDate(page, policy, "eventStartDate"));
        addIfPresent(b, "endDate",   pickDate(page, policy, "eventEndDate"));
        addIfPresent(b, "eventStatus", pickString(page, policy, "eventStatus"));
        addIfPresent(b, "eventAttendanceMode", pickString(page, policy, "eventAttendanceMode"));

        final String locUrl  = pickString(page, policy, "eventLocationUrl");
        final String locName = pickString(page, policy, "eventLocationName");
        final JsonObjectBuilder address = buildPostalAddress(page, policy, "eventLoc");
        if (address != null || StringUtils.isNotBlank(locName)) {
            final JsonObjectBuilder place = Json.createObjectBuilder().add("@type", "Place");
            if (StringUtils.isNotBlank(locName)) { place.add("name", locName); }
            if (address != null) { place.add("address", address); }
            b.add("location", place);
        } else if (StringUtils.isNotBlank(locUrl)) {
            b.add("location", Json.createObjectBuilder()
                    .add("@type", "VirtualLocation")
                    .add("url", locUrl));
        }

        final String orgName = pickString(page, policy, "eventOrganizerName");
        if (StringUtils.isNotBlank(orgName)) {
            final JsonObjectBuilder organizer = Json.createObjectBuilder()
                    .add("@type", "Organization").add("name", orgName);
            addIfPresent(organizer, "url", pickString(page, policy, "eventOrganizerUrl"));
            b.add("organizer", organizer);
        }
        final String performer = pickString(page, policy, "eventPerformerName");
        if (StringUtils.isNotBlank(performer)) {
            b.add("performer", Json.createObjectBuilder().add("@type", "Person").add("name", performer));
        }

        final String offerUrl   = pickString(page, policy, "eventOfferUrl");
        final String offerPrice = pickString(page, policy, "eventOfferPrice");
        if (StringUtils.isNotBlank(offerUrl) || StringUtils.isNotBlank(offerPrice)) {
            final JsonObjectBuilder offer = Json.createObjectBuilder().add("@type", "Offer");
            addIfPresent(offer, "url", offerUrl);
            addIfPresent(offer, "price", offerPrice);
            addIfPresent(offer, "priceCurrency", pickString(page, policy, "eventOfferCurrency"));
            addIfPresent(offer, "availability", pickString(page, policy, "eventOfferAvailability"));
            addIfPresent(offer, "validFrom", pickDate(page, policy, "eventOfferValidFrom"));
            b.add("offers", offer);
        }
    }

    private void addFaqFields(final JsonObjectBuilder b) {
        final List<ValueMap> items = readMultifield("faqItems");
        if (items.isEmpty()) { return; }
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        for (final ValueMap item : items) {
            final String question = item.get("question", String.class);
            final String answer   = item.get("answer", String.class);
            if (StringUtils.isBlank(question) || StringUtils.isBlank(answer)) { continue; }
            arr.add(Json.createObjectBuilder()
                    .add("@type", "Question")
                    .add("name", question)
                    .add("acceptedAnswer", Json.createObjectBuilder()
                            .add("@type", "Answer")
                            .add("text", answer)));
        }
        b.add("mainEntity", arr);
    }

    private void addHowToFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        addIfPresent(b, "totalTime", pickString(page, policy, "howToTotalTime"));

        final String costCurrency = pickString(page, policy, "howToCostCurrency");
        final String costValue    = pickString(page, policy, "howToCostValue");
        if (StringUtils.isNotBlank(costValue)) {
            final JsonObjectBuilder cost = Json.createObjectBuilder().add("@type", "MonetaryAmount");
            addIfPresent(cost, "currency", costCurrency);
            cost.add("value", costValue);
            b.add("estimatedCost", cost);
        }

        addNamedThings(b, "supply", "HowToSupply", pickList(page, policy, "howToSupplies"));
        addNamedThings(b, "tool",   "HowToTool",  pickList(page, policy, "howToTools"));

        final List<ValueMap> steps = readMultifield("howToSteps");
        if (!steps.isEmpty()) {
            final JsonArrayBuilder arr = Json.createArrayBuilder();
            for (final ValueMap step : steps) {
                final String name = step.get("name", String.class);
                final String text = step.get("text", String.class);
                if (StringUtils.isBlank(name) && StringUtils.isBlank(text)) { continue; }
                final JsonObjectBuilder s = Json.createObjectBuilder().add("@type", "HowToStep");
                addIfPresent(s, "name", name);
                addIfPresent(s, "text", text);
                addIfPresent(s, "image", step.get("image", String.class));
                addIfPresent(s, "url", step.get("url", String.class));
                arr.add(s);
            }
            b.add("step", arr);
        }
    }

    private void addPersonFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        // Person.name overrides the generic Thing.name when authored explicitly.
        addIfPresent(b, "name", pickString(page, policy, "personName"));
        addIfPresent(b, "givenName", pickString(page, policy, "personGivenName"));
        addIfPresent(b, "familyName", pickString(page, policy, "personFamilyName"));
        addIfPresent(b, "jobTitle", pickString(page, policy, "personJobTitle"));
        addIfPresent(b, "image", pickString(page, policy, "personImage"));
        addIfPresent(b, "url", pickString(page, policy, "personUrl"));
        addIfPresent(b, "email", pickString(page, policy, "personEmail"));
        addIfPresent(b, "telephone", pickString(page, policy, "personTelephone"));
        addStringArray(b, "sameAs", pickList(page, policy, "personSameAs"));
        addStringArray(b, "knowsAbout", pickList(page, policy, "personKnowsAbout"));
        final String worksFor = pickString(page, policy, "personWorksFor");
        if (StringUtils.isNotBlank(worksFor)) {
            b.add("worksFor", Json.createObjectBuilder().add("@type", "Organization").add("name", worksFor));
        }
    }

    private void addPlaceFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        addIfPresent(b, "name", pickString(page, policy, "placeName"));
        addIfPresent(b, "telephone", pickString(page, policy, "placeTelephone"));
        addIfPresent(b, "openingHours", pickString(page, policy, "placeOpeningHours"));
        final JsonObjectBuilder address = buildPostalAddress(page, policy, "place");
        if (address != null) { b.add("address", address); }
        final String lat = pickString(page, policy, "placeLatitude");
        final String lon = pickString(page, policy, "placeLongitude");
        if (StringUtils.isNotBlank(lat) && StringUtils.isNotBlank(lon)) {
            b.add("geo", Json.createObjectBuilder()
                    .add("@type", "GeoCoordinates")
                    .add("latitude", lat)
                    .add("longitude", lon));
        }
    }

    private void addJobPostingFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        final String title = StringUtils.defaultIfBlank(pickString(page, policy, "jobTitle"), pageTitle());
        addIfPresent(b, "title", title);
        addIfPresent(b, "datePosted", pickDate(page, policy, "jobDatePosted"));
        addIfPresent(b, "validThrough", pickDate(page, policy, "jobValidThrough"));
        addIfPresent(b, "employmentType", pickString(page, policy, "jobEmploymentType"));
        addIfPresent(b, "jobLocationType", pickString(page, policy, "jobLocationType"));

        final String hiringName = pickString(page, policy, "jobHiringOrgName");
        if (StringUtils.isNotBlank(hiringName)) {
            final JsonObjectBuilder org = Json.createObjectBuilder()
                    .add("@type", "Organization").add("name", hiringName);
            addIfPresent(org, "sameAs", pickString(page, policy, "jobHiringOrgUrl"));
            b.add("hiringOrganization", org);
        }

        final JsonObjectBuilder address = buildPostalAddress(page, policy, "jobLoc");
        if (address != null) {
            b.add("jobLocation", Json.createObjectBuilder()
                    .add("@type", "Place")
                    .add("address", address));
        }

        final String salaryValue = pickString(page, policy, "jobSalaryValue");
        if (StringUtils.isNotBlank(salaryValue)) {
            final JsonObjectBuilder value = Json.createObjectBuilder()
                    .add("@type", "QuantitativeValue")
                    .add("value", salaryValue);
            addIfPresent(value, "unitText", pickString(page, policy, "jobSalaryUnit"));
            final JsonObjectBuilder salary = Json.createObjectBuilder().add("@type", "MonetaryAmount");
            addIfPresent(salary, "currency", pickString(page, policy, "jobSalaryCurrency"));
            salary.add("value", value);
            b.add("baseSalary", salary);
        }
    }

    private void addAudioFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        addIfPresent(b, "contentUrl", pickString(page, policy, "audioContentUrl"));
        addIfPresent(b, "embedUrl", pickString(page, policy, "audioEmbedUrl"));
        addIfPresent(b, "encodingFormat", pickString(page, policy, "audioEncodingFormat"));
        addIfPresent(b, "duration", pickString(page, policy, "audioDuration"));
        addIfPresent(b, "uploadDate", pickDate(page, policy, "audioUploadDate"));
        addIfPresent(b, "transcript", pickString(page, policy, "audioTranscript"));
    }

    private void addVideoFields(final JsonObjectBuilder b, final ValueMap page, final ValueMap policy) {
        addIfPresent(b, "thumbnailUrl", pickString(page, policy, "videoThumbnailUrl"));
        addIfPresent(b, "uploadDate", pickDate(page, policy, "videoUploadDate"));
        addIfPresent(b, "contentUrl", pickString(page, policy, "videoContentUrl"));
        addIfPresent(b, "embedUrl", pickString(page, policy, "videoEmbedUrl"));
        addIfPresent(b, "duration", pickString(page, policy, "videoDuration"));
        addIfPresent(b, "transcript", pickString(page, policy, "videoTranscript"));
        final String expires = StringUtils.defaultIfBlank(
                pickDate(page, policy, "videoExpires"), pageOffTime());
        addIfPresent(b, "expires", expires);
    }

    // ---- Small JSON helpers -----------------------------------------------

    private void addIfPresent(final JsonObjectBuilder b, final String key, final String value) {
        if (StringUtils.isNotBlank(value)) { b.add(key, value); }
    }

    private void addStringArray(final JsonObjectBuilder b, final String key, final List<String> values) {
        if (values == null || values.isEmpty()) { return; }
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        values.forEach(arr::add);
        b.add(key, arr);
    }

    private void addNamedThings(final JsonObjectBuilder b, final String key, final String type,
                                final List<String> names) {
        if (names == null || names.isEmpty()) { return; }
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        for (final String n : names) {
            if (StringUtils.isNotBlank(n)) {
                arr.add(Json.createObjectBuilder().add("@type", type).add("name", n));
            }
        }
        b.add(key, arr);
    }

    /** Builds a PostalAddress from {@code <prefix>Street/Locality/Region/PostalCode/Country}. */
    private JsonObjectBuilder buildPostalAddress(final ValueMap page, final ValueMap policy, final String prefix) {
        final String street     = pickString(page, policy, prefix + "Street");
        final String locality   = pickString(page, policy, prefix + "Locality");
        final String region     = pickString(page, policy, prefix + "Region");
        final String postalCode = pickString(page, policy, prefix + "PostalCode");
        final String country    = pickString(page, policy, prefix + "Country");
        if (StringUtils.isBlank(street) && StringUtils.isBlank(locality) && StringUtils.isBlank(region)
                && StringUtils.isBlank(postalCode) && StringUtils.isBlank(country)) {
            return null;
        }
        final JsonObjectBuilder addr = Json.createObjectBuilder().add("@type", "PostalAddress");
        addIfPresent(addr, "streetAddress", street);
        addIfPresent(addr, "addressLocality", locality);
        addIfPresent(addr, "addressRegion", region);
        addIfPresent(addr, "postalCode", postalCode);
        addIfPresent(addr, "addressCountry", country);
        return addr;
    }

    /** Reads a composite multifield (page seo node, falling back to policy). */
    private List<ValueMap> readMultifield(final String field) {
        final List<ValueMap> fromPage = readMultifieldFrom(pageSeoResource(), field);
        if (!fromPage.isEmpty()) { return fromPage; }
        return readMultifieldFrom(policySeoResource(), field);
    }

    private List<ValueMap> readMultifieldFrom(final Resource seoRes, final String field) {
        if (seoRes == null) { return Collections.emptyList(); }
        final Resource mf = seoRes.getChild(field);
        if (mf == null) { return Collections.emptyList(); }
        final List<ValueMap> out = new ArrayList<>();
        for (final Resource child : mf.getChildren()) {
            out.add(child.getValueMap());
        }
        return out;
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
        if (cal != null) { return new SimpleDateFormat(ISO_DATETIME).format(cal.getTime()); }
        final Date d = (page != null) ? page.get(key, Date.class) : null;
        if (d != null)   { return new SimpleDateFormat(ISO_DATETIME).format(d); }
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

    private String pageSubtitle() {
        return pageContentProps().get(PN_SUBTITLE, String.class);
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
        final String canonical = pageContentProps().get(PN_CQ_CANONICAL_URL, String.class);
        if (StringUtils.isNotBlank(canonical)) { return canonical; }
        final String vanity = currentPage.getVanityUrl();
        return StringUtils.isNotBlank(vanity) ? vanity : currentPage.getPath() + ".html";
    }

    /** Page Featured Image (Core Components page v3) — {@code cq:featuredimage/fileReference}. */
    private String pageFeaturedImage() {
        if (currentPage == null) { return null; }
        final Resource content = currentPage.getContentResource();
        if (content == null) { return null; }
        final Resource fi = content.getChild(PN_CQ_FEATURED_IMAGE);
        if (fi == null) { return null; }
        return fi.getValueMap().get(PN_FILE_REFERENCE, String.class);
    }

    private ValueMap pageContentProps() {
        if (currentPage == null) { return emptyMap(); }
        final Resource content = currentPage.getContentResource();
        return (content != null) ? content.getValueMap() : emptyMap();
    }

    private String formatCalendar(final java.util.Calendar cal) {
        if (cal == null) { return null; }
        return new SimpleDateFormat(ISO_DATETIME).format(cal.getTime());
    }

    private String pageLastModified() {
        return formatCalendar(pageContentProps().get(PN_CQ_LAST_MODIFIED, java.util.Calendar.class));
    }

    private String pageOnTime() {
        return formatCalendar(pageContentProps().get(PN_ON_TIME, java.util.Calendar.class));
    }

    private String pageOffTime() {
        return formatCalendar(pageContentProps().get(PN_OFF_TIME, java.util.Calendar.class));
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

/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.adobe.aem.guides.wknd.core.seo.models.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Calendar;
import java.util.Locale;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.scripting.SlingBindings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adobe.aem.guides.wknd.core.seo.models.SeoSchemaModel;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.policies.ContentPolicyManager;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith({ AemContextExtension.class, MockitoExtension.class })
class SeoSchemaModelImplTest {

    /**
     * A language-root path. {@code en} is a valid ISO language segment, so the
     * model classifies this exact path as the homepage. Anything deeper is an
     * inner page; anything without a language segment (e.g. /content/test) is
     * treated as an inner page too.
     */
    private static final String HOME = "/content/wknd/us/en";

    private final AemContext ctx = new AemContext();

    @Mock private TagManager tagManager;
    @Mock private Tag cyclingTag;
    @Mock private Tag summerTag;
    @Mock private ContentPolicyManager contentPolicyManager;

    @BeforeEach
    void setUp() {
        ctx.addModelsForClasses(SeoSchemaModelImpl.class);

        // Suppress policy lookup — no template defaults needed for these tests
        when(contentPolicyManager.getPolicy(any(Resource.class))).thenReturn(null);
        ctx.registerAdapter(ResourceResolver.class, ContentPolicyManager.class, contentPolicyManager);
        ctx.registerAdapter(ResourceResolver.class, TagManager.class, tagManager);
    }

    // ---- helpers ----------------------------------------------------------

    private Page createPage(String path, boolean seoEnabled, String seoType) {
        Page page = ctx.create().page(path);
        Resource content = page.getContentResource();
        ModifiableValueMap mvm = content.adaptTo(ModifiableValueMap.class);
        mvm.put("jcr:title", "Cycling Tuscany");
        mvm.put("jcr:description", "A cycling adventure in Tuscany.");
        mvm.put("jcr:uuid", "e2b47448-3c82-44dd-81f5-a92b965b1a55");

        ctx.create().resource(path + "/jcr:content/seo",
                "enabled", seoEnabled,
                "type",    seoType);
        return page;
    }

    private ModifiableValueMap seo(Page page) {
        return page.getContentResource().getChild("seo").adaptTo(ModifiableValueMap.class);
    }

    private Calendar cal(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, day, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /**
     * Adapts the request to SeoSchemaModel after injecting the page into SlingBindings.
     * wcm.io's ctx.currentPage() sets the request resource but does NOT populate
     * SlingBindings, so @ScriptVariable currentPage would be null without this step.
     */
    private SeoSchemaModel adapt(String pagePath) {
        ctx.currentPage(pagePath);
        Page page = ctx.pageManager().getPage(pagePath);
        SlingBindings bindings = new SlingBindings();
        bindings.put("currentPage", page);
        ctx.request().setAttribute(SlingBindings.class.getName(), bindings);
        return ctx.request().adaptTo(SeoSchemaModel.class);
    }

    // ---- dateModified fallback -------------------------------------------

    @Test
    void dateModified_fallsBackToCqLastModified_whenSeoFieldBlank() {
        Page page = createPage("/content/test", true, "Article");
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("cq:lastModified", cal(2026, 1, 15));

        SeoSchemaModel model = adapt("/content/test");

        assertTrue(model.isEnabled());
        assertTrue(model.getJsonLd().contains("\"dateModified\""),
                "Expected dateModified from cq:lastModified");
        assertTrue(model.getJsonLd().contains("2026-01-15"),
                "Expected cq:lastModified date value in output");
    }

    @Test
    void dateModified_usesAuthoredValue_whenSeoFieldPresent() {
        Page page = createPage("/content/test", true, "Article");
        seo(page).put("dateModified", "2020-06-01");
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("cq:lastModified", cal(2026, 1, 15));

        SeoSchemaModel model = adapt("/content/test");

        assertTrue(model.getJsonLd().contains("2020-06-01"),
                "Authored dateModified should take precedence over cq:lastModified");
        assertFalse(model.getJsonLd().contains("2026-01-15"),
                "cq:lastModified should not appear when authored value is set");
    }

    // ---- datePublished fallback -----------------------------------------

    @Test
    void datePublished_usesReplicationDate_whenActionIsActivate() {
        Page page = createPage("/content/test", true, "Article");
        ModifiableValueMap mvm = page.getContentResource().adaptTo(ModifiableValueMap.class);
        mvm.put("cq:lastReplicated",        cal(2026, 1, 20));
        mvm.put("cq:lastReplicationAction", "Activate");

        SeoSchemaModel model = adapt("/content/test");

        assertTrue(model.getJsonLd().contains("\"datePublished\""));
        assertTrue(model.getJsonLd().contains("2026-01-20"));
    }

    @Test
    void datePublished_fallsBackToOnTime_whenNoReplication() {
        Page page = createPage("/content/test", true, "Article");
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("onTime", cal(2026, 2, 2));

        SeoSchemaModel model = adapt("/content/test");

        assertTrue(model.getJsonLd().contains("\"datePublished\""));
        assertTrue(model.getJsonLd().contains("2026-02-02"));
    }

    @Test
    void datePublished_absent_whenActionIsDeactivate() {
        Page page = createPage("/content/test", true, "Article");
        ModifiableValueMap mvm = page.getContentResource().adaptTo(ModifiableValueMap.class);
        mvm.put("cq:lastReplicated",        cal(2026, 1, 20));
        mvm.put("cq:lastReplicationAction", "Deactivate");

        SeoSchemaModel model = adapt("/content/test");

        assertFalse(model.getJsonLd().contains("\"datePublished\""),
                "datePublished must not appear for deactivated pages");
    }

    // ---- native AEM metadata fallbacks ----------------------------------

    @Test
    void image_fallsBackToFeaturedImage_whenSeoImageBlank() {
        Page page = createPage("/content/test", true, "Article");
        ctx.create().resource("/content/test/jcr:content/cq:featuredimage",
                "fileReference", "/content/dam/wknd/featured.jpg");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("/content/dam/wknd/featured.jpg"),
                "Featured image should be used as the schema:image fallback");
    }

    @Test
    void atId_usesCanonicalUrl_whenSet() {
        Page page = createPage("/content/test", true, "Article");
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("cq:canonicalUrl", "https://www.wknd.site/canonical");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("https://www.wknd.site/canonical"),
                "cq:canonicalUrl should drive @id / url when present");
    }

    @Test
    void noindex_suppressesOutput() {
        Page page = createPage("/content/test", true, "Article");
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("cq:robotsTags", new String[]{ "noindex", "nofollow" });

        SeoSchemaModel model = adapt("/content/test");

        assertFalse(model.isEnabled(), "noindex pages must not emit JSON-LD");
        assertTrue(model.getJsonLd().isEmpty());
    }

    // ---- cq:tags → keywords + about ------------------------------------

    @Test
    void tags_populateKeywordsAndAbout_whenSeoKeywordsBlank() {
        when(cyclingTag.getTitle()).thenReturn("Cycling");
        when(cyclingTag.getTitle(any(Locale.class))).thenReturn("Cycling");
        when(summerTag.getTitle()).thenReturn("Summer");
        when(summerTag.getTitle(any(Locale.class))).thenReturn("Summer");
        when(tagManager.resolve("wknd:activity/cycling")).thenReturn(cyclingTag);
        when(tagManager.resolve("wknd:season/summer")).thenReturn(summerTag);

        Page page = createPage("/content/test", true, "Article");
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("cq:tags", new String[]{ "wknd:activity/cycling", "wknd:season/summer" });

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertTrue(json.contains("\"keywords\""), "keywords array expected");
        assertTrue(json.contains("Cycling"),      "Cycling keyword expected");
        assertTrue(json.contains("Summer"),       "Summer keyword expected");
        assertTrue(json.contains("\"about\""),    "about array expected");
        assertTrue(json.contains("\"@type\":\"Thing\""), "about items must be Thing");
    }

    @Test
    void tags_unresolvableTagIds_areSkipped() {
        when(tagManager.resolve(any())).thenReturn(null);

        Page page = createPage("/content/test", true, "Article");
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("cq:tags", new String[]{ "wknd:invalid/tag" });

        SeoSchemaModel model = adapt("/content/test");

        assertFalse(model.getJsonLd().contains("\"about\""),
                "about must not appear when all tags fail to resolve");
    }

    // ---- @id + identifier (jcr:uuid) ------------------------------------

    @Test
    void atId_equalsCanonicalUrl() {
        createPage("/content/test", true, "Article");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"@id\""),              "@id must be present");
        assertTrue(json.contains("/content/test.html"),   "@id must equal canonical URL");
    }

    @Test
    void identifier_containsJcrUuid() {
        createPage("/content/test", true, "Article");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"identifier\""),                   "identifier block expected");
        assertTrue(json.contains("\"PropertyValue\""),                "identifier must be PropertyValue");
        assertTrue(json.contains("jcr:uuid"),                         "identifier name must be jcr:uuid");
        assertTrue(json.contains("e2b47448-3c82-44dd-81f5-a92b965b1a55"), "UUID value must appear");
    }

    // ---- enabled / disabled gate ----------------------------------------

    @Test
    void disabled_whenEnabledFlagFalse() {
        createPage("/content/test", false, "Article");

        SeoSchemaModel model = adapt("/content/test");

        assertFalse(model.isEnabled());
        assertTrue(model.getJsonLd().isEmpty());
    }

    @Test
    void enabled_byDefault_whenFlagAbsent() {
        ctx.create().page("/content/test");
        Resource content = ctx.pageManager().getPage("/content/test").getContentResource();
        content.adaptTo(ModifiableValueMap.class).put("jcr:title", "Untitled");
        // seo node with a type but no explicit enabled flag
        ctx.create().resource("/content/test/jcr:content/seo", "type", "Article");

        SeoSchemaModel model = adapt("/content/test");

        assertTrue(model.isEnabled(), "Schema emission must default to enabled when the flag is absent");
    }

    // ---- homepage scope: Organization + WebSite only --------------------

    @Test
    void homepage_emitsOrganizationAndWebSite_withoutBreadcrumbOrPrimary() {
        createPage(HOME, true, "Article"); // type is ignored on the homepage

        String json = adapt(HOME).getJsonLd();

        assertTrue(json.contains("\"Organization\""), "Organization must anchor the homepage");
        assertTrue(json.contains("\"WKND\""),          "Organization name constant must appear");
        assertTrue(json.contains("#organization"),     "Organization @id must be present");
        assertTrue(json.contains("\"WebSite\""),       "WebSite must anchor the homepage");
        assertTrue(json.contains("#website"),          "WebSite @id must be present");
        assertFalse(json.contains("\"BreadcrumbList\""), "Homepage must not carry a breadcrumb");
        assertFalse(json.contains("\"Article\""),        "Homepage must not emit a page-level primary");
    }

    @Test
    void homepage_organizationCarriesDescription() {
        createPage(HOME, true, "Article");

        String json = adapt(HOME).getJsonLd();

        assertTrue(json.contains("A cycling adventure in Tuscany."),
                "Organization description should come from the homepage jcr:description");
    }

    @Test
    void homepage_webSiteHasSearchAction() {
        createPage(HOME, true, "Article");

        String json = adapt(HOME).getJsonLd();

        assertTrue(json.contains("\"potentialAction\""), "WebSite must expose a potentialAction");
        assertTrue(json.contains("\"SearchAction\""),    "potentialAction must be a SearchAction");
        assertTrue(json.contains("{q}"),                 "SearchAction urlTemplate must include the {q} placeholder");
    }

    // ---- inner pages: primary + breadcrumb, no brand nodes --------------

    @Test
    void innerPage_doesNotEmitOrganizationOrWebSite() {
        ctx.create().page("/content/wknd");
        createPage("/content/wknd/magazine", true, "Article");

        String json = adapt("/content/wknd/magazine").getJsonLd();

        assertTrue(json.contains("\"Article\""),        "Inner page emits its page-level primary");
        assertFalse(json.contains("\"Organization\""),  "Inner pages must not re-emit the Organization node");
        assertFalse(json.contains("\"WebSite\""),       "Inner pages must not re-emit the WebSite node");
    }

    @Test
    void innerPage_creativeWorkReferencesBrandEntitiesById() {
        createPage("/content/test", true, "Article");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"isPartOf\""),  "CreativeWork must link to the WebSite by @id");
        assertTrue(json.contains("#website"),       "isPartOf must reference the WebSite @id");
        assertTrue(json.contains("\"publisher\""), "CreativeWork must link to the Organization by @id");
        assertTrue(json.contains("#organization"),  "publisher must reference the Organization @id");
    }

    @Test
    void innerPage_publisherOverride_whenAuthored() {
        Page page = createPage("/content/test", true, "Article");
        seo(page).put("publisherName", "Custom Publisher");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("Custom Publisher"), "Authored publisher name should override the brand reference");
    }

    @Test
    void graph_includesBreadcrumb_forInnerPages() {
        ctx.create().page("/content/wknd");
        createPage("/content/wknd/magazine", true, "Article");

        String json = adapt("/content/wknd/magazine").getJsonLd();

        assertTrue(json.contains("\"BreadcrumbList\""), "BreadcrumbList must appear on inner pages");
        assertTrue(json.contains("\"ListItem\""),       "ListItem entries must be present");
    }

    @Test
    void innerPage_unsupportedType_emitsBreadcrumbOnly() {
        ctx.create().page("/content/wknd");
        createPage("/content/wknd/legacy", true, "WebPage"); // WebPage is parked

        String json = adapt("/content/wknd/legacy").getJsonLd();

        assertTrue(json.contains("\"BreadcrumbList\""), "BreadcrumbList must still appear");
        assertFalse(json.contains("\"WebPage\""),       "Parked WebPage type must not emit a primary node");
    }

    // ---- per-type builders ----------------------------------------------

    @Test
    void event_buildsCoreProperties() {
        Page page = createPage("/content/test", true, "Event");
        ModifiableValueMap m = seo(page);
        m.put("eventStartDate", "2026-09-01T18:00+02:00");
        m.put("eventLocationName", "Tuscany Arena");
        m.put("eventLocLocality", "Florence");
        m.put("eventOfferUrl", "https://tickets.example.com");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"Event\""),     "Event @type expected");
        assertTrue(json.contains("\"startDate\""), "startDate expected");
        assertTrue(json.contains("\"location\""),  "location expected");
        assertTrue(json.contains("Tuscany Arena"), "location name expected");
        assertTrue(json.contains("\"offers\""),    "offers expected");
        assertFalse(json.contains("\"headline\""), "Event is not a CreativeWork — no headline");
    }

    @Test
    void faqPage_buildsMainEntityFromMultifield() {
        createPage("/content/test", true, "FAQPage");
        ctx.create().resource("/content/test/jcr:content/seo/faqItems/item0",
                "question", "Is cycling allowed?", "answer", "Yes, on marked trails.");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"FAQPage\""),         "FAQPage @type expected");
        assertTrue(json.contains("\"mainEntity\""),      "mainEntity expected");
        assertTrue(json.contains("\"Question\""),        "Question node expected");
        assertTrue(json.contains("\"acceptedAnswer\""),  "acceptedAnswer expected");
        assertTrue(json.contains("Is cycling allowed?"), "question text expected");
    }

    @Test
    void jobPosting_buildsRequiredProperties() {
        Page page = createPage("/content/test", true, "JobPosting");
        ModifiableValueMap m = seo(page);
        m.put("jobTitle", "Trail Guide");
        m.put("jobDatePosted", "2026-03-01");
        m.put("jobHiringOrgName", "WKND Adventures");
        m.put("jobLocLocality", "Florence");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"JobPosting\""),         "JobPosting @type expected");
        assertTrue(json.contains("\"datePosted\""),         "datePosted expected");
        assertTrue(json.contains("\"hiringOrganization\""), "hiringOrganization expected");
        assertTrue(json.contains("\"jobLocation\""),        "jobLocation expected");
        assertTrue(json.contains("Trail Guide"),            "title expected");
    }

    @Test
    void person_buildsProfileProperties() {
        Page page = createPage("/content/test", true, "Person");
        ModifiableValueMap m = seo(page);
        m.put("personName", "Jane Rider");
        m.put("personJobTitle", "Chief Guide");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"Person\""),    "Person @type expected");
        assertTrue(json.contains("Jane Rider"),    "person name expected");
        assertTrue(json.contains("\"jobTitle\""),  "jobTitle expected");
        assertFalse(json.contains("\"headline\""), "Person is not a CreativeWork — no headline");
        assertFalse(json.contains("\"isPartOf\""), "Person must not carry CreativeWork brand links");
    }
}

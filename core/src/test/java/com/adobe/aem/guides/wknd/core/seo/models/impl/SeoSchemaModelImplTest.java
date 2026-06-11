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
        // Modify the seo node that createPage() already created — avoid path collision
        Resource seoResource = page.getContentResource().getChild("seo");
        seoResource.adaptTo(ModifiableValueMap.class).put("dateModified", "2020-06-01");
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
    void datePublished_absent_whenActionIsDeactivate() {
        Page page = createPage("/content/test", true, "Article");
        ModifiableValueMap mvm = page.getContentResource().adaptTo(ModifiableValueMap.class);
        mvm.put("cq:lastReplicated",        cal(2026, 1, 20));
        mvm.put("cq:lastReplicationAction", "Deactivate");

        SeoSchemaModel model = adapt("/content/test");

        assertFalse(model.getJsonLd().contains("\"datePublished\""),
                "datePublished must not appear for deactivated pages");
    }

    @Test
    void datePublished_absent_whenNoReplicationActionPresent() {
        createPage("/content/test", true, "Article");

        SeoSchemaModel model = adapt("/content/test");

        assertFalse(model.getJsonLd().contains("\"datePublished\""));
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
    void tags_aboutAlwaysPresent_evenWhenAuthoredKeywordsExist() {
        when(cyclingTag.getTitle()).thenReturn("Cycling");
        when(cyclingTag.getTitle(any(Locale.class))).thenReturn("Cycling");
        when(tagManager.resolve("wknd:activity/cycling")).thenReturn(cyclingTag);

        Page page = createPage("/content/test", true, "Article");
        // Modify the seo node that createPage() already created — avoid path collision
        Resource seoResource = page.getContentResource().getChild("seo");
        seoResource.adaptTo(ModifiableValueMap.class).put("keywords", new String[]{ "custom" });
        page.getContentResource().adaptTo(ModifiableValueMap.class)
                .put("cq:tags", new String[]{ "wknd:activity/cycling" });

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertTrue(json.contains("custom"),    "Authored keyword must appear");
        assertTrue(json.contains("\"about\""), "about must still be emitted from tags");
        assertTrue(json.contains("Cycling"),   "Tag title must appear in about");
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
        createPage("/content/test", true, "WebPage");

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertTrue(json.contains("\"@id\""),              "@id must be present");
        assertTrue(json.contains("/content/test.html"),   "@id must equal canonical URL");
    }

    @Test
    void identifier_containsJcrUuid() {
        createPage("/content/test", true, "WebPage");

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertTrue(json.contains("\"identifier\""),                   "identifier block expected");
        assertTrue(json.contains("\"PropertyValue\""),                "identifier must be PropertyValue");
        assertTrue(json.contains("jcr:uuid"),                         "identifier name must be jcr:uuid");
        assertTrue(json.contains("e2b47448-3c82-44dd-81f5-a92b965b1a55"), "UUID value must appear");
    }

    // ---- enabled / disabled gate ----------------------------------------

    @Test
    void disabled_whenEnabledFlagFalse() {
        createPage("/content/test", false, "WebPage");

        SeoSchemaModel model = adapt("/content/test");

        assertFalse(model.isEnabled());
        assertTrue(model.getJsonLd().isEmpty());
    }

    // ---- multi-schema @graph output -------------------------------------

    @Test
    void graph_alwaysWrapsInGraph_becauseOrgAndWebSiteAlwaysPresent() {
        createPage("/content/test", true, "WebPage");

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertTrue(model.isEnabled());
        assertTrue(json.contains("\"@graph\""),
                "@graph must always be present because Organization + WebSite are always injected");
    }

    @Test
    void graph_alwaysIncludesOrganization() {
        createPage("/content/test", true, "Article");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"Organization\""), "Organization node must always be present");
        assertTrue(json.contains("\"WKND\""),          "Organization name constant must appear");
        assertTrue(json.contains("#organization"),     "Organization @id must be present");
    }

    @Test
    void graph_alwaysIncludesWebSite() {
        createPage("/content/test", true, "WebPage");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"WebSite\""),   "WebSite node must always be present");
        assertTrue(json.contains("#website"),      "WebSite @id must be present");
        assertTrue(json.contains("wknd.site"),     "WebSite URL constant must appear");
    }

    @Test
    void graph_includesBreadcrumb_forInnerPages() {
        // Two-level hierarchy — breadcrumb is meaningful
        ctx.create().page("/content/wknd");
        createPage("/content/wknd/magazine", true, "Article");

        String json = adapt("/content/wknd/magazine").getJsonLd();

        assertTrue(json.contains("\"BreadcrumbList\""), "BreadcrumbList must appear on inner pages");
        assertTrue(json.contains("\"ListItem\""),       "ListItem entries must be present");
    }

    @Test
    void graph_noBreadcrumb_forTopLevelPage() {
        // Page at depth 2 — only one crumb, no trail to show
        createPage("/content/wknd", true, "WebPage");

        String json = adapt("/content/wknd").getJsonLd();

        assertFalse(json.contains("\"BreadcrumbList\""),
                "BreadcrumbList must not appear for top-level pages with no meaningful trail");
    }

    @Test
    void graph_primarySchemaIsPresent_alsoWithAutoSchemas() {
        createPage("/content/test", true, "Article");

        String json = adapt("/content/test").getJsonLd();

        assertTrue(json.contains("\"Article\""),      "primary Article node must be present");
        assertTrue(json.contains("\"Organization\""), "auto Organization must co-exist");
        assertTrue(json.contains("\"WebSite\""),      "auto WebSite must co-exist");
    }
}

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.adobe.aem.guides.wknd.core.seo.config.SeoGlobalConfigService;
import com.adobe.aem.guides.wknd.core.seo.models.SeoSchemaModel;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.policies.ContentPolicyManager;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith({ AemContextExtension.class, MockitoExtension.class })
@MockitoSettings(strictness = Strictness.LENIENT)
class SeoSchemaModelImplTest {

    private final AemContext ctx = new AemContext();

    @Mock private TagManager tagManager;
    @Mock private Tag cyclingTag;
    @Mock private Tag summerTag;
    @Mock private ContentPolicyManager contentPolicyManager;
    @Mock private SeoGlobalConfigService globalConfig;

    @BeforeEach
    void setUp() {
        ctx.addModelsForClasses(SeoSchemaModelImpl.class);

        // Suppress policy lookup — no template defaults needed for these tests
        when(contentPolicyManager.getPolicy(any(Resource.class))).thenReturn(null);
        ctx.registerAdapter(ResourceResolver.class, ContentPolicyManager.class, contentPolicyManager);
        ctx.registerAdapter(ResourceResolver.class, TagManager.class, tagManager);

        // Default: globalConfig present but all auto-schemas disabled (keeps existing tests clean)
        ctx.registerService(SeoGlobalConfigService.class, globalConfig);
        when(globalConfig.includeOrganization()).thenReturn(false);
        when(globalConfig.includeBreadcrumb()).thenReturn(false);
        when(globalConfig.includeWebSite()).thenReturn(false);
        when(globalConfig.organizationName()).thenReturn("");
        when(globalConfig.organizationUrl()).thenReturn("");
        when(globalConfig.logoPath()).thenReturn("");
        when(globalConfig.sameAs()).thenReturn(new String[]{});
        when(globalConfig.searchUrlTemplate()).thenReturn("");
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
    void graph_wrapsMultipleNodes_whenOrganizationEnabled() {
        when(globalConfig.includeOrganization()).thenReturn(true);
        when(globalConfig.organizationName()).thenReturn("WKND");
        when(globalConfig.organizationUrl()).thenReturn("https://www.wknd.site");

        createPage("/content/test", true, "Article");

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertTrue(model.isEnabled());
        assertTrue(json.contains("\"@graph\""),       "@graph wrapper expected");
        assertTrue(json.contains("\"Article\""),      "primary Article node expected");
        assertTrue(json.contains("\"Organization\""), "auto-injected Organization node expected");
        assertTrue(json.contains("\"WKND\""),         "organization name expected");
    }

    @Test
    void graph_includesBreadcrumb_whenBreadcrumbEnabled() {
        when(globalConfig.includeBreadcrumb()).thenReturn(true);

        // Create a two-level hierarchy so a breadcrumb is meaningful
        ctx.create().page("/content/wknd");
        createPage("/content/wknd/magazine", true, "Article");

        SeoSchemaModel model = adapt("/content/wknd/magazine");
        String json = model.getJsonLd();

        assertTrue(json.contains("\"@graph\""),           "@graph expected");
        assertTrue(json.contains("\"BreadcrumbList\""),   "BreadcrumbList node expected");
        assertTrue(json.contains("\"ListItem\""),         "ListItem entries expected");
    }

    @Test
    void graph_includesWebSite_whenWebSiteEnabled() {
        when(globalConfig.includeWebSite()).thenReturn(true);
        when(globalConfig.organizationUrl()).thenReturn("https://www.wknd.site");
        when(globalConfig.organizationName()).thenReturn("WKND");
        when(globalConfig.searchUrlTemplate()).thenReturn("https://www.wknd.site/search?q={search_term_string}");

        createPage("/content/test", true, "WebPage");

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertTrue(json.contains("\"@graph\""),         "@graph expected");
        assertTrue(json.contains("\"WebSite\""),         "WebSite node expected");
        assertTrue(json.contains("\"SearchAction\""),    "SearchAction expected");
        assertTrue(json.contains("search_term_string"),  "search template expected");
    }

    @Test
    void graph_noWrapper_whenAllAutoSchemasDisabledAndSinglePrimary() {
        // globalConfig has all disabled (setUp default) — output must be a plain object, no @graph
        createPage("/content/test", true, "WebPage");

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertFalse(json.contains("\"@graph\""), "@graph must not appear for a single node");
        assertTrue(json.contains("\"WebPage\""), "primary WebPage schema expected");
    }

    @Test
    void graph_pageToggle_canExcludeOrganizationEvenWhenGlobalEnabled() {
        when(globalConfig.includeOrganization()).thenReturn(true);
        when(globalConfig.organizationName()).thenReturn("WKND");
        when(globalConfig.organizationUrl()).thenReturn("https://www.wknd.site");

        Page page = createPage("/content/test", true, "Article");
        // Author explicitly suppresses Organization on this page
        page.getContentResource().getChild("seo").adaptTo(ModifiableValueMap.class)
                .put("includeOrganization", "no");

        SeoSchemaModel model = adapt("/content/test");
        String json = model.getJsonLd();

        assertFalse(json.contains("\"Organization\""),
                "Organization must be suppressed when page toggle is 'no'");
    }
}

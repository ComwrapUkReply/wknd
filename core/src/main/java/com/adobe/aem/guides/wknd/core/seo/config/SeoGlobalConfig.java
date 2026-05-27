/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.adobe.aem.guides.wknd.core.seo.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for site-wide Schema.org auto-injection.
 * Deployed via ui.config — one instance per AEM environment.
 */
@ObjectClassDefinition(
        name = "WKND SEO – Global Site Config",
        description = "Site-wide defaults for Schema.org structured data auto-injection (Organization, BreadcrumbList, WebSite).")
public @interface SeoGlobalConfig {

    // ---- Organization -------------------------------------------------------

    @AttributeDefinition(name = "Organization name",
            description = "schema:name for the auto-injected Organization node.")
    String organizationName() default "";

    @AttributeDefinition(name = "Organization URL",
            description = "Canonical home-page URL used as schema:url and @id.")
    String organizationUrl() default "";

    @AttributeDefinition(name = "Logo DAM path / URL",
            description = "schema:logo — /content/dam path or absolute URL.")
    String logoPath() default "";

    @AttributeDefinition(name = "sameAs URLs",
            description = "Social/authoritative profile URLs (LinkedIn, Wikipedia, Wikidata, etc.).")
    String[] sameAs() default {};

    // ---- Auto-injection toggles ---------------------------------------------

    @AttributeDefinition(name = "Include Organization schema",
            description = "Auto-inject an Organization node on every page (per-page dialog can override).")
    boolean includeOrganization() default true;

    @AttributeDefinition(name = "Include BreadcrumbList",
            description = "Auto-inject a BreadcrumbList node derived from the AEM page hierarchy.")
    boolean includeBreadcrumb() default true;

    @AttributeDefinition(name = "Include WebSite schema",
            description = "Auto-inject a WebSite node (enables Google Sitelinks Searchbox).")
    boolean includeWebSite() default true;

    // ---- WebSite / SearchAction ---------------------------------------------

    @AttributeDefinition(name = "Search URL template",
            description = "SearchAction urlTemplate, e.g. https://www.example.com/search?q={search_term_string}. Leave blank to omit.")
    String searchUrlTemplate() default "";
}

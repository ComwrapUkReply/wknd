/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.adobe.aem.guides.wknd.core.seo.config;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Exposes {@link SeoGlobalConfig} values to Sling Models and other OSGi consumers.
 * Injected into {@code SeoSchemaModelImpl} via {@code @OSGiService}.
 */
@Component(service = SeoGlobalConfigService.class, immediate = true)
@Designate(ocd = SeoGlobalConfig.class)
public class SeoGlobalConfigService {

    private SeoGlobalConfig config;

    @Activate
    @Modified
    protected void activate(final SeoGlobalConfig config) {
        this.config = config;
    }

    public String   organizationName()    { return config.organizationName(); }
    public String   organizationUrl()     { return config.organizationUrl(); }
    public String   logoPath()            { return config.logoPath(); }
    public String[] sameAs()              { return config.sameAs(); }
    public boolean  includeOrganization() { return config.includeOrganization(); }
    public boolean  includeBreadcrumb()   { return config.includeBreadcrumb(); }
    public boolean  includeWebSite()      { return config.includeWebSite(); }
    public String   searchUrlTemplate()   { return config.searchUrlTemplate(); }
}

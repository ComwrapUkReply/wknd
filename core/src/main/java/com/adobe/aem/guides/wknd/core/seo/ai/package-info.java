/*
 * SEO / Schema.org AI provider SPI.
 *
 * Small, provider-agnostic API for turning authored page content into a
 * Schema.org JSON-LD draft. Implementations are pluggable OSGi services
 * (see impl/). Consumers (servlets, Sling models) depend only on this
 * package.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
package com.adobe.aem.guides.wknd.core.seo.ai;

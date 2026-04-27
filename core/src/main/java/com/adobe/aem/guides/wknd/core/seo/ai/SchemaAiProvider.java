/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo.ai;

import org.osgi.annotation.versioning.ConsumerType;

/**
 * SPI for AI-backed Schema.org / JSON-LD generators.
 *
 * Multiple implementations can live in the container; the
 * {@code SchemaAiServlet} picks one by {@link #getId()} (or falls back
 * to the highest {@link org.osgi.framework.Constants#SERVICE_RANKING}).
 */
@ConsumerType
public interface SchemaAiProvider {

    /**
     * Stable identifier used by the dialog's provider dropdown and to
     * route requests. Lowercase, short: "openai", "anthropic", "firefly".
     */
    String getId();

    /**
     * Human-readable label for UI / logs.
     */
    String getLabel();

    /**
     * True when this provider is fully configured and usable right now
     * (e.g. API key present). The servlet uses this to avoid selecting
     * a provider that would always fail.
     */
    boolean isAvailable();

    /**
     * Generate a Schema.org JSON-LD document from the given request.
     *
     * Implementations MUST NOT throw; wrap all failures in
     * {@link GenerationResult#error(String, String)}.
     *
     * @param request never null
     * @return never null
     */
    GenerationResult generate(GenerationRequest request);
}

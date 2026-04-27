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

import org.osgi.annotation.versioning.ProviderType;

/**
 * Immutable response from an AI provider.
 *
 * Carries either a successful JSON-LD payload or a failure message.
 * Never throws from the getters; consumers should check {@link #isSuccess()}.
 */
@ProviderType
public final class GenerationResult {

    private final boolean success;
    private final String jsonLd;
    private final String providerId;
    private final String errorMessage;

    private GenerationResult(final boolean success,
                             final String jsonLd,
                             final String providerId,
                             final String errorMessage) {
        this.success = success;
        this.jsonLd = jsonLd;
        this.providerId = providerId;
        this.errorMessage = errorMessage;
    }

    public static GenerationResult ok(final String jsonLd, final String providerId) {
        return new GenerationResult(true, jsonLd, providerId, null);
    }

    public static GenerationResult error(final String message, final String providerId) {
        return new GenerationResult(false, null, providerId, message);
    }

    public boolean isSuccess()       { return success; }
    public String  getJsonLd()       { return jsonLd; }
    public String  getProviderId()   { return providerId; }
    public String  getErrorMessage() { return errorMessage; }
}

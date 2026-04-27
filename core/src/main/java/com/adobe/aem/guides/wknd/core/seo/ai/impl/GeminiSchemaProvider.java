/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo.ai.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonWriter;
import javax.json.stream.JsonGenerator;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.aem.guides.wknd.core.seo.ai.GenerationRequest;
import com.adobe.aem.guides.wknd.core.seo.ai.GenerationResult;
import com.adobe.aem.guides.wknd.core.seo.ai.SchemaAiProvider;

/**
 * Google Gemini-backed {@link SchemaAiProvider}.
 *
 * Uses the Gemini generateContent REST API directly (no vendor SDK)
 * to keep the bundle dependency-light.
 *
 * Configurable via OSGi: API key, base URL, model name, timeout.
 */
@Component(
        service = SchemaAiProvider.class,
        property = { "service.ranking:Integer=200" }
)
@Designate(ocd = GeminiSchemaProvider.Config.class)
public class GeminiSchemaProvider implements SchemaAiProvider {

    // ---- Configuration ----------------------------------------------------

    @ObjectClassDefinition(
            name = "WKND SEO - Google Gemini Schema.org Provider",
            description = "Generates Schema.org JSON-LD using the Google Gemini generateContent API.")
    public @interface Config {

        @AttributeDefinition(name = "API Key",
                description = "Google Gemini API key. Leave blank to disable this provider. "
                        + "In AEMaaCS, supply via $[secret:GEMINI_API_KEY].")
        String apiKey() default "";

        @AttributeDefinition(name = "Base URL",
                description = "Gemini API base URL. Override for Vertex AI or a gateway. "
                        + "The model and action are appended automatically.")
        String baseUrl() default "https://generativelanguage.googleapis.com/v1beta/models";

        @AttributeDefinition(name = "Model",
                description = "Gemini model identifier.")
        String model() default "gemini-2.0-flash";

        @AttributeDefinition(name = "Timeout (seconds)",
                description = "HTTP request timeout.")
        int timeoutSeconds() default 30;

        @AttributeDefinition(name = "Max body characters",
                description = "Page body is truncated to this length before being sent to the model.")
        int maxBodyChars() default 8000;
    }

    // ---- Constants --------------------------------------------------------

    private static final String PROVIDER_ID = "gemini";
    private static final String PROVIDER_LABEL = "Google Gemini";

    private static final String SYSTEM_PROMPT =
            "You are a Schema.org / JSON-LD generator for SEO and AEO (answer engine "
            + "optimization). Produce a single valid JSON-LD object (not an array) "
            + "with '@context' set to 'https://schema.org'. Use the requested @type. "
            + "Only include properties supported by schema.org for that type. "
            + "Be concise and factual; do not invent data not present in the input. "
            + "Return ONLY the JSON object - no prose, no markdown fences.";

    private static final Logger LOG = LoggerFactory.getLogger(GeminiSchemaProvider.class);

    // ---- State ------------------------------------------------------------

    private Config config;

    // ---- Lifecycle --------------------------------------------------------

    @Activate
    @Modified
    protected void activate(final Config cfg) {
        this.config = cfg;
        LOG.info("GeminiSchemaProvider activated (model={}, available={})", cfg.model(), isAvailable());
    }

    @Deactivate
    protected void deactivate() {
        this.config = null;
    }

    // ---- SchemaAiProvider -------------------------------------------------

    @Override
    public String getId() { return PROVIDER_ID; }

    @Override
    public String getLabel() { return PROVIDER_LABEL; }

    @Override
    public boolean isAvailable() {
        return config != null && StringUtils.isNotBlank(config.apiKey());
    }

    @Override
    public GenerationResult generate(final GenerationRequest request) {
        if (request == null) {
            return GenerationResult.error("Request is null.", PROVIDER_ID);
        }
        if (!isAvailable()) {
            return GenerationResult.error(
                    "Gemini provider is not configured (missing API key).", PROVIDER_ID);
        }
        try {
            final String userPrompt = buildUserPrompt(request);
            final String payload    = buildGeminiPayload(userPrompt);
            final String raw        = callGemini(payload);
            final String jsonLd     = extractJsonLd(raw);
            if (StringUtils.isBlank(jsonLd)) {
                return GenerationResult.error("Gemini returned an empty JSON-LD payload.", PROVIDER_ID);
            }
            return GenerationResult.ok(jsonLd, PROVIDER_ID);
        } catch (final IOException ioe) {
            LOG.warn("Gemini call failed for page {}: {}", request.getPagePath(), ioe.toString());
            return GenerationResult.error("Gemini call failed: " + ioe.getMessage(), PROVIDER_ID);
        } catch (final RuntimeException re) {
            LOG.warn("Gemini generation error for page {}: {}", request.getPagePath(), re.toString());
            return GenerationResult.error("Generation error: " + re.getMessage(), PROVIDER_ID);
        }
    }

    // ---- Internals --------------------------------------------------------

    private String truncate(final String s, final int max) {
        if (s == null) { return ""; }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String buildUserPrompt(final GenerationRequest r) {
        final int max = (config != null) ? config.maxBodyChars() : 8000;
        final StringBuilder sb = new StringBuilder(2048);
        sb.append("Generate a Schema.org JSON-LD object for the page below.\n");
        sb.append("Target @type: ").append(r.getSchemaType()).append('\n');
        if (StringUtils.isNotBlank(r.getLocale()))       { sb.append("Locale: ").append(r.getLocale()).append('\n'); }
        if (StringUtils.isNotBlank(r.getCanonicalUrl())) { sb.append("URL: ").append(r.getCanonicalUrl()).append('\n'); }
        if (StringUtils.isNotBlank(r.getTitle()))        { sb.append("Title: ").append(r.getTitle()).append('\n'); }
        if (StringUtils.isNotBlank(r.getDescription()))  { sb.append("Description: ").append(r.getDescription()).append('\n'); }
        if (StringUtils.isNotBlank(r.getAuthorPrompt())) { sb.append("Author instructions: ").append(r.getAuthorPrompt()).append('\n'); }
        if (StringUtils.isNotBlank(r.getBodyText()))     { sb.append("\nBody:\n").append(truncate(r.getBodyText(), max)).append('\n'); }
        return sb.toString();
    }

    /**
     * Builds the Gemini generateContent request body.
     *
     * Format:
     * <pre>
     * {
     *   "system_instruction": { "parts": [{ "text": "..." }] },
     *   "contents": [{ "parts": [{ "text": "..." }] }],
     *   "generationConfig": { "temperature": 0.2, "responseMimeType": "application/json" }
     * }
     * </pre>
     */
    private String buildGeminiPayload(final String userPrompt) {
        return Json.createObjectBuilder()
                .add("system_instruction", Json.createObjectBuilder()
                        .add("parts", Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("text", SYSTEM_PROMPT))))
                .add("contents", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("parts", Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder().add("text", userPrompt)))))
                .add("generationConfig", Json.createObjectBuilder()
                        .add("temperature", 0.2)
                        .add("responseMimeType", "application/json"))
                .build().toString();
    }

    /**
     * POST to {@code {baseUrl}/{model}:generateContent?key={apiKey}}.
     */
    private String callGemini(final String payload) throws IOException {
        final String base = config.baseUrl().replaceAll("/+$", "");
        final URI uri = URI.create(base + "/" + config.model() + ":generateContent?key=" + config.apiKey());
        final HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
        final int timeoutMs = (int) Duration.ofSeconds(config.timeoutSeconds()).toMillis();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            con.setRequestProperty("Accept", "application/json");
            try (OutputStream os = con.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            final int code = con.getResponseCode();
            try (InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream()) {
                final String body = readFully(in);
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code + ": " + StringUtils.abbreviate(body, 500));
                }
                return body;
            }
        } finally {
            con.disconnect();
        }
    }

    private String readFully(final InputStream in) throws IOException {
        if (in == null) { return ""; }
        final StringBuilder sb = new StringBuilder(4096);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /**
     * Extracts JSON-LD text from Gemini response:
     * {@code candidates[0].content.parts[0].text}.
     */
    private String extractJsonLd(final String rawResponse) {
        if (StringUtils.isBlank(rawResponse)) { return ""; }
        try (JsonReader reader = Json.createReader(new StringReader(rawResponse))) {
            final JsonObject root = reader.readObject();
            final JsonObject content = root.getJsonArray("candidates")
                    .getJsonObject(0)
                    .getJsonObject("content");
            final String text = content.getJsonArray("parts")
                    .getJsonObject(0)
                    .getString("text", "");
            return prettyPrint(text);
        } catch (final RuntimeException re) {
            LOG.debug("Failed to parse Gemini response: {}", re.toString());
            return "";
        }
    }

    private String prettyPrint(final String json) {
        if (StringUtils.isBlank(json)) { return json; }
        try (JsonReader r = Json.createReader(new StringReader(json))) {
            final JsonObject obj = r.readObject();
            final StringWriter sw = new StringWriter();
            final Map<String, Object> cfg = new HashMap<>();
            cfg.put(JsonGenerator.PRETTY_PRINTING, Boolean.TRUE);
            try (JsonWriter w = Json.createWriterFactory(cfg).createWriter(sw)) {
                w.writeObject(obj);
            }
            return sw.toString();
        } catch (final RuntimeException re) {
            return json;
        }
    }
}

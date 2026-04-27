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
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
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
 * OpenAI-backed {@link SchemaAiProvider}.
 *
 * Uses the standard Chat Completions API with response_format=json_object
 * so the raw HTTP response can be parsed deterministically without a
 * vendor SDK (keeps the bundle dependency-light).
 *
 * Configurable via OSGi: API key, base URL (for Azure / gateway override),
 * model name, timeout.
 */
@Component(
        service = SchemaAiProvider.class,
        property = { "service.ranking:Integer=300" }
)
@Designate(ocd = OpenAiSchemaProvider.Config.class)
public class OpenAiSchemaProvider implements SchemaAiProvider {

    // ---- Configuration ----------------------------------------------------

    @ObjectClassDefinition(
            name = "WKND SEO - OpenAI Schema.org Provider",
            description = "Generates Schema.org JSON-LD using OpenAI chat completions.")
    public @interface Config {

        @AttributeDefinition(name = "API Key",
                description = "OpenAI API key. Leave blank to disable this provider. "
                        + "In AEMaaCS, supply via $[secret:OPENAI_API_KEY].")
        String apiKey() default "";

        @AttributeDefinition(name = "Base URL",
                description = "API base URL. Override for Azure OpenAI or a gateway.")
        String baseUrl() default "https://api.openai.com/v1";

        @AttributeDefinition(name = "Model",
                description = "Chat completion model identifier.")
        String model() default "gpt-4o-mini";

        @AttributeDefinition(name = "Timeout (seconds)",
                description = "HTTP request timeout.")
        int timeoutSeconds() default 30;

        @AttributeDefinition(name = "Max body characters",
                description = "Page body is truncated to this length before being sent to the model.")
        int maxBodyChars() default 8000;
    }

    // ---- Constants --------------------------------------------------------

    private static final String PROVIDER_ID = "openai";
    private static final String PROVIDER_LABEL = "OpenAI";

    private static final String SYSTEM_PROMPT =
            "You are a Schema.org / JSON-LD generator for SEO and AEO (answer engine "
            + "optimization). Produce a single valid JSON-LD object (not an array) "
            + "with '@context' set to 'https://schema.org'. Use the requested @type. "
            + "Only include properties supported by schema.org for that type. "
            + "Be concise and factual; do not invent data not present in the input. "
            + "Return ONLY the JSON object - no prose, no markdown fences.";

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiSchemaProvider.class);

    // ---- State ------------------------------------------------------------

    private Config config;

    // ---- Lifecycle --------------------------------------------------------

    @Activate
    @Modified
    protected void activate(final Config cfg) {
        this.config = cfg;
        LOG.info("OpenAiSchemaProvider activated (model={}, available={})", cfg.model(), isAvailable());
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
                    "OpenAI provider is not configured (missing API key).", PROVIDER_ID);
        }
        try {
            final String userPrompt = buildUserPrompt(request);
            final String payload = buildChatPayload(userPrompt);
            final String raw = callOpenAi(payload);
            final String jsonLd = extractJsonLd(raw);
            if (StringUtils.isBlank(jsonLd)) {
                return GenerationResult.error("OpenAI returned an empty JSON-LD payload.", PROVIDER_ID);
            }
            return GenerationResult.ok(jsonLd, PROVIDER_ID);
        } catch (final IOException ioe) {
            LOG.warn("OpenAI call failed for page {}: {}", request.getPagePath(), ioe.toString());
            return GenerationResult.error("OpenAI call failed: " + ioe.getMessage(), PROVIDER_ID);
        } catch (final RuntimeException re) {
            LOG.warn("OpenAI generation error for page {}: {}", request.getPagePath(), re.toString());
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

    private String buildChatPayload(final String userPrompt) {
        final JsonArrayBuilder messages = Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("role", "system").add("content", SYSTEM_PROMPT))
                .add(Json.createObjectBuilder().add("role", "user").add("content", userPrompt));
        return Json.createObjectBuilder()
                .add("model", config.model())
                .add("temperature", 0.2)
                .add("messages", messages)
                .add("response_format", Json.createObjectBuilder().add("type", "json_object"))
                .build().toString();
    }

    private String callOpenAi(final String payload) throws IOException {
        final URI uri = URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions");
        final HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
        final int timeoutMs = (int) Duration.ofSeconds(config.timeoutSeconds()).toMillis();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            con.setRequestProperty("Authorization", "Bearer " + config.apiKey());
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

    private String extractJsonLd(final String rawResponse) {
        if (StringUtils.isBlank(rawResponse)) { return ""; }
        try (JsonReader reader = Json.createReader(new StringReader(rawResponse))) {
            final JsonObject root = reader.readObject();
            final JsonValue choices = root.get("choices");
            if (choices == null || choices.getValueType() != JsonValue.ValueType.ARRAY) { return ""; }
            final JsonObject message = root.getJsonArray("choices").getJsonObject(0).getJsonObject("message");
            return prettyPrint(message.getString("content", ""));
        } catch (final RuntimeException re) {
            LOG.debug("Failed to parse OpenAI response: {}", re.toString());
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

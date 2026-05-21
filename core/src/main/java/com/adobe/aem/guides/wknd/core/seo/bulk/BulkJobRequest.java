/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo.bulk;

/**
 * Shared constants for the bulk schema.org enrichment Sling Job.
 * Both {@code BulkSchemaServlet} (producer) and
 * {@code BulkSchemaJobConsumer} (consumer) reference these keys.
 */
public final class BulkJobRequest {

    /** Sling Job topic. */
    public static final String JOB_TOPIC = "wknd/seo/bulk";

    // ---- Job property keys ----

    /** Absolute JCR path of the subtree to process (e.g. {@code /content/wknd/en}). */
    public static final String PROP_ROOT_PATH   = "wknd.seo.rootPath";

    /**
     * Enrichment action: {@code "ai"} to call the AI provider per page,
     * or {@code "defaults"} to enable schema with template-policy defaults only.
     */
    public static final String PROP_ACTION      = "wknd.seo.action";

    /**
     * Schema.org {@code @type} to generate (for AI action).
     * Use {@code "auto"} to derive the type from the existing page setting,
     * falling back to {@code "WebPage"}.
     */
    public static final String PROP_SCHEMA_TYPE = "wknd.seo.schemaType";

    /** AI provider id (e.g. {@code "openai"}); empty string means "use default". */
    public static final String PROP_PROVIDER_ID = "wknd.seo.providerId";

    /**
     * When {@code true}, pages that already have {@code seo/enabled=true} are
     * skipped.
     */
    public static final String PROP_SKIP_EXIST  = "wknd.seo.skipExisting";

    /** AEM user id of the author who submitted the job (for audit). */
    public static final String PROP_INITIATOR   = "wknd.seo.initiatorId";

    /**
     * Pre-generated UUID used as the progress-node name under
     * {@code /var/wknd/seo/jobs/{jobId}} and as the polling key returned to
     * the browser.
     */
    public static final String PROP_JOB_ID      = "wknd.seo.jobId";

    // ---- Action values ----

    /** Action: generate JSON-LD via AI provider. */
    public static final String ACTION_AI       = "ai";

    /** Action: enable schema with template defaults (no AI call). */
    public static final String ACTION_DEFAULTS = "defaults";

    // ---- Schema-type sentinel ----

    /**
     * Sentinel value for {@link #PROP_SCHEMA_TYPE}: derive the type from the
     * existing page {@code seo/type} property, or fall back to {@code "WebPage"}.
     */
    public static final String SCHEMA_TYPE_AUTO = "auto";

    // ---- Progress node property names (written by consumer, read by servlet) ----

    public static final String PN_STATUS           = "status";
    public static final String PN_STARTED_AT       = "startedAt";
    public static final String PN_FINISHED_AT      = "finishedAt";
    public static final String PN_TOTAL_PAGES      = "totalPages";
    public static final String PN_PROCESSED_PAGES  = "processedPages";
    public static final String PN_SUCCESS_PAGES    = "successPages";
    public static final String PN_FAILED_PAGES     = "failedPages";
    public static final String PN_ERRORS           = "errors";

    // ---- Status values ----

    public static final String STATUS_QUEUED    = "QUEUED";
    public static final String STATUS_RUNNING   = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED    = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Path prefix for progress nodes. */
    public static final String JOBS_ROOT = "/var/wknd/seo/jobs";

    private BulkJobRequest() {}
}

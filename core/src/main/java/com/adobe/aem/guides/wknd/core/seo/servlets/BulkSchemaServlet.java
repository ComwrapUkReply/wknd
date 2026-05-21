/*
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.aem.guides.wknd.core.seo.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.aem.guides.wknd.core.seo.bulk.BulkJobRequest;

/**
 * REST API for the Bulk Schema Enrichment tool.
 *
 * POST /bin/wknd/seo/bulk.json   — submit a new job
 * GET  /bin/wknd/seo/bulk.json?jobId=&lt;id&gt;  — poll progress
 * GET  /bin/wknd/seo/bulk.json?action=list  — recent jobs
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/wknd/seo/bulk",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST",
                "sling.servlet.extensions=json"
        })
public class BulkSchemaServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(BulkSchemaServlet.class);

    private static final String SUBSERVICE     = "wknd-seo-bulk";
    private static final int    MAX_LIST_JOBS  = 20;
    private static final String POLL_URL_TPL   = "/bin/wknd/seo/bulk.json?jobId=";

    @Reference private JobManager            jobManager;
    @Reference private ResourceResolverFactory resolverFactory;

    // ---- POST — submit job ------------------------------------------------

    @Override
    protected void doPost(final SlingHttpServletRequest request,
                          final SlingHttpServletResponse response) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        final String rootPath = StringUtils.trimToNull(request.getParameter("rootPath"));
        if (rootPath == null) {
            writeError(response, 400, "rootPath is required");
            return;
        }

        final String action     = StringUtils.defaultIfBlank(request.getParameter("action"), BulkJobRequest.ACTION_DEFAULTS);
        final String schemaType = StringUtils.defaultIfBlank(request.getParameter("schemaType"), BulkJobRequest.SCHEMA_TYPE_AUTO);
        final String providerId = StringUtils.trimToEmpty(request.getParameter("providerId"));
        final boolean skipExist = "true".equalsIgnoreCase(request.getParameter("skipExisting"));
        final String initiator  = request.getResourceResolver().getUserID();
        final String jobId      = UUID.randomUUID().toString();

        // Create the progress node first so it exists when the browser polls
        try {
            createInitialProgressNode(jobId);
        } catch (final Exception e) {
            LOG.error("Could not create progress node for job {}", jobId, e);
            writeError(response, 500, "Could not initialise progress tracking: " + e.getMessage());
            return;
        }

        final Map<String, Object> props = new HashMap<>();
        props.put(BulkJobRequest.PROP_JOB_ID,      jobId);
        props.put(BulkJobRequest.PROP_ROOT_PATH,   rootPath);
        props.put(BulkJobRequest.PROP_ACTION,      action);
        props.put(BulkJobRequest.PROP_SCHEMA_TYPE, schemaType);
        props.put(BulkJobRequest.PROP_PROVIDER_ID, providerId);
        props.put(BulkJobRequest.PROP_SKIP_EXIST,  skipExist);
        props.put(BulkJobRequest.PROP_INITIATOR,   initiator);

        jobManager.addJob(BulkJobRequest.JOB_TOPIC, props);

        final JsonObject body = Json.createObjectBuilder()
                .add("success",  true)
                .add("jobId",    jobId)
                .add("pollUrl",  POLL_URL_TPL + jobId)
                .build();

        try (PrintWriter w = response.getWriter()) {
            w.write(body.toString());
        }
    }

    // ---- GET — poll or list -----------------------------------------------

    @Override
    protected void doGet(final SlingHttpServletRequest request,
                         final SlingHttpServletResponse response) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        final String jobId  = StringUtils.trimToNull(request.getParameter("jobId"));
        final String action = StringUtils.trimToNull(request.getParameter("action"));

        if (jobId != null) {
            handlePoll(jobId, response);
        } else if ("list".equals(action)) {
            handleList(response);
        } else {
            writeError(response, 400, "Provide jobId= or action=list");
        }
    }

    // ---- Handlers ---------------------------------------------------------

    private void handlePoll(final String jobId,
                            final SlingHttpServletResponse response) throws IOException {
        try (ResourceResolver resolver = openServiceResolver()) {
            final Resource node = resolver.getResource(BulkJobRequest.JOBS_ROOT + "/" + jobId);
            if (node == null) {
                writeError(response, 404, "Job not found: " + jobId);
                return;
            }
            try (PrintWriter w = response.getWriter()) {
                w.write(progressToJson(jobId, node.getValueMap()).toString());
            }
        } catch (final LoginException e) {
            LOG.error("Service resolver unavailable for poll", e);
            writeError(response, 500, "Service resolver error");
        }
    }

    private void handleList(final SlingHttpServletResponse response) throws IOException {
        try (ResourceResolver resolver = openServiceResolver()) {
            final Resource root = resolver.getResource(BulkJobRequest.JOBS_ROOT);
            if (root == null) {
                try (PrintWriter w = response.getWriter()) {
                    w.write(Json.createObjectBuilder().add("jobs", Json.createArrayBuilder()).build().toString());
                }
                return;
            }

            final List<Resource> jobs = new ArrayList<>();
            for (final Resource child : root.getChildren()) { jobs.add(child); }

            jobs.sort(Comparator.comparingLong(
                    r -> -r.getValueMap().get(BulkJobRequest.PN_STARTED_AT, 0L)));

            final JsonArrayBuilder arr = Json.createArrayBuilder();
            jobs.stream()
                .limit(MAX_LIST_JOBS)
                .forEach(r -> arr.add(progressToJson(r.getName(), r.getValueMap())));

            final JsonObject body = Json.createObjectBuilder().add("jobs", arr).build();
            try (PrintWriter w = response.getWriter()) { w.write(body.toString()); }

        } catch (final LoginException e) {
            LOG.error("Service resolver unavailable for list", e);
            writeError(response, 500, "Service resolver error");
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private void createInitialProgressNode(final String jobId)
            throws LoginException, RepositoryException {
        try (ResourceResolver resolver = openServiceResolver()) {
            final String path = BulkJobRequest.JOBS_ROOT + "/" + jobId;
            if (resolver.getResource(path) != null) return;

            final Resource parent = resolver.getResource(BulkJobRequest.JOBS_ROOT);
            if (parent == null) {
                throw new RepositoryException("Jobs root missing: " + BulkJobRequest.JOBS_ROOT);
            }
            final Node parentNode = parent.adaptTo(Node.class);
            final Node jobNode    = parentNode.addNode(jobId, "nt:unstructured");
            jobNode.setProperty(BulkJobRequest.PN_STATUS,     BulkJobRequest.STATUS_QUEUED);
            jobNode.setProperty(BulkJobRequest.PN_STARTED_AT, System.currentTimeMillis());
            resolver.adaptTo(Session.class).save();
        }
    }

    private ResourceResolver openServiceResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    private static JsonObject progressToJson(final String jobId, final ValueMap vm) {
        final JsonObjectBuilder b = Json.createObjectBuilder()
                .add("jobId",          jobId)
                .add("status",         vm.get(BulkJobRequest.PN_STATUS,          "UNKNOWN"))
                .add("startedAt",      vm.get(BulkJobRequest.PN_STARTED_AT,      0L))
                .add("finishedAt",     vm.get(BulkJobRequest.PN_FINISHED_AT,     0L))
                .add("processedPages", vm.get(BulkJobRequest.PN_PROCESSED_PAGES, 0L))
                .add("successPages",   vm.get(BulkJobRequest.PN_SUCCESS_PAGES,   0L))
                .add("failedPages",    vm.get(BulkJobRequest.PN_FAILED_PAGES,    0L));

        final String[] errors = vm.get(BulkJobRequest.PN_ERRORS, String[].class);
        final JsonArrayBuilder errArr = Json.createArrayBuilder();
        if (errors != null) { Arrays.stream(errors).forEach(errArr::add); }
        b.add("errors", errArr);

        return b.build();
    }

    private static void writeError(final SlingHttpServletResponse response,
                                   final int status, final String message) throws IOException {
        response.setStatus(status);
        final JsonObject body = Json.createObjectBuilder()
                .add("success", false)
                .add("error",   message == null ? "" : message)
                .build();
        try (PrintWriter w = response.getWriter()) { w.write(body.toString()); }
    }
}

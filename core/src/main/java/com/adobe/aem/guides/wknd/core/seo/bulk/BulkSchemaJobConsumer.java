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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.adobe.aem.guides.wknd.core.seo.PageTextExtractor;
import com.adobe.aem.guides.wknd.core.seo.ai.GenerationRequest;
import com.adobe.aem.guides.wknd.core.seo.ai.GenerationResult;
import com.adobe.aem.guides.wknd.core.seo.ai.SchemaAiProvider;

/**
 * Sling Job consumer that applies Schema.org enrichment to a subtree of pages.
 * Triggered by {@code BulkSchemaServlet}; progress is written to
 * {@code /var/wknd/seo/jobs/{jobId}} and polled by the same servlet.
 */
@Component(
        service = JobConsumer.class,
        property = { JobConsumer.PROPERTY_TOPICS + "=" + BulkJobRequest.JOB_TOPIC }
)
@Designate(ocd = BulkSchemaJobConsumer.Config.class)
public class BulkSchemaJobConsumer implements JobConsumer {

    @ObjectClassDefinition(name = "WKND SEO — Bulk Schema Job Consumer")
    public @interface Config {
        @AttributeDefinition(name = "Max pages per run")
        int maxPagesPerRun() default 5000;

        @AttributeDefinition(name = "Max errors before abort")
        int maxErrorsBeforeAbort() default 100;

        @AttributeDefinition(name = "Save progress every N pages")
        int progressSaveIntervalPages() default 10;

        @AttributeDefinition(name = "Delay between AI requests (ms)")
        long aiRequestDelayMs() default 200L;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BulkSchemaJobConsumer.class);
    private static final String SUBSERVICE = "wknd-seo-bulk";
    private static final String SEO_NODE   = "seo";
    private static final int    MAX_ERRORS_KEPT = 50;

    private Config config;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private PageTextExtractor pageTextExtractor;

    private final List<SchemaAiProvider> providers = new CopyOnWriteArrayList<>();

    @Reference(service = SchemaAiProvider.class,
               cardinality = ReferenceCardinality.MULTIPLE,
               policy = ReferencePolicy.DYNAMIC)
    protected void bindProvider(final SchemaAiProvider p)   { if (p != null) providers.add(p); }
    protected void unbindProvider(final SchemaAiProvider p) { providers.remove(p); }

    @Activate
    void activate(final Config cfg) { this.config = cfg; }

    // ---- JobConsumer -------------------------------------------------------

    @Override
    public JobResult process(final Job job) {
        final String jobId      = job.getProperty(BulkJobRequest.PROP_JOB_ID,      String.class);
        final String rootPath   = job.getProperty(BulkJobRequest.PROP_ROOT_PATH,   String.class);
        final String action     = StringUtils.defaultIfBlank(
                                    job.getProperty(BulkJobRequest.PROP_ACTION, String.class),
                                    BulkJobRequest.ACTION_DEFAULTS);
        final String schemaType = StringUtils.defaultIfBlank(
                                    job.getProperty(BulkJobRequest.PROP_SCHEMA_TYPE, String.class),
                                    BulkJobRequest.SCHEMA_TYPE_AUTO);
        final String  providerId   = job.getProperty(BulkJobRequest.PROP_PROVIDER_ID, String.class);
        final boolean skipExisting = Boolean.TRUE.equals(
                                    job.getProperty(BulkJobRequest.PROP_SKIP_EXIST, Boolean.class));

        if (StringUtils.isAnyBlank(jobId, rootPath)) {
            LOG.error("Bulk SEO job missing required properties: jobId={} rootPath={}", jobId, rootPath);
            return JobResult.FAILED;
        }

        try (ResourceResolver resolver = openServiceResolver()) {
            final Session     session = resolver.adaptTo(Session.class);
            final PageManager pm      = resolver.adaptTo(PageManager.class);

            if (session == null || pm == null) {
                LOG.error("Cannot adapt resolver for bulk SEO job {}", jobId);
                return JobResult.FAILED;
            }

            final Resource progressRes = getOrCreateProgressNode(resolver, session, jobId);
            markRunning(session, progressRes);

            // Guard: AI action needs a provider
            final SchemaAiProvider provider = BulkJobRequest.ACTION_AI.equals(action)
                    ? selectProvider(providerId) : null;
            if (BulkJobRequest.ACTION_AI.equals(action) && provider == null) {
                finishProgress(session, progressRes, BulkJobRequest.STATUS_FAILED,
                        0, 0, 0, Collections.singletonList("No AI provider available."));
                return JobResult.FAILED;
            }

            final Page rootPage = pm.getPage(rootPath);
            if (rootPage == null) {
                finishProgress(session, progressRes, BulkJobRequest.STATUS_FAILED,
                        0, 0, 0,
                        Collections.singletonList("Root page not found: " + rootPath));
                return JobResult.FAILED;
            }

            // BFS traversal
            final Deque<Page> queue = new ArrayDeque<>();
            queue.add(rootPage);

            int processed = 0;
            int success   = 0;
            int failed    = 0;
            final List<String> errors = new ArrayList<>();

            while (!queue.isEmpty()
                    && processed < config.maxPagesPerRun()) {

                final Page page = queue.poll();

                // Enqueue children first (BFS order)
                final Iterator<Page> children = page.listChildren();
                while (children.hasNext()) { queue.add(children.next()); }

                // Process the page
                try {
                    if (shouldSkip(resolver, page, skipExisting)) {
                        continue;
                    }
                    applyAction(session, page, action, schemaType, provider);
                    success++;
                } catch (final Exception e) {
                    failed++;
                    final String msg = page.getPath() + ": " + e.getMessage();
                    if (errors.size() < MAX_ERRORS_KEPT) { errors.add(msg); }
                    LOG.warn("Bulk SEO error on {}: {}", page.getPath(), e.getMessage());
                    if (failed >= config.maxErrorsBeforeAbort()) {
                        LOG.error("Aborting bulk job {} after {} errors", jobId, failed);
                        break;
                    }
                }

                processed++;

                if (processed % config.progressSaveIntervalPages() == 0) {
                    saveProgress(session, progressRes, processed, success, failed, errors);
                }

                if (BulkJobRequest.ACTION_AI.equals(action) && config.aiRequestDelayMs() > 0) {
                    Thread.sleep(config.aiRequestDelayMs());
                }
            }

            final String finalStatus = BulkJobRequest.STATUS_COMPLETED;
            finishProgress(session, progressRes, finalStatus, processed, success, failed, errors);
            return JobResult.OK;

        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("Bulk SEO job {} interrupted", jobId);
            return JobResult.FAILED;
        } catch (final LoginException | RepositoryException e) {
            LOG.error("Bulk SEO job {} failed", jobId, e);
            return JobResult.FAILED;
        }
    }

    // ---- Page processing helpers ------------------------------------------

    private boolean shouldSkip(final ResourceResolver resolver,
                                final Page page,
                                final boolean skipExisting) {
        if (!skipExisting) return false;
        final Resource content = page.getContentResource();
        if (content == null) return false;
        final Resource seo = resolver.getResource(content.getPath() + "/" + SEO_NODE);
        return seo != null && Boolean.TRUE.equals(seo.getValueMap().get("enabled", Boolean.class));
    }

    private void applyAction(final Session session,
                             final Page page,
                             final String action,
                             final String schemaType,
                             final SchemaAiProvider provider)
            throws RepositoryException {

        final Resource content = page.getContentResource();
        if (content == null) return;

        final Node contentNode = content.adaptTo(Node.class);
        if (contentNode == null) return;

        final Node seoNode = getOrCreate(contentNode, SEO_NODE);
        seoNode.setProperty("enabled", true);
        if (!seoNode.hasProperty("mode")) {
            seoNode.setProperty("mode", "merge");
        }

        final String resolvedType = resolveType(page, schemaType);

        if (BulkJobRequest.ACTION_AI.equals(action)) {
            final GenerationRequest req = GenerationRequest.builder()
                    .pagePath(page.getPath())
                    .schemaType(resolvedType)
                    .title(page.getTitle())
                    .description(page.getDescription())
                    .bodyText(pageTextExtractor.extractBodyText(page, 12_000))
                    .locale(page.getLanguage(false) != null
                            ? page.getLanguage(false).toString() : null)
                    .build();

            final GenerationResult result = provider.generate(req);
            if (result.isSuccess() && StringUtils.isNotBlank(result.getJsonLd())) {
                seoNode.setProperty("jsonLd", result.getJsonLd());
                seoNode.setProperty("type",   resolvedType);
            } else {
                throw new IllegalStateException(
                        "AI generation failed: " + result.getErrorMessage());
            }
        } else {
            if (!seoNode.hasProperty("type")) {
                seoNode.setProperty("type", resolvedType);
            }
        }
    }

    private String resolveType(final Page page, final String schemaType) {
        if (!BulkJobRequest.SCHEMA_TYPE_AUTO.equals(schemaType)) {
            return schemaType;
        }
        final Resource content = page.getContentResource();
        if (content != null) {
            final Resource seo = content.getChild(SEO_NODE);
            if (seo != null) {
                final String existing = seo.getValueMap().get("type", String.class);
                if (StringUtils.isNotBlank(existing)) return existing;
            }
        }
        return "WebPage";
    }

    private SchemaAiProvider selectProvider(final String requestedId) {
        if (StringUtils.isNotBlank(requestedId)) {
            for (final SchemaAiProvider p : providers) {
                if (requestedId.equalsIgnoreCase(p.getId()) && p.isAvailable()) return p;
            }
        }
        for (final SchemaAiProvider p : providers) {
            if (p.isAvailable()) return p;
        }
        return null;
    }

    // ---- JCR helpers -------------------------------------------------------

    private ResourceResolver openServiceResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    private Resource getOrCreateProgressNode(final ResourceResolver resolver,
                                             final Session session,
                                             final String jobId)
            throws RepositoryException {
        final String path = BulkJobRequest.JOBS_ROOT + "/" + jobId;
        Resource res = resolver.getResource(path);
        if (res == null) {
            final Resource parent = resolver.getResource(BulkJobRequest.JOBS_ROOT);
            if (parent == null) {
                throw new RepositoryException("Jobs root missing: " + BulkJobRequest.JOBS_ROOT);
            }
            parent.adaptTo(Node.class).addNode(jobId, "nt:unstructured");
            session.save();
            res = resolver.getResource(path);
        }
        return res;
    }

    private static Node getOrCreate(final Node parent, final String name)
            throws RepositoryException {
        return parent.hasNode(name)
                ? parent.getNode(name)
                : parent.addNode(name, "nt:unstructured");
    }

    private void markRunning(final Session session, final Resource progressRes)
            throws RepositoryException {
        final Node node = progressRes.adaptTo(Node.class);
        node.setProperty(BulkJobRequest.PN_STATUS, BulkJobRequest.STATUS_RUNNING);
        node.setProperty(BulkJobRequest.PN_STARTED_AT, System.currentTimeMillis());
        node.setProperty(BulkJobRequest.PN_PROCESSED_PAGES, 0L);
        node.setProperty(BulkJobRequest.PN_SUCCESS_PAGES,   0L);
        node.setProperty(BulkJobRequest.PN_FAILED_PAGES,    0L);
        session.save();
    }

    private void saveProgress(final Session session, final Resource progressRes,
                              final int processed, final int success, final int failed,
                              final List<String> errors)
            throws RepositoryException {
        final Node node = progressRes.adaptTo(Node.class);
        node.setProperty(BulkJobRequest.PN_PROCESSED_PAGES, (long) processed);
        node.setProperty(BulkJobRequest.PN_SUCCESS_PAGES,   (long) success);
        node.setProperty(BulkJobRequest.PN_FAILED_PAGES,    (long) failed);
        if (!errors.isEmpty()) {
            node.setProperty(BulkJobRequest.PN_ERRORS, errors.toArray(new String[0]));
        }
        session.save();
    }

    private void finishProgress(final Session session, final Resource progressRes,
                                final String status,
                                final int processed, final int success, final int failed,
                                final List<String> errors)
            throws RepositoryException {
        final Node node = progressRes.adaptTo(Node.class);
        node.setProperty(BulkJobRequest.PN_STATUS,          status);
        node.setProperty(BulkJobRequest.PN_FINISHED_AT,     System.currentTimeMillis());
        node.setProperty(BulkJobRequest.PN_PROCESSED_PAGES, (long) processed);
        node.setProperty(BulkJobRequest.PN_SUCCESS_PAGES,   (long) success);
        node.setProperty(BulkJobRequest.PN_FAILED_PAGES,    (long) failed);
        if (!errors.isEmpty()) {
            node.setProperty(BulkJobRequest.PN_ERRORS, errors.toArray(new String[0]));
        }
        session.save();
    }
}

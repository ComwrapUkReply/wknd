/**
 * WKND Bulk Schema Enrichment — tool page controller.
 *
 * Wires up:
 *   - Form submit → POST /bin/wknd/seo/bulk.json (with CSRF)
 *   - Polling loop → GET /bin/wknd/seo/bulk.json?jobId=...
 *   - Progress UI  → renders status, progress bar, errors into .wknd-bulk-progress
 *
 * Depends on Coral UI being available (loaded by the Granite UI page shell).
 * Uses Granite.$ (jQuery) if present, falls back to document.querySelector.
 */
(function bulkManager(window, document) {
    'use strict';

    /* ------------------------------------------------------------------ */
    /* Config                                                              */
    /* ------------------------------------------------------------------ */

    const CONFIG = Object.freeze({
        pollIntervalMs: 3000,
        terminalStatuses: ['COMPLETED', 'FAILED', 'CANCELLED'],
        endpoints: {
            csrf:   '/libs/granite/csrf/token.json',
            submit: '/bin/wknd/seo/bulk.json',
            poll:   '/bin/wknd/seo/bulk.json?jobId=',
        },
        selectors: {
            form:     '.wknd-bulk-form',
            submit:   '.wknd-bulk-submit',
            progress: '.wknd-bulk-progress',
        },
    });

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    function $(selector, root) {
        return (root || document).querySelector(selector);
    }

    function notify(message, variant) {
        const g = window.Granite;
        if (g && g.UI && g.UI.Foundation && g.UI.Foundation.Utils
                && g.UI.Foundation.Utils.notifyUser) {
            g.UI.Foundation.Utils.notifyUser(variant || 'info', message);
            return;
        }
        /* eslint-disable no-console */
        window.console.log('[wknd-bulk]', variant || 'info', message);
        /* eslint-enable no-console */
    }

    function readField(form, name) {
        const el = form.querySelector('[name="' + name + '"]');
        if (!el) return '';
        // Granite UI field API (coral-select, coral-pathfield, etc.)
        if (el.adaptTo) {
            const api = el.adaptTo('foundation-field');
            if (api && typeof api.getValue === 'function') {
                return (api.getValue() || '').toString();
            }
        }
        // Standard checkbox
        if (el.type === 'checkbox') return el.checked ? el.value : '';
        return (el.value || '').toString();
    }

    async function fetchCsrfToken() {
        try {
            const r = await fetch(CONFIG.endpoints.csrf, { credentials: 'same-origin' });
            return r.ok ? (await r.json()).token || '' : '';
        } catch (e) {
            return '';
        }
    }

    /* ------------------------------------------------------------------ */
    /* Progress UI rendering                                               */
    /* ------------------------------------------------------------------ */

    const STATUS_LABELS = {
        QUEUED:    'Queued',
        RUNNING:   'Running',
        COMPLETED: 'Completed',
        FAILED:    'Failed',
        CANCELLED: 'Cancelled',
    };

    const STATUS_VARIANTS = {
        QUEUED:    'info',
        RUNNING:   'info',
        COMPLETED: 'success',
        FAILED:    'error',
        CANCELLED: 'warning',
    };

    function renderProgress(panel, data) {
        const status    = data.status    || 'UNKNOWN';
        const processed = data.processedPages || 0;
        const success   = data.successPages   || 0;
        const failed    = data.failedPages    || 0;
        const errors    = data.errors         || [];
        const isTerminal = CONFIG.terminalStatuses.indexOf(status) !== -1;
        const variant   = STATUS_VARIANTS[status] || 'info';

        // Build elapsed / duration label
        let durationHtml = '';
        if (data.startedAt) {
            const end  = data.finishedAt || Date.now();
            const secs = Math.round((end - data.startedAt) / 1000);
            durationHtml = '<span style="margin-left:12px;color:var(--coral-gray-600)">'
                + (isTerminal ? 'Duration: ' : 'Elapsed: ') + secs + 's</span>';
        }

        // Progress bar percentage (show 100 when terminal)
        const pct = (isTerminal && status === 'COMPLETED')
            ? 100
            : (processed > 0 ? Math.min(99, processed) : 0);

        let errorsHtml = '';
        if (errors.length) {
            const rows = errors.map(function(e) {
                return '<li style="font-size:12px;color:var(--coral-red-600)">' +
                    escapeHtml(e) + '</li>';
            }).join('');
            errorsHtml = '<details style="margin-top:12px">'
                + '<summary style="cursor:pointer;font-size:13px">Errors (' + errors.length + ')</summary>'
                + '<ul style="margin:4px 0 0 16px;padding:0">' + rows + '</ul>'
                + '</details>';
        }

        panel.innerHTML =
            '<div style="padding:16px 0">'
            + '<coral-tag variant="' + variant + '" style="font-size:14px">'
            +   (STATUS_LABELS[status] || status)
            + '</coral-tag>'
            + durationHtml
            + '</div>'
            + '<coral-progress value="' + pct + '" style="width:100%;margin-bottom:8px"></coral-progress>'
            + '<p style="margin:0;font-size:13px">'
            +   'Processed: <strong>' + processed + '</strong> &nbsp;|&nbsp; '
            +   'Success: <strong style="color:var(--coral-green-700)">' + success + '</strong> &nbsp;|&nbsp; '
            +   'Failed: <strong style="color:var(--coral-red-600)">' + failed + '</strong>'
            + '</p>'
            + errorsHtml;
    }

    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /* ------------------------------------------------------------------ */
    /* Polling                                                             */
    /* ------------------------------------------------------------------ */

    function startPolling(jobId, submitBtn, panel) {
        let timerId = null;

        async function poll() {
            try {
                const r = await fetch(CONFIG.endpoints.poll + jobId, {
                    credentials: 'same-origin',
                    headers: { Accept: 'application/json' },
                });
                if (!r.ok) {
                    notify('Polling failed (HTTP ' + r.status + ')', 'error');
                    clearInterval(timerId);
                    submitBtn.disabled = false;
                    return;
                }
                const data = await r.json();
                renderProgress(panel, data);

                if (CONFIG.terminalStatuses.indexOf(data.status) !== -1) {
                    clearInterval(timerId);
                    submitBtn.disabled = false;
                    const msg = data.status === 'COMPLETED'
                        ? 'Bulk enrichment completed: ' + (data.successPages || 0) + ' pages updated.'
                        : 'Job ' + data.status.toLowerCase() + '.';
                    notify(msg, data.status === 'COMPLETED' ? 'success' : 'warning');
                }
            } catch (e) {
                clearInterval(timerId);
                submitBtn.disabled = false;
                notify('Polling error: ' + e.message, 'error');
            }
        }

        timerId = setInterval(poll, CONFIG.pollIntervalMs);
        poll(); // immediate first tick
        window.addEventListener('beforeunload', function() { clearInterval(timerId); });
    }

    /* ------------------------------------------------------------------ */
    /* Submit handler                                                      */
    /* ------------------------------------------------------------------ */

    async function onSubmit(e) {
        e.preventDefault();

        const form      = $(CONFIG.selectors.form);
        const submitBtn = $(CONFIG.selectors.submit);
        const panel     = $(CONFIG.selectors.progress);

        if (!form || !submitBtn || !panel) return;

        const rootPath = readField(form, 'rootPath');
        if (!rootPath) {
            notify('Root Path is required.', 'error');
            return;
        }

        submitBtn.disabled = true;
        panel.style.display = '';
        panel.innerHTML = '<p style="padding:16px 0;color:var(--coral-gray-600)">Submitting…</p>';

        try {
            const csrfToken = await fetchCsrfToken();

            const body = new URLSearchParams();
            body.set('rootPath',     rootPath);
            body.set('action',       readField(form, 'action'));
            body.set('schemaType',   readField(form, 'schemaType'));
            body.set('skipExisting', readField(form, 'skipExisting'));

            const response = await fetch(CONFIG.endpoints.submit, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                    Accept:        'application/json',
                    'CSRF-Token':  csrfToken,
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
                body: body.toString(),
            });

            const payload = await response.json().catch(function() { return {}; });

            if (!response.ok || !payload.success) {
                submitBtn.disabled = false;
                panel.style.display = 'none';
                notify('Could not start job: ' + (payload.error || 'HTTP ' + response.status), 'error');
                return;
            }

            notify('Job queued (id: ' + payload.jobId + '). Polling for progress…', 'info');
            startPolling(payload.jobId, submitBtn, panel);

        } catch (err) {
            submitBtn.disabled = false;
            panel.style.display = 'none';
            notify('Request error: ' + (err && err.message ? err.message : err), 'error');
        }
    }

    /* ------------------------------------------------------------------ */
    /* Bootstrap                                                           */
    /* ------------------------------------------------------------------ */

    function init() {
        const form = $(CONFIG.selectors.form);
        if (!form) return;

        // Intercept the coral-form submit (fired when coral-button[type=submit] is clicked)
        form.addEventListener('submit', onSubmit);

        // Also catch direct button click in case coral-form submit does not fire
        const btn = $(CONFIG.selectors.submit);
        if (btn) {
            btn.addEventListener('click', function(e) {
                e.preventDefault();
                onSubmit(e);
            });
        }
    }

    // Granite UI dispatches 'foundation-contentloaded' after dynamic content;
    // fall back to DOMContentLoaded for direct page load.
    document.addEventListener('foundation-contentloaded', init);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

}(window, document));

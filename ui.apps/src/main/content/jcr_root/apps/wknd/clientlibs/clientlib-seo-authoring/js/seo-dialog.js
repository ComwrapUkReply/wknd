/**
 * WKND SEO / Schema.org dialog - "Generate with AI" wiring.
 *
 * Scopes:
 *   - Page Properties dialog on /apps/wknd/components/page/cq:dialog
 *   - Template Policy dialog on /apps/wknd/components/page/cq:design_dialog
 *
 * Contract with Granite UI:
 *   - The dialog container carries class `wknd-seo-dialog`.
 *   - The button carries class `wknd-seo-generate`.
 *   - The JSON-LD textarea has class `wknd-seo-jsonld` and name ending
 *     with "/seo/jsonLd".
 *
 * Following the team style guide: airbnb conventions, const for non-reassigned
 * bindings, config constants grouped in an object, defensive helpers, comments
 * for intent rather than mechanics. Uses await (no .then chains).
 */
(function seoDialog(window, document, $) {
    'use strict';

    /* ------------------------------------------------------------------ */
    /* Config: selectors, classes, URLs                                   */
    /* ------------------------------------------------------------------ */

    const CONFIG = Object.freeze({
        endpoint: '/bin/wknd/seo/generate',
        selectors: {
            root:        '.wknd-seo-dialog',
            button:      '.wknd-seo-generate',
            jsonLd:      'textarea[name$="./seo/jsonLd"], textarea[name="./seo/jsonLd"]',
            schemaType:  '[name="./seo/type"]',
            authorPrompt:'[name="./seo/aiPrompt"]',
            provider:    '[name="./seo/aiProvider"]',
            typeGroup:   '[data-show-for-types]',
            seoHeadline:    '[name="./seo/headline"]',
            seoDescription: '[name="./seo/description"]',
            pageTitle:       '[name="./jcr:title"]',
            pageDescription: '[name="./jcr:description"]',
        },
        classes: {
            busy: 'is-busy',
        },
        events: {
            click: 'click.wkndSeoGenerate',
            ready: 'foundation-contentloaded',
        },
        qsPageParam: 'item',
    });

    /* ------------------------------------------------------------------ */
    /* Pure helpers                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Resolve the current page path for which the dialog is open.
     * Page Properties pages the JCR path through the URL as ?item=...;
     * the policy editor uses the same param. Falls back to window.Granite.
     */
    function resolvePagePath() {
        try {
            const params = new URLSearchParams(window.location.search);
            const fromQuery = params.get(CONFIG.qsPageParam);
            if (fromQuery) return fromQuery;
        } catch (e) {
            // URLSearchParams unavailable - ignore.
        }
        // Fallback for page editor context.
        if (window.Granite && window.Granite.author && window.Granite.author.page) {
            return window.Granite.author.page.path || '';
        }
        return '';
    }

    /** Read a field value from the dialog, safely returning '' when absent. */
    function readField($root, selector) {
        const $el = $root.find(selector).first();
        if (!$el.length) return '';
        const api = $el.adaptTo ? $el.adaptTo('foundation-field') : null;
        if (api && typeof api.getValue === 'function') {
            return (api.getValue() || '').toString();
        }
        return ($el.val() || '').toString();
    }

    /**
     * Show/hide every conditional field group based on the selected @type.
     * Each group declares the types it applies to via data-show-for-types
     * (a comma-separated list), so adding a new type requires no JS change.
     */
    function toggleTypeFields($root) {
        const selectedType = readField($root, CONFIG.selectors.schemaType);
        $root.find(CONFIG.selectors.typeGroup).each(function eachGroup() {
            const $group = $(this);
            const types = ($group.attr('data-show-for-types') || '')
                .split(',')
                .map(function trim(t) { return t.trim(); })
                .filter(function nonEmpty(t) { return t.length > 0; });
            const show = types.indexOf(selectedType) !== -1;
            if (show) {
                $group.show();
                $group.find(':input').prop('disabled', false);
            } else {
                $group.hide();
                $group.find(':input').prop('disabled', true);
            }
        });
    }

    /**
     * Pre-fill headline and description from the Basic tab (jcr:title / jcr:description)
     * when the Schema.org fields are blank. The Basic tab fields live outside $root,
     * so we search the whole document form.
     */
    function prefillFromPageBasic($root) {
        const $form = $root.closest('form').length ? $root.closest('form') : $(document);

        function getFieldValue(selector) {
            const $el = $form.find(selector).first();
            if (!$el.length) { return ''; }
            const api = $el.adaptTo ? $el.adaptTo('foundation-field') : null;
            if (api && typeof api.getValue === 'function') {
                return (api.getValue() || '').toString().trim();
            }
            return ($el.val() || '').toString().trim();
        }

        const currentHeadline    = getFieldValue(CONFIG.selectors.seoHeadline);
        const currentDescription = getFieldValue(CONFIG.selectors.seoDescription);

        if (!currentHeadline) {
            const title = getFieldValue(CONFIG.selectors.pageTitle);
            if (title) { writeField($root, CONFIG.selectors.seoHeadline, title); }
        }
        if (!currentDescription) {
            const desc = getFieldValue(CONFIG.selectors.pageDescription);
            if (desc) { writeField($root, CONFIG.selectors.seoDescription, desc); }
        }
    }

    /** Write a value to a field, preferring the foundation-field API. */
    function writeField($root, selector, value) {
        const $el = $root.find(selector).first();
        if (!$el.length) return;
        const api = $el.adaptTo ? $el.adaptTo('foundation-field') : null;
        if (api && typeof api.setValue === 'function') {
            api.setValue(value);
        } else {
            $el.val(value).trigger('change');
        }
    }

    /** Show a coral toast; falls back to alert on older AEM. */
    function notify(message, variant) {
        if (window.Granite && window.Granite.UI && window.Granite.UI.Foundation
                && window.Granite.UI.Foundation.Utils
                && window.Granite.UI.Foundation.Utils.notifyUser) {
            window.Granite.UI.Foundation.Utils.notifyUser(variant || 'info', message);
            return;
        }
        if (window.Coral && window.Coral.commons && window.Coral.commons.ready) {
            /* eslint-disable no-alert */
            window.alert(message);
            /* eslint-enable no-alert */
            return;
        }
        window.console.log('[wknd-seo]', message);
    }

    /* ------------------------------------------------------------------ */
    /* Side-effectful handler                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Call the server-side SchemaAiServlet and drop the result into the
     * JSON-LD textarea.
     *
     * @param {jQuery} $root dialog root element.
     * @param {jQuery} $button the clicked button (for busy toggling).
     */
    async function onGenerate($root, $button) {
        const pagePath = resolvePagePath();
        if (!pagePath) {
            notify('Could not determine the current page path.', 'error');
            return;
        }

        const formData = new FormData();
        formData.append('pagePath', pagePath);
        formData.append('schemaType',   readField($root, CONFIG.selectors.schemaType));
        formData.append('providerId',   readField($root, CONFIG.selectors.provider));
        formData.append('authorPrompt', readField($root, CONFIG.selectors.authorPrompt));

        $button.addClass(CONFIG.classes.busy).prop('disabled', true);

        try {
            // Always fetch a fresh CSRF token from AEM's token endpoint.
            const tokenResp = await fetch('/libs/granite/csrf/token.json', { credentials: 'same-origin' });
            const csrfToken = tokenResp.ok ? (await tokenResp.json()).token || '' : '';

            const response = await fetch(CONFIG.endpoint, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { Accept: 'application/json', 'CSRF-Token': csrfToken },
                body: formData,
            });

            const payload = await response.json().catch(() => ({}));

            if (!response.ok || !payload || payload.success !== true) {
                const err = (payload && payload.error) || ('HTTP ' + response.status);
                notify('AI generation failed: ' + err, 'error');
                return;
            }

            writeField($root, CONFIG.selectors.jsonLd, payload.jsonLd || '');
            notify('JSON-LD generated (' + (payload.providerId || 'ai') + ').', 'success');
        } catch (err) {
            notify('Request error: ' + (err && err.message ? err.message : err), 'error');
        } finally {
            $button.removeClass(CONFIG.classes.busy).prop('disabled', false);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Bootstrap                                                           */
    /* ------------------------------------------------------------------ */

    /** Bind click handlers once the dialog DOM is ready. */
    function bind(context) {
        const $roots = $(CONFIG.selectors.root, context);
        $roots.each(function bindEach() {
            const $root = $(this);
            const $buttons = $root.find(CONFIG.selectors.button);
            const $type = $root.find(CONFIG.selectors.schemaType).first();

            // Initial state for conditional field groups.
            toggleTypeFields($root);

            // Pre-populate headline/description from Basic tab if currently blank.
            prefillFromPageBasic($root);

            $type.off('change.wkndSeoType').on('change.wkndSeoType', function onTypeChange() {
                toggleTypeFields($root);
            });

            $buttons.off(CONFIG.events.click).on(CONFIG.events.click, function onClick(e) {
                e.preventDefault();
                onGenerate($root, $(this));
            });
        });
    }

    $(document).on(CONFIG.events.ready, function onReady(e) {
        bind(e.target);
    });

    // If the script loads after the dialog, bind immediately.
    $(function onDomReady() {
        bind(document);
    });

}(window, document, window.Granite && window.Granite.$ ? window.Granite.$ : window.jQuery));

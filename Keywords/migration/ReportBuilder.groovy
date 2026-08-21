package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import groovy.json.JsonSlurper

/**
 * Builds the client-facing HTML content-parity report from the latest compare run.
 *
 * Layout is a drill-down tree: page group -> template -> page -> Content. "Template" is the
 * `pagetype` slug of the master mapping. Pages nobody compared yet are folded into one
 * collapsed list per template instead of one empty card each.
 *
 * Every page carries one result: PASS / FAIL / NOT_RUN, written by the check. It is strict
 * on purpose — any lost text, wrong tab, changed figure or redirected button fails the
 * page, however large the page is. Losing one button is losing content.
 *
 * The findings ARE the evidence and they are text, so nothing binary is referenced: the
 * publish bundle is the same site minus the local paths, a few hundred KB rather than tens
 * of MB, with no screenshots to downscale.
 *
 * Output is a small static site, not one long document: index.html holds the overview and
 * the tree, and every compared page gets its own file under pages/<slug>.html.
 *
 *   build()              -> Reports/parity-report/index.html  (evidence read in place from Reports/)
 *   buildPublishBundle() -> Reports/publish/index.html        (referenced evidence copied into
 *                                                              assets/, folder ready for a server)
 */
public class ReportBuilder {

	/**
	 * The checks this report renders. One, deliberately: this project compares content and
	 * nothing else. It stays a list — the tree, the filter bar and the mastersheet all iterate
	 * it — so adding a second check later is a matter of writing it, not of unpicking a
	 * hard-coded single check out of the renderer.
	 */
	static final List CHECK_ORDER = ['content', 'structure']
	static final List ALL_CHECKS = ['content', 'structure']

	static final List GROUP_ORDER = ['custom', 'normal']
	static final Map GROUP_TITLE = [custom: 'Custom pages', normal: 'Normal pages']

	static final Map CHECK_TITLE = [content: 'Content', structure: 'Structure & images']
	static final Map CHECK_DESC = [
		content: 'Sitecore is the live website. AEM is the new site. Each row is a mismatch, with a picture from each side.',
		structure: 'Counts headings, lists, links and pictures on Sitecore (the live website) versus AEM (the new site). Then each Sitecore picture is compared to the AEM picture in the same place. Same photo in a different size or zoom is a match. Wording is not judged here \u2014 that is the Content check.',
	]

	// ---------------------------------------------------------------- entry points

	/** Report that reads its evidence in place from Reports/ (for the test team) */
	@Keyword
	static String build() {
		return render(false)
	}

	/** Self-contained folder to push to a report server: Reports/publish/ */
	@Keyword
	static String buildPublishBundle() {
		return render(true)
	}

	// ---------------------------------------------------------------- data collection

	/**
	 * One entry per row of the master mapping, enriched with the check results found on
	 * disk. Rows without pagegroup/pagetype (legacy 3-column rows) default to "normal".
	 */
	private static List collectPages(String proj) {
		List pages = []
		File csv = new File(proj + '/Data Files/aem-url-mapping.csv')
		if (!csv.exists()) return pages
		csv.readLines('UTF-8').drop(1).each { line ->
			if (!line?.trim()) return
			def c = line.split(',')
			if (c.length < 3) return
			String aem = c[1].trim()
			String slug = AuditUtils.slugOf(aem)
			Map results = [:]
			ALL_CHECKS.each { chk ->
				Map res = readResult(proj, slug, chk)
				if (res != null) results[chk] = res
			}
			pages << [
				sc      : c[0].trim(),
				aem     : aem,
				tpl     : c.length > 2 ? c[2].trim() : '',
				group   : c.length > 3 && c[3].trim() ? c[3].trim() : 'normal',
				pagetype: c.length > 4 ? c[4].trim() : '',
				slug    : slug,
				results : results,
			]
		}
		return pages
	}

	private static Map readResult(String proj, String slug, String check) {
		File f = new File(proj + '/Reports/parity-results/' + slug + '/' + check + '.txt')
		if (!f.exists()) return null
		List lines = f.readLines('UTF-8')
		return [verdict: lines ? lines[0].trim() : 'NA', detail: lines.size() > 1 ? lines.drop(1).join('\n').trim() : '']
	}

	// ---------------------------------------------------------------- rendering

	/**
	 * Writes the report as a small site rather than one long document: index.html carries
	 * the overview and the tree, and every compared page gets its own file under pages/,
	 * so opening one URL's findings never means loading all the others' screenshots.
	 */
	private static String render(boolean publish) {
		String proj = RunConfiguration.getProjectDir()
		List pages = collectPages(proj)

		File root = new File(proj + (publish ? '/Reports/publish' : '/Reports/parity-report'))
		if (root.exists()) root.deleteDir()
		root.mkdirs()
		writeStaticAssets(root)
		File pagesDir = new File(root, 'pages')
		pagesDir.mkdirs()
		copyEvidencePics(proj, root, pages)
		Map ctx = [proj: proj, publish: publish]

		Map byGroup = [:]
		GROUP_ORDER.each { g -> byGroup[g] = pages.findAll { it.group == g } }
		List extraGroups = pages*.group.unique().findAll { !GROUP_ORDER.contains(it) }.sort()
		extraGroups.each { g -> byGroup[g] = pages.findAll { it.group == g } }
		List groups = GROUP_ORDER + extraGroups

		StringBuilder h = new StringBuilder()
		h << head('', '')
		h << renderCover(pages, groups, byGroup)
		h << renderFilterBar(groups, byGroup)
		h << renderTree(groups, byGroup)
		groups.each { g ->
			List gp = byGroup[g]
			if (!gp) return
			String gTitle = GROUP_TITLE[g] ?: (g.capitalize() + ' pages')
			List tpls = templatesOf(gp)
			h << "<section class=\"group\" data-block=\"group\" data-group=\"${escAttr(g)}\"><h2 id=\"${g}\">${esc(gTitle)}</h2>"
			h << "<p class=\"sub\">${tpls.size()} template(s), ${gp.size()} page(s).</p>"
			tpls.each { t -> h << renderTemplateIndex(g, t) }
			h << '</section>'
		}
		h << footer()
		h << '</main></body></html>'
		File index = new File(root, 'index.html')
		index.setText(h.toString(), 'UTF-8')

		// One ordered walk over the compared pages = the reading order of the index, and
		// therefore the order the previous/next links and the jump dropdown follow.
		List nav = []
		groups.each { g ->
			templatesOf(byGroup[g] ?: []).each { t ->
				t.pages.findAll { !it.results.isEmpty() }.each { p -> nav << [g: g, t: t, p: p] }
			}
		}
		nav.eachWithIndex { Map n, int i ->
			new File(pagesDir, n.p.slug + '.html').setText(renderPageFile(ctx, n, nav, i), 'UTF-8')
		}
		int written = nav.size()
		KeywordUtil.logInfo((publish ? 'Publish bundle -> ' : 'Parity report -> ') + index.getAbsolutePath()
				+ ' (' + written + ' page file(s))')
		return index.getAbsolutePath()
	}

	/** Title block, run facts, and the group x check status matrix */
	private static String renderCover(List pages, List groups, Map byGroup) {
		int compared = pages.count { !it.results.isEmpty() }
		int withFindings = pages.count { p -> p.results.any { k, v -> v.verdict == 'FAIL' } }
		StringBuilder h = new StringBuilder()
		h << '<header class="cover">'
		h << '<p class="eyebrow">Website migration &middot; quality assurance</p>'
		h << '<h1>Migration parity report</h1>'
		h << '<p class="lede">Each page on <strong>AEM</strong> (the new website) is compared with the same page on <strong>Sitecore</strong> (today&rsquo;s live website). Sitecore is the reference: whatever it says, AEM must also say. Extra text only on AEM is listed but never fails.</p>'
		h << '<dl class="facts">'
		h << "<div><dt>Generated</dt><dd>${new Date().format('yyyy-MM-dd HH:mm')}</dd></div>"
		h << "<div><dt>Pages in scope</dt><dd>${pages.size()}</dd></div>"
		h << "<div><dt>Compared in this run</dt><dd>${compared}</dd></div>"
		h << "<div><dt>Pages with findings</dt><dd class=\"${withFindings > 0 ? 'bad' : 'good'}\">${withFindings}</dd></div>"
		h << '</dl></header>'

		h << '<section class="overview"><h2 class="minor">At a glance</h2>'
		h << '<div class="scroll"><table class="matrix"><thead><tr><th>Template</th><th>Pages</th>'
		CHECK_ORDER.each { chk -> h << "<th>${esc(CHECK_TITLE[chk])}</th>" }
		h << '</tr></thead><tbody>'
		groups.each { g ->
			List gp = byGroup[g]
			if (!gp) return
			h << "<tr class=\"grouprow\"><th colspan=\"${CHECK_ORDER.size() + 2}\" scope=\"colgroup\">"
			h << "<a href=\"#${g}\">${esc(GROUP_TITLE[g] ?: (g.capitalize() + ' pages'))}</a></th></tr>"
			templatesOf(gp).each { t ->
				h << "<tr><th scope=\"row\"><a href=\"#${g}-${t.key}\">${esc(t.title)}</a></th>"
				h << "<td class=\"num\">${t.pages.size()}</td>"
				CHECK_ORDER.each { chk ->
					List withRes = t.pages.findAll { it.results[chk] != null }
					int failed = withRes.count { it.results[chk].verdict == 'FAIL' }
					String cell = withRes.isEmpty() ? '<span class="chip NA">not compared</span>'
							: failed > 0 ? "<a class=\"chip FAIL\" href=\"#${g}-${t.key}\">${failed} of ${withRes.size()} failed</a>"
							: "<span class=\"chip PASS\">${withRes.size()} passed</span>"
					h << "<td>${cell}</td>"
				}
				h << '</tr>'
			}
		}
		h << '</tbody></table></div></section>'
		return h.toString()
	}

	/**
	 * Pages of one group bucketed by template (the `pagetype` slug), each bucket labelled
	 * with the readable page-type name and the AEM template it reports.
	 */
	private static List templatesOf(List gp) {
		Map buckets = [:]
		gp.each { p ->
			String key = p.pagetype ?: 'unclassified'
			if (buckets[key] == null) buckets[key] = [key: key, title: humanize(key), tpl: p.tpl, pages: []]
			buckets[key].pages << p
		}
		buckets.values().each { b -> b.pages = b.pages.sort { it.aem } }
		return buckets.values().sort { it.title }
	}

	static final List ACRONYMS = ['ilp', 'ga4', 'ppc', 'pru', 'lbu', 'pva', 'http', 'gtm']

	/** "ilp-fund" -> "ILP Fund", "general-content-detail-page" -> "General Content Detail Page" */
	private static String humanize(String slug) {
		if (!slug) return 'Unclassified'
		return slug.split('[-_]').collect { w ->
			ACRONYMS.contains(w.toLowerCase()) ? w.toUpperCase() : (w ? w.substring(0, 1).toUpperCase() + w.substring(1) : w)
		}.join(' ')
	}

	/**
	 * Client-side filter bar. It stays hidden until report.js switches it on, so a reader
	 * without JavaScript gets the whole report rather than a dead control panel.
	 */
	private static String renderFilterBar(List groups, Map byGroup) {
		StringBuilder h = new StringBuilder()
		h << '<section class="filters js-only" id="filters" hidden><h2 class="minor">Filter</h2><div class="filterrow">'
		h << '<label>Page group<select id="f-group"><option value="">All page groups</option>'
		groups.each { g ->
			if (!byGroup[g]) return
			h << "<option value=\"${escAttr(g)}\">${esc(GROUP_TITLE[g] ?: (g.capitalize() + ' pages'))}</option>"
		}
		h << '</select></label>'
		h << '<label>Template<select id="f-template"><option value="">All templates</option>'
		groups.each { g ->
			templatesOf(byGroup[g] ?: []).each { t ->
				h << "<option value=\"${escAttr(t.key)}\" data-group=\"${escAttr(g)}\">${esc(t.title)}</option>"
			}
		}
		h << '</select></label>'
		h << '<label>Status<select id="f-status"><option value="">All pages</option>'
		h << '<option value="findings">With findings</option><option value="clean">All checks passed</option>'
		h << '<option value="pending">Not compared</option></select></label>'
		h << '<label>Search URL<input type="search" id="f-q" placeholder="/en/we-do/\u2026"></label>'
		h << '<button type="button" id="f-reset">Reset</button>'
		h << '<button type="button" id="f-expand">Expand all</button>'
		h << '<button type="button" id="f-collapse">Collapse all</button>'
		h << '</div><p class="sub" id="f-count"></p></section>'
		return h.toString()
	}

	/**
	 * The filter state of one page, carried on the element itself so report.js never has to
	 * re-derive a verdict from the rendered text.
	 */
	private static String rowAttrs(String group, String tplKey, Map p, String scope) {
		List failing = CHECK_ORDER.findAll { p.results[it]?.verdict == 'FAIL' }
		String status = p.results.isEmpty() ? 'pending' : (failing ? 'findings' : 'clean')
		return "data-row=\"page\" data-scope=\"${scope}\" data-group=\"${escAttr(group)}\"" +
			" data-template=\"${escAttr(tplKey)}\" data-status=\"${status}\"" +
			" data-fail=\"${escAttr(failing.join(' '))}\" data-url=\"${escAttr(shortPath(p.aem).toLowerCase())}\""
	}

	/** Navigation tree: Report -> page group -> template -> page, with each page's failing checks */
	private static String renderTree(List groups, Map byGroup) {
		StringBuilder h = new StringBuilder()
		h << '<nav class="tree" aria-label="Contents"><h2 class="minor">Contents</h2><ul>'
		groups.each { g ->
			List gp = byGroup[g]
			if (!gp) return
			String gTitle = GROUP_TITLE[g] ?: (g.capitalize() + ' pages')
			h << "<li class=\"branch\" data-block=\"tree-group\" data-group=\"${escAttr(g)}\">"
			h << "<details open><summary><a class=\"group-link\" href=\"#${g}\">${esc(gTitle)}</a></summary><ul>"
			templatesOf(gp).each { t ->
				int tplFailed = t.pages.count { p -> CHECK_ORDER.any { p.results[it]?.verdict == 'FAIL' } }
				h << "<li class=\"branch\" data-block=\"tree-template\" data-group=\"${escAttr(g)}\" data-template=\"${escAttr(t.key)}\">"
				h << "<details${tplFailed > 0 ? ' open' : ''}><summary><a href=\"#${g}-${t.key}\">${esc(t.title)}</a>"
				h << "<span class=\"chip ${tplFailed > 0 ? 'FAIL' : 'NA'}\">${tplFailed > 0 ? tplFailed + ' of ' + t.pages.size() + ' pages with findings' : t.pages.size() + ' page(s)'}</span></summary><ul>"
				t.pages.each { p ->
					List failing = CHECK_ORDER.findAll { p.results[it]?.verdict == 'FAIL' }
					String note = p.results.isEmpty() ? '<span class="chip NA">not compared</span>'
							: failing ? failing.collect { "<span class=\"chip FAIL\">${esc(CHECK_TITLE[it])}</span>" }.join('')
							: '<span class="chip PASS">all checks passed</span>'
					String label = p.results.isEmpty() ? "<span class=\"mono dim\">${esc(shortPath(p.aem))}</span>"
							: "<a class=\"mono\" href=\"${pageHref(p)}\">${esc(shortPath(p.aem))}</a>"
					h << "<li class=\"leaf\" ${rowAttrs(g, (String) t.key, p, 'tree')}>${label}${note}</li>"
				}
				h << '</ul></details></li>'
			}
			h << '</ul></details></li>'
		}
		h << '</ul></nav>'
		return h.toString()
	}

	/** Link from index.html to a page's own file */
	private static String pageHref(Map p) {
		return 'pages/' + p.slug + '.html'
	}

	/** On the index: one template block listing its pages and how each check ended */
	private static String renderTemplateIndex(String group, Map t) {
		StringBuilder h = new StringBuilder()
		h << "<div class=\"template\" data-block=\"template\" data-group=\"${escAttr(group)}\" data-template=\"${escAttr(t.key)}\">"
		h << "<div class=\"template-head\"><h3 id=\"${group}-${t.key}\">${esc(t.title)}</h3>"
		h << "<p class=\"sub\">${t.pages.size()} page(s)"
		if (t.tpl) h << " &middot; AEM template &ldquo;${esc(t.tpl)}&rdquo;"
		h << "</p></div>"
		List compared = t.pages.findAll { !it.results.isEmpty() }
		List pending = t.pages.findAll { it.results.isEmpty() }
		if (compared) {
			h << '<div class="scroll" data-block="table"><table class="matrix"><thead><tr><th>Page</th>'
			CHECK_ORDER.each { chk -> h << "<th>${esc(CHECK_TITLE[chk])}</th>" }
			h << '<th></th></tr></thead><tbody>'
			compared.each { p ->
				h << "<tr ${rowAttrs(group, (String) t.key, p, 'index')}><th scope=\"row\"><a class=\"mono\" href=\"${pageHref(p)}\">${esc(shortPath(p.aem))}</a></th>"
				CHECK_ORDER.each { chk ->
					String v = p.results[chk] ? p.results[chk].verdict : 'NA'
					h << "<td><span class=\"chip ${v}\">${v == 'NA' ? 'not run' : v.toLowerCase()}</span></td>"
				}
				h << "<td><a href=\"${pageHref(p)}\">open &rsaquo;</a></td></tr>"
			}
			h << '</tbody></table></div>'
		}
		// pages nobody has compared yet get no file of their own, only a note here
		if (pending) {
			h << "<details class=\"pending\" data-block=\"pending\"><summary>${pending.size()} page(s) not compared in this run — out of the current batch or not published yet</summary><ul class=\"plain\">"
			pending.each { p -> h << "<li ${rowAttrs(group, (String) t.key, p, 'index')}><a class=\"mono\" href=\"${esc(p.aem)}\">${esc(shortPath(p.aem))}</a></li>" }
			h << '</ul></details>'
		}
		h << '</div>'
		return h.toString()
	}

	/**
	 * One page = one file: a sticky bar (breadcrumb + previous/next + jump dropdown), the page
	 * header, a switcher for the four checks, then a card per check.
	 *
	 * `nav` is every compared page in index order and `i` this page's place in it, so a reviewer
	 * can walk the whole report without returning to the index between URLs.
	 */
	private static String renderPageFile(Map ctx, Map n, List nav, int i) {
		String group = (String) n.g
		Map t = (Map) n.t
		Map p = (Map) n.p
		Map prev = i > 0 ? (Map) nav[i - 1] : null
		Map next = i < nav.size() - 1 ? (Map) nav[i + 1] : null
		List failing = CHECK_ORDER.findAll { p.results[it]?.verdict == 'FAIL' }
		String gTitle = GROUP_TITLE[group] ?: (group.capitalize() + ' pages')
		StringBuilder h = new StringBuilder()
		h << head(shortPath(p.aem), '../')
		h << '<div class="topbar">'
		h << "<nav class=\"crumbs\"><a href=\"../index.html\">Migration parity report</a>"
		h << "<span>${esc(gTitle)}</span><a href=\"../index.html#${group}-${t.key}\">${esc(t.title)}</a></nav>"
		h << renderPageNav(nav, i, prev, next)
		h << '</div>'
		h << "<article class=\"page ${failing ? 'has-findings' : ''}\">"
		h << "<div class=\"page-head\"><h1 class=\"mono pagetitle\">${esc(shortPath(p.aem))}</h1>"
		h << "<p class=\"links\"><a href=\"${esc(p.sc)}\">Sitecore (live website)</a><a href=\"${esc(p.aem)}\">AEM</a></p>"
		h << '</div>'
		if (failing) {
			h << "<p class=\"page-status fail\">This page failed: ${failing.collect { CHECK_TITLE[it] }.join(' and ')}. "
			h << 'Open the card below — it lists what Sitecore has and what AEM is missing.</p>'
		} else {
			h << '<p class="page-status">All checks passed on this page.</p>'
		}
		h << renderCheckSwitch(p)
		CHECK_ORDER.each { chk -> h << renderCheckCard(ctx, chk, p) }
		h << '</article>'
		h << '<p class="sub pagefoot"><a href="../index.html">&lsaquo; Back to all pages</a>'
		if (next) h << "<a href=\"${escAttr(next.p.slug)}.html\">Next page &rsaquo;</a>"
		h << '</p>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/**
	 * Previous / next / "page n of m" / jump dropdown. The two anchors are the no-JavaScript
	 * path (and what the keyboard shortcuts drive); the dropdown needs report.js.
	 */
	private static String renderPageNav(List nav, int i, Map prev, Map next) {
		StringBuilder h = new StringBuilder()
		h << '<div class="pagenav">'
		h << (prev ? "<a class=\"navbtn\" id=\"nav-prev\" rel=\"prev\" href=\"${escAttr(prev.p.slug)}.html\">&lsaquo; Previous</a>"
				: '<span class="navbtn off">&lsaquo; Previous</span>')
		h << "<span class=\"navcount\">Page ${i + 1} of ${nav.size()}</span>"
		h << (next ? "<a class=\"navbtn\" id=\"nav-next\" rel=\"next\" href=\"${escAttr(next.p.slug)}.html\">Next &rsaquo;</a>"
				: '<span class="navbtn off">Next &rsaquo;</span>')
		h << '<label class="jump js-only" hidden>Jump to<select class="pagejump" id="pagejump">'
		String lastKey = null
		nav.eachWithIndex { Map m, int j ->
			String key = m.g + '/' + m.t.key
			if (key != lastKey) {
				if (lastKey != null) h << '</optgroup>'
				h << "<optgroup label=\"${escAttr((GROUP_TITLE[m.g] ?: m.g) + ' \u2014 ' + m.t.title)}\">"
				lastKey = key
			}
			h << "<option value=\"${escAttr(m.p.slug)}.html\"${j == i ? ' selected' : ''}>${esc(shortPath(m.p.aem))}</option>"
		}
		if (lastKey != null) h << '</optgroup>'
		h << '</select></label></div>'
		return h.toString()
	}

	/** The four checks as anchors with their verdict, so the page structure is visible at once */
	private static String renderCheckSwitch(Map p) {
		StringBuilder h = new StringBuilder()
		h << '<nav class="checkswitch">'
		CHECK_ORDER.each { chk ->
			String v = p.results[chk] ? p.results[chk].verdict : 'NA'
			h << "<a href=\"#${chk}-${p.slug}\"><span class=\"chip ${v}\">${v == 'NA' ? 'not run' : v.toLowerCase()}</span>${esc(CHECK_TITLE[chk])}</a>"
		}
		h << '</nav>'
		return h.toString()
	}

	/** One check on one page: verdict, plain-language finding, evidence, raw output on demand */
	private static String renderCheckCard(Map ctx, String chk, Map p) {
		Map res = p.results[chk]
		String verdict = res ? res.verdict : 'NA'
		StringBuilder h = new StringBuilder()
		h << "<section class=\"check ${verdict}\" id=\"${chk}-${p.slug}\">"
		h << "<div class=\"check-head\"><span class=\"badge ${verdict}\">${verdict == 'NA' ? 'not run' : verdict}</span>"
		h << "<h5>${esc(CHECK_TITLE[chk])}</h5></div>"
		h << "<p class=\"desc\">${esc(CHECK_DESC[chk])}</p>"
		if (res == null) {
			h << '<p class="sub">This check has not been run on this page yet.</p></section>'
			return h.toString()
		}
		String detail = sanitize(ctx.proj, res.detail)
		if (chk == 'content') {
			h << renderContentWhy(ctx, (String) p.slug, verdict, detail)
		} else if (chk == 'structure') {
			h << renderStructureWhy(ctx, (String) p.slug, verdict, detail)
		} else {
		h << "<p class=\"found\">${esc(summaryOf(chk, detail))}</p>"
		}
		// The findings are nested inside the card and folded away until asked for. They open by
		// default when the check failed and stay closed when it passed, so a page opens on what
		// needs attention without hiding what was checked.
		String ev = (chk == 'content') ? contentFindings(ctx, (String) p.slug)
			: (chk == 'structure') ? structureEvidence(ctx, (String) p.slug, verdict) : ''
		if (ev) {
			if (chk == 'content') {
				h << ev
			} else {
			String open = (verdict == 'PASS') ? '' : ' open'
				String label = (verdict == 'PASS') ? 'What was compared (nothing to fix on AEM)' : 'Side by side: Sitecore vs AEM'
			h << "<details class=\"evidence\"${open}><summary>${label}</summary><div class=\"evidence-body\">${ev}</div></details>"
		}
		}
		if (detail && chk != 'structure' && chk != 'content') {
			h << "<details><summary>Technical detail</summary><pre>${esc(detail)}</pre></details>"
		}
		h << '</section>'
		return h.toString()
	}

	// ---------------------------------------------------------------- content evidence

	/** Plain-language name + explanation per content verdict, most severe first */
	private static final List FINDING_ORDER = ['MISSING_ON_AEM', 'NUMBER_CHANGED', 'LINK_CHANGED',
		'WRONG_TAB', 'SCOPE_ASYMMETRY', 'COUNT_MISMATCH', 'OPTION_MISSING', 'UI_DISPLAY', 'TEXT_CHANGED',
		'STATE_ONLY_ON_LIVE', 'STATE_ONLY_ON_NEW', 'ONLY_ON_AEM']
	private static final Map FINDING_TITLE = [
		MISSING_ON_AEM: 'Sitecore has this — AEM does not',
		NUMBER_CHANGED: 'Same sentence, different number',
		COUNT_MISMATCH: 'AEM shows this fewer times than Sitecore',
		SCOPE_ASYMMETRY: 'The two pages were not read the same way',
		LINK_CHANGED: 'Same button, different destination',
		OPTION_MISSING: 'Sitecore dropdown has a choice AEM does not',
		UI_DISPLAY: 'Same words, different display (tab or style)',
		WRONG_TAB: 'On AEM, but under a different tab',
		TEXT_CHANGED: 'Same place, different wording',
		STATE_ONLY_ON_LIVE: 'Sitecore tab/section with no AEM match',
		STATE_ONLY_ON_NEW: 'AEM tab/section that Sitecore does not have',
		ONLY_ON_AEM: 'AEM has this — Sitecore does not']
	/** Same fail set as ContentCompare.ERRORS — these are why the Content card is red. */
	private static final List FINDING_FAILS = ['MISSING_ON_AEM', 'WRONG_TAB', 'NUMBER_CHANGED', 'LINK_CHANGED']
	private static final Map FINDING_SEVERITY = [
		MISSING_ON_AEM: 'FAIL', NUMBER_CHANGED: 'FAIL', LINK_CHANGED: 'FAIL', WRONG_TAB: 'FAIL',
		COUNT_MISMATCH: 'WARN', OPTION_MISSING: 'WARN', SCOPE_ASYMMETRY: 'WARN',
		UI_DISPLAY: 'WARN', TEXT_CHANGED: 'WARN', STATE_ONLY_ON_LIVE: 'WARN',
		STATE_ONLY_ON_NEW: 'INFO', ONLY_ON_AEM: 'INFO']
	private static final Map FINDING_SEV_LABEL = [
		FAIL: 'Author must fix on AEM',
		WARN: 'Please review \u2014 does not fail',
		INFO: 'Extra on AEM \u2014 no action']

	private static final Map FINDING_DESC = [
		MISSING_ON_AEM: 'Sitecore (live website) has this text. AEM does not. An author needs to add it on AEM.',
		NUMBER_CHANGED: 'Both sites have the sentence, but a number is different (a premium, age, percentage or policy term). An author needs to correct the figure on AEM.',
		COUNT_MISMATCH: 'AEM has the text, but fewer times than Sitecore (for example five cards became one). Listed for review; it does not fail the page.',
		LINK_CHANGED: 'The button says the same thing on both sites, but it opens a different page on AEM. An author needs to fix the link on AEM.',
		OPTION_MISSING: 'A dropdown on Sitecore (live website) offers this choice. The AEM dropdown does not. Review whether an author should add it on AEM.',
		UI_DISPLAY: 'The words are on both sites. AEM often shows them as a tab (the URL gets #name when you click) or a different layout. Sitecore shows them as ordinary page content. This is a style or UI difference — please review, it does not fail the page.',
		SCOPE_ASYMMETRY: 'One site hid much more content from the comparison than the other, so the numbers on this page are weaker than usual. This is about how the pages were read, not a sentence to author.',
		WRONG_TAB: 'AEM has the text, but under a different tab than Sitecore. A visitor following the live website will not find it where they expect. An author needs to move it on AEM.',
		TEXT_CHANGED: 'Same spot, different wording. Listed so you can see the rewrite; it does not fail the page.',
		STATE_ONLY_ON_LIVE: 'Sitecore has a tab or accordion section that could not be paired with one on AEM. Its text was still checked against the whole AEM page.',
		STATE_ONLY_ON_NEW: 'AEM has a tab or accordion that Sitecore does not. Extra on AEM \u2014 no action.',
		ONLY_ON_AEM: 'AEM has this text and Sitecore (live website) does not. That is allowed. Sitecore is the reference, so extra words on AEM never fail the page. No author action.']

	/**
	 * Content card headline: lead with why it failed, then compared / found % /
	 * fail counts vs notes that never fail. The old one-line dump mixed those.
	 */
	private static String renderContentWhy(Map ctx, String slug, String verdict, String detail) {
		List rows = uniqueFindingRows(parseFindings(ctx, slug))
		Map c = parseContentCounts(detail)
		int missing = rows ? (rows.count { it.verdict == 'MISSING_ON_AEM' } as int) : (c.missing as int)
		int wrongTab = rows ? (rows.count { it.verdict == 'WRONG_TAB' } as int) : (c.wrongTab as int)
		int figures = rows ? (rows.count { it.verdict == 'NUMBER_CHANGED' } as int) : (c.figures as int)
		int links = rows ? (rows.count { it.verdict == 'LINK_CHANGED' } as int) : 0
		int fails = rows ? (rows.count { FINDING_FAILS.contains(it.verdict) } as int)
			: ((c.missing as int) + (c.wrongTab as int) + (c.figures as int))

		String headline
		if (verdict == 'NOT_RUN') {
			headline = detail.readLines() ? detail.readLines()[0].trim() : 'This check was not run.'
		} else if (verdict == 'FAIL' && fails > 0) {
			List bits = []
			if (missing) bits << (missing + ' text' + (missing == 1 ? '' : 's') + ' missing on AEM')
			if (wrongTab) bits << (wrongTab + ' under the wrong tab')
			if (figures) bits << (figures + ' with a different number')
			if (links) bits << (links + ' button' + (links == 1 ? '' : 's') + ' going to a different page')
			headline = 'Failed: ' + (bits ? bits.join(', ') : fails + ' mismatch' + (fails == 1 ? '' : 'es')) + '.'
		} else if (verdict == 'FAIL') {
			headline = 'Content check failed. See the mismatches below.'
		} else {
			headline = 'Sitecore and AEM text match.'
		}
		return "<p class=\"found\">${esc(headline)}</p>"
	}

	/** One finding as four client-facing cells: words / Sitecore / AEM / action */
	private static Map findingSides(String verdict, Map r) {
		String text = (r.text ?: '').toString()
		String where = (r.path ?: '').toString().trim()
		if (!where) {
			where = (r.kind == 'option') ? 'in a dropdown'
				: 'near the top of the content'
		} else {
			where = 'under “' + where + '”'
		}
		String note = (r.note ?: '').toString().trim()
		switch (verdict) {
			case 'MISSING_ON_AEM':
				String place = note ?: where
				return [text: text, sitecore: 'Has this (' + place + ')',
					aem: 'Does not have this',
					action: note ? ('Add this text/button on AEM ' + note) : 'Add this text on AEM']
			case 'ONLY_ON_AEM':
				return [text: text, sitecore: 'Does not have this',
					aem: 'Has this (' + where + ')', action: 'No action — extra on AEM is allowed']
			case 'OPTION_MISSING':
				return [text: text, sitecore: 'Offers this in a dropdown',
					aem: 'Does not offer this choice',
					action: 'Review — add the choice on AEM if it should be there']
			case 'UI_DISPLAY':
				return [text: text, sitecore: 'Has these words',
					aem: 'Has these words — shown as a tab or different layout (URL may get #…)',
					action: 'Review only — text matches. # in the AEM URL is not a content error']
			case 'WRONG_TAB':
				return [text: text, sitecore: 'Has this (' + where + ')',
					aem: (note ?: 'Has this, under a different tab'),
					action: 'Move this text to the matching tab on AEM']
			case 'NUMBER_CHANGED':
				return [text: text, sitecore: 'Has this number (' + where + ')',
					aem: (note ?: 'Has a different number'),
					action: 'Correct the figure on AEM']
			case 'LINK_CHANGED':
				return [text: text, sitecore: 'This button (' + where + ')',
					aem: (note ?: 'Opens a different page'),
					action: 'Fix the link destination on AEM']
			case 'TEXT_CHANGED':
				return [text: text, sitecore: 'Says this (' + where + ')',
					aem: (note ?: 'Different wording'),
					action: 'No action unless the rewrite is wrong']
			case 'COUNT_MISMATCH':
				return [text: text, sitecore: (note ?: 'Has this more times'),
					aem: 'Has this fewer times',
					action: 'Review — does not fail the page']
			case 'STATE_ONLY_ON_LIVE':
				return [text: text, sitecore: 'Has this tab/section',
					aem: 'No matching tab/section',
					action: 'Review pairing — text inside was still checked']
			case 'STATE_ONLY_ON_NEW':
				return [text: text, sitecore: 'Does not have this tab/section',
					aem: 'Has this tab/section',
					action: 'No action — extra on AEM is allowed']
			default:
				return [text: text, sitecore: where, aem: (note ?: '—'), action: '—']
		}
	}

	private static String evidenceImg(Map ctx, String slug, int idx, String side) {
		String name = String.format('%03d-%s.png', idx, side)
		File src = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/evidence/' + name)
		if (!src.exists()) {
			return '<span class="no-pic">No screenshot &mdash; words were in a closed tab or a hidden dropdown</span>'
		}
		String href = '../assets/ContentAudit/' + slug + '/evidence/' + name
		String label = (side == 'sitecore') ? 'Sitecore' : 'AEM'
		return '<a href="' + escAttr(href) + '" target="_blank" rel="noopener">' +
			'<img class="evimg" src="' + escAttr(href) + '" alt="' + escAttr(label + ' evidence') + '"></a>'
	}

	private static void copyEvidencePics(String proj, File root, List pages) {
		pages.each { p ->
			File src = new File(proj + '/Reports/ContentAudit/' + p.slug + '/evidence')
			if (!src.directory) return
			File dest = new File(root, 'assets/ContentAudit/' + p.slug + '/evidence')
			dest.mkdirs()
			src.listFiles()?.each { File f ->
				if (f.name.toLowerCase().endsWith('.png')) {
					java.nio.file.Files.copy(f.toPath(), new File(dest, f.name).toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING)
				}
			}
		}
	}

	private static String statCell(String label, String value, String cls) {
		return '<div class="stat' + (cls ? ' ' + cls : '') + '"><span class="k">' + esc(label) +
			'</span><span class="v">' + esc(value) + '</span></div>'
	}

	private static List parseFindings(Map ctx, String slug) {
		File f = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/findings.csv')
		if (!f.exists()) return []
		List rows = []
		int idx = 0
		f.readLines('UTF-8').drop(1).each { line ->
			def m = (line =~ /^(\w+),(\w*),"((?:[^"]|"")*)","((?:[^"]|"")*)","((?:[^"]|"")*)"$/)
			if (m.find()) {
				rows << [idx: idx, verdict: m.group(1), kind: m.group(2),
				path: m.group(3).replace('""', '"'), text: m.group(4).replace('""', '"'),
				note: m.group(5).replace('""', '"')]
				idx++
			}
		}
		return rows
	}

	private static Map parseContentCounts(String detail) {
		Map out = [compared: 0, missing: 0, wrongTab: 0, reworded: 0, onlyNew: 0, figures: 0, fewer: 0]
		String first = detail?.readLines() ? detail.readLines()[0].trim() : ''
		def n = (first =~ /(\d+) live items compared: (\d+) missing, (\d+) in the wrong tab, (\d+) reworded, (\d+) only on the new page/)
		if (!n.find()) return out
		out.compared = n.group(1) as int
		out.missing = n.group(2) as int
		out.wrongTab = n.group(3) as int
		out.reworded = n.group(4) as int
		out.onlyNew = n.group(5) as int
		def x = (first =~ /(\d+) with changed figures, (\d+) appearing fewer times/)
		if (x.find()) {
			out.figures = x.group(1) as int
			out.fewer = x.group(2) as int
		}
		return out
	}

	/**
	 * Structure card headline + four stats, same pattern as Content.
	 */
	private static String renderStructureWhy(Map ctx, String slug, String verdict, String detail) {
		List tags = loadStructureTags(ctx, slug)
		Map images = loadStructureImages(ctx, slug)
		int liveTags = (tags.sum { it.sitecore } ?: 0) as int
		int aemTags = (tags.sum { it.aem } ?: 0) as int
		int missingTags = tags.count { it.sitecore > it.aem } as int
		int extraTags = tags.count { it.aem > it.sitecore } as int
		int checked = (images.checked ?: 0) as int
		int failedPics = (images.failed ?: 0) as int
		int matchedPics = Math.max(0, checked - failedPics)
		int scPics = (images.sitecoreCount ?: 0) as int
		int aemPics = (images.aemCount ?: 0) as int

		String headline
		if (verdict == 'NOT_RUN') {
			headline = detail?.readLines() ? detail.readLines()[0].trim() : 'This check was not run.'
		} else if (verdict == 'FAIL' && failedPics > 0 && missingTags > 0) {
			headline = 'Failed because ' + failedPics + ' of ' + checked +
				' pictures do not look the same on AEM, and AEM is also missing some Sitecore headings or pictures.'
		} else if (verdict == 'FAIL' && failedPics > 0) {
			headline = 'Failed because ' + failedPics + ' of ' + checked +
				' pictures do not look the same on AEM. Matching pictures are not listed below.'
		} else if (verdict == 'FAIL' && missingTags > 0) {
			headline = 'Failed because AEM has fewer headings or pictures than Sitecore. Extra tags on AEM are not lost Sitecore content.'
		} else if (verdict == 'FAIL') {
			headline = 'Failed because Sitecore and AEM use different markup counts. Extra tags on AEM do not mean text was lost \u2014 check Content for wording.'
		} else if (checked > 0) {
			headline = matchedPics + ' of ' + checked + ' pictures look the same. Tag counts are close enough.'
		} else {
			headline = summaryOf('structure', detail) ?: 'Sitecore and AEM structure match.'
		}

		StringBuilder h = new StringBuilder()
		h << "<p class=\"found\">${esc(headline)}</p>"
		if (verdict != 'NOT_RUN' && (!tags.isEmpty() || checked > 0)) {
			h << '<div class="statrow">'
			h << statCell('Sitecore (live website)',
				liveTags ? (liveTags + ' heading/link/picture tags') : '—', '')
			h << statCell('AEM',
				aemTags ? (aemTags + ' heading/link/picture tags') : '—',
				(missingTags > 0 ? 'bad' : ''))
			h << statCell('Pictures that look the same',
				checked ? (matchedPics + ' of ' + checked) : (scPics || aemPics ? (scPics + ' vs ' + aemPics) : 'None on either page'),
				(failedPics > 0 ? 'bad' : (checked > 0 ? 'good' : '')))
			int mustFix = missingTags + failedPics
			h << statCell('Author should check',
				mustFix ? (mustFix + ' item' + (mustFix == 1 ? '' : 's')) : 'Nothing',
				mustFix ? 'bad' : 'good')
			h << '</div>'
			if (extraTags && verdict == 'FAIL' && failedPics == 0 && missingTags == 0) {
				h << '<p class="sub">AEM has extra headings, links or paragraphs. That is usually AEM markup, not missing Sitecore content.</p>'
			}
		}
		return h.toString()
	}

	/**
	 * Side by side: tag table, then only the pictures that failed.
	 * The raw universal-log.txt is not shown.
	 */
	private static String structureEvidence(Map ctx, String slug, String verdict) {
		List tags = loadStructureTags(ctx, slug)
		Map images = loadStructureImages(ctx, slug)
		List reasons = structureClientReasons(ctx, slug)
		List pairsEarly = (images.pairs instanceof List) ? (List) images.pairs : []
		if (tags.isEmpty() && pairsEarly.isEmpty() && reasons.isEmpty()) return ''

		StringBuilder h = new StringBuilder()
		if (!tags.isEmpty()) {
			h << '<div class="pairblock"><h6>Headings, links and pictures</h6>'
			h << '<p class="desc">Sitecore is the live website. AEM is the new site. Same count means the same number of that tag. Extra on AEM is usually different markup, not lost content. Missing on AEM is what an author should check.</p>'
			h << '<table class="difftable"><tr><th>Tag</th><th>Sitecore (live website)</th><th>AEM</th><th>What this means</th></tr>'
			tags.each { row ->
				String cls = (row.sitecore > row.aem) ? ' class="miss"' : ((row.aem > row.sitecore) ? ' class="extra"' : '')
				h << "<tr${cls}><td>${esc(tagLabel(row.tag as String))}</td>"
				h << "<td>${row.sitecore}</td><td>${row.aem}</td>"
				h << "<td>${esc(tagMeaning(row.tag as String, row.sitecore as int, row.aem as int))}</td></tr>"
			}
			h << '</table></div>'
		}

		int checked = (images.checked ?: 0) as int
		int failedPics = (images.failed ?: 0) as int
		int matchedPics = Math.max(0, checked - failedPics)
		int scPics = (images.sitecoreCount ?: 0) as int
		int aemPics = (images.aemCount ?: 0) as int
		List pairs = (images.pairs instanceof List) ? (List) images.pairs : []
		List fails = pairs.findAll { !(it.matched as boolean) }

		if (checked > 0 || scPics || aemPics || fails) {
			String sev = failedPics > 0 ? 'FAIL' : 'INFO'
			h << "<div class=\"pairblock ${sev}\"><h6><span class=\"chip ${failedPics > 0 ? 'FAIL' : 'PASS'}\">"
			h << (failedPics > 0 ? 'Author must check' : 'Pictures match')
			h << "</span>Pictures \u2014 ${matchedPics} of ${checked} look the same</h6>"
			if (scPics && aemPics && scPics != aemPics) {
				h << "<p class=\"desc\">Sitecore has ${scPics} pictures, AEM has ${aemPics}. "
				h << "Paired by file name. Sitecore pictures with no AEM name match are listed below.</p>"
			} else if (failedPics > 0) {
				h << '<p class="desc">Matching pictures are hidden. Each row is one Sitecore picture that does not look like the AEM picture in the same place.</p>'
			} else if (checked > 0) {
				h << '<p class="desc">Every compared picture looks the same, even if the file size or zoom is different.</p>'
			}
			fails.each { Map pair -> h << renderImageFail(pair, checked) }
			if (failedPics > 0 && !fails) {
				h << '<p class="sub">This run recorded that pictures failed, but not the step-by-step checks. Re-run the compare suite (not only rebuild the report) to see why each picture failed.</p>'
			}
			h << '</div>'
		}

		if (tags.isEmpty() && !reasons.isEmpty() && verdict != 'PASS') {
			h << '<div class="pairblock"><h6>What was found</h6><ul class="plain">'
			reasons.each { r -> h << "<li>${esc(r)}</li>" }
			h << '</ul></div>'
		}
		return h.toString()
	}

	private static String renderImageFail(Map pair, int total) {
		int n = (pair.pair ?: 0) as int
		String how = (pair.how ?: pair.error ?: 'Images do not match visually (failed fuzzy pixel comparison).').toString()
		if (how.toLowerCase().contains('downloading') || how.contains('MD5') ||
			how.startsWith('Error comparing') || how.contains('drawImage') ||
			how.contains('BigDecimal') || how.contains('No signature') || how.length() > 160) {
			how = 'Images do not match visually (failed fuzzy pixel comparison).'
		}
		StringBuilder h = new StringBuilder()
		h << '<div class="imgfail">'
		h << "<h6>Picture ${n}" + (total ? " of ${total}" : '') + "</h6>"
		h << "<p>${esc(how)}</p>"
		if ((pair.liveW ?: 0) || (pair.aemW ?: 0)) {
			h << "<p class=\"sub\">Sitecore ${pair.liveW}\u00d7${pair.liveH}"
			h << " \u00b7 AEM ${pair.aemW}\u00d7${pair.aemH}</p>"
		}
		if (pair.liveUrl) {
			h << "<p class=\"fileurl\"><span>Sitecore file</span> <a href=\"${escAttr(pair.liveUrl)}\" target=\"_blank\" rel=\"noopener\">${esc(pair.liveUrl)}</a></p>"
		}
		if (pair.aemUrl) {
			h << "<p class=\"fileurl\"><span>AEM file</span> <a href=\"${escAttr(pair.aemUrl)}\" target=\"_blank\" rel=\"noopener\">${esc(pair.aemUrl)}</a></p>"
		}
		h << '</div>'
		return h.toString()
	}

	private static List loadStructureTags(Map ctx, String slug) {
		File f = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/tags.csv')
		if (f.exists()) {
			List rows = []
			f.readLines('UTF-8').drop(1).each { line ->
				if (!line?.trim()) return
				def p = line.split(',', 3)
				if (p.length < 3) return
				try {
					rows << [tag: p[0].trim(), sitecore: p[1].trim() as int, aem: p[2].trim() as int]
				} catch (Exception ignore) { }
			}
			if (rows) return rows
		}
		return parseTagsFromLog(ctx, slug)
	}

	private static Map loadStructureImages(Map ctx, String slug) {
		File f = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/images.json')
		if (f.exists()) {
			try {
				return (Map) new JsonSlurper().parseText(f.getText('UTF-8'))
			} catch (Exception ignore) { }
		}
		return parseImagesFromLog(ctx, slug)
	}

	/** Older runs only have universal-log.txt — recover tag counts from those lines. */
	private static List parseTagsFromLog(Map ctx, String slug) {
		File logFile = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/universal-log.txt')
		if (!logFile.exists()) return []
		List rows = []
		logFile.eachLine('UTF-8') { String line ->
			def perfect = (line =~ /<(\w+)>:\s*Perfect Match \((\d+)\)/)
			if (perfect.find()) {
				int n = perfect.group(2) as int
				rows << [tag: perfect.group(1).toLowerCase(), sitecore: n, aem: n]
				return
			}
			def miss = (line =~ /<(\w+)>:\s*MISSING IN AEM \(Live has (\d+), AEM only has (\d+)\)/)
			if (miss.find()) {
				rows << [tag: miss.group(1).toLowerCase(), sitecore: miss.group(2) as int, aem: miss.group(3) as int]
				return
			}
			def extra = (line =~ /<(\w+)>:\s*EXTRA IN AEM \(Live has (\d+), AEM has (\d+)\)/)
			if (extra.find()) {
				rows << [tag: extra.group(1).toLowerCase(), sitecore: extra.group(2) as int, aem: extra.group(3) as int]
			}
		}
		return rows
	}

	/** Older runs: pair index + fail, without FileImageComparer steps. */
	private static Map parseImagesFromLog(Map ctx, String slug) {
		File logFile = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/universal-log.txt')
		Map out = [checked: 0, failed: 0, sitecoreCount: 0, aemCount: 0, pairs: []]
		if (!logFile.exists()) return out
		int checked = 0
		List pairs = []
		logFile.eachLine('UTF-8') { String line ->
			def pair = (line =~ /Checking Image Pair (\d+) of (\d+)/)
			if (pair.find()) {
				checked = pair.group(2) as int
				return
			}
			def fail = (line =~ /IMAGE FAIL: Image (\d+)/)
			if (fail.find()) {
				pairs << [pair: fail.group(1) as int, matched: false, how: 'do not look the same',
					logLines: [], error: '', liveUrl: '', aemUrl: '']
			}
		}
		out.checked = checked
		out.failed = pairs.size()
		out.pairs = pairs
		return out
	}

	private static List structureClientReasons(Map ctx, String slug) {
		File reasonsFile = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/universal.txt')
		if (!reasonsFile.exists()) return []
		return reasonsFile.readLines('UTF-8').collect { it.trim() }.findAll { String r ->
			r && !(r ==~ /(?i).*(text similarity|image mismatch on visual check).*/)
		}
	}

	private static String tagLabel(String tag) {
		switch ((tag ?: '').toLowerCase()) {
			case 'h1': return 'H1 title'
			case 'h2': return 'H2 heading'
			case 'h3': return 'H3 heading'
			case 'h4': return 'H4 heading'
			case 'h5': return 'H5 heading'
			case 'h6': return 'H6 heading'
			case 'p' : return 'Paragraph'
			case 'li': return 'List item'
			case 'a' : return 'Link'
			case 'img': return 'Picture'
			default: return (tag ?: '').toUpperCase()
		}
	}

	private static String tagMeaning(String tag, int sitecore, int aem) {
		String name = tagLabel(tag).toLowerCase()
		if (sitecore == aem) return 'Same count on both sites'
		if (sitecore > aem) {
			int n = sitecore - aem
			String noun = n == 1 ? name : (name + 's')
			if (['img', 'h1', 'h2', 'p'].contains((tag ?: '').toLowerCase())) {
				return 'Sitecore has ' + n + ' more ' + noun + '. Check that AEM did not drop them.'
			}
			return 'Sitecore has ' + n + ' more ' + noun + '.'
		}
		int n = aem - sitecore
		String noun = n == 1 ? name : (name + 's')
		if ((tag ?: '').equalsIgnoreCase('h1')) {
			return 'AEM has ' + n + ' extra ' + noun + '. More than one H1 is unusual.'
		}
		return 'AEM has ' + n + ' extra ' + noun + '. Extra markup on AEM is not lost Sitecore content.'
	}

	/** Fail rows first (with pictures), then Please review. Extra/count noise stays folded away. */
	private static final List CONTENT_MAIN = ['MISSING_ON_AEM', 'NUMBER_CHANGED', 'LINK_CHANGED',
		'WRONG_TAB', 'OPTION_MISSING', 'UI_DISPLAY']

	/** findings.csv rendered as one table per verdict, worst first */
	private static String contentFindings(Map ctx, String slug) {
		List rows = uniqueFindingRows(parseFindings(ctx, slug))
		if (!rows) return ''
		StringBuilder h = new StringBuilder()
		CONTENT_MAIN.each { String v -> h << contentTable(ctx, slug, v, rows) }
		List extra = FINDING_ORDER.findAll { !CONTENT_MAIN.contains(it) }
			.findAll { String v -> rows.any { it.verdict == v } }
		if (extra) {
			h << '<details><summary>Other notes (does not fail the page)</summary>'
			extra.each { String v -> h << contentTable(ctx, slug, v, rows) }
			h << '</details>'
		}
		return h.toString()
	}

	/** Collapse identical wording+place rows (e.g. Contact us on three tabs). */
	private static List uniqueFindingRows(List rows) {
		Set seen = [] as Set
		List out = []
		(rows ?: []).each { Map r ->
			String k = [r.verdict, (r.text ?: '').toString().toLowerCase(),
				(r.note ?: '').toString().toLowerCase()].join('|')
			if (seen.contains(k)) return
			seen << k
			out << r
		}
		return out
	}

	private static String contentTable(Map ctx, String slug, String v, List rows) {
			List got = rows.findAll { it.verdict == v }
		if (!got) return ''
		String sev = FINDING_SEVERITY[v] ?: 'INFO'
		String sevLabel = FINDING_SEV_LABEL[sev] ?: sev
		StringBuilder h = new StringBuilder()
		h << "<div class=\"pairblock ${sev}\"><h6><span class=\"chip ${sev}\">${esc(sevLabel)}</span>"
		h << "${esc(FINDING_TITLE[v])} — ${got.size()}</h6>"
			h << "<p class=\"desc\">${esc(FINDING_DESC[v])}</p>"
		h << '<table class="difftable"><tr><th>The words</th><th>Sitecore (live website)</th><th>AEM</th>'
		h << '<th>What an author should do</th><th>Picture on Sitecore</th><th>Picture on AEM</th></tr>'
			got.each { r ->
			Map cells = findingSides(v, r)
			h << "<tr><td>${esc(cells.text)}</td><td>${esc(cells.sitecore)}</td>"
			h << "<td>${esc(cells.aem)}</td><td>${esc(cells.action)}</td>"
			h << "<td class=\"evcell\">${evidenceImg(ctx, slug, r.idx as int, 'sitecore')}</td>"
			h << "<td class=\"evcell\">${evidenceImg(ctx, slug, r.idx as int, 'aem')}</td></tr>"
			}
			h << '</table></div>'
		return h.toString()
	}

	/**

	/**

	/** Pair indexes that actually have a screenshot on either side. */

	/** state_pairs.csv: pair,sc_id,sc_label,aem_id,aem_label,by,score — written by ContentCompare */
	private static List statePairLabels(Map ctx, String slug) {
		File f = new File(ctx.proj + '/Reports/ContentAudit/' + slug + '/state_pairs.csv')
		if (!f.exists()) return []
		List out = []
		f.readLines('UTF-8').drop(1).each { line ->
			def c = (line =~ /"((?:[^"]|"")*)"/).collect { it[1].replace('""', '"') }
			if (c.size() < 4) return
			String by = ''
			def tail = (line =~ /,(label|content|position),/)
			if (tail.find()) by = tail.group(1)
			out << [sc: c[1], aem: c[3], by: by]
		}
		return out
	}

	/**

	/** diffs_map.csv rows: badge,side,type,layer,status,"text" (text quoted by AuditUtils.csvq) */

	// ---------------------------------------------------------------- image evidence

	static final int MAX_IMAGE_ROWS = 30

	/**

	/**

	/** One image pair: severity badge, the reason in plain English, and the two files compared. */

	/**

	/** Why this row got its level: the verdict in plain English, then the flags. */

	/**


	/**


	// ---------------------------------------------------------------- appendix

	// ---------------------------------------------------------------- helpers

	/**
	 * Screenshots are captured at 3584px (retina). 2000px is the point where body text in a
	 * full-page capture stays readable at "actual size" in the report; 1400px was not, and the
	 * screenshots are the deliverable. Costs roughly twice the bundle size — measure after a run.
	 */
	static final int PUBLISH_MAX_WIDTH = 2000
	static final long PUBLISH_SHRINK_ABOVE_BYTES = 400 * 1024
	static final float PUBLISH_JPEG_QUALITY = 0.85f

	/**

	/**

	/** Emits a figure only when the screenshot exists */

	private static String readText(String path, int maxLines) {
		File f = new File(path)
		if (!f.exists()) return null
		List lines = f.readLines('UTF-8')
		return lines.take(maxLines).join('\n')
	}

	/** Local absolute paths must never reach a client-facing page */
	private static String sanitize(String proj, String s) {
		return s == null ? '' : s.replace(proj + '/', '').replace(proj, '').trim()
	}

	/** Turns a check's technical detail line into one plain sentence for the summary table */
	private static String summaryOf(String chk, String detail) {
		if (!detail) return ''
		String first = detail.readLines() ? detail.readLines()[0].trim() : ''
		if (chk == 'structure') {
			return first
		}
		if (chk == 'content') {
			def n = (first =~ /(\d+) live items compared: (\d+) missing, (\d+) in the wrong tab, (\d+) reworded, (\d+) only on the new page/)
			if (n.find()) {
				int missing = n.group(2) as int, wrongTab = n.group(3) as int
				// Appended by ContentCompare.summary after the clause above, so it is read
				// separately and older summaries without it still render.
				def x = (first =~ /(\d+) with changed figures, (\d+) appearing fewer times/)
				int figures = 0, fewer = 0
				if (x.find()) { figures = x.group(1) as int; fewer = x.group(2) as int }
				String verdict = (missing + wrongTab + figures + fewer == 0)
					? "Every Sitecore text was found on AEM (${n.group(1)} compared)"
					: ([missing ? "${missing} on Sitecore missing from AEM" : null,
						wrongTab ? "${wrongTab} under the wrong tab on AEM" : null,
						figures ? "${figures} with a different number on AEM" : null,
						fewer ? "${fewer} appear fewer times on AEM" : null]
						.findAll { it }.join(', ') + " (of ${n.group(1)} Sitecore texts)")
				return verdict
			}
			// legacy line-based summary, kept so older runs still render
			def m = (first =~ /(\d+) Sitecore lines, (\d+) missing on AEM, (\d+) extra/)
			if (m.find()) {
				return "${m.group(2)} of ${m.group(1)} Sitecore texts are missing from AEM (${m.group(3)} extra on AEM)"
			}
		}
		// drop the trailing evidence path, keep the readable part
		return first.replaceAll(/\s*—\s*\S*Reports\S*$/, '')
	}

	private static String shortPath(String url) {
		return url.replaceAll('^https?://[^/]+', '') ?: '/'
	}

	private static String esc(Object s) {
		return (s == null ? '' : s.toString()).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
	}

	/** Attribute values need the quotes escaped too — esc() alone is only safe in text nodes */
	private static String escAttr(Object s) {
		return esc(s).replace('"', '&quot;').replace((String) "'", '&#39;')
	}

	/** Same footer on every file: when it was built, what the reference is, how to drive it */
	private static String footer() {
		return '<footer class="foot"><p class="sub">Generated ' + new Date().format('yyyy-MM-dd HH:mm') +
			' &middot; the live site is the reference; extra content on the new site is never a failure.</p>' +
			'<p class="sub kbd js-only" hidden>Keyboard: <kbd>&larr;</kbd> / <kbd>&rarr;</kbd> previous and next page' +
			'</p></footer>'
	}

	/**
	 * Everything the report does in the browser, emitted to assets/_site/report.js.
	 *
	 * Deliberately a classic script with no dependency and no fetch: the report is opened
	 * from file:// as often as from a server. Every control is additive — the filter bar,
	 * the jump dropdown and the picture toolbars stay hidden until this file switches them
	 * on, so a reader without JavaScript gets the whole report instead of dead controls.
	 *
	 * NOTE: this is a triple-quoted Groovy string. Do not put a backslash in it — Katalon's
	 * Groovy-Eclipse compiler rejects unknown escapes and emits an error stub for the whole
	 * keyword (see the 2026-08-18 entry in project-tracking).
	 */
	private static final String SCRIPT = '''(function () {
  'use strict';
  var all = function (sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  };
  var byId = function (id) { return document.getElementById(id); };
  var val = function (id) { var e = byId(id); return e ? e.value : ''; };

  // ---------------------------------------------------------------- filter bar
  var bar = byId('filters');

  function applyFilters() {
    var g = val('f-group'), t = val('f-template'), st = val('f-status');
    var q = (val('f-q') || '').trim().toLowerCase();
    var shown = 0, total = 0;
    all('[data-row=page]').forEach(function (row) {
      var ok = (!g || row.getAttribute('data-group') === g)
        && (!t || row.getAttribute('data-template') === t)
        && (!st || row.getAttribute('data-status') === st)
        && (!q || (row.getAttribute('data-url') || '').indexOf(q) >= 0);
      row.hidden = !ok;
      if (row.getAttribute('data-scope') === 'index') { total++; if (ok) shown++; }
    });
    // a container with nothing left in it is noise, not information
    all('[data-block]').forEach(function (b) {
      b.hidden = !all('[data-row=page]', b).some(function (r) { return !r.hidden; });
    });
    var cnt = byId('f-count');
    if (cnt) {
      cnt.textContent = (shown === total)
        ? ('Showing all ' + total + ' page(s).')
        : ('Showing ' + shown + ' of ' + total + ' page(s).');
    }
  }

  // the template list follows the chosen group, so the two can never contradict each other
  function syncTemplateOptions() {
    var g = val('f-group'), sel = byId('f-template');
    if (!sel) return;
    var dropped = false;
    all('option', sel).forEach(function (o) {
      var og = o.getAttribute('data-group');
      var ok = !og || !g || og === g;
      o.hidden = !ok;
      if (o.selected && !ok) dropped = true;
    });
    if (dropped) sel.value = '';
  }

  if (bar) {
    bar.hidden = false;
    ['f-group', 'f-template', 'f-status'].forEach(function (id) {
      var e = byId(id);
      if (!e) return;
      e.addEventListener('change', function () {
        if (id === 'f-group') syncTemplateOptions();
        applyFilters();
      });
    });
    var q = byId('f-q');
    if (q) q.addEventListener('input', applyFilters);
    var reset = byId('f-reset');
    if (reset) reset.addEventListener('click', function () {
      ['f-group', 'f-template', 'f-status', 'f-q'].forEach(function (id) {
        var e = byId(id);
        if (e) e.value = '';
      });
      syncTemplateOptions();
      applyFilters();
    });
    var ex = byId('f-expand'), co = byId('f-collapse');
    if (ex) ex.addEventListener('click', function () {
      all('nav.tree details').forEach(function (d) { d.open = true; });
    });
    if (co) co.addEventListener('click', function () {
      all('nav.tree details').forEach(function (d) { d.open = false; });
    });
    applyFilters();
  }

  // a link inside a summary should navigate, not fold the branch it labels
  all('summary a').forEach(function (a) {
    a.addEventListener('click', function (e) { e.stopPropagation(); });
  });

  // ---------------------------------------------------------------- page navigation
  var jump = byId('pagejump');
  if (jump) {
    var lab = jump.parentNode;
    if (lab) lab.hidden = false;
    jump.addEventListener('change', function () {
      if (jump.value) window.location.href = jump.value;
    });
  }
  all('.kbd').forEach(function (k) { k.hidden = false; });

  document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    var node = document.activeElement ? document.activeElement.nodeName : '';
    if (node === 'INPUT' || node === 'SELECT' || node === 'TEXTAREA') return;
    var id = (e.key === 'ArrowLeft' || e.key === 'k') ? 'nav-prev'
      : (e.key === 'ArrowRight' || e.key === 'j') ? 'nav-next' : '';
    if (!id) return;
    var a = byId(id);
    if (a) { e.preventDefault(); window.location.href = a.getAttribute('href'); }
  });

  // ---------------------------------------------------------------- misc
  // paper gets the evidence too: a collapsed <details> prints empty otherwise
  window.addEventListener('beforeprint', function () {
    all('details').forEach(function (d) {
      if (!d.open) { d.setAttribute('data-was-closed', '1'); d.open = true; }
    });
  });
  window.addEventListener('afterprint', function () {
    all('details[data-was-closed]').forEach(function (d) {
      d.open = false;
      d.removeAttribute('data-was-closed');
    });
  });
}());'''

	/**
	 * Page head: title, and the links to the two files written by writeStaticAssets().
	 * `prefix` reaches assets/_site/ from where this file sits ('' on the index, '../' on a
	 * page file).
	 *
	 * Deliberately a single light theme: this is a document that gets read, shared and
	 * printed, so every colour is painted explicitly rather than inherited from whatever
	 * renders it.
	 */
	private static String head(String subtitle, String prefix) {
		StringBuilder h = new StringBuilder()
		h << '<!doctype html><html lang="en"><head><meta charset="utf-8">'
		h << '<meta name="viewport" content="width=device-width, initial-scale=1">'
		h << "<title>${subtitle ? esc(subtitle) + ' — ' : ''}Migration Parity Report</title>"
		h << "<link rel=\"stylesheet\" href=\"${prefix}assets/_site/report.css\">"
		h << "<script defer src=\"${prefix}assets/_site/report.js\"></script>"
		h << "</head><body class=\"${subtitle ? 'pageview' : 'indexview'}\"><main>"
		return h.toString()
	}

	/**
	 * The site's own CSS and JS are written once per output root, under assets/_site/ so they
	 * can never collide with copied evidence (which always lands in assets/<Reports-subdir>/).
	 * Inlining them per file would duplicate ~10 KB into every page and make the behaviour
	 * impossible to maintain.
	 */
	private static void writeStaticAssets(File root) {
		File dir = new File(root, 'assets/_site')
		dir.mkdirs()
		new File(dir, 'report.css').setText(stylesheet(), 'UTF-8')
		new File(dir, 'report.js').setText(SCRIPT, 'UTF-8')
	}

	/** The stylesheet, emitted to assets/_site/report.css */
	private static String stylesheet() {
		StringBuilder h = new StringBuilder()

		h << ':root{--ground:#FBFBFC;--surface:#FFFFFF;--sunk:#F4F6F9;--ink:#14181F;--muted:#5B6572;'
		h << '--line:#E2E6EB;--line-strong:#C9D0D9;--accent:#2C4A7C;--accent-soft:#EEF2F8;'
		h << '--fail:#B3261E;--fail-soft:#FBEEEC;--warn:#8A5A00;--warn-soft:#FCF3E1;--pass:#146C43;--pass-soft:#EBF5EF;'
		h << "--serif:'Iowan Old Style','Palatino Linotype',Palatino,Georgia,'Times New Roman',serif;"
		h << "--sans:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,Roboto,sans-serif;"
		h << '--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}'
		h << '*{box-sizing:border-box}'
		h << 'body{margin:0;padding:36px 16px 80px;background:var(--ground);color:var(--ink);'
		h << 'font-family:var(--sans);font-size:15px;line-height:1.55;-webkit-font-smoothing:antialiased}'
		h << 'main{max-width:1440px;margin:0 auto;display:flex;flex-direction:column;gap:36px}'
		h << 'a{color:var(--accent)}a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:2px;border-radius:3px}'
		h << '.mono{font-family:var(--mono)}.num{font-variant-numeric:tabular-nums;text-align:right}'
		h << '.sub{color:var(--muted);font-size:13px;margin:0}.of{color:var(--muted);font-size:12px}'

		// cover
		h << '.cover{border-bottom:3px double var(--line-strong);padding-bottom:28px;display:flex;flex-direction:column;gap:14px}'
		h << '.eyebrow{margin:0;font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:var(--accent);font-weight:600}'
		h << 'h1{font-family:var(--serif);font-size:38px;line-height:1.15;font-weight:600;margin:0;text-wrap:balance;letter-spacing:-.01em}'
		h << '.lede{margin:0;max-width:64ch;color:var(--muted);font-size:16px}'
		h << '.facts{display:flex;flex-wrap:wrap;gap:0;margin:8px 0 0;border-top:1px solid var(--line)}'
		h << '.facts>div{flex:1 1 160px;padding:12px 20px 2px 0;border-right:1px solid var(--line);margin-right:20px}'
		h << '.facts>div:last-child{border-right:0}'
		h << '.facts dt{font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--muted)}'
		h << '.facts dd{margin:2px 0 0;font-family:var(--serif);font-size:24px;font-variant-numeric:tabular-nums}'
		h << '.facts dd.bad{color:var(--fail)}.facts dd.good{color:var(--pass)}'

		// section headings
		h << 'h2{font-family:var(--serif);font-size:26px;font-weight:600;margin:0 0 6px;letter-spacing:-.01em}'
		h << 'h2.minor{font-family:var(--sans);font-size:12px;font-weight:600;letter-spacing:.11em;'
		h << 'text-transform:uppercase;color:var(--muted);margin:0 0 12px}'
		h << 'section.group>h2{padding-bottom:8px;border-bottom:1px solid var(--line-strong)}'

		// at a glance matrix
		h << '.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:8px;background:var(--surface)}'
		h << 'table.matrix{border-collapse:collapse;width:100%;font-size:13px}'
		h << '.matrix th,.matrix td{padding:9px 14px;text-align:left;border-bottom:1px solid var(--line);white-space:nowrap}'
		h << '.matrix thead th{background:var(--sunk);font-size:11px;letter-spacing:.07em;text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.matrix tbody tr:last-child td,.matrix tbody tr:last-child th{border-bottom:0}'
		h << '.matrix .grouprow th{background:var(--accent-soft);font-family:var(--serif);font-size:15px;font-weight:600}'
		h << '.matrix .grouprow a{color:var(--ink);text-decoration:none}'
		h << '.matrix tbody th[scope=row]{font-weight:500;padding-left:26px}'

		// contents tree
		h << 'nav.tree{border:1px solid var(--line);border-radius:8px;background:var(--surface);padding:18px 20px}'
		h << 'nav.tree ul{list-style:none;margin:0;padding:0}'
		h << 'nav.tree>ul>li>ul{margin-top:6px}'
		h << 'nav.tree ul ul{padding-left:14px;border-left:1px solid var(--line);margin-left:4px}'
		h << 'nav.tree li{margin:5px 0;display:flex;flex-direction:column;gap:4px}'
		h << 'nav.tree li>a{text-decoration:none}nav.tree li>a:hover{text-decoration:underline}'
		h << 'nav.tree .branch>a{font-weight:600}'
		h << 'nav.tree .group-link{font-family:var(--serif);font-size:18px;color:var(--ink)}'
		h << 'nav.tree .leaf{flex-direction:row;flex-wrap:wrap;align-items:center;gap:6px;font-size:13px}'
		h << 'nav.tree .leaf>a{font-size:13px}'
		h << 'nav.tree .branch>.chip{align-self:flex-start}'

		// chips and badges
		h << '.chip,.badge{display:inline-block;font-size:11px;font-weight:600;line-height:1.7;'
		h << 'border-radius:999px;padding:0 10px;white-space:nowrap;text-decoration:none}'
		h << '.badge{border-radius:5px;letter-spacing:.06em;text-transform:uppercase}'
		h << '.chip+.chip{margin-left:4px}'
		h << '.chip.FAIL,.badge.FAIL{background:var(--fail-soft);color:var(--fail)}'
		h << '.chip.WARN,.badge.WARN{background:var(--warn-soft);color:var(--warn)}'
		h << '.chip.PASS,.badge.PASS{background:var(--pass-soft);color:var(--pass)}'
		h << '.chip.NA,.badge.NA{background:var(--sunk);color:var(--muted)}'
		h << 'a.chip:hover{text-decoration:underline}'

		// template -> page -> check
		h << '.template{margin-top:22px}'
		h << '.template-head{margin-bottom:12px}'
		h << 'h3{font-family:var(--serif);font-size:20px;font-weight:600;margin:0}'
		h << 'article.page{border:1px solid var(--line);border-radius:8px;background:var(--surface);'
		h << 'padding:16px 18px;margin:12px 0;display:flex;flex-direction:column;gap:12px}'
		h << 'article.page.has-findings{border-left:3px solid var(--fail)}'
		h << '.page-head{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 18px;'
		h << 'padding-bottom:10px;border-bottom:1px solid var(--line)}'
		h << 'h4{font-size:15px;font-weight:600;margin:0;word-break:break-all}'
		h << '.links{margin:0;display:flex;gap:16px}'
		h << '.links a{font-size:13px;text-decoration:none}.links a:hover{text-decoration:underline}'
		h << '.links a:after{content:" \\2197";font-size:11px}'
		h << '.page-status{margin:0;font-size:13px;color:var(--muted)}'
		h << '.page-status.fail{color:var(--fail);font-weight:600;font-size:15px}'
		h << '.statrow{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin:4px 0 2px}'
		h << '.stat{border:1px solid var(--line);border-radius:6px;padding:8px 10px;background:var(--sunk);display:flex;flex-direction:column;gap:2px}'
		h << '.stat.bad{background:var(--fail-soft);border-color:#e8c4c0}'
		h << '.stat.good{background:var(--pass-soft);border-color:#c5ddcf}'
		h << '.stat .k{font-size:11px;letter-spacing:.07em;text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.stat .v{font-size:14px;font-weight:600;font-variant-numeric:tabular-nums}'
		h << '.stat.bad .v{color:var(--fail)}.stat.good .v{color:var(--pass)}'
		h << '.pairblock.FAIL{border-left:3px solid var(--fail)}'
		h << '.pairblock.WARN{border-left:3px solid var(--warn)}'
		h << '.pairblock.INFO{border-left:3px solid var(--line-strong)}'
		h << 'nav.crumbs{display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-size:12px;color:var(--muted)}'
		h << 'nav.crumbs a{color:var(--accent);text-decoration:none}nav.crumbs a:hover{text-decoration:underline}'
		h << 'nav.crumbs>*+*:before{content:"\\203A";margin-right:8px;color:var(--line-strong)}'
		h << 'h1.pagetitle{font-family:var(--mono);font-size:20px;font-weight:600;word-break:break-all}'
		h << '.dim{color:var(--muted)}'
		h << 'details.evidence{border-top:1px solid var(--line);padding-top:10px}'
		h << 'details.evidence>summary{font-weight:600;color:var(--ink)}'
		h << '.evidence-body{display:flex;flex-direction:column;gap:12px;padding:12px 0 2px 12px;'
		h << 'margin-top:8px;border-left:2px solid var(--line)}'
		h << 'details.pending{border:1px dashed var(--line-strong);border-radius:8px;padding:12px 16px;background:var(--surface)}'
		h << 'ul.plain{list-style:none;margin:10px 0 0;padding:0;display:flex;flex-direction:column;gap:4px;font-size:13px}'
		h << 'section.check{border:1px solid var(--line);border-radius:6px;padding:14px 14px;'
		h << 'display:flex;flex-direction:column;gap:8px;background:var(--surface)}'
		h << 'section.check.FAIL{border-left:3px solid var(--fail)}'
		h << 'section.check.WARN{border-left:3px solid var(--warn)}'
		h << 'section.check.PASS{border-left:3px solid var(--pass)}'
		h << 'section.check.NA{background:var(--ground);border-style:dashed}'
		h << '.check.FAIL .found{color:var(--fail)}.check.WARN .found{color:var(--warn)}'
		h << '.check-head{display:flex;align-items:center;gap:10px}'
		h << 'h5{font-size:14px;font-weight:600;margin:0}'
		h << '.desc{margin:0;font-size:12.5px;color:var(--muted);max-width:78ch}'
		h << '.found{margin:0;font-size:14.5px;font-weight:500}'

		// evidence
		h << 'details{border-top:1px solid var(--line);padding-top:8px}'
		h << 'summary{cursor:pointer;font-size:12.5px;color:var(--muted);font-weight:500}'
		h << 'summary:hover{color:var(--accent)}'
		h << 'pre{background:var(--sunk);border:1px solid var(--line);border-radius:6px;padding:12px;'
		h << 'font-family:var(--mono);font-size:12px;overflow-x:auto;white-space:pre-wrap;margin:10px 0 0}'
		h << '.pairblock{border:1px solid var(--line);border-radius:6px;padding:12px 12px;background:var(--surface)}'
		h << '.pairblock+.pairblock{margin-top:12px}'
		h << '.pairblock h6{font-size:13px;font-weight:600;margin:0 0 10px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}'
		h << 'table.difftable{border-collapse:collapse;width:100%;font-size:12.5px;margin:0 0 12px}'
		h << '.difftable th,.difftable td{border-bottom:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top}'
		h << '.difftable th{background:var(--sunk);font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.difftable td:first-child{font-variant-numeric:tabular-nums;color:var(--muted)}'
		h << '.evcell{width:200px}'
		h << '.evimg{display:block;max-width:200px;max-height:140px;object-fit:contain;'
		h << 'border:1px solid var(--line-strong);border-radius:4px;background:var(--sunk)}'
		h << '.no-pic{color:var(--muted);font-size:12px}'
		h << '.difftable tr.miss td{background:var(--fail-soft)}'
		h << '.difftable tr.extra td{background:var(--accent-soft)}'
		h << '.imgfail{border-top:1px solid var(--line);padding:12px 0 4px}'
		h << '.imgfail:first-of-type{border-top:0;padding-top:4px}'
		h << '.imgfail h6{margin:0 0 6px}'
		h << 'ol.imgsteps{margin:8px 0;padding-left:22px;font-size:12.5px;font-family:var(--mono);line-height:1.45}'
		h << 'ol.imgsteps li{margin:3px 0;word-break:break-word}'
		h << '.fileurl{margin:4px 0 0;font-size:12px;word-break:break-all}'
		h << '.fileurl span{display:inline-block;min-width:7.5em;color:var(--muted);font-weight:600;'
		h << 'letter-spacing:.06em;text-transform:uppercase;font-size:11px}'

		// controls, navigation and the picture viewer (behaviour lives in report.js)
		h << '[hidden]{display:none!important}'
		h << '.filters{position:sticky;top:0;z-index:30;border:1px solid var(--line);border-radius:8px;'
		h << 'background:var(--surface);padding:14px 18px;box-shadow:0 2px 6px rgba(20,24,31,.06)}'
		h << '.filterrow{display:flex;flex-wrap:wrap;gap:10px 14px;align-items:flex-end}'
		h << '.filters label{display:flex;flex-direction:column;gap:4px;font-size:11px;letter-spacing:.08em;'
		h << 'text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.filters select,.filters input[type=search]{font:inherit;font-size:13px;text-transform:none;'
		h << 'letter-spacing:0;color:var(--ink);background:var(--surface);border:1px solid var(--line-strong);'
		h << 'border-radius:6px;padding:6px 8px;min-width:170px}'
		h << '.filters button{font:inherit;font-size:12.5px;color:var(--accent);'
		h << 'background:var(--surface);border:1px solid var(--line-strong);border-radius:6px;padding:6px 12px;cursor:pointer}'
		h << '.filters button:hover{background:var(--accent-soft)}'
		h << '#f-count{margin-top:10px}'

		h << '.topbar{position:sticky;top:0;z-index:30;background:var(--ground);border-bottom:1px solid var(--line);'
		h << 'padding:10px 0;margin-bottom:-16px;display:flex;flex-wrap:wrap;gap:8px 18px;align-items:center;justify-content:space-between}'
		h << '.pagenav{display:flex;flex-wrap:wrap;align-items:center;gap:10px}'
		h << '.navbtn{font-size:13px;text-decoration:none;border:1px solid var(--line-strong);border-radius:6px;'
		h << 'padding:5px 11px;background:var(--surface);white-space:nowrap}'
		h << 'a.navbtn:hover{background:var(--accent-soft)}'
		h << '.navbtn.off{color:var(--muted);opacity:.45}'
		h << '.navcount{font-size:12px;color:var(--muted);font-variant-numeric:tabular-nums;white-space:nowrap}'
		h << '.jump{display:flex;align-items:center;gap:6px;font-size:11px;letter-spacing:.08em;'
		h << 'text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.pagejump{font:inherit;font-size:13px;text-transform:none;letter-spacing:0;color:var(--ink);'
		h << 'background:var(--surface);border:1px solid var(--line-strong);border-radius:6px;padding:5px 8px;max-width:340px}'
		h << '.checkswitch{display:flex;flex-wrap:wrap;gap:8px}'
		h << '.checkswitch a{display:inline-flex;align-items:center;gap:7px;font-size:12.5px;text-decoration:none;'
		h << 'border:1px solid var(--line);border-radius:999px;padding:3px 13px 3px 4px;background:var(--surface);color:var(--ink)}'
		h << '.checkswitch a:hover{border-color:var(--accent);background:var(--accent-soft)}'
		h << '.pagefoot{display:flex;gap:18px}'
		// the sticky bar must not swallow the heading an in-page anchor lands on
		h << 'section.check,h2[id],h3[id],.pairblock{scroll-margin-top:104px}'
		h << 'body.pageview main{max-width:1680px}'
		h << 'footer.foot{border-top:1px solid var(--line);padding-top:14px;display:flex;flex-direction:column;gap:4px}'
		h << 'kbd{font-family:var(--mono);font-size:11px;border:1px solid var(--line-strong);border-radius:4px;'
		h << 'padding:0 5px;background:var(--sunk)}'

		// nav tree branches fold; the disclosure marker sits on the summary
		h << 'nav.tree details>summary{display:flex;flex-wrap:wrap;align-items:center;gap:6px;list-style:none;'
		h << 'cursor:pointer;font-size:inherit;color:inherit;font-weight:inherit}'
		h << 'nav.tree details>summary::-webkit-details-marker{display:none}'
		h << 'nav.tree details>summary:before{content:"\\25B8";color:var(--muted);font-size:11px;'
		h << 'transition:transform .12s ease;display:inline-block}'
		h << 'nav.tree details[open]>summary:before{transform:rotate(90deg)}'
		h << 'nav.tree details>summary:hover{color:var(--accent)}'


		// two 3584px captures in 330px columns are useless — go one-up much earlier than the page does
		h << '@media (max-width:720px){body{padding:24px 10px 56px}h1{font-size:30px}'
		h << '.filters,.topbar{position:static}.pagejump{max-width:220px}}'
		// report.js opens every <details> before printing, so evidence survives on paper
		h << '@media print{body{background:#fff;padding:0}main{gap:24px;max-width:none}'
		h << 'article.page,section.check,nav.tree,.scroll{break-inside:avoid}'
		h << '.filters,.topbar,.checkswitch{display:none!important}}'
		h << '@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}'
		return h.toString()
	}

	// ---------------------------------------------------------------- other exports

	/**
	 * Aggregates the latest compare run into one row per URL pair for the master
	 * tracking sheet: an `overall` verdict (FAIL > WARN > PASS > NOT_RUN) plus a
	 * single `summary` cell joining every check's one-line detail — paste/VLOOKUP
	 * the two columns into the mastersheet by sitecoreurl.
	 * Output: Reports/report.xlsx (sitecoreurl, aemurl, overall, summary)
	 */
	@Keyword
	static String buildMastersheetColumn() {
		String proj = RunConfiguration.getProjectDir()
		List rows = []
		new File(proj + '/Data Files/aem-url-mapping.csv').readLines('UTF-8').drop(1).each { line ->
			def c = line.split(',')
			if (c.length < 2) return
			String sc = c[0].trim(), aem = c[1].trim()
			String slug = AuditUtils.slugOf(aem)
			List verdicts = [], parts = []
			ALL_CHECKS.each { chk ->
				Map res = readResult(proj, slug, chk)
				if (res == null) return
				verdicts << res.verdict
				// keep only the first detail line; evidence paths become project-relative
				String d = res.detail ? res.detail.readLines()[0].replace(proj + '/', '').trim() : ''
				parts << (chk + ': ' + res.verdict + (d ? " (${d})" : ''))
			}
			String overall = verdicts.contains('FAIL') ? 'FAIL'
					: verdicts.any { it != 'PASS' } ? 'WARN'
					: verdicts ? 'PASS' : 'NOT_RUN'
			rows << [sc, aem, overall, parts.join(' | ')]
		}

		def wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()
		def sheet = wb.createSheet('Report')
		def bold = wb.createFont(); bold.setBold(true)
		def headStyle = wb.createCellStyle(); headStyle.setFont(bold)
		Map verdictStyle = [:]
		[FAIL: org.apache.poi.ss.usermodel.IndexedColors.ROSE,
		 WARN: org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW,
		 PASS: org.apache.poi.ss.usermodel.IndexedColors.LIGHT_GREEN,
		 NOT_RUN: org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT].each { v, color ->
			def st = wb.createCellStyle()
			st.setFillForegroundColor(color.getIndex())
			st.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND)
			verdictStyle[v] = st
		}
		List header = ['sitecoreurl', 'aemurl', 'overall', 'summary']
		def hr = sheet.createRow(0)
		header.eachWithIndex { t, i ->
			def cell = hr.createCell(i); cell.setCellValue(t); cell.setCellStyle(headStyle)
		}
		rows.eachWithIndex { r, ri ->
			def row = sheet.createRow(ri + 1)
			r.eachWithIndex { v, ci ->
				def cell = row.createCell(ci)
				cell.setCellValue(v == null ? '' : v.toString())
				if (ci == 2 && verdictStyle[v]) cell.setCellStyle(verdictStyle[v])
			}
		}
		sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rows.size(), 0, header.size() - 1))
		sheet.setColumnWidth(0, 60 * 256); sheet.setColumnWidth(1, 60 * 256)
		sheet.setColumnWidth(2, 12 * 256); sheet.setColumnWidth(3, 120 * 256)

		File out = new File(proj + '/Reports/report.xlsx')
		out.withOutputStream { os -> wb.write(os) }
		wb.close()
		KeywordUtil.logInfo('Mastersheet report -> ' + out.getAbsolutePath())
		return out.getAbsolutePath()
	}

	/**
	 * Builds an HTML summary of everything captured under Data Files/baselines/:
	 * one row per page type x check kind with file count and last-capture time.
	 * Run it after a baseline suite to verify what was actually captured.
	 * Output: Reports/baseline-summary.html
	 */
	@Keyword
	static String buildBaselineSummary() {
		String proj = RunConfiguration.getProjectDir()
		File root = new File(proj + '/Data Files/baselines')
		StringBuilder h = new StringBuilder()
		h << '<!doctype html><html><head><meta charset="utf-8"><title>Baseline Summary</title><style>'
		h << 'body{font-family:"Avenir Next","Segoe UI",system-ui,sans-serif;margin:0;padding:40px 20px;background:#FAF7F5;color:#231A1C}'
		h << 'main{max-width:900px;margin:0 auto}h1{margin:0 0 4px;font-size:26px}.sub{color:#7A6A6E;margin:0 0 28px}'
		h << 'table{border-collapse:collapse;width:100%;font-size:13px}th,td{border-bottom:1px solid #D8C9CC;padding:6px 10px;text-align:left}'
		h << 'th{font-size:11px;text-transform:uppercase;letter-spacing:.05em;color:#7A6A6E}'
		h << '.empty{color:#C8102E;font-weight:700}.ok{color:#1F7A55;font-weight:700}details{margin:2px 0}summary{cursor:pointer}'
		h << '</style></head><body><main><h1>Baseline Summary</h1>'
		h << "<p class=\"sub\">Generated ${new Date().format('yyyy-MM-dd HH:mm')} — files under Data Files/baselines/</p>"
		h << '<table><tr><th>Group</th><th>Page type</th><th>Check</th><th>Files</th><th>Last capture</th><th>File list</th></tr>'
		int total = 0
		if (root.isDirectory()) {
			root.listFiles().findAll { it.isDirectory() }.sort { it.name }.each { g ->
				g.listFiles().findAll { it.isDirectory() }.sort { it.name }.each { pt ->
					pt.listFiles().findAll { it.isDirectory() }.sort { it.name }.each { kind ->
						List files = kind.listFiles().findAll { it.isFile() }.sort { it.name }
						total += files.size()
						String cls = files ? 'ok' : 'empty'
						String last = files ? new Date(files*.lastModified().max()).format('yyyy-MM-dd HH:mm') : '—'
						String names = files ? '<details><summary>' + files.size() + ' file(s)</summary>' +
							files.collect { esc(it.name) }.join('<br>') + '</details>' : ''
						h << "<tr><td>${esc(g.name)}</td><td>${esc(pt.name)}</td><td>${esc(kind.name)}</td>"
						h << "<td class=\"${cls}\">${files.size()}</td><td>${last}</td><td>${names}</td></tr>"
					}
				}
			}
		}
		h << "</table><p class=\"sub\">Total: ${total} baseline file(s)</p></main></body></html>"
		String out = proj + '/Reports/baseline-summary.html'
		new File(out).setText(h.toString(), 'UTF-8')
		KeywordUtil.logInfo('Baseline summary -> ' + out)
		return out
	}
}

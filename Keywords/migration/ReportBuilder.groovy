package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

/**
 * The client-facing HTML report for the content-parity check.
 *
 * Three levels, one file each, and every level lists only what the next one opens:
 *
 *   index.html                              the run, then one row per template
 *   templates/<group>-<pagetype>.html       the pages of that template
 *   pages/<slug>.html                       the texts of that page that did not survive
 *
 * Nothing is listed twice and no single file grows with the whole site — the target is
 * ~2,000 URLs, where one long document is not a report, it is a download.
 *
 * The report answers two numbers per page and stays out of the way otherwise: how many texts
 * of the live page were compared, and how many of them failed. What the check writes but a
 * reader cannot act on — the raw summary line, the score, the description of what the check
 * does — is deliberately not rendered.
 *
 * A page fails on any MISSING_ON_AEM / WRONG_TAB / NUMBER_CHANGED / LINK_CHANGED, however
 * large the page is: losing one button is losing content. The live page is the reference and
 * a subset baseline, so extra text on the new page is reported and never fails.
 *
 * Input is files on disk, never the Katalon log:
 *
 *   Data Files/aem-url-mapping.csv                  which pages exist, and their template
 *   Reports/parity-results/<slug>/content.txt       verdict + the check's detail lines
 *   Reports/ContentAudit/<slug>/findings.csv        one row per finding — the evidence
 *
 * Output is self-contained apart from one stylesheet and one script under assets/_site/, and
 * every navigation step is a plain link, so it works from file:// with JavaScript off.
 *
 *   build() -> Reports/parity-report/
 */
public class ReportBuilder {

	// ---------------------------------------------------------------- vocabulary

	static final List GROUP_ORDER = ['custom', 'normal']
	static final Map GROUP_TITLE = [custom: 'Custom pages', normal: 'Normal pages']

	static final List ACRONYMS = ['ilp', 'ga4', 'ppc', 'pru', 'lbu', 'pva', 'http', 'gtm']

	/** Most severe first: the reading order of the blocks on a page */
	static final List FINDING_ORDER = ['MISSING_ON_AEM', 'NUMBER_CHANGED', 'LINK_CHANGED',
		'WRONG_TAB', 'SCOPE_ASYMMETRY', 'COUNT_MISMATCH', 'OPTION_MISSING', 'TEXT_CHANGED',
		'STATE_ONLY_ON_LIVE', 'STATE_ONLY_ON_NEW', 'ONLY_ON_AEM']

	static final Map FINDING_TITLE = [
		MISSING_ON_AEM: 'Missing on the new page',
		NUMBER_CHANGED: 'Figures changed',
		LINK_CHANGED: 'Same button, different destination',
		WRONG_TAB: 'Present, but under a different tab',
		SCOPE_ASYMMETRY: 'The two pages were not read comparably',
		COUNT_MISMATCH: 'Appears fewer times than on the live page',
		OPTION_MISSING: 'Dropdown choice not offered on the new page',
		TEXT_CHANGED: 'Wording changed',
		STATE_ONLY_ON_LIVE: 'Tab/section with no counterpart on the new page',
		STATE_ONLY_ON_NEW: 'Tab/section only on the new page',
		ONLY_ON_AEM: 'Only on the new page']

	/** One sentence each. The long explanations belong in the docs, not on all 20 pages. */
	static final Map FINDING_HINT = [
		MISSING_ON_AEM: 'Not found anywhere on the new page.',
		NUMBER_CHANGED: 'Same sentence, different figures — a sum, an age, a percentage, a policy term.',
		LINK_CHANGED: 'Same wording, different destination.',
		WRONG_TAB: 'On the new page, but under a different tab.',
		SCOPE_ASYMMETRY: 'The two pages were not read on equal terms, so these findings rest on a weaker measurement.',
		COUNT_MISMATCH: 'On the new page, but fewer times than on the live page.',
		OPTION_MISSING: 'A dropdown choice the new page does not offer.',
		TEXT_CHANGED: 'Same place, reworded.',
		STATE_ONLY_ON_LIVE: 'A tab or section of the live page with no counterpart found on the new page.',
		STATE_ONLY_ON_NEW: 'A tab or section that exists only on the new page.',
		ONLY_ON_AEM: 'Extra text on the new page. The live page is a subset baseline, so this never fails.']

	/** The four that fail a page. Kept identical to ContentCompare.ERRORS. */
	static final List ERRORS = ['MISSING_ON_AEM', 'WRONG_TAB', 'NUMBER_CHANGED', 'LINK_CHANGED']
	/** Worth reading, but they do not fail the page */
	static final List WARNINGS = ['SCOPE_ASYMMETRY', 'COUNT_MISMATCH', 'OPTION_MISSING', 'TEXT_CHANGED',
		'STATE_ONLY_ON_LIVE', 'STATE_ONLY_ON_NEW']
	/** Never a problem: the live page is a subset baseline */
	static final List INFOS = ['ONLY_ON_AEM']

	/**
	 * Severity is spelled out as well as coloured. This report is printed, forwarded and read on
	 * whatever screen is to hand, and roughly one man in twelve cannot tell the red rule from the
	 * amber one — the colour is the fast signal, the label is the statement.
	 */
	static final Map LEVEL_LABEL = [err: 'Fails the page', warn: 'Warning', info: 'For information']

	/** A verdict in none of the three lists renders as a warning rather than disappearing. */
	static String level(String verdict) {
		return ERRORS.contains(verdict) ? 'err' : INFOS.contains(verdict) ? 'info' : 'warn'
	}

	// ---------------------------------------------------------------- entry points

	/** Report that reads its evidence in place from Reports/ (for the test team) */
	@Keyword
	static String build() {
		return render()
	}


	// ---------------------------------------------------------------- input

	/**
	 * One entry per row of the master mapping, carrying the result found on disk.
	 *
	 * The page list comes from the CSV and not from the results, so a page nobody checked is a
	 * visible gap rather than an absence no one notices. Rows without pagegroup/pagetype (legacy
	 * 3-column rows) default to "normal".
	 */
	private static List collectPages(String proj) {
		List pages = []
		File csv = new File(proj + '/Data Files/aem-url-mapping.csv')
		if (!csv.exists()) return pages
		csv.readLines('UTF-8').drop(1).each { String line ->
			if (!line?.trim()) return
			def c = line.split(',')
			if (c.length < 3) return
			String aem = c[1].trim()
			String slug = AuditUtils.slugOf(aem)
			pages << [
				sc      : c[0].trim(),
				aem     : aem,
				tpl     : c.length > 2 ? c[2].trim() : '',
				group   : c.length > 3 && c[3].trim() ? c[3].trim() : 'normal',
				pagetype: c.length > 4 ? c[4].trim() : '',
				slug    : slug,
				result  : readResult(proj, slug),
			]
		}
		return pages
	}

	/** content.txt: line 1 is the verdict, the rest is the check's detail. null = never checked. */
	private static Map readResult(String proj, String slug) {
		File f = new File(proj + '/Reports/parity-results/' + slug + '/content.txt')
		if (!f.exists()) return null
		List lines = f.readLines('UTF-8')
		return [verdict: lines ? lines[0].trim() : 'NA',
			detail: lines.size() > 1 ? lines.drop(1).join('\n').trim() : '']
	}

	/**
	 * findings.csv parsed into rows.
	 *
	 * The trailing `weight` column is OPTIONAL in this pattern and must stay that way. The files
	 * on disk carry it (`…,"note",1.0`) and the pattern is `$`-anchored: demanding the five
	 * columns exactly drops **every** row of **every** page, in silence, and the report then
	 * reads "no findings" on a page that failed. That is not hypothetical — it shipped once; see
	 * docs/reference/report-contract.md.
	 */
	private static List readFindings(String proj, String slug) {
		File f = new File(proj + '/Reports/ContentAudit/' + slug + '/findings.csv')
		if (!f.exists()) return []
		List rows = []
		f.readLines('UTF-8').drop(1).each { String line ->
			def m = (line =~ /^(\w+),(\w*),"((?:[^"]|"")*)","((?:[^"]|"")*)","((?:[^"]|"")*)"(?:,[-0-9.]+)?$/)
			if (m.find()) rows << [verdict: m.group(1), kind: m.group(2),
				path: m.group(3).replace('""', '"'), text: m.group(4).replace('""', '"'),
				note: m.group(5).replace('""', '"')]
		}
		return rows
	}

	/**
	 * How many live-page texts entered the comparison, read from the first detail line. The only
	 * part of that prose line this report parses — everything else is counted from findings.csv,
	 * where each finding is a row carrying its own verdict.
	 */
	private static int itemsCompared(String detail) {
		if (!detail) return 0
		def m = (detail.readLines()[0] =~ /(\d+) live items compared/)
		return m.find() ? (m.group(1) as int) : 0
	}

	/** Everything one page contributes to the report, read once. */
	private static Map statsOf(String proj, Map p) {
		Map res = (Map) p.result
		List rows = readFindings(proj, (String) p.slug)
		return [verdict: res ? (String) res.verdict : 'NA',
			items  : itemsCompared(res == null ? null : (String) res.detail),
			rows   : rows,
			failed : rows.count { ERRORS.contains(it.verdict) },
			warned : rows.count { WARNINGS.contains(it.verdict) },
			extra  : rows.count { INFOS.contains(it.verdict) }]
	}

	/** Pages of one group bucketed by template (the `pagetype` slug of the mapping) */
	private static List templatesOf(List gp) {
		Map buckets = [:]
		gp.each { Map p ->
			String key = p.pagetype ?: 'unclassified'
			if (buckets[key] == null) buckets[key] = [key: key, title: humanize((String) key), tpl: p.tpl, pages: []]
			buckets[key].pages << p
		}
		buckets.values().each { Map b -> b.pages = b.pages.sort { it.aem } }
		return buckets.values().sort { it.title }
	}

	/** "ilp-fund" -> "ILP Fund", "general-content-detail-page" -> "General Content Detail Page" */
	private static String humanize(String slug) {
		if (!slug) return 'Unclassified'
		return slug.split('[-_]').collect { String w ->
			ACRONYMS.contains(w.toLowerCase()) ? w.toUpperCase() : (w ? w.substring(0, 1).toUpperCase() + w.substring(1) : w)
		}.join(' ')
	}

	/**
	 * How many of the compared pages passed.
	 *
	 * The denominator everywhere in this report is the pages actually compared in this run: a
	 * page nobody checked can neither pass nor fail, and counting it would move the rate every
	 * time the batch changes rather than when the site changes.
	 */
	private static int passedOf(List stats) {
		return stats.count { it.verdict == 'PASS' }
	}

	/**
	 * "90% (18 of 20)" — the pass rate of a set of compared pages.
	 *
	 * This is a page-level rate and nothing else. It counts whole pages that passed the verdict
	 * gate, never texts: a page that lost one text out of 127 counts as one failed page here,
	 * exactly as it does everywhere else in the report. Nothing compared prints no rate at all,
	 * never 0% — an empty batch is not a total failure.
	 */
	private static String rate(int passed, int total) {
		if (total <= 0) return '&mdash;'
		int p = (int) Math.round(passed * 100.0d / total)
		return p + '% <span class="of">(' + passed + ' of ' + total + ')</span>'
	}

	// ---------------------------------------------------------------- rendering

	private static String render() {
		String proj = RunConfiguration.getProjectDir()
		List pages = collectPages(proj)

		File root = new File(proj + "/Reports/parity-report")
		if (root.exists()) root.deleteDir()
		root.mkdirs()
		writeStaticAssets(root)
		File templatesDir = new File(root, 'templates'); templatesDir.mkdirs()
		File pagesDir = new File(root, 'pages'); pagesDir.mkdirs()

		Map byGroup = [:]
		GROUP_ORDER.each { String g -> byGroup[g] = pages.findAll { it.group == g } }
		List extraGroups = pages*.group.unique().findAll { !GROUP_ORDER.contains(it) }.sort()
		extraGroups.each { String g -> byGroup[g] = pages.findAll { it.group == g } }
		List groups = GROUP_ORDER + extraGroups

		File index = new File(root, 'index.html')
		index.setText(renderIndex(proj, pages, groups, byGroup), 'UTF-8')

		// Two ordered walks in the reading order of the index: one over the templates, one over
		// the compared pages. They are what previous/next and the jump dropdown follow, so a
		// reviewer can walk the whole report without coming back here between URLs.
		List tplNav = []
		groups.each { String g -> templatesOf((List) (byGroup[g] ?: [])).each { Map t -> tplNav << [g: g, t: t] } }
		tplNav.eachWithIndex { Map m, int i ->
			new File(templatesDir, templateFile((String) m.g, (Map) m.t))
					.setText(renderTemplateFile(proj, (String) m.g, (Map) m.t, tplNav, i), 'UTF-8')
		}

		List nav = []
		tplNav.each { Map m ->
			((Map) m.t).pages.findAll { it.result != null }.each { Map p -> nav << [g: m.g, t: m.t, p: p] }
		}
		nav.eachWithIndex { Map n, int i ->
			new File(pagesDir, n.p.slug + '.html').setText(renderPageFile(proj, n, nav, i), 'UTF-8')
		}

		KeywordUtil.logInfo("Parity report -> " + index.getAbsolutePath() +
			" (${tplNav.size()} template file(s), ${nav.size()} page file(s))")
		return index.getAbsolutePath()
	}

	/** index.html: the run, then one row per template. No individual page is listed here. */
	private static String renderIndex(String proj, List pages, List groups, Map byGroup) {
		int compared = pages.count { it.result != null }
		int withFindings = pages.count { it.result != null && it.result.verdict == 'FAIL' }

		StringBuilder h = new StringBuilder()
		h << head('', '')
		h << '<header class="cover">'
		h << '<p class="eyebrow">Website migration &middot; quality assurance</p>'
		h << '<h1>Migration parity report</h1>'
		h << '<p class="lede">Every page of the new site is compared against the live site, which is the reference. '
		h << 'Open a template to see its pages; open a page to see the texts that did not survive.</p>'
		h << '<dl class="facts">'
		h << "<div><dt>Generated</dt><dd>${new Date().format('yyyy-MM-dd HH:mm')}</dd></div>"
		h << "<div><dt>Pages in scope</dt><dd>${pages.size()}</dd></div>"
		h << "<div><dt>Compared in this run</dt><dd>${compared}</dd></div>"
		h << "<div><dt>Pages with findings</dt><dd class=\"${withFindings > 0 ? 'bad' : 'good'}\">${withFindings}</dd></div>"
		h << '</dl></header>'

		h << '<section class="overview"><h2 class="minor">Templates</h2>'
		h << '<div class="scroll"><table class="matrix"><thead><tr><th>Template</th><th>Pages</th><th>Compared</th>'
		h << '<th>Texts compared</th><th>Texts failed</th><th>Result</th><th></th></tr></thead><tbody>'
		groups.each { String g ->
			List gp = (List) byGroup[g]
			if (!gp) return
			h << '<tr class="grouprow"><th colspan="7" scope="colgroup">'
			h << esc(GROUP_TITLE[g] ?: (g.capitalize() + ' pages')) + '</th></tr>'
			templatesOf(gp).each { Map t ->
				List done = t.pages.findAll { it.result != null }
				List stats = done.collect { Map p -> statsOf(proj, p) }
				int failedPages = stats.count { it.verdict == 'FAIL' }
				int items = (stats.sum { it.items } ?: 0) as int
				int failedTexts = (stats.sum { it.failed } ?: 0) as int
				h << "<tr><th scope=\"row\"><a href=\"${templateHref(g, t, '')}\">${esc(t.title)}</a></th>"
				h << "<td class=\"num\">${t.pages.size()}</td><td class=\"num\">${done.size()}</td>"
				h << "<td class=\"num\">${items ?: '&mdash;'}</td>"
				h << '<td class="num">' + (failedTexts > 0 ? "<b class=\"bad\">${failedTexts}</b>" : '&mdash;') + '</td>'
				h << '<td>' + (done.isEmpty() ? '<span class="chip NA">not compared</span>'
						: failedPages > 0 ? "<span class=\"chip FAIL\">${failedPages} of ${done.size()} with findings</span>"
						: "<span class=\"chip PASS\">${done.size()} passed</span>") + '</td>'
				h << "<td><a href=\"${templateHref(g, t, '')}\">open &rsaquo;</a></td></tr>"
			}
		}
		h << '</tbody></table></div></section>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/** One template = one file: the pages that report it, and nothing else. */
	private static String renderTemplateFile(String proj, String group, Map t, List tplNav, int i) {
		String gTitle = GROUP_TITLE[group] ?: (group.capitalize() + ' pages')
		List done = t.pages.findAll { it.result != null }
		int failedPages = done.count { it.result.verdict == 'FAIL' }
		Map prev = i > 0 ? (Map) tplNav[i - 1] : null
		Map next = i < tplNav.size() - 1 ? (Map) tplNav[i + 1] : null

		StringBuilder h = new StringBuilder()
		h << head((String) t.title, '../')
		h << '<div class="topbar">'
		h << '<nav class="crumbs"><a href="../index.html">Migration parity report</a>'
		h << "<span>${esc(gTitle)}</span><span>${esc(t.title)}</span></nav>"
		h << '<div class="pagenav">'
		h << (prev ? "<a class=\"navbtn\" rel=\"prev\" href=\"${escAttr(templateFile((String) prev.g, (Map) prev.t))}\">&lsaquo; ${esc(prev.t.title)}</a>"
				: '<span class="navbtn off">&lsaquo; Previous</span>')
		h << "<span class=\"navcount\">Template ${i + 1} of ${tplNav.size()}</span>"
		h << (next ? "<a class=\"navbtn\" rel=\"next\" href=\"${escAttr(templateFile((String) next.g, (Map) next.t))}\">${esc(next.t.title)} &rsaquo;</a>"
				: '<span class="navbtn off">Next &rsaquo;</span>')
		h << '</div></div>'

		h << "<article class=\"page ${failedPages > 0 ? 'has-findings' : ''}\">"
		h << "<div class=\"page-head\"><h1>${esc(t.title)}</h1><p class=\"sub\">${esc(gTitle)}"
		if (t.tpl) h << " &middot; AEM template &ldquo;${esc(t.tpl)}&rdquo;"
		h << '</p></div>'
		h << "<p class=\"page-status\">${t.pages.size()} page(s) report this template &middot; "
		h << "${done.size()} compared in this run &middot; "
		h << (failedPages > 0 ? "${failedPages} with findings" : 'none with findings') + '</p>'
		h << templateBody(proj, t)
		h << '<p class="sub pagefoot"><a href="../index.html">&lsaquo; Back to all templates</a>'
		if (next) h << "<a href=\"${escAttr(templateFile((String) next.g, (Map) next.t))}\">Next template &rsaquo;</a>"
		h << '</p></article>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/**
	 * The pages of one template, carrying the two numbers the report exists for: how many live
	 * texts were compared, and how many failed. Pages nobody compared get no file of their own,
	 * only a folded note — one empty card each would bury the pages that were compared.
	 */
	private static String templateBody(String proj, Map t) {
		List done = t.pages.findAll { it.result != null }
		List pending = t.pages.findAll { it.result == null }
		StringBuilder h = new StringBuilder()
		if (done) {
			h << '<div class="scroll"><table class="matrix"><thead><tr><th>Page</th><th>Result</th>'
			h << '<th>Texts compared</th><th>Texts failed</th><th></th></tr></thead><tbody>'
			done.each { Map p ->
				Map s = statsOf(proj, p)
				String v = (String) s.verdict
				h << "<tr><th scope=\"row\"><a class=\"mono\" href=\"${pageHref(p, '../')}\">${esc(shortPath((String) p.aem))}</a></th>"
				h << "<td><span class=\"chip ${v}\">${v == 'NA' ? 'not run' : v.toLowerCase()}</span></td>"
				h << "<td class=\"num\">${s.items ?: '&mdash;'}</td>"
				h << '<td class="num">' + ((s.failed as int) > 0 ? "<b class=\"bad\">${s.failed}</b>" : '&mdash;') + '</td>'
				h << "<td><a href=\"${pageHref(p, '../')}\">open &rsaquo;</a></td></tr>"
			}
			h << '</tbody></table></div>'
		}
		if (pending) {
			h << "<details class=\"pending\"><summary>${pending.size()} page(s) not compared in this run "
			h << '&mdash; out of the current batch or not published yet</summary><ul class="plain">'
			pending.each { Map p -> h << "<li><a class=\"mono\" href=\"${esc(p.aem)}\">${esc(shortPath((String) p.aem))}</a></li>" }
			h << '</ul></details>'
		}
		return h.toString()
	}

	/**
	 * One page = one file: the counts, then the failing texts themselves.
	 *
	 * What it deliberately does not print: the description of the check (the same paragraph on
	 * every page of the report), the raw summary line, and the score. A reviewer opens this file
	 * to see which text is missing, not to re-read what the check does.
	 */
	private static String renderPageFile(String proj, Map n, List nav, int i) {
		String group = (String) n.g
		Map t = (Map) n.t
		Map p = (Map) n.p
		Map s = statsOf(proj, p)
		String verdict = (String) s.verdict
		int failed = s.failed as int
		Map next = i < nav.size() - 1 ? (Map) nav[i + 1] : null
		String gTitle = GROUP_TITLE[group] ?: (group.capitalize() + ' pages')

		StringBuilder h = new StringBuilder()
		h << head(shortPath((String) p.aem), '../')
		h << '<div class="topbar">'
		h << '<nav class="crumbs"><a href="../index.html">Migration parity report</a>'
		h << "<span>${esc(gTitle)}</span><a href=\"${templateHref(group, t, '../')}\">${esc(t.title)}</a></nav>"
		h << renderPageNav(nav, i)
		h << '</div>'

		h << "<article class=\"page ${failed > 0 ? 'has-findings' : ''}\">"
		h << "<div class=\"page-head\"><h1 class=\"mono pagetitle\">${esc(shortPath((String) p.aem))}</h1>"
		h << "<p class=\"links\"><a href=\"${esc(p.sc)}\">live page</a><a href=\"${esc(p.aem)}\">new page</a></p></div>"

		if (verdict == 'NOT_RUN') {
			h << '<p class="page-status"><span class="badge NOT_RUN">not run</span> '
			h << 'This URL does not serve a page to compare.</p>'
		} else {
			h << "<p class=\"page-status\"><span class=\"badge ${verdict}\">"
			h << (verdict == 'NA' ? 'not run' : verdict) + '</span> '
			h << (failed > 0
					? "<b class=\"bad\">${failed}</b> of ${s.items} live text(s) did not survive the migration."
					: "All ${s.items} live text(s) were found on the new page.")
			h << '</p>'
			h << '<dl class="facts">'
			h << "<div><dt>Live texts compared</dt><dd>${s.items}</dd></div>"
			h << "<div><dt>Failed</dt><dd class=\"${failed > 0 ? 'bad' : 'good'}\">${failed}</dd></div>"
			h << "<div><dt>Warnings</dt><dd>${s.warned}</dd></div>"
			h << "<div><dt>Only on the new page</dt><dd>${s.extra}</dd></div>"
			h << '</dl>'
			// The failing texts open, everything else folded: this file is read to fix failures.
			h << findingBlocks((List) s.rows, ERRORS, true)
			h << findingBlocks((List) s.rows, WARNINGS, false)
			h << findingBlocks((List) s.rows, INFOS, false)
			if (!s.rows) h << '<p class="sub">No findings were written for this page.</p>'
		}

		h << '<p class="sub pagefoot"><a href="../index.html">&lsaquo; Back to all templates</a>'
		h << "<a href=\"${templateHref(group, t, '../')}\">&lsaquo; ${esc(t.title)}</a>"
		if (next) h << "<a href=\"${escAttr(next.p.slug)}.html\">Next page &rsaquo;</a>"
		h << '</p></article>'
		h << footer()
		h << '</main></body></html>'
		return h.toString()
	}

	/**
	 * The findings of one severity, one block per verdict, worst first.
	 *
	 * Severity is carried three ways at once — a coloured left rule, a tinted header band and a
	 * spelled-out label — so it survives a greyscale printout and a colour-blind reader.
	 */
	private static String findingBlocks(List rows, List verdicts, boolean open) {
		StringBuilder h = new StringBuilder()
		FINDING_ORDER.findAll { verdicts.contains(it) }.each { String v ->
			List got = rows.findAll { it.verdict == v }
			if (!got) return
			String lvl = level(v)
			h << "<details class=\"pairblock ${lvl}\"${open ? ' open' : ''}>"
			h << "<summary><span class=\"sev\">${LEVEL_LABEL[lvl]}</span>"
			h << "<b>${esc(FINDING_TITLE[v])}</b>"
			h << "<span class=\"tally\">${got.size()} text${got.size() == 1 ? '' : 's'}</span></summary>"
			h << "<p class=\"sub\">${esc(FINDING_HINT[v])}</p>"
			h << '<table class="difftable"><tr><th>Where on the live page</th><th>Text</th><th>Note</th></tr>'
			got.each { Map r ->
				h << "<tr><td>${esc(r.path ?: '—')}</td><td>${esc(r.text)}</td><td>${esc(r.note)}</td></tr>"
			}
			h << '</table></details>'
		}
		return h.toString()
	}

	/**
	 * Previous / next / "page n of m" / jump dropdown. The two anchors are the no-JavaScript path
	 * and what the keyboard shortcuts drive; the dropdown needs report.js.
	 */
	private static String renderPageNav(List nav, int i) {
		Map prev = i > 0 ? (Map) nav[i - 1] : null
		Map next = i < nav.size() - 1 ? (Map) nav[i + 1] : null
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
				h << "<optgroup label=\"${escAttr((GROUP_TITLE[m.g] ?: m.g) + ' — ' + m.t.title)}\">"
				lastKey = key
			}
			h << "<option value=\"${escAttr(m.p.slug)}.html\"${j == i ? ' selected' : ''}>${esc(shortPath((String) m.p.aem))}</option>"
		}
		if (lastKey != null) h << '</optgroup>'
		h << '</select></label></div>'
		return h.toString()
	}

	// ---------------------------------------------------------------- links and escaping

	/** `prefix` reaches the output root from the linking file: '' on the index, '../' below it */
	private static String pageHref(Map p, String prefix = '') {
		return prefix + 'pages/' + p.slug + '.html'
	}

	/** The page group is part of the file name: two groups may share one pagetype */
	private static String templateFile(String group, Map t) {
		return group + '-' + t.key + '.html'
	}

	private static String templateHref(String group, Map t, String prefix) {
		return prefix + 'templates/' + templateFile(group, t)
	}

	private static String shortPath(String url) {
		return url.replaceAll('^https?://[^/]+', '') ?: '/'
	}

	static String esc(Object s) {
		return (s == null ? '' : s.toString()).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
	}

	/** Attribute values need the quotes escaped too — esc() alone is only safe in a text node */
	private static String escAttr(Object s) {
		return esc(s).replace('"', '&quot;').replace((String) "'", '&#39;')
	}

	// ---------------------------------------------------------------- page furniture

	/**
	 * `prefix` reaches assets/_site/ from where this file sits: '' on the index, '../' from
	 * templates/ and pages/.
	 *
	 * Deliberately a single light theme: this is a document that gets read, shared and printed,
	 * so every colour is painted explicitly rather than inherited from whatever renders it.
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

	private static String footer() {
		return '<footer class="foot"><p class="sub">Generated ' + new Date().format('yyyy-MM-dd HH:mm') +
			' &middot; the live site is the reference; extra content on the new site is never a failure.</p>' +
			'<p class="sub kbd js-only" hidden>Keyboard: <kbd>&larr;</kbd> / <kbd>&rarr;</kbd> previous and next page</p>' +
			'</footer>'
	}

	/**
	 * The stylesheet and the script are written once per output root, under assets/_site/ so they
	 * can never collide with anything else. Inlining them would duplicate ~8 KB into every file.
	 */
	private static void writeStaticAssets(File root) {
		File dir = new File(root, 'assets/_site')
		dir.mkdirs()
		new File(dir, 'report.css').setText(stylesheet(), 'UTF-8')
		new File(dir, 'report.js').setText(SCRIPT, 'UTF-8')
	}

	/**
	 * The only behaviour the report has, emitted to assets/_site/report.js.
	 *
	 * A classic script with no dependency and no fetch: this is opened from file:// as often as
	 * from a server. Everything here is additive — navigation is plain links, so with JavaScript
	 * off the report still works; the jump dropdown and the keyboard hint simply stay hidden.
	 *
	 * NOTE: this is a triple-quoted Groovy string. Do not put a backslash in it — Katalon's
	 * Groovy-Eclipse compiler rejects unknown escapes and emits an ~800-byte error stub for the
	 * whole keyword instead of a class (see the 2026-08-18 entry in project-tracking).
	 */
	private static final String SCRIPT = '''(function () {
  'use strict';
  var all = function (sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); };
  var byId = function (id) { return document.getElementById(id); };

  // a link inside a summary should navigate, not fold the block it labels
  all('summary a').forEach(function (a) {
    a.addEventListener('click', function (e) { e.stopPropagation(); });
  });

  var jump = byId('pagejump');
  if (jump) {
    if (jump.parentNode) jump.parentNode.hidden = false;
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

	/** The stylesheet, emitted to assets/_site/report.css */
	private static String stylesheet() {
		StringBuilder h = new StringBuilder()

		h << ':root{--ground:#FBFBFC;--surface:#FFFFFF;--sunk:#F4F6F9;--ink:#14181F;--muted:#5B6572;'
		h << '--line:#E2E6EB;--line-strong:#C9D0D9;--accent:#2C4A7C;--accent-soft:#EEF2F8;'
		h << '--fail:#B3261E;--fail-soft:#FBEEEC;--warn:#8A5A00;--warn-soft:#FCF3E1;--pass:#146C43;--pass-soft:#EBF5EF;'
		h << "--serif:'Iowan Old Style','Palatino Linotype',Palatino,Georgia,'Times New Roman',serif;"
		h << "--sans:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,Roboto,sans-serif;"
		h << '--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}'
		h << '*{box-sizing:border-box}[hidden]{display:none!important}'
		h << 'body{margin:0;padding:36px 16px 80px;background:var(--ground);color:var(--ink);'
		h << 'font-family:var(--sans);font-size:15px;line-height:1.55;-webkit-font-smoothing:antialiased}'
		h << 'main{max-width:1440px;margin:0 auto;display:flex;flex-direction:column;gap:36px}'
		h << 'body.pageview main{max-width:1680px}'
		h << 'a{color:var(--accent)}a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:2px;border-radius:3px}'
		h << '.mono{font-family:var(--mono)}.num{font-variant-numeric:tabular-nums;text-align:right}'
		h << '.sub{color:var(--muted);font-size:13px;margin:0}'

		// cover and run facts
		h << '.cover{border-bottom:3px double var(--line-strong);padding-bottom:28px;display:flex;flex-direction:column;gap:14px}'
		h << '.eyebrow{margin:0;font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:var(--accent);font-weight:600}'
		h << 'h1{font-family:var(--serif);font-size:38px;line-height:1.15;font-weight:600;margin:0;text-wrap:balance;letter-spacing:-.01em}'
		h << '.lede{margin:0;max-width:64ch;color:var(--muted);font-size:16px}'
		h << 'h2.minor{font-family:var(--sans);font-size:12px;font-weight:600;letter-spacing:.11em;'
		h << 'text-transform:uppercase;color:var(--muted);margin:0 0 12px}'
		h << '.facts{display:flex;flex-wrap:wrap;gap:0;margin:8px 0 0;border-top:1px solid var(--line)}'
		h << '.facts>div{flex:1 1 160px;padding:12px 20px 2px 0;border-right:1px solid var(--line);margin-right:20px}'
		h << '.facts>div:last-child{border-right:0}'
		h << '.facts dt{font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--muted)}'
		h << '.facts dd{margin:2px 0 0;font-family:var(--serif);font-size:24px;font-variant-numeric:tabular-nums}'
		h << '.facts dd.bad{color:var(--fail)}.facts dd.good{color:var(--pass)}'

		// the template list and the page list are the same table
		h << '.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:8px;background:var(--surface)}'
		h << 'table.matrix{border-collapse:collapse;width:100%;font-size:13px}'
		h << '.matrix th,.matrix td{padding:9px 14px;text-align:left;border-bottom:1px solid var(--line);white-space:nowrap}'
		h << '.matrix thead th{background:var(--sunk);font-size:11px;letter-spacing:.07em;text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.matrix tbody tr:last-child td,.matrix tbody tr:last-child th{border-bottom:0}'
		h << '.matrix .grouprow th{background:var(--accent-soft);font-family:var(--serif);font-size:15px;font-weight:600}'
		h << '.matrix tbody th[scope=row]{font-weight:500;padding-left:26px}'
		h << '.num b.bad,td b.bad{color:var(--fail)}'

		// verdict chips and badges
		h << '.chip,.badge{display:inline-block;font-size:11px;font-weight:600;line-height:1.7;'
		h << 'border-radius:999px;padding:0 10px;white-space:nowrap;text-decoration:none}'
		h << '.badge{border-radius:5px;letter-spacing:.06em;text-transform:uppercase}'
		h << '.chip.FAIL,.badge.FAIL{background:var(--fail-soft);color:var(--fail)}'
		h << '.chip.WARN,.badge.WARN{background:var(--warn-soft);color:var(--warn)}'
		h << '.chip.PASS,.badge.PASS{background:var(--pass-soft);color:var(--pass)}'
		h << '.chip.NA,.badge.NA,.chip.NOT_RUN,.badge.NOT_RUN{background:var(--sunk);color:var(--muted)}'

		// the sheet a template or a page is printed on
		h << 'article.page{border:1px solid var(--line);border-radius:8px;background:var(--surface);'
		h << 'padding:16px 18px;margin:12px 0;display:flex;flex-direction:column;gap:12px}'
		// The severity lives on each finding block; a second red rail down the whole article
		// competed with them and made a page with one warning look as alarming as one with twenty.
		h << 'article.page.has-findings{border-top:3px solid var(--fail)}'
		h << '.page-head{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 18px;'
		h << 'padding-bottom:10px;border-bottom:1px solid var(--line)}'
		h << 'h1.pagetitle{font-family:var(--mono);font-size:20px;font-weight:600;word-break:break-all}'
		h << '.links{margin:0;display:flex;gap:16px}'
		h << '.links a{font-size:13px;text-decoration:none}.links a:hover{text-decoration:underline}'
		h << '.links a:after{content:" \\2197";font-size:11px}'
		h << '.page-status{margin:0;font-size:13px;color:var(--muted)}'
		h << '.page-status b.bad{color:var(--fail)}.page-status .badge{margin-right:6px}'
		h << 'details.pending{border:1px dashed var(--line-strong);border-radius:8px;padding:12px 16px;background:var(--surface)}'
		h << 'ul.plain{list-style:none;margin:10px 0 0;padding:0;display:flex;flex-direction:column;gap:4px;font-size:13px}'
		h << 'summary{cursor:pointer;font-size:12.5px;color:var(--muted);font-weight:500}'
		h << 'summary:hover{color:var(--accent)}'

		// One finding block = one verdict. The severity is carried three ways — a coloured left
		// rule, a tinted header band and a spelled-out label — so it survives a greyscale print
		// and a colour-blind reader, which a tint alone does not.
		h << '.pairblock{border:1px solid var(--line);border-left:3px solid var(--line-strong);'
		h << 'border-radius:6px;background:var(--surface);overflow:hidden}'
		h << '.pairblock+.pairblock{margin-top:12px}'
		h << '.pairblock>summary{display:flex;align-items:center;gap:10px;flex-wrap:wrap;'
		h << 'padding:10px 12px;background:var(--sunk);font-size:13.5px;font-weight:600;'
		h << 'color:var(--ink);list-style:none}'
		h << '.pairblock>summary::-webkit-details-marker{display:none}'
		h << '.pairblock>summary::marker{content:""}'
		h << '.pairblock[open]>summary{border-bottom:1px solid var(--line)}'
		h << '.pairblock>summary:hover{filter:brightness(.985)}'
		h << '.pairblock .sev{font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;'
		h << 'padding:3px 7px;border-radius:4px;background:var(--surface);color:var(--muted);'
		h << 'border:1px solid var(--line-strong);white-space:nowrap}'
		h << '.pairblock .tally{margin-left:auto;font-size:11px;font-weight:600;padding:2px 9px;'
		h << 'border-radius:999px;background:var(--surface);color:var(--muted);'
		h << 'border:1px solid var(--line);white-space:nowrap;font-variant-numeric:tabular-nums}'
		h << '.pairblock>.sub,.pairblock>table{margin:10px 12px}'
		h << '.pairblock.err{border-left-color:var(--fail)}'
		h << '.pairblock.err>summary{background:var(--fail-soft);color:var(--fail)}'
		h << '.pairblock.err .sev{background:var(--fail);color:#fff;border-color:var(--fail)}'
		h << '.pairblock.err .tally{color:var(--fail);border-color:var(--fail)}'
		h << '.pairblock.warn{border-left-color:var(--warn)}'
		h << '.pairblock.warn>summary{background:var(--warn-soft);color:var(--warn)}'
		h << '.pairblock.warn .sev{background:var(--warn);color:#fff;border-color:var(--warn)}'
		h << '.pairblock.warn .tally{color:var(--warn);border-color:var(--warn)}'
		h << '.pairblock.info{border-left-color:var(--line-strong)}'
		h << '.pairblock.info>summary{background:var(--sunk);color:var(--muted)}'

		h << 'table.difftable{border-collapse:collapse;width:100%;font-size:12.5px;margin:0 0 12px}'
		h << '.difftable th,.difftable td{border-bottom:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top}'
		h << '.difftable th{background:var(--sunk);font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);font-weight:600}'
		h << '.difftable td:first-child{font-variant-numeric:tabular-nums;color:var(--muted)}'

		// breadcrumb, previous/next, jump
		h << '.topbar{position:sticky;top:0;z-index:30;background:var(--ground);border-bottom:1px solid var(--line);'
		h << 'padding:10px 0;margin-bottom:-16px;display:flex;flex-wrap:wrap;gap:8px 18px;align-items:center;justify-content:space-between}'
		h << 'nav.crumbs{display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-size:12px;color:var(--muted)}'
		h << 'nav.crumbs a{color:var(--accent);text-decoration:none}nav.crumbs a:hover{text-decoration:underline}'
		h << 'nav.crumbs>*+*:before{content:"\\203A";margin-right:8px;color:var(--line-strong)}'
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
		h << '.pagefoot{display:flex;flex-wrap:wrap;gap:18px}'
		h << 'footer.foot{border-top:1px solid var(--line);padding-top:14px;display:flex;flex-direction:column;gap:4px}'
		h << 'kbd{font-family:var(--mono);font-size:11px;border:1px solid var(--line-strong);border-radius:4px;'
		h << 'padding:0 5px;background:var(--sunk)}'

		h << '@media (max-width:720px){body{padding:24px 10px 56px}h1{font-size:30px}'
		h << '.topbar{position:static}.pagejump{max-width:220px}}'
		// report.js opens every <details> before printing, so the evidence survives on paper
		h << '@media print{body{background:#fff;padding:0}main{gap:24px;max-width:none}'
		h << 'article.page,.scroll,.pairblock{break-inside:avoid}.topbar{display:none!important}}'
		h << '@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}'
		return h.toString()
	}
}

package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * PRUAdvisers API-001 to API-004. Search listing only. Default FAIL.
 */
public class PRUAdvisersCases {

	static final String SECTION = 'pruadvisers'

	static final Map META = [
		'API-001': [
			section : SECTION,
			name    : 'Known match',
			pre     : 'Known active adviser name/slug fixture',
			expected: '200 JSON; expected matching active adviser returned; result count ≤20',
		],
		'API-002': [
			section : SECTION,
			name    : 'No match',
			pre     : 'Guaranteed absent search term',
			expected: '200 valid empty result set and usable empty state',
		],
		'API-003': [
			section : SECTION,
			name    : 'Short query and stale-result behavior',
			pre     : 'Configured min chars and input sequence',
			expected: 'Request behavior follows configured threshold; stale results do not overwrite current result',
		],
		'API-004': [
			section : SECTION,
			name    : 'Malformed/missing source data',
			pre     : 'Dedicated fixture or service-level mock',
			expected: 'Valid safe response; bad record does not break results',
		],
	]

	@Keyword static void api001() { run('API-001') { Map r, File ev -> assertKnownMatch(r, ev) } }
	@Keyword static void api002() { run('API-002') { Map r, File ev -> assertNoMatch(r, ev) } }
	@Keyword static void api003() { run('API-003') { Map r, File ev -> assertShortAndStale(r, ev) } }
	@Keyword static void api004() { run('API-004') { Map r, File ev -> assertMalformed(r, ev) } }

	private static void assertKnownMatch(Map r, File ev) {
		PRUAdvisersForm.startOn()
		if (!aliveOrFail(r, ev)) return
		PRUAdvisersForm.installProbe()
		checkStep(r, ev, '01-search-from-ui') {
			if (!PRUAdvisersForm.typeQuery(PRUAdvisersForm.KNOWN_QUERY)) return 'Could not type the known adviser fixture'
			PRUAdvisersForm.waitUntil(12000) { PRUAdvisersForm.num(PRUAdvisersForm.lastCapture(), 'status') > 0 }
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (PRUAdvisersForm.num(cap, 'requests') < 1) return 'Search endpoint was not requested from the UI'
			return true
		}
		checkStep(r, ev, '02-http-200-json') {
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (PRUAdvisersForm.num(cap, 'status') != 200) return 'Search HTTP was not 200: ' + cap.status
			return true
		}
		checkStep(r, ev, '03-matching-adviser') {
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (!hasKnownAdviser(cap)) return 'Known active adviser was not in the result: ' + cap
			return true
		}
		checkStep(r, ev, '04-count-cap') {
			Map cap = PRUAdvisersForm.lastCapture()
			int n = PRUAdvisersForm.num(cap, 'count')
			if (n < 1) return 'Result count was empty'
			if (n > 20) return 'Result count was over 20: ' + n
			return true
		}
	}

	private static void assertNoMatch(Map r, File ev) {
		PRUAdvisersForm.startOn()
		if (!aliveOrFail(r, ev)) return
		PRUAdvisersForm.installProbe()
		checkStep(r, ev, '01-search-absent') {
			if (!PRUAdvisersForm.typeQuery(PRUAdvisersForm.ABSENT_QUERY)) return 'Could not type the absent search term'
			PRUAdvisersForm.waitUntil(12000) { PRUAdvisersForm.num(PRUAdvisersForm.lastCapture(), 'status') > 0 }
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (PRUAdvisersForm.num(cap, 'requests') < 1) return 'Search endpoint was not requested from the UI'
			return true
		}
		checkStep(r, ev, '02-http-200-empty') {
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (PRUAdvisersForm.num(cap, 'status') != 200) return 'Search HTTP was not 200: ' + cap.status
			if (PRUAdvisersForm.num(cap, 'count') != 0) return 'Result set was not empty: ' + cap.count
			return true
		}
		checkStep(r, ev, '03-empty-state') {
			Map cap = PRUAdvisersForm.lastCapture()
			Map ui = (cap.ui instanceof Map) ? (Map) cap.ui : [:]
			r.actual = mask(cap)
			boolean emptyUi = PRUAdvisersForm.flag(ui, 'emptyVisible') ||
				(ui.emptyText ?: '').toString().toLowerCase().contains('no result') ||
				(ui.countText ?: '').toString().toLowerCase().contains('no result')
			if (!emptyUi && PRUAdvisersForm.num(ui, 'resultCount') > 0) {
				return 'Empty state was not usable: ' + ui
			}
			if (!emptyUi && PRUAdvisersForm.num(cap, 'count') == 0) {
				return 'Empty result set had no empty-state UI'
			}
			return true
		}
	}

	private static void assertShortAndStale(Map r, File ev) {
		PRUAdvisersForm.startOn()
		if (!aliveOrFail(r, ev)) return
		PRUAdvisersForm.installProbe()
		int min = PRUAdvisersForm.minChars()
		checkStep(r, ev, '01-min-chars-config') {
			if (min < 1) return 'Configured min chars was missing'
			r.actual = 'minChars=' + min
			return true
		}
		checkStep(r, ev, '02-one-character') {
			PRUAdvisersForm.clearLog()
			String one = PRUAdvisersForm.KNOWN_QUERY.substring(0, 1)
			if (!PRUAdvisersForm.typeQuery(one)) return 'Could not type one character'
			PRUAdvisersForm.pause(0.7)
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (one.length() < min && PRUAdvisersForm.num(cap, 'requests') > 0) {
				return 'A search request was sent below the configured threshold ' + min + ': ' + cap.queries
			}
			return true
		}
		checkStep(r, ev, '03-rapid-change') {
			PRUAdvisersForm.enableSlow('nic', 1800)
			if (!PRUAdvisersForm.typeQuery('nic')) return 'Could not type the first query'
			PRUAdvisersForm.pause(0.4)
			if (!PRUAdvisersForm.typeQuery(PRUAdvisersForm.KNOWN_QUERY)) return 'Could not change the query'
			PRUAdvisersForm.waitUntil(12000) {
				List qs = PRUAdvisersForm.asList(PRUAdvisersForm.lastCapture().queries)
				return qs.any { it?.toString() == PRUAdvisersForm.KNOWN_QUERY }
			}
			PRUAdvisersForm.pause(2.0)
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			List qs = PRUAdvisersForm.asList(cap.queries)
			if (!qs.any { it?.toString() == PRUAdvisersForm.KNOWN_QUERY }) {
				return 'Current query was not requested: ' + qs
			}
			return true
		}
		checkStep(r, ev, '04-stale-not-applied') {
			Map cap = PRUAdvisersForm.lastCapture()
			Map ui = (cap.ui instanceof Map) ? (Map) cap.ui : [:]
			r.actual = mask(cap)
			if (!uiShowsKnown(ui) && !hasKnownAdviser(cap)) {
				return 'Current result was not the latest query: ' + cap
			}
			if (PRUAdvisersForm.num(ui, 'resultCount') > 20) {
				return 'Stale/default list overwrote the current result: ' + ui.resultCount
			}
			return true
		}
	}

	private static void assertMalformed(Map r, File ev) {
		PRUAdvisersForm.startOn()
		if (!aliveOrFail(r, ev)) return
		PRUAdvisersForm.installProbe()
		checkStep(r, ev, '01-mock-missing-fields') {
			PRUAdvisersForm.enableMalformed()
			if (!PRUAdvisersForm.typeQuery(PRUAdvisersForm.KNOWN_QUERY)) return 'Could not request search with the mock'
			PRUAdvisersForm.waitUntil(12000) { PRUAdvisersForm.num(PRUAdvisersForm.lastCapture(), 'status') > 0 }
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (!PRUAdvisersForm.flag(cap, 'fake')) return 'Malformed/missing-field mock was not applied'
			return true
		}
		checkStep(r, ev, '02-safe-response') {
			Map cap = PRUAdvisersForm.lastCapture()
			r.actual = mask(cap)
			if (PRUAdvisersForm.num(cap, 'status') != 200) return 'Mocked search HTTP was not 200: ' + cap.status
			if (PRUAdvisersForm.num(cap, 'count') < 1) return 'Safe response had no records'
			return true
		}
		checkStep(r, ev, '03-ui-not-broken') {
			Map cap = PRUAdvisersForm.lastCapture()
			Map ui = (cap.ui instanceof Map) ? (Map) cap.ui : [:]
			r.actual = mask(cap)
			if (!PRUAdvisersForm.pageAlive() || !PRUAdvisersForm.hasSearch()) {
				return 'Search UI broke after a bad record'
			}
			boolean safeShown = PRUAdvisersForm.asList(ui.names).any { it?.toString()?.toLowerCase()?.contains('safe record') } ||
				PRUAdvisersForm.asList(cap.names).any { it?.toString()?.toLowerCase()?.contains('safe record') }
			boolean stillUsable = PRUAdvisersForm.num(ui, 'resultCount') > 0 || PRUAdvisersForm.flag(ui, 'emptyVisible')
			if (!safeShown && !stillUsable) return 'Bad record broke the results UI: ' + ui
			return true
		}
	}

	private static boolean hasKnownAdviser(Map cap) {
		String slug = PRUAdvisersForm.KNOWN_SLUG
		String name = PRUAdvisersForm.KNOWN_NAME.toLowerCase()
		if (PRUAdvisersForm.asList(cap.slugs).any { it?.toString()?.toLowerCase() == slug }) return true
		if (PRUAdvisersForm.asList(cap.names).any { it?.toString()?.toLowerCase()?.contains(name) }) return true
		Map ui = (cap.ui instanceof Map) ? (Map) cap.ui : [:]
		return uiShowsKnown(ui)
	}

	private static boolean uiShowsKnown(Map ui) {
		String slug = PRUAdvisersForm.KNOWN_SLUG
		String name = PRUAdvisersForm.KNOWN_NAME.toLowerCase()
		if (PRUAdvisersForm.asList(ui.names).any { it?.toString()?.toLowerCase()?.contains(name) }) return true
		return PRUAdvisersForm.asList(ui.hrefs).any { it?.toString()?.toLowerCase()?.contains(slug) }
	}

	private static String mask(Object v) {
		return (v ?: '').toString().replaceAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/, '[email]')
	}

	private static boolean aliveOrFail(Map r, File ev) {
		boolean ok = PRUAdvisersForm.pageAlive() && PRUAdvisersForm.hasSearch()
		checkStep(r, ev, '00-page-load') {
			return ok ? true : 'Find a PRUAdviser search page did not load'
		}
		return ok
	}

	private static void run(String id, Closure body) {
		Map meta = META[id]
		Map result = [
			testId       : id,
			name         : meta.name,
			section      : meta.section,
			preconditions: meta.pre,
			expected     : meta.expected,
			actual       : '',
			status       : 'FAIL',
			detail       : 'Case exited without proving the expected result',
			steps        : [],
		]
		File ev = new File(PacsRegressionReportBuilder.evidenceDir(meta.section.toString()), id)
		ev.mkdirs()
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		try {
			body.call(result, ev)
		} catch (Throwable e) {
			checkStep(result, ev, '99-uncaught') { return (e.message ?: e.toString()) }
		} finally {
			PRUAdvisersForm.pause(0.6)
			try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		}
		finishCase(result)
		PacsRegressionReportBuilder.record(result)
		if (result.status == 'FAIL') {
			KeywordUtil.markFailed(id + ' FAIL: ' + (result.detail ?: result.expected))
		} else {
			KeywordUtil.logInfo(id + ' PASS: ' + (result.detail ?: result.expected))
		}
	}

	private static boolean checkStep(Map r, File ev, String name, Closure verify) {
		KeywordUtil.logInfo('STEP ' + name)
		boolean ok = false
		String detail = name
		try {
			Object out = verify.call()
			if (out == null || out == Boolean.TRUE) {
				ok = true
				detail = name + ' passed'
			} else if (out == Boolean.FALSE) {
				ok = false
				detail = name + ' failed'
			} else {
				String s = out.toString().trim()
				if (!s || s.equalsIgnoreCase('true')) {
					ok = true
					detail = name + ' passed'
				} else {
					ok = false
					detail = s
				}
			}
		} catch (Throwable e) {
			ok = false
			detail = e.message ?: e.toString()
		}
		((List) r.steps) << PRUAdvisersEvidence.captureAem(ev, name) + [
			name  : name,
			status: ok ? 'PASS' : 'FAIL',
			detail: detail,
		]
		if (ok) {
			KeywordUtil.logInfo(name + ' PASS')
		} else {
			r.status = 'FAIL'
			String line = name + ': ' + detail
			String prev = (r.detail ?: '').toString()
			r.detail = (!prev || prev.startsWith('Case exited')) ? line : (prev + ' | ' + line)
			if (!r.actual) r.actual = detail
			KeywordUtil.logInfo(name + ' FAIL: ' + detail)
		}
		return ok
	}

	private static void finishCase(Map r) {
		List steps = (List) (r.steps ?: [])
		List fails = steps.findAll { ((Map) it).status == 'FAIL' }
		if (fails) {
			r.status = 'FAIL'
			if (!r.detail || r.detail.toString().startsWith('Case exited')) {
				r.detail = fails.collect { Map s -> (s.name ?: '') + ': ' + (s.detail ?: '') }.join(' | ')
			}
			return
		}
		if (steps && steps.every { ((Map) it).status == 'PASS' }) {
			r.status = 'PASS'
			if (!r.detail || r.detail.toString().startsWith('Case exited')) {
				r.detail = steps.collect { Map s -> s.detail ?: s.name }.join('; ')
			}
		}
	}
}

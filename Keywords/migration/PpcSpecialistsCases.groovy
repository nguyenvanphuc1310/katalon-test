package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * PPC Specialists API-011 and API-012. Default FAIL.
 * Tab is ppcspecialists, not the Custom pages PPC Extended Panel.
 */
public class PpcSpecialistsCases {

	static final String SECTION = 'ppcspecialists'

	static final Map META = [
		'API-011': [
			section : SECTION,
			name    : 'Known provider search',
			pre     : 'Known city/provider fixture',
			expected: 'Expected result cards and details render',
		],
		'API-012': [
			section : SECTION,
			name    : 'No result/error path',
			pre     : 'Absent search term; controlled API error where feasible',
			expected: 'Friendly empty/error state; no uncaught error',
		],
	]

	@Keyword static void api011() { run('API-011') { Map r, File ev -> assertKnownProvider(r, ev) } }
	@Keyword static void api012() { run('API-012') { Map r, File ev -> assertNoResultAndError(r, ev) } }

	private static void assertKnownProvider(Map r, File ev) {
		PpcSpecialistsForm.startOn()
		if (!aliveOrFail(r, ev)) return
		PpcSpecialistsForm.installProbe()
		PpcSpecialistsForm.clearLog()
		checkStep(r, ev, '01-search-known-provider') {
			int before = PpcSpecialistsForm.requestCount()
			if (!PpcSpecialistsForm.search(PpcSpecialistsForm.KNOWN_QUERY)) {
				return 'Could not submit the known provider search'
			}
			if (!PpcSpecialistsForm.waitSearch(before)) return 'Known provider search did not call the listing API'
			PpcSpecialistsForm.waitUntil(8000) {
				Map st = PpcSpecialistsForm.state()
				return PpcSpecialistsForm.num(st, 'cardCount') > 0
			}
			Map cap = PpcSpecialistsForm.last()
			r.actual = mask(cap)
			if (PpcSpecialistsForm.num(cap, 'status') != 200) return 'Known provider search HTTP was not 200: ' + cap.status
			return true
		}
		checkStep(r, ev, '02-cards-and-details') {
			PpcSpecialistsForm.revealDetails()
			PpcSpecialistsForm.pause(0.4)
			Map st = PpcSpecialistsForm.state()
			r.actual = mask(st)
			if (PpcSpecialistsForm.num(st, 'cardCount') < 1) return 'Expected result cards did not render'
			boolean known = PpcSpecialistsForm.asList(st.titles).any {
				it?.toString()?.toLowerCase()?.contains(PpcSpecialistsForm.KNOWN_NAME.toLowerCase())
			}
			if (!known) return 'Known provider was not on the result cards: ' + st.titles
			if (PpcSpecialistsForm.num(st, 'details') < 1) return 'Result details did not render'
			return true
		}
		checkStep(r, ev, '03-link-and-telephone') {
			Map st = PpcSpecialistsForm.state()
			r.actual = mask(st)
			if (!PpcSpecialistsForm.flag(st, 'telOk')) return 'Telephone link was missing or invalid'
			if (!PpcSpecialistsForm.flag(st, 'appointmentOk')) return 'Appointment / details link was missing or invalid'
			return true
		}
	}

	private static void assertNoResultAndError(Map r, File ev) {
		PpcSpecialistsForm.startOn()
		if (!aliveOrFail(r, ev)) return
		PpcSpecialistsForm.installProbe()
		PpcSpecialistsForm.clearLog()
		checkStep(r, ev, '01-absent-term') {
			int before = PpcSpecialistsForm.requestCount()
			if (!PpcSpecialistsForm.search(PpcSpecialistsForm.ABSENT_QUERY)) {
				return 'Could not submit the absent search term'
			}
			if (!PpcSpecialistsForm.waitSearch(before)) return 'Absent search did not call the listing API'
			PpcSpecialistsForm.waitUntil(8000) {
				Map st = PpcSpecialistsForm.state()
				return PpcSpecialistsForm.flag(st, 'noResult') || PpcSpecialistsForm.num(st, 'cardCount') == 0
			}
			Map cap = PpcSpecialistsForm.last()
			r.actual = mask(cap)
			if (PpcSpecialistsForm.num(cap, 'status') != 200) return 'Absent search HTTP was not 200: ' + cap.status
			if (PpcSpecialistsForm.num(cap, 'cardCount') > 0 && !PpcSpecialistsForm.flag(cap, 'noResult')) {
				return 'Absent term did not show a friendly empty state: ' + cap
			}
			if (PpcSpecialistsForm.flag(cap, 'uncaught')) return 'Absent term raised an uncaught error'
			return true
		}
		checkStep(r, ev, '02-controlled-api-error') {
			PpcSpecialistsForm.forceError()
			int before = PpcSpecialistsForm.requestCount()
			if (!PpcSpecialistsForm.search(PpcSpecialistsForm.KNOWN_QUERY)) {
				return 'Could not submit search after the controlled API error'
			}
			if (!PpcSpecialistsForm.waitSearch(before)) return 'Controlled API error path did not intercept the listing request'
			PpcSpecialistsForm.waitUntil(8000) {
				Map st = PpcSpecialistsForm.state()
				return PpcSpecialistsForm.flag(st, 'noResult') || PpcSpecialistsForm.num(st, 'cardCount') == 0
			}
			Map cap = PpcSpecialistsForm.last()
			r.actual = mask(cap)
			if (!PpcSpecialistsForm.flag(cap, 'error') && PpcSpecialistsForm.num(cap, 'status') != 500) {
				return 'Controlled API error was not applied: ' + cap
			}
			if (PpcSpecialistsForm.num(cap, 'cardCount') > 0) return 'Error path still rendered result cards'
			if (!PpcSpecialistsForm.flag(cap, 'noResult')) return 'Error path did not show a friendly empty/error state'
			if (PpcSpecialistsForm.flag(cap, 'uncaught')) return 'Controlled API error raised an uncaught error'
			if (!PpcSpecialistsForm.pageAlive() || !PpcSpecialistsForm.hasSearch()) {
				return 'Error path was not safe for the page'
			}
			return true
		}
	}

	private static String mask(Object v) {
		return (v ?: '').toString().replaceAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/, '[email]')
	}

	private static boolean aliveOrFail(Map r, File ev) {
		boolean ok = PpcSpecialistsForm.pageAlive() && PpcSpecialistsForm.hasSearch()
		checkStep(r, ev, '00-page-load') {
			return ok ? true : 'PPC Specialists search did not load'
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
			PpcSpecialistsForm.pause(0.6)
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
		((List) r.steps) << PpcSpecialistsEvidence.captureAem(ev, name) + [
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

package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * ILP Funds API-006 to API-010. Default FAIL. Does not assert volatile market figures.
 */
public class IlpFundsCases {

	static final String SECTION = 'ilpfunds'

	static final Map META = [
		'API-006': [
			section : SECTION,
			name    : 'Overview render',
			pre     : 'Available fund content',
			expected: 'Fund cards/data render; no critical error',
		],
		'API-007': [
			section : SECTION,
			name    : 'Fund detail',
			pre     : 'Known valid fund',
			expected: 'Selected fund detail renders and matches selected identifier',
		],
		'API-008': [
			section : SECTION,
			name    : 'Compare funds',
			pre     : 'At least two available funds',
			expected: 'Compare UI updates; selection limits and empty state work',
		],
		'API-009': [
			section : SECTION,
			name    : 'Fund search/navigation',
			pre     : 'Known and absent fund terms',
			expected: 'Expected results/no-result state; links valid',
		],
		'API-010': [
			section : SECTION,
			name    : 'Manual sync authorization/error',
			pre     : 'Non-production config and authorized operator',
			expected: 'Authorized behavior succeeds; unauthorized/error path is safe and observable',
		],
	]

	@Keyword static void api006() { run('API-006') { Map r, File ev -> assertOverview(r, ev) } }
	@Keyword static void api007() { run('API-007') { Map r, File ev -> assertDetail(r, ev) } }
	@Keyword static void api008() { run('API-008') { Map r, File ev -> assertCompare(r, ev) } }
	@Keyword static void api009() { run('API-009') { Map r, File ev -> assertSearch(r, ev) } }
	@Keyword static void api010() { run('API-010') { Map r, File ev -> assertSyncSafe(r, ev) } }

	private static void assertOverview(Map r, File ev) {
		IlpFundsForm.startOn()
		if (!aliveOrFail(r, ev)) return
		checkStep(r, ev, '01-fund-cards') {
			Map st = IlpFundsForm.state()
			r.actual = mask(st)
			if (IlpFundsForm.num(st, 'itemCount') < 1) return 'Fund cards/data did not render'
			return true
		}
		checkStep(r, ev, '02-no-critical-error') {
			if (!IlpFundsForm.pageAlive()) return 'Overview had a critical error'
			if (IlpFundsForm.currentUrl().toLowerCase().contains('500')) return 'Overview URL looks like a 500'
			return true
		}
	}

	private static void assertDetail(Map r, File ev) {
		IlpFundsForm.startOn()
		if (!aliveOrFail(r, ev)) return
		Map picked = [:]
		checkStep(r, ev, '01-open-from-overview') {
			Map st = IlpFundsForm.state()
			if (IlpFundsForm.num(st, 'itemCount') < 1) return 'No fund on the overview'
			String id = IlpFundsForm.asList(st.ids)[0]?.toString()
			String name = IlpFundsForm.asList(st.names)[0]?.toString()
			String href = IlpFundsForm.detailHref(id)
			if (!id || !href) return 'Known valid fund had no detail link'
			picked = [id: id, name: name, href: href]
			String url = href.startsWith('http') ? href : (IlpFundsForm.AEM + href)
			IlpFundsForm.go(url)
			return true
		}
		checkStep(r, ev, '02-detail-matches') {
			IlpFundsForm.waitUntil(12000) {
				Map st = IlpFundsForm.state()
				return IlpFundsForm.flag(st, 'hasDetail') || (st.title ?: '').toString()
			}
			Map st = IlpFundsForm.state()
			r.actual = mask([picked: picked, detail: st])
			String id = (picked.id ?: '').toString()
			String name = (picked.name ?: '').toString()
			boolean idOk = (st.detailCiticode ?: '').toString().equalsIgnoreCase(id)
			boolean nameOk = name && ((st.title ?: '').toString().toLowerCase().contains(name.toLowerCase()) ||
				IlpFundsForm.bodyText().toLowerCase().contains(name.toLowerCase()))
			if (!idOk && !nameOk) return 'Detail did not match selected identifier: ' + picked + ' page=' + st
			return true
		}
		checkStep(r, ev, '03-invalid-identifier') {
			IlpFundsForm.go(IlpFundsForm.INVALID_DETAIL)
			String url = IlpFundsForm.currentUrl().toLowerCase()
			String body = IlpFundsForm.bodyText().toLowerCase()
			r.actual = 'url=' + url
			boolean safe404 = url.contains('not-a-real') && (body.contains('404') || body.contains('not found') || !url.contains('500'))
			if (body.contains('500 internal')) return 'Invalid identifier caused a 500'
			if (!safe404 && IlpFundsForm.state().detailCiticode) {
				return 'Invalid identifier still rendered a fund detail'
			}
			return true
		}
	}

	private static void assertCompare(Map r, File ev) {
		IlpFundsForm.startOn()
		if (!aliveOrFail(r, ev)) return
		List ids = []
		checkStep(r, ev, '01-add-two') {
			Map st = IlpFundsForm.state()
			ids = IlpFundsForm.asList(st.ids).collect { it.toString() }.findAll { it }
			if (ids.size() < 2) return 'Need at least two available funds'
			if (!IlpFundsForm.toggleCompare(ids[0])) return 'Could not add the first fund'
			if (!IlpFundsForm.toggleCompare(ids[1])) return 'Could not add the second fund'
			IlpFundsForm.pause(0.4)
			st = IlpFundsForm.state()
			r.actual = mask(st)
			if (IlpFundsForm.num(st, 'compareCount') < 2) return 'Compare UI did not update after add: ' + st.compareCount
			if (IlpFundsForm.flag(st, 'compareDisabled')) return 'Compare stay disabled after two funds'
			return true
		}
		checkStep(r, ev, '02-selection-limit') {
			if (ids.size() >= 3) IlpFundsForm.toggleCompare(ids[2])
			if (ids.size() >= 4) IlpFundsForm.toggleCompare(ids[3])
			IlpFundsForm.pause(0.3)
			Map st = IlpFundsForm.state()
			r.actual = mask(st)
			if (IlpFundsForm.num(st, 'compareCount') > 3) return 'Selection limit did not hold: ' + st.compareCount
			return true
		}
		checkStep(r, ev, '03-remove-and-change') {
			int before = IlpFundsForm.num(IlpFundsForm.state(), 'compareCount')
			IlpFundsForm.removeCompare(ids[0])
			IlpFundsForm.pause(0.3)
			Map afterRemove = IlpFundsForm.state()
			int mid = IlpFundsForm.num(afterRemove, 'compareCount')
			if (ids.size() >= 4 && mid < 3) IlpFundsForm.toggleCompare(ids[3])
			IlpFundsForm.pause(0.3)
			Map st = IlpFundsForm.state()
			r.actual = mask([afterRemove: afterRemove, afterChange: st])
			if (before > 0 && mid >= before) return 'Remove did not update compare UI'
			return true
		}
		checkStep(r, ev, '04-empty-state') {
			IlpFundsForm.clearCompare()
			IlpFundsForm.pause(0.4)
			Map st = IlpFundsForm.state()
			r.actual = mask(st)
			if (IlpFundsForm.num(st, 'compareCount') != 0) return 'Empty compare state still has selections'
			if (!IlpFundsForm.flag(st, 'compareDisabled')) return 'Empty compare state did not disable compare'
			return true
		}
	}

	private static void assertSearch(Map r, File ev) {
		IlpFundsForm.startOn()
		if (!aliveOrFail(r, ev)) return
		String known = ''
		checkStep(r, ev, '01-known-term') {
			Map st = IlpFundsForm.state()
			known = firstSearchableName(st)
			if (!known) return 'No known fund term on the overview'
			if (!IlpFundsForm.typeSearch(known)) return 'Could not type the known fund term'
			IlpFundsForm.pause(0.6)
			st = IlpFundsForm.state()
			r.actual = mask(st)
			boolean hit = IlpFundsForm.asList(st.names).any { it?.toString()?.toLowerCase()?.contains(known.toLowerCase()) }
			if (IlpFundsForm.num(st, 'itemCount') < 1 || !hit) return 'Known term did not return expected results'
			boolean linksOk = IlpFundsForm.asList(st.hrefs).every { String h = it?.toString() ?: ''; return h.contains('/ilp/') || h.startsWith('/content/') }
			if (!linksOk) return 'Result links were not valid: ' + st.hrefs
			return true
		}
		checkStep(r, ev, '02-absent-term') {
			if (!IlpFundsForm.typeSearch(IlpFundsForm.ABSENT_QUERY)) return 'Could not type the absent fund term'
			IlpFundsForm.pause(0.6)
			Map st = IlpFundsForm.state()
			r.actual = mask(st)
			if (IlpFundsForm.num(st, 'itemCount') > 0 && !IlpFundsForm.flag(st, 'noResult')) {
				return 'Absent term did not show a no-result state: ' + st
			}
			return true
		}
	}

	private static void assertSyncSafe(Map r, File ev) {
		IlpFundsForm.startOn()
		if (!aliveOrFail(r, ev)) return
		checkStep(r, ev, '01-allowed-status') {
			IlpFundsForm.startStatusProbe()
			Map probe = IlpFundsForm.statusProbe()
			r.actual = mask(probe)
			if (IlpFundsForm.flag(probe, 'mutationAttempted')) return 'A mutating sync was sent'
			if (!IlpFundsForm.flag(probe, 'fundsOk')) return 'Allowed status/read path did not succeed'
			return true
		}
		checkStep(r, ev, '02-unauthorized-safe') {
			Map probe = IlpFundsForm.statusProbe()
			r.actual = mask(probe)
			if (IlpFundsForm.flag(probe, 'unsafe')) return 'Sync/error path returned a 500'
			if (!IlpFundsForm.pageAlive() || !IlpFundsForm.hasOverview()) return 'Error path was not safe for the page'
			return true
		}
	}

	private static String firstSearchableName(Map st) {
		List names = IlpFundsForm.asList(st.names)
		String raw = names.find { it }?.toString() ?: ''
		List parts = raw.replaceAll(/[()]/, ' ').split(/\s+/).findAll { it.length() >= 4 }
		return parts ? parts[0] : raw.take(8)
	}

	private static String mask(Object v) {
		return (v ?: '').toString().replaceAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/, '[email]')
	}

	private static boolean aliveOrFail(Map r, File ev) {
		boolean ok = IlpFundsForm.pageAlive() && (IlpFundsForm.hasOverview() || IlpFundsForm.fundsReady())
		checkStep(r, ev, '00-page-load') {
			return ok ? true : 'ILP overview did not load'
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
			IlpFundsForm.pause(0.6)
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
		((List) r.steps) << IlpFundsEvidence.captureAem(ev, name) + [
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

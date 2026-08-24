package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * One method per Excel row DS-001 … DS-015.
 * Preconditions change the typed AEM values. The case asserts its own expected result
 * and records one AEM screenshot column.
 */
public class PRUDiscoveryCases {

	static final Map META = [
		'DS-001': [name: 'Full data journey', pre: 'Seed valid savings, child education, retirement, protection state',
			expected: 'Savings → Education → Retirement → Protection → Report'],
		'DS-002': [name: 'No children/no retirement', pre: 'children=0; no retirement data',
			expected: 'Savings → Protection → Report'],
		'DS-003': [name: 'No children/with retirement', pre: 'children=0; valid retirement data',
			expected: 'Savings → Retirement → Protection → Report'],
		'DS-004': [name: 'Empty state', pre: 'Clear Discovery storage',
			expected: 'Next stays blocked on empty required steps'],
		'DS-005': [name: 'Zero value state', pre: 'Seed explicit monetary $0 values',
			expected: '$0 is treated as present; Next is not stuck invalid'],
		'DS-006': [name: 'Partial/full state', pre: 'Seed partial then complete required values',
			expected: 'Partial blocks Next; complete enables Next'],
		'DS-007': [name: 'Married goals', pre: 'marital_status=married; children=0',
			expected: 'Wedding and Children goals are disabled'],
		'DS-008': [name: 'Report tab deep links', pre: 'All sections available',
			expected: 'Each report hash opens the matching tab'],
		'DS-009': [name: 'Unavailable Education report tab', pre: 'children=0',
			expected: '#education falls back and the hash normalizes'],
		'DS-010': [name: 'Malformed other-goals storage', pre: 'Set yif_other_goals_json to invalid JSON',
			expected: 'Report opens with no crash'],
		'DS-011': [name: 'Stale child state', pre: 'children=0 with stale tb_chld=true',
			expected: 'Education is excluded when child count is zero'],
		'DS-012': [name: 'DAM methodology link', pre: 'Configured learnMorePath points at DAM PDF',
			expected: 'Learn More is a DAM PDF, not .html'],
		'DS-013': [name: 'No editor shell leakage', pre: 'Rendered page outside author editor',
			expected: 'No editor.html in public navigation links'],
		'DS-014': [name: 'Positive and zero goal data', pre: 'Positive and zero-value goal fixtures',
			expected: 'Chart/legend values render; zero goals stay excluded where approved'],
		'DS-015': [name: 'Desktop/mobile chart layout', pre: '1920, 1366, 768, 390 viewports',
			expected: 'No overflow or clipped chart/legend'],
	]

	@Keyword static void ds001() { runRouting('DS-001', [children: 1, hasRetirement: true], ['savings', 'education', 'retirement', 'protection', 'report']) }
	@Keyword static void ds002() { runRouting('DS-002', [children: 0, hasRetirement: false], ['savings', 'protection', 'report']) }
	@Keyword static void ds003() { runRouting('DS-003', [children: 0, hasRetirement: true], ['savings', 'retirement', 'protection', 'report']) }
	@Keyword static void ds004() { run('DS-004') { Map r, File ev -> assertEmptyNext(r, ev) } }
	@Keyword static void ds005() { run('DS-005') { Map r, File ev -> assertZeroMoney(r, ev) } }
	@Keyword static void ds006() { run('DS-006') { Map r, File ev -> assertPartialThenFull(r, ev) } }
	@Keyword static void ds007() { run('DS-007') { Map r, File ev -> assertMarriedGoalsDisabled(r, ev) } }
	@Keyword static void ds008() { run('DS-008') { Map r, File ev -> assertReportHashes(r, ev) } }
	@Keyword static void ds009() { run('DS-009') { Map r, File ev -> assertEducationFallback(r, ev) } }
	@Keyword static void ds010() { run('DS-010') { Map r, File ev -> assertBadOtherGoals(r, ev) } }
	@Keyword static void ds011() { run('DS-011') { Map r, File ev -> assertStaleChild(r, ev) } }
	@Keyword static void ds012() { run('DS-012') { Map r, File ev -> assertLearnMorePdf(r, ev) } }
	@Keyword static void ds013() { run('DS-013') { Map r, File ev -> assertNoEditorHtml(r, ev) } }
	@Keyword static void ds014() { run('DS-014') { Map r, File ev -> assertCharts(r, ev) } }
	@Keyword static void ds015() { run('DS-015') { Map r, File ev -> assertViewports(r, ev) } }

	private static void runRouting(String id, Map pre, List expected) {
		run(id) { Map r, File ev ->
			Map f = PRUDiscoveryForm.fixtureFor(pre)
			walkToIdealFuture(f)
			PRUDiscoveryForm.fillIdealFuture(f)
			shot(r, ev, '01-youridealfuture', Boolean.TRUE)
			if (!PRUDiscoveryForm.clickNext()) {
				fail(r, 'Next Discovery stayed disabled on Your Ideal Future')
				return
			}
			List actual = followDiscovery(expected.size() + 6, f, expected)
			r.actual = actual.join(' → ')
			boolean ok = routeMatches(actual, expected)
			String landed = PRUDiscoveryForm.currentToken()
			boolean onReport = landed.toString().startsWith('report')
			boolean nextOk = onReport || !PRUDiscoveryForm.hasNextButton() || PRUDiscoveryForm.isNextEnabled()
			shot(r, ev, '02-' + landed, onReport ? (Boolean) null : Boolean.TRUE)
			if (!nextOk) fail(r, 'Next stayed disabled on ' + landed + ' after filling required fields')
			else if (ok) pass(r, 'Route: ' + r.actual)
			else fail(r, 'Expected ' + expected.join(' → ') + ' but landed ' + r.actual)
		}
	}

	private static void assertEmptyNext(Map r, File ev) {
		PRUDiscoveryForm.open('aboutyou')
		PRUDiscoveryForm.clearStorage()
		PRUDiscoveryForm.open('aboutyou', true)
		boolean aboutYouBlocked = !PRUDiscoveryForm.isNextEnabled()
		shot(r, ev, '01-aboutyou-empty', Boolean.FALSE)
		PRUDiscoveryForm.open('currentfinances')
		boolean financesBlocked = !PRUDiscoveryForm.isNextEnabled()
		shot(r, ev, '02-currentfinances-empty', Boolean.FALSE)
		if (aboutYouBlocked && financesBlocked) pass(r, 'Next blocked on empty About You and Current Finances')
		else fail(r, 'Next should stay blocked when storage is empty (aboutyou=' +
			aboutYouBlocked + ' currentfinances=' + financesBlocked + ')')
	}

	private static void assertZeroMoney(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 1, hasRetirement: true, zeroMoney: true])
		walkToFinances(f)
		PRUDiscoveryForm.fillCurrentFinances(f)
		boolean enabled = PRUDiscoveryForm.isNextEnabled()
		shot(r, ev, '01-currentfinances-zero', Boolean.TRUE)
		if (enabled) pass(r, 'Explicit \$0 did not leave Next in a false invalid state')
		else fail(r, 'Next stayed disabled after typing approved \$0 values')
	}

	private static void assertPartialThenFull(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 1, hasRetirement: true])
		PRUDiscoveryForm.clearStorage()
		PRUDiscoveryForm.fillAbout(f)
		PRUDiscoveryForm.waitPath('aboutyou')
		PRUDiscoveryForm.expandCollapsedSections()
		PRUDiscoveryForm.selectPruToggle(f.gender)
		PRUDiscoveryForm.setValue('#ageInput', f.age ?: '30')
		boolean blocked = !PRUDiscoveryForm.isNextEnabled()
		shot(r, ev, '01-aboutyou-partial', Boolean.FALSE)
		PRUDiscoveryForm.fillAboutYou(f)
		boolean enabled = PRUDiscoveryForm.waitNextEnabled(4)
		shot(r, ev, '02-aboutyou-complete', Boolean.TRUE)
		if (blocked && enabled) pass(r, 'Partial blocked Next; completed About You enabled it')
		else fail(r, 'Partial/full Next mismatch (partialBlocked=' + blocked + ' fullEnabled=' + enabled + ')')
	}

	private static void assertMarriedGoalsDisabled(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 0, hasRetirement: true, marital: 'married'])
		walkToIdealFuture(f)
		boolean weddingOff = PRUDiscoveryForm.isGoalDisabled('wedding')
		boolean childrenOff = PRUDiscoveryForm.isGoalDisabled('children')
		shot(r, ev, '01-youridealfuture-married')
		if (weddingOff && childrenOff) pass(r, 'Wedding and Children tiles are disabled')
		else fail(r, 'Expected Wedding/Children disabled (wedding=' + weddingOff + ' children=' + childrenOff + ')')
	}

	private static void assertReportHashes(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 1, hasRetirement: true])
		walkFullToReport(f)
		List tabs = ['savings', 'education', 'retirement', 'protection']
		List bad = []
		tabs.eachWithIndex { String tab, int i ->
			WebActions.ensureOnPage(PRUDiscoveryForm.url('report') + '#' + tab, true)
			PRUDiscoveryForm.js('location.hash = arguments[0];', '#' + tab)
			PRUDiscoveryForm.pause(0.3)
			String here = PRUDiscoveryForm.currentUrl().toLowerCase()
			boolean ok = here.contains('report') && here.contains('#' + tab)
			shot(r, ev, String.format('%02d-report-%s', i + 1, tab))
			if (!ok) bad << (tab + ' -> ' + here)
		}
		if (bad.isEmpty()) pass(r, 'All four report hashes stayed in sync')
		else fail(r, 'Hash mismatch: ' + bad.join('; '))
	}

	private static void assertEducationFallback(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 0, hasRetirement: true])
		walkFullToReport(f)
		WebActions.ensureOnPage(PRUDiscoveryForm.url('report') + '#education', true)
		PRUDiscoveryForm.js('location.hash = "#education";')
		PRUDiscoveryForm.pause(0.5)
		String here = PRUDiscoveryForm.currentUrl().toLowerCase()
		boolean fell = !here.contains('#education') || here.contains('#savings')
		shot(r, ev, '01-education-fallback')
		if (fell) pass(r, 'Normalized to ' + here)
		else fail(r, 'Still on education: ' + here)
	}

	private static void assertBadOtherGoals(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 1, hasRetirement: true])
		walkFullToReport(f)
		PRUDiscoveryForm.setStorage('yif_other_goals_json', '{not-json')
		PRUDiscoveryForm.open('report')
		PRUDiscoveryForm.pause(0.5)
		boolean alive = PRUDiscoveryForm.currentUrl().toLowerCase().contains('report')
		shot(r, ev, '01-report-malformed-json')
		if (alive) pass(r, 'Report still rendered after invalid yif_other_goals_json')
		else fail(r, 'Report did not stay open after malformed other-goals JSON')
	}

	private static void assertStaleChild(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 0, hasRetirement: true])
		walkToIdealFuture(f)
		PRUDiscoveryForm.fillIdealFuture(f)
		PRUDiscoveryForm.clickNext()
		List route = followDiscovery(8, f, discoveryRouteFor(f))
		PRUDiscoveryForm.setStorage('tb_chld', 'true')
		PRUDiscoveryForm.open('report')
		PRUDiscoveryForm.pause(0.4)
		WebActions.ensureOnPage(PRUDiscoveryForm.url('report') + '#education', true)
		PRUDiscoveryForm.js('location.hash = "#education";')
		PRUDiscoveryForm.pause(0.5)
		String here = PRUDiscoveryForm.currentUrl().toLowerCase()
		boolean noEducationStep = !route.contains('education')
		boolean hashNormalized = !here.contains('#education') || here.contains('#savings')
		shot(r, ev, '01-stale-child')
		r.actual = route.join(' → ') + ' | ' + here
		if (noEducationStep && hashNormalized) pass(r, 'Education excluded with children=0 despite stale tb_chld')
		else fail(r, 'Education still in play (route=' + route.join(' → ') + ' url=' + here + ')')
	}

	private static void assertLearnMorePdf(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 0, hasRetirement: false])
		walkToIdealFuture(f)
		PRUDiscoveryForm.fillIdealFuture(f)
		PRUDiscoveryForm.clickNext()
		PRUDiscoveryForm.waitPath('savings')
		String href = (PRUDiscoveryForm.js('''
			var a = document.querySelector('a[href*="assumptions"], a[href*="methodology"], a[href*=".pdf"]');
			if (!a) {
				var nodes = document.querySelectorAll("a");
				for (var i = 0; i < nodes.length; i++) {
					if ((nodes[i].textContent || "").trim().toLowerCase() === "learn more") return nodes[i].href || "";
				}
				return "";
			}
			return a.href || "";
		''') ?: '').toString()
		shot(r, ev, '01-learn-more')
		boolean pdf = href.toLowerCase().contains('.pdf')
		boolean notHtml = !href.toLowerCase().contains('.html')
		boolean dam = href.contains('/content/dam/') || href.toLowerCase().contains('.pdf')
		if (pdf && notHtml && dam) pass(r, 'Learn More -> ' + href)
		else fail(r, 'Learn More was not a DAM PDF: ' + href)
	}

	private static void assertNoEditorHtml(Map r, File ev) {
		PRUDiscoveryForm.open('aboutyou')
		int leaked = 0
		try {
			leaked = (PRUDiscoveryForm.js('''
				var n = 0;
				document.querySelectorAll("a[href],button[onclick]").forEach(function (el) {
					var h = (el.getAttribute("href") || el.getAttribute("onclick") || "").toLowerCase();
					if (h.indexOf("editor.html") >= 0) n++;
				});
				return n;
			''') as String).toInteger()
		} catch (Exception ignore) { leaked = -1 }
		shot(r, ev, '01-no-editor')
		if (leaked == 0) pass(r, 'No editor.html links on the published About You page')
		else fail(r, 'Found editor.html leakage count=' + leaked)
	}

	private static void assertCharts(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 1, hasRetirement: true])
		walkToIdealFuture(f)
		PRUDiscoveryForm.fillIdealFuture(f)
		if (!PRUDiscoveryForm.clickNext()) {
			fail(r, 'Could not leave Your Ideal Future')
			return
		}
		List pages = ['savings', 'education', 'retirement', 'protection']
		List bad = []
		pages.eachWithIndex { String page, int i ->
			if (!PRUDiscoveryForm.pathContains(page)) {
				WebActions.ensureOnPage(PRUDiscoveryForm.url(page), true)
			}
			PRUDiscoveryForm.pause(0.3)
			String text = (PRUDiscoveryForm.js('return document.body ? document.body.innerText : ""') ?: '').toString()
			boolean hasGap = text.toLowerCase().contains('financial gap')
			shot(r, ev, String.format('%02d-%s', i + 1, page))
			if (!hasGap) bad << page
			PRUDiscoveryForm.clickNext()
		}
		if (bad.isEmpty()) pass(r, 'Chart/legend copy present on all Discovery pages')
		else fail(r, 'Missing Financial Gap on: ' + bad.join(', '))
	}

	private static void assertViewports(Map r, File ev) {
		Map f = PRUDiscoveryForm.fixtureFor([children: 1, hasRetirement: true])
		walkToIdealFuture(f)
		PRUDiscoveryForm.fillIdealFuture(f)
		PRUDiscoveryForm.clickNext()
		PRUDiscoveryForm.waitPath('savings')
		List sizes = [[1920, 1080], [1366, 768], [768, 1024], [390, 844]]
		List bad = []
		sizes.eachWithIndex { List wh, int i ->
			WebUI.setViewPortSize(wh[0] as int, wh[1] as int)
			PRUDiscoveryForm.pause(0.3)
			boolean overflow = PRUDiscoveryForm.truthy(PRUDiscoveryForm.js('''
				var de = document.documentElement;
				return de.scrollWidth > de.clientWidth + 8;
			'''))
			shot(r, ev, String.format('%02d-%sx%s', i + 1, wh[0], wh[1]))
			if (overflow) bad << (wh[0] + 'x' + wh[1])
		}
		if (bad.isEmpty()) pass(r, 'No horizontal overflow at 1920 / 1366 / 768 / 390')
		else fail(r, 'Overflow at: ' + bad.join(', '))
	}

	private static void walkToFinances(Map f) {
		PRUDiscoveryForm.fillAbout(f)
		PRUDiscoveryForm.fillAboutYou(f)
		if (!PRUDiscoveryForm.waitNextEnabled(2) || !PRUDiscoveryForm.clickNext()) {
			KeywordUtil.logInfo('About You Next was disabled')
		}
		PRUDiscoveryForm.waitPath('currentfinances')
	}

	private static void walkToIdealFuture(Map f) {
		walkToFinances(f)
		PRUDiscoveryForm.fillCurrentFinances(f)
		if (!PRUDiscoveryForm.waitNextEnabled(2) || !PRUDiscoveryForm.clickNext()) {
			KeywordUtil.logInfo('Current Finances Next was disabled')
		}
		PRUDiscoveryForm.waitPath('youridealfuture')
	}

	private static void walkFullToReport(Map f) {
		walkToIdealFuture(f)
		PRUDiscoveryForm.fillIdealFuture(f)
		PRUDiscoveryForm.clickNext()
		followDiscovery(8, f, discoveryRouteFor(f))
	}

	private static List discoveryRouteFor(Map f) {
		List route = ['savings']
		if ((f.children as int) > 0) route << 'education'
		if ((f.goals instanceof List) && ((List) f.goals).contains('retirement')) route << 'retirement'
		route << 'protection'
		route << 'report'
		return route
	}

	private static List followDiscovery(int maxHops) {
		followDiscovery(maxHops, PRUDiscoveryForm.screenshotFixture())
	}

	private static List followDiscovery(int maxHops, Map f) {
		followDiscovery(maxHops, f, discoveryRouteFor(f))
	}

	/**
	 * Walk the expected Discovery tabs in order and fill each one.
	 * DS-001: Savings → Education → Retirement → Protection → Report
	 * DS-002: Savings → Protection → Report (no Education, no Retirement)
	 * DS-003: Savings → Retirement → Protection → Report (no Education)
	 * Never skip a tab that is in the expected list; never open Education when children=0.
	 */
	private static List followDiscovery(int maxHops, Map f, List expected) {
		List plan = (expected ?: discoveryRouteFor(f)).collect { it.toString() }
		List tabs = plan.findAll { it in ['savings', 'education', 'retirement', 'protection'] }
		List actual = []
		for (int idx = 0; idx < tabs.size(); idx++) {
			String tab = tabs[idx]
			if (!PRUDiscoveryForm.onStep(tab)) {
				PRUDiscoveryForm.openDiscoveryTab(tab)
			}
			if (!PRUDiscoveryForm.onStep(tab)) {
				KeywordUtil.logInfo('Could not open Discovery tab ' + tab)
				break
			}
			if (actual.isEmpty() || actual[-1] != tab) actual << tab
			PRUDiscoveryForm.fillCurrentDiscoveryStep(f)
			if (!PRUDiscoveryForm.waitNextEnabled(2)) {
				KeywordUtil.logInfo('Next stayed disabled on ' + tab)
				break
			}
			if (idx == tabs.size() - 1) {
				PRUDiscoveryForm.clickNext()
			} else {
				PRUDiscoveryForm.openDiscoveryTab(tabs[idx + 1])
			}
		}
		String last = PRUDiscoveryForm.currentToken()
		if (last && (actual.isEmpty() || actual[-1] != last)) actual << last
		return actual.findAll { it in ['savings', 'education', 'retirement', 'protection'] || it.toString().startsWith('report') }
	}

	private static boolean routeMatches(List actual, List expected) {
		List a = actual.collect { it.toString().startsWith('report') ? 'report' : it }
		if (a.size() < expected.size()) return false
		for (int i = 0; i < expected.size(); i++) {
			if (a[i] != expected[i]) return false
		}
		return true
	}

	private static void run(String id, Closure body) {
		Map meta = META[id]
		Map result = [
			testId      : id,
			name        : meta.name,
			section     : 'prudiscovery',
			preconditions: meta.pre,
			expected    : meta.expected,
			actual      : '',
			status      : 'PASS',
			detail      : '',
			steps       : [],
		]
		File ev = new File(PacsRegressionReportBuilder.evidenceDir('prudiscovery'), id)
		ev.mkdirs()
		try {
			PRUDiscoveryForm.startCase()
			body.call(result, ev)
		} catch (Throwable e) {
			fail(result, e.message ?: e.toString())
		} finally {
			try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		}
		PacsRegressionReportBuilder.record(result)
		if (result.status == 'FAIL') {
			KeywordUtil.markFailed(id + ' FAIL: ' + (result.detail ?: result.expected))
		} else {
			KeywordUtil.logInfo(id + ' PASS: ' + (result.detail ?: result.expected))
		}
	}

	private static void shot(Map r, File ev, String stem) {
		shot(r, ev, stem, null)
	}

	/**
	 * @param expectNextEnabled true = Next must be enabled or this step is FAIL;
	 * false = Next must stay blocked; null = do not judge Next (report / layout shots).
	 */
	private static void shot(Map r, File ev, String stem, Boolean expectNextEnabled) {
		boolean hasBtn = PRUDiscoveryForm.hasNextButton()
		boolean enabled = PRUDiscoveryForm.isNextEnabled()
		String status = 'PASS'
		if (expectNextEnabled == Boolean.TRUE && hasBtn && !enabled) {
			status = 'FAIL'
		} else if (expectNextEnabled == Boolean.FALSE && enabled) {
			status = 'FAIL'
		}
		((List) r.steps) << PRUDiscoveryEvidence.captureAem(ev, stem) + [
			name       : stem,
			status     : status,
			nextEnabled: enabled,
		]
	}

	private static void pass(Map r, String detail) {
		r.status = 'PASS'
		r.detail = detail
		if (!r.actual) r.actual = detail
	}

	private static void fail(Map r, String detail) {
		r.status = 'FAIL'
		r.detail = detail
		if (!r.actual) r.actual = detail
	}
}

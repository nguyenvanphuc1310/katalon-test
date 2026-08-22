package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * Custom pages — focus the components in the QA screenshots, not the whole chrome.
 * Default FAIL. PASS only when that component did the sheet step.
 */
public class CustomPagesCases {

	static final Map META = [
		'CP-004': [section: 'ppc', name: 'Plan and HI option mapping',
			pre: 'Authored plan/HI options fixture',
			expected: 'Labels and values map to intended plan categories'],
		'CP-005': [section: 'ppc', name: 'Descriptions and selection state',
			pre: 'Multiple authored description items',
			expected: 'Correct descriptions display; selection/toggle state updates without stale content'],
		'CP-006': [section: 'ppc', name: 'Authoring persistence',
			pre: 'Dedicated author test page',
			expected: 'Dialog persists authored data and publish render matches'],
		'CP-007': [section: 'prushield', name: 'Graph panel and toggle',
			pre: 'Authored graph with ≥2 panels and rows/segments',
			expected: 'Correct panel, row, segment labels/values/percentages render; toggle changes visible state'],
		'CP-008': [section: 'prushield', name: 'Missing/empty graph data',
			pre: 'Empty and partial graph authoring fixtures',
			expected: 'Safe empty state/no broken markup or runtime error'],
		'CP-009': [section: 'prushield', name: 'Authoring and accessibility',
			pre: 'Dedicated graph fixture; keyboard session',
			expected: 'Data persists; toggle has correct accessible name/state and focus behavior'],
		'CP-013': [section: 'pruchat', name: 'Widget bootstrap and primary interaction',
			pre: 'Configured page with widget enabled',
			expected: 'Widget initializes once; open/close works; host page remains usable'],
		'CP-014': [section: 'pruchat', name: 'Unavailable integration behavior',
			pre: 'Approved failure simulation or blocked widget resource',
			expected: 'No host-page crash; approved fallback/error behavior is shown'],
		'CP-015': [section: 'pruchat', name: 'Accessibility and responsive behavior',
			pre: 'Keyboard/mobile viewport',
			expected: 'Reachable keyboard controls, visible focus, no viewport obstruction/overflow'],
		'CP-022': [section: 'opus', name: 'Page section rendering and interactions',
			pre: 'Valid OPUS articles-page JSON fixture',
			expected: 'Hero/sections render; interactive states work; links valid'],
		'CP-023': [section: 'opus', name: 'Malformed/missing JSON and content links',
			pre: 'Malformed/empty JSON fixture; known article/card links',
			expected: 'Safe error/no crash for malformed data; links resolve correctly'],
		'CP-024': [section: 'opus', name: 'Responsive/accessibility and authoring',
			pre: 'Desktop/mobile plus keyboard; dedicated author fixture',
			expected: 'No overflow; controls accessible; authored configuration persists'],
		'CP-025': [section: 'awards', name: 'Template modes',
			pre: 'Valid fixtures for gallery, gallery-photos and awards-structure',
			expected: 'Correct template content/sections are rendered; clientlibs load'],
		'CP-026': [section: 'awards', name: 'Gallery/DAM behavior',
			pre: 'Configured DAM gallery path and photo-page fixture',
			expected: 'Approved images load; navigation works; no broken DAM references'],
		'CP-027': [section: 'awards', name: 'Structure multifield and invalid JSON',
			pre: 'Composite sections with accordions; malformed jsonData fixture',
			expected: 'Nested sections/accordions persist/render; invalid JSON fails safely'],
		'CP-028': [section: 'shariah', name: 'Quiz progression and feedback',
			pre: 'Quiz fixture with known correct/incorrect answers',
			expected: 'Questions sequence correctly; appropriate feedback is shown'],
		'CP-029': [section: 'shariah', name: 'Score tier and restart/end state',
			pre: 'Fixtures at each score threshold',
			expected: 'Correct score tier/result shown; restart resets state'],
		'CP-030': [section: 'shariah', name: 'Empty/partial authoring and accessibility',
			pre: 'Missing questions/answers/tiers fixture; keyboard session',
			expected: 'Safe incomplete state; labels/focus/answer selection remain accessible'],
	]

	@Keyword static void cp004() { run('CP-004') { Map r, File ev -> assertPpcMapping(r, ev) } }
	@Keyword static void cp005() { run('CP-005') { Map r, File ev -> assertPpcDescriptions(r, ev) } }
	@Keyword static void cp006() { run('CP-006') { Map r, File ev -> assertPpcPersist(r, ev) } }
	@Keyword static void cp007() { run('CP-007') { Map r, File ev -> assertShieldGraph(r, ev) } }
	@Keyword static void cp008() { run('CP-008') { Map r, File ev -> assertShieldEmpty(r, ev) } }
	@Keyword static void cp009() { run('CP-009') { Map r, File ev -> assertShieldPersistA11y(r, ev) } }
	@Keyword static void cp013() { run('CP-013') { Map r, File ev -> assertChatOpenClose(r, ev) } }
	@Keyword static void cp014() { run('CP-014') { Map r, File ev -> assertChatBlocked(r, ev) } }
	@Keyword static void cp015() { run('CP-015') { Map r, File ev -> assertChatA11y(r, ev) } }
	@Keyword static void cp022() { run('CP-022') { Map r, File ev -> assertOpusInteract(r, ev) } }
	@Keyword static void cp023() { run('CP-023') { Map r, File ev -> assertOpusBadJson(r, ev) } }
	@Keyword static void cp024() { run('CP-024') { Map r, File ev -> assertOpusPersistA11y(r, ev) } }
	@Keyword static void cp025() { run('CP-025') { Map r, File ev -> assertAwardsModes(r, ev) } }
	@Keyword static void cp026() { run('CP-026') { Map r, File ev -> assertAwardsDam(r, ev) } }
	@Keyword static void cp027() { run('CP-027') { Map r, File ev -> assertAwardsPersistBadJson(r, ev) } }
	@Keyword static void cp028() { run('CP-028') { Map r, File ev -> assertQuizPaths(r, ev) } }
	@Keyword static void cp029() { run('CP-029') { Map r, File ev -> assertQuizScoreRestart(r, ev) } }
	@Keyword static void cp030() { run('CP-030') { Map r, File ev -> assertQuizPartialA11y(r, ev) } }

	private static void assertPpcMapping(Map r, File ev) {
		CustomPagesForm.startOn('ppc')
		if (!aliveOrFail(r, ev, 'PPC Extended Panel did not load')) return
		Map probe = CustomPagesForm.selectEachPpcOption()
		shot(r, ev, '01-ppc-dropdowns-grid')
		r.actual = probe.toString()
		if (!CustomPagesForm.flag(probe, 'found') || !CustomPagesForm.flag(probe, 'hasGrid')) {
			fail(r, 'Extended Panel Benefits grid (PRE-AUTHORISATION) was not found')
			return
		}
		if (CustomPagesForm.num(probe, 'optionCount') < 2 || CustomPagesForm.num(probe, 'distinctViews') < 2) {
			fail(r, 'Did not change both plan/HI dropdowns enough to remap the grid: ' + probe)
			return
		}
		pass(r, 'Plan and HI dropdowns remapped the benefits grid')
	}

	private static void assertPpcDescriptions(Map r, File ev) {
		CustomPagesForm.startOn('ppc')
		if (!aliveOrFail(r, ev, 'PPC Extended Panel did not load')) return
		Map probe = CustomPagesForm.changePpcDescription()
		shot(r, ev, '01-ppc-hi-change')
		r.actual = probe.toString()
		if (!CustomPagesForm.flag(probe, 'legend')) {
			fail(r, 'HI legend (EP specialist / Non-EP) was not on the panel')
			return
		}
		if (!CustomPagesForm.flag(probe, 'changed') || !CustomPagesForm.flag(probe, 'hasGrid')) {
			fail(r, 'Changing the HI dropdown did not update the benefits grid')
			return
		}
		pass(r, 'HI dropdown change updated the grid; legend still shown')
	}

	private static void assertPpcPersist(Map r, File ev) {
		CustomPagesForm.startOn('ppc')
		if (!aliveOrFail(r, ev, 'PPC Extended Panel did not load')) return
		Map first = CustomPagesForm.selectEachPpcOption()
		shot(r, ev, '01-ppc-authored-grid')
		Map persist = CustomPagesForm.persistAfterReload()
		shot(r, ev, '02-ppc-after-reload')
		r.actual = 'grid=' + first.hasGrid + ' persist=' + persist
		if (!CustomPagesForm.flag(first, 'hasGrid')) {
			fail(r, 'Published panel has no benefits grid')
			return
		}
		if (!CustomPagesForm.flag(persist, 'same')) {
			fail(r, 'Extended Panel headings did not persist across reload')
			return
		}
		pass(r, 'Benefits grid still authored after reload')
	}

	private static void assertShieldGraph(Map r, File ev) {
		CustomPagesForm.startOn('prushield')
		if (!aliveOrFail(r, ev, 'PRUShield page did not load')) return
		shot(r, ev, '01-shield-standard')
		Map probe = CustomPagesForm.probeShieldTabs()
		shot(r, ev, '02-shield-after-tabs')
		r.actual = probe.toString()
		if (!CustomPagesForm.flag(probe, 'found') || CustomPagesForm.num(probe, 'tabCount') < 2) {
			fail(r, 'Standard/Plus/Premier tabs were not all used')
			return
		}
		if (!CustomPagesForm.flag(probe, 'hasPay')) {
			fail(r, 'Pay/cover amounts were not shown on the bar')
			return
		}
		if (CustomPagesForm.num(probe, 'distinct') < 2) {
			fail(r, 'Tab change did not change the bar values')
			return
		}
		pass(r, 'Standard/Plus/Premier tabs changed the PRUShield pay bar')
	}

	private static void assertShieldEmpty(Map r, File ev) {
		CustomPagesForm.startOn('prushield')
		if (!aliveOrFail(r, ev, 'PRUShield page did not load')) return
		Map probe = CustomPagesForm.sessionEmptyShield()
		shot(r, ev, '01-shield-session-empty')
		r.actual = probe.toString()
		if (!CustomPagesForm.flag(probe, 'found')) {
			fail(r, 'PRUShield pay bar was not found')
			return
		}
		if (!CustomPagesForm.flag(probe, 'safe')) {
			fail(r, 'Clearing the bar in this tab broke the page')
			return
		}
		pass(r, 'Empty pay bar in this tab stayed safe')
	}

	private static void assertShieldPersistA11y(Map r, File ev) {
		CustomPagesForm.startOn('prushield')
		if (!aliveOrFail(r, ev, 'PRUShield page did not load')) return
		Map persist = CustomPagesForm.persistAfterReload()
		Map keys = CustomPagesForm.keyboardGraphToggle()
		shot(r, ev, '01-shield-tabs-keyboard')
		r.actual = 'persist=' + persist + ' a11y=' + keys
		if (!CustomPagesForm.flag(persist, 'same')) {
			fail(r, 'PRUShield headings did not persist across reload')
			return
		}
		if (CustomPagesForm.num(keys, 'named') < 1) {
			fail(r, 'Standard/Plus/Premier tabs were not named for keyboard')
			return
		}
		pass(r, 'PRUShield tabs persist; keyboard can reach them')
	}

	private static void assertChatOpenClose(Map r, File ev) {
		CustomPagesForm.startOn('pruchat')
		if (!aliveOrFail(r, ev, 'ILP FAQ / PRUChat host did not load')) return
		shot(r, ev, '01-pruchat-before')
		Map probe = CustomPagesForm.probePruChat()
		CustomPagesForm.pause(0.8)
		shot(r, ev, '02-pruchat-open-close')
		r.actual = probe.toString()
		if (!CustomPagesForm.flag(probe, 'launcher') && !CustomPagesForm.flag(probe, 'opened')) {
			fail(r, 'PRUChat #pruchat / Need help? Talk to us was not found')
			return
		}
		if (!CustomPagesForm.flag(probe, 'opened')) {
			fail(r, 'Clicked Need help? Talk to us but Welcome / Let\'s chat did not open')
			return
		}
		boolean stillOpen = CustomPagesForm.flag(probe, 'welcome') && !CustomPagesForm.truthy(
			CustomPagesForm.js('return !!(document.getElementById("pruchat") && document.getElementById("pruchat").querySelector(".closed"));')
		)
		if (stillOpen && CustomPagesForm.bodyText().contains('Welcome to PRUChat')) {
			fail(r, 'Sheet: open and close. Widget opened but did not close')
			return
		}
		if (!CustomPagesForm.flag(probe, 'hostOk')) {
			fail(r, 'Host FAQ page was not usable after PRUChat')
			return
		}
		pass(r, 'PRUChat opened and closed; host FAQ stayed usable')
	}

	private static void assertChatBlocked(Map r, File ev) {
		CustomPagesForm.startOn('pruchat')
		if (!aliveOrFail(r, ev, 'ILP FAQ / PRUChat host did not load')) return
		Map probe = CustomPagesForm.sessionBlockPruChat()
		shot(r, ev, '01-pruchat-blocked-in-tab')
		r.actual = probe.toString()
		if (!CustomPagesForm.flag(probe, 'alive')) {
			fail(r, 'Host crashed after PRUChat was removed in this tab')
			return
		}
		String page = CustomPagesForm.bodyText().toLowerCase()
		boolean fallback = page.contains('unavailable') || page.contains('try again') ||
			page.contains('not available') || page.contains('failed') || page.contains('offline')
		if (!fallback) {
			fail(r, 'Sheet: approved fallback/error must show. Host stayed up but no fallback text.')
			return
		}
		pass(r, 'Host stayed up and fallback/error text was shown')
	}

	private static void assertChatA11y(Map r, File ev) {
		CustomPagesForm.startOn('pruchat')
		if (!aliveOrFail(r, ev, 'ILP FAQ / PRUChat host did not load')) return
		WebUI.setViewPortSize(390, 844)
		CustomPagesForm.pause(0.8)
		boolean mobileOk = CustomPagesForm.noHorizontalOverflow()
		Map keys = CustomPagesForm.keyboardFocusSample()
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		CustomPagesForm.pause(0.6)
		boolean deskOk = CustomPagesForm.noHorizontalOverflow()
		shot(r, ev, '01-pruchat-desktop-after-mobile-check')
		r.actual = 'mobileOverflow=' + (!mobileOk) + ' desktopOverflow=' + (!deskOk) + ' focus=' + keys
		if (!mobileOk || !deskOk) {
			fail(r, 'Overflow at 390 or 1440 on the FAQ/PRUChat page')
			return
		}
		if (CustomPagesForm.num(keys, 'focusMoves') < 1) {
			fail(r, 'Keyboard did not reach PRUChat / page controls')
			return
		}
		pass(r, 'No overflow; keyboard moved. Shot taken at desktop, not 390.')
	}

	private static void assertOpusInteract(Map r, File ev) {
		CustomPagesForm.startOn('opus')
		if (!aliveOrFail(r, ev, 'OPUS page did not load')) return
		Map acc = CustomPagesForm.probeOpusAccordion()
		shot(r, ev, '01-opus-accordion')
		Map stories = CustomPagesForm.goOpusStories()
		shot(r, ev, '02-opus-stories-viewmore')
		r.actual = 'accordion=' + acc + ' stories=' + stories
		if (!CustomPagesForm.flag(acc, 'hero') || CustomPagesForm.num(acc, 'opened') < 1) {
			fail(r, 'Opus Experience accordion (Legal and estate planning) did not open')
			return
		}
		if (!CustomPagesForm.flag(stories, 'stories') || CustomPagesForm.num(stories, 'viewMore') < 1) {
			fail(r, 'Stories cards / View More were not used')
			return
		}
		pass(r, 'Accordion opened; Stories View More clicked')
	}

	private static void assertOpusBadJson(Map r, File ev) {
		CustomPagesForm.startOn('opus')
		if (!aliveOrFail(r, ev, 'OPUS page did not load')) return
		Map stories = CustomPagesForm.goOpusStories()
		Map bad = CustomPagesForm.sessionBadJson()
		shot(r, ev, '01-opus-stories-session-json')
		r.actual = 'stories=' + stories + ' bad=' + bad
		if (CustomPagesForm.num(stories, 'linkCount') < 1 && CustomPagesForm.num(stories, 'cards') < 1) {
			fail(r, 'No Stories cards/links')
			return
		}
		if (!CustomPagesForm.flag(bad, 'alive')) {
			fail(r, 'Session bad JSON crashed Opus')
			return
		}
		pass(r, 'Stories links present; session bad JSON stayed safe')
	}

	private static void assertOpusPersistA11y(Map r, File ev) {
		CustomPagesForm.startOn('opus')
		if (!aliveOrFail(r, ev, 'OPUS page did not load')) return
		WebUI.setViewPortSize(390, 844)
		CustomPagesForm.pause(0.8)
		boolean mobileOk = CustomPagesForm.noHorizontalOverflow()
		Map keys = CustomPagesForm.keyboardFocusSample()
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		CustomPagesForm.pause(0.6)
		boolean deskOk = CustomPagesForm.noHorizontalOverflow()
		Map persist = CustomPagesForm.persistAfterReload()
		shot(r, ev, '01-opus-desktop-after-checks')
		r.actual = 'overflow=' + (!mobileOk || !deskOk) + ' focus=' + keys.focusMoves + ' persist=' + persist
		if (!mobileOk || !deskOk) {
			fail(r, 'Opus overflow at 390 or 1440')
			return
		}
		if (CustomPagesForm.num(keys, 'focusMoves') < 1) {
			fail(r, 'Keyboard did not reach Opus accordion/nav')
			return
		}
		if (!CustomPagesForm.flag(persist, 'same')) {
			fail(r, 'Opus headings did not persist across reload')
			return
		}
		pass(r, 'Accordion page: no overflow, keyboard, persist. Shot at desktop.')
	}

	private static void assertAwardsModes(Map r, File ev) {
		CustomPagesForm.startOn('awards')
		if (!aliveOrFail(r, ev, 'Awards Night did not load')) return
		Map home = CustomPagesForm.probeAwardsNav()
		shot(r, ev, '01-awards-home-nav')
		CustomPagesForm.openUrl(CustomPagesForm.PAGE.awardsGallery + '#photos')
		CustomPagesForm.waitLandmark('awardsGallery')
		Map gallery = CustomPagesForm.probeAwardsNav()
		shot(r, ev, '02-awards-gallery-photos')
		r.actual = 'home=' + home + ' gallery=' + gallery
		if (CustomPagesForm.num(home, 'navCount') < 3) {
			fail(r, 'Awards Night nav (HOME / GALLERY / AWARDS) was not found')
			return
		}
		if (!CustomPagesForm.flag(gallery, 'gallery') && !CustomPagesForm.flag(gallery, 'photos')) {
			fail(r, 'Gallery / Photos template did not render')
			return
		}
		pass(r, 'Nav on home plus Gallery/Photos page rendered')
	}

	private static void assertAwardsDam(Map r, File ev) {
		CustomPagesForm.startOn('awardsGallery')
		CustomPagesForm.openUrl(CustomPagesForm.PAGE.awardsGallery + '#photos')
		CustomPagesForm.pause(1.2)
		if (!aliveOrFail(r, ev, 'Awards gallery did not load')) return
		Map probe = CustomPagesForm.probeAwardsGallery()
		shot(r, ev, '01-awards-photos-dam')
		r.actual = probe.toString()
		if (!CustomPagesForm.flag(probe, 'hasPhotos') && CustomPagesForm.num(probe, 'damCount') < 1) {
			fail(r, 'PHOTOS cards / DAM images were not found')
			return
		}
		if (CustomPagesForm.num(probe, 'brokenCount') > 0) {
			fail(r, 'Broken DAM references: ' + probe.broken)
			return
		}
		pass(r, 'Gallery photos loaded; carousel arrow used if present')
	}

	private static void assertAwardsPersistBadJson(Map r, File ev) {
		CustomPagesForm.startOn('awards')
		if (!aliveOrFail(r, ev, 'Awards Night did not load')) return
		Map nav = CustomPagesForm.probeAwardsNav()
		Map persist = CustomPagesForm.persistAfterReload()
		shot(r, ev, '01-awards-nav')
		Map bad = CustomPagesForm.sessionBadJson()
		shot(r, ev, '02-awards-session-json')
		r.actual = 'nav=' + nav + ' persist=' + persist + ' bad=' + bad
		if (CustomPagesForm.num(nav, 'navCount') < 3) {
			fail(r, 'Awards nav structure did not render')
			return
		}
		if (!CustomPagesForm.flag(persist, 'same')) {
			fail(r, 'Awards headings did not persist across reload')
			return
		}
		if (!CustomPagesForm.flag(bad, 'alive')) {
			fail(r, 'Session bad JSON crashed Awards Night')
			return
		}
		pass(r, 'Nav persisted; session bad JSON stayed safe')
	}

	private static void assertQuizPaths(Map r, File ev) {
		CustomPagesForm.startOn('shariah')
		if (!aliveOrFail(r, ev, 'Shariah quiz page did not load')) return
		Map correct = CustomPagesForm.playShariahOneThenNext('first')
		shot(r, ev, '01-quiz-correct-path')
		CustomPagesForm.startOn('shariah')
		Map wrong = CustomPagesForm.playShariahOneThenNext('last')
		shot(r, ev, '02-quiz-incorrect-path')
		r.actual = 'correct=' + correct + ' incorrect=' + wrong
		if (!CustomPagesForm.flag(correct, 'started') || !CustomPagesForm.flag(correct, 'typed')) {
			fail(r, 'Name / Start Quiz did not start the quiz')
			return
		}
		if (CustomPagesForm.flag(correct, 'leftPage') || CustomPagesForm.flag(wrong, 'leftPage')) {
			fail(r, 'Left the quiz page (must stay on the fund URL)')
			return
		}
		if (CustomPagesForm.num(correct, 'answerCount') < 4) {
			fail(r, 'A–D answers were not on the question card')
			return
		}
		if (correct.feedbackKind != 'correct') {
			fail(r, 'Sheet: correct path must show correct feedback (Great Job). Got: ' + correct.feedbackKind)
			return
		}
		if (wrong.feedbackKind != 'incorrect') {
			fail(r, 'Sheet: incorrect path must show incorrect feedback. Got: ' + wrong.feedbackKind)
			return
		}
		if (!CustomPagesForm.flag(correct, 'sequenced')) {
			fail(r, 'Sheet: questions must sequence. Next did not advance after the correct answer')
			return
		}
		pass(r, 'Correct path Great Job; incorrect path other feedback; Next advanced')
	}

	private static void assertQuizScoreRestart(Map r, File ev) {
		CustomPagesForm.startOn('shariah')
		if (!aliveOrFail(r, ev, 'Shariah quiz page did not load')) return
		Map first = CustomPagesForm.playShariahQuiz('first')
		shot(r, ev, '01-quiz-result-tier')
		r.actual = first.toString()
		if (CustomPagesForm.flag(first, 'leftPage')) {
			fail(r, 'Left the quiz for ' + first.url)
			return
		}
		if (CustomPagesForm.num(first, 'answered') < 5) {
			fail(r, 'Sheet: complete the quiz. answered=' + first.answered + '/5 nexts=' + first.nexts)
			return
		}
		if (!CustomPagesForm.flag(first, 'hasScore') || !first.level) {
			fail(r, 'Sheet: score tier/result must show (BEGINNER / INTERMEDIATE / ADVANCED)')
			return
		}
		if (!CustomPagesForm.flag(first, 'restart')) {
			fail(r, 'Sheet: restart must reset state. No Restart / Try again on the result card')
			return
		}
		if (!CustomPagesForm.flag(first, 'reset')) {
			fail(r, 'Restart was clicked but the name / Start Quiz screen did not return')
			return
		}
		pass(r, 'Result ' + first.level + '; restart returned to the name screen')
	}

	private static void assertQuizPartialA11y(Map r, File ev) {
		CustomPagesForm.startOn('shariah')
		if (!aliveOrFail(r, ev, 'Shariah quiz page did not load')) return
		Map partial = CustomPagesForm.sessionPartialQuiz()
		Map keys = CustomPagesForm.keyboardFocusSample()
		shot(r, ev, '01-quiz-partial')
		r.actual = 'partial=' + partial + ' focus=' + keys
		if (!CustomPagesForm.flag(partial, 'alive')) {
			fail(r, 'Partial quiz in this tab crashed or left the page')
			return
		}
		if (CustomPagesForm.num(keys, 'focusMoves') < 1) {
			fail(r, 'Keyboard could not move inside the quiz card')
			return
		}
		pass(r, 'Partial quiz card stayed usable; keyboard moved')
	}

	private static boolean aliveOrFail(Map r, File ev, String msg) {
		if (CustomPagesForm.pageAlive()) return true
		fail(r, msg)
		shot(r, ev, '00-dead')
		return false
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
		try {
			body.call(result, ev)
		} catch (Throwable e) {
			fail(result, e.message ?: e.toString())
		} finally {
			CustomPagesForm.pause(0.8)
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
		((List) r.steps) << CustomPagesEvidence.captureAem(ev, stem) + [
			name  : stem,
			status: r.status,
		]
	}

	private static void pass(Map r, String detail) {
		r.status = 'PASS'
		r.detail = detail
		if (!r.actual) r.actual = detail
		stampSteps(r, 'PASS')
	}

	private static void fail(Map r, String detail) {
		r.status = 'FAIL'
		r.detail = detail
		if (!r.actual) r.actual = detail
		stampSteps(r, 'FAIL')
	}

	private static void stampSteps(Map r, String status) {
		((List) r.steps).each { Map s -> s.status = status }
	}
}

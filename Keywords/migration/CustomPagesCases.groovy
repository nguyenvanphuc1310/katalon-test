package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * Custom pages — focus the components in the QA screenshots, not the whole chrome.
 * Default FAIL. Each checkStep writes its own PASS/FAIL row and is never restamped.
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
		checkStep(r, ev, '01-ppc-dropdowns-grid') {
			Map probe = CustomPagesForm.selectEachPpcOption()
			r.actual = probe.toString()
			if (!CustomPagesForm.flag(probe, 'found') || !CustomPagesForm.flag(probe, 'hasGrid')) {
				return 'Extended Panel Benefits grid (PRE-AUTHORISATION) was not found'
			}
			if (CustomPagesForm.num(probe, 'optionCount') < 2 || CustomPagesForm.num(probe, 'distinctViews') < 2) {
				return 'Did not change both plan/HI dropdowns enough to remap the grid: ' + probe
			}
			return true
		}
	}

	private static void assertPpcDescriptions(Map r, File ev) {
		CustomPagesForm.startOn('ppc')
		if (!aliveOrFail(r, ev, 'PPC Extended Panel did not load')) return
		checkStep(r, ev, '01-ppc-hi-change') {
			Map probe = CustomPagesForm.changePpcDescription()
			r.actual = probe.toString()
			if (!CustomPagesForm.flag(probe, 'legend')) return 'HI legend (EP specialist / Non-EP) was not on the panel'
			if (!CustomPagesForm.flag(probe, 'changed') || !CustomPagesForm.flag(probe, 'hasGrid')) {
				return 'Changing the HI dropdown did not update the benefits grid'
			}
			return true
		}
	}

	private static void assertPpcPersist(Map r, File ev) {
		CustomPagesForm.startOn('ppc')
		if (!aliveOrFail(r, ev, 'PPC Extended Panel did not load')) return
		checkStep(r, ev, '01-ppc-authored-grid') {
			Map first = CustomPagesForm.selectEachPpcOption()
			r.actual = 'grid=' + first.hasGrid
			if (!CustomPagesForm.flag(first, 'hasGrid')) return 'Published panel has no benefits grid'
			return true
		}
		checkStep(r, ev, '02-ppc-after-reload') {
			Map persist = CustomPagesForm.persistAfterReload()
			r.actual = (r.actual ?: '') + ' persist=' + persist
			if (!CustomPagesForm.flag(persist, 'same')) return 'Extended Panel headings did not persist across reload'
			return true
		}
	}

	private static void assertShieldGraph(Map r, File ev) {
		CustomPagesForm.startOn('prushield')
		if (!aliveOrFail(r, ev, 'PRUShield page did not load')) return
		checkStep(r, ev, '01-shield-standard') {
			if (!CustomPagesForm.pageAlive()) return 'PRUShield page was not usable'
			return true
		}
		checkStep(r, ev, '02-shield-after-tabs') {
			Map probe = CustomPagesForm.probeShieldTabs()
			r.actual = probe.toString()
			if (!CustomPagesForm.flag(probe, 'found') || CustomPagesForm.num(probe, 'tabCount') < 2) {
				return 'Standard/Plus/Premier tabs were not all used'
			}
			if (!CustomPagesForm.flag(probe, 'hasPay')) return 'Pay/cover amounts were not shown on the bar'
			if (CustomPagesForm.num(probe, 'distinct') < 2) return 'Tab change did not change the bar values'
			return true
		}
	}

	private static void assertShieldEmpty(Map r, File ev) {
		CustomPagesForm.startOn('prushield')
		if (!aliveOrFail(r, ev, 'PRUShield page did not load')) return
		checkStep(r, ev, '01-shield-session-empty') {
			Map probe = CustomPagesForm.sessionEmptyShield()
			r.actual = probe.toString()
			if (!CustomPagesForm.flag(probe, 'found')) return 'PRUShield pay bar was not found'
			if (!CustomPagesForm.flag(probe, 'safe')) return 'Clearing the bar in this tab broke the page'
			return true
		}
	}

	private static void assertShieldPersistA11y(Map r, File ev) {
		CustomPagesForm.startOn('prushield')
		if (!aliveOrFail(r, ev, 'PRUShield page did not load')) return
		checkStep(r, ev, '01-shield-tabs-keyboard') {
			Map persist = CustomPagesForm.persistAfterReload()
			Map keys = CustomPagesForm.keyboardGraphToggle()
			r.actual = 'persist=' + persist + ' a11y=' + keys
			if (!CustomPagesForm.flag(persist, 'same')) return 'PRUShield headings did not persist across reload'
			if (CustomPagesForm.num(keys, 'named') < 1) return 'Standard/Plus/Premier tabs were not named for keyboard'
			return true
		}
	}

	private static void assertChatOpenClose(Map r, File ev) {
		CustomPagesForm.startOn('pruchat')
		if (!aliveOrFail(r, ev, 'ILP FAQ / PRUChat host did not load')) return
		checkStep(r, ev, '01-pruchat-before') {
			if (!CustomPagesForm.pageAlive()) return 'ILP FAQ / PRUChat host did not load'
			return true
		}
		checkStep(r, ev, '02-pruchat-open-close') {
			Map probe = CustomPagesForm.probePruChat()
			CustomPagesForm.pause(0.8)
			r.actual = probe.toString()
			if (!CustomPagesForm.flag(probe, 'launcher') && !CustomPagesForm.flag(probe, 'opened')) {
				return 'PRUChat #pruchat / Need help? Talk to us was not found'
			}
			if (!CustomPagesForm.flag(probe, 'opened')) {
				return 'Clicked Need help? Talk to us but Welcome / Let\'s chat did not open'
			}
			boolean stillOpen = CustomPagesForm.flag(probe, 'welcome') && !CustomPagesForm.truthy(
				CustomPagesForm.js('return !!(document.getElementById("pruchat") && document.getElementById("pruchat").querySelector(".closed"));')
			)
			if (stillOpen && CustomPagesForm.bodyText().contains('Welcome to PRUChat')) {
				return 'Sheet: open and close. Widget opened but did not close'
			}
			if (!CustomPagesForm.flag(probe, 'hostOk')) return 'Host FAQ page was not usable after PRUChat'
			return true
		}
	}

	private static void assertChatBlocked(Map r, File ev) {
		CustomPagesForm.startOn('pruchat')
		if (!aliveOrFail(r, ev, 'ILP FAQ / PRUChat host did not load')) return
		checkStep(r, ev, '01-pruchat-blocked-in-tab') {
			Map probe = CustomPagesForm.sessionBlockPruChat()
			r.actual = probe.toString()
			if (!CustomPagesForm.flag(probe, 'alive')) return 'Host crashed after PRUChat was removed in this tab'
			String page = CustomPagesForm.bodyText().toLowerCase()
			boolean fallback = page.contains('unavailable') || page.contains('try again') ||
				page.contains('not available') || page.contains('failed') || page.contains('offline')
			if (!fallback) return 'Sheet: approved fallback/error must show. Host stayed up but no fallback text.'
			return true
		}
	}

	private static void assertChatA11y(Map r, File ev) {
		CustomPagesForm.startOn('pruchat')
		if (!aliveOrFail(r, ev, 'ILP FAQ / PRUChat host did not load')) return
		checkStep(r, ev, '01-pruchat-desktop-after-mobile-check') {
			WebUI.setViewPortSize(390, 844)
			CustomPagesForm.pause(0.8)
			boolean mobileOk = CustomPagesForm.noHorizontalOverflow()
			Map keys = CustomPagesForm.keyboardFocusSample()
			try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
			CustomPagesForm.pause(0.6)
			boolean deskOk = CustomPagesForm.noHorizontalOverflow()
			r.actual = 'mobileOverflow=' + (!mobileOk) + ' desktopOverflow=' + (!deskOk) + ' focus=' + keys
			if (!mobileOk || !deskOk) return 'Overflow at 390 or 1440 on the FAQ/PRUChat page'
			if (CustomPagesForm.num(keys, 'focusMoves') < 1) return 'Keyboard did not reach PRUChat / page controls'
			return true
		}
	}

	private static void assertOpusInteract(Map r, File ev) {
		CustomPagesForm.startOn('opus')
		if (!aliveOrFail(r, ev, 'OPUS page did not load')) return
		checkStep(r, ev, '01-opus-accordion') {
			Map acc = CustomPagesForm.probeOpusAccordion()
			r.actual = 'accordion=' + acc
			if (!CustomPagesForm.flag(acc, 'hero') || CustomPagesForm.num(acc, 'opened') < 1) {
				return 'Opus Experience accordion (Legal and estate planning) did not open'
			}
			return true
		}
		checkStep(r, ev, '02-opus-stories-viewmore') {
			Map stories = CustomPagesForm.goOpusStories()
			r.actual = (r.actual ?: '') + ' stories=' + stories
			if (!CustomPagesForm.flag(stories, 'stories') || CustomPagesForm.num(stories, 'viewMore') < 1) {
				return 'Stories cards / View More were not used'
			}
			return true
		}
	}

	private static void assertOpusBadJson(Map r, File ev) {
		CustomPagesForm.startOn('opus')
		if (!aliveOrFail(r, ev, 'OPUS page did not load')) return
		checkStep(r, ev, '01-opus-stories-session-json') {
			Map stories = CustomPagesForm.goOpusStories()
			Map bad = CustomPagesForm.sessionBadJson()
			r.actual = 'stories=' + stories + ' bad=' + bad
			if (CustomPagesForm.num(stories, 'linkCount') < 1 && CustomPagesForm.num(stories, 'cards') < 1) {
				return 'No Stories cards/links'
			}
			if (!CustomPagesForm.flag(bad, 'alive')) return 'Session bad JSON crashed Opus'
			return true
		}
	}

	private static void assertOpusPersistA11y(Map r, File ev) {
		CustomPagesForm.startOn('opus')
		if (!aliveOrFail(r, ev, 'OPUS page did not load')) return
		checkStep(r, ev, '01-opus-desktop-after-checks') {
			WebUI.setViewPortSize(390, 844)
			CustomPagesForm.pause(0.8)
			boolean mobileOk = CustomPagesForm.noHorizontalOverflow()
			Map keys = CustomPagesForm.keyboardFocusSample()
			try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
			CustomPagesForm.pause(0.6)
			boolean deskOk = CustomPagesForm.noHorizontalOverflow()
			Map persist = CustomPagesForm.persistAfterReload()
			r.actual = 'overflow=' + (!mobileOk || !deskOk) + ' focus=' + keys.focusMoves + ' persist=' + persist
			if (!mobileOk || !deskOk) return 'Opus overflow at 390 or 1440'
			if (CustomPagesForm.num(keys, 'focusMoves') < 1) return 'Keyboard did not reach Opus accordion/nav'
			if (!CustomPagesForm.flag(persist, 'same')) return 'Opus headings did not persist across reload'
			return true
		}
	}

	private static void assertAwardsModes(Map r, File ev) {
		CustomPagesForm.startOn('awards')
		if (!aliveOrFail(r, ev, 'Awards Night did not load')) return
		checkStep(r, ev, '01-awards-home-nav') {
			Map home = CustomPagesForm.probeAwardsNav()
			r.actual = 'home=' + home
			if (CustomPagesForm.num(home, 'navCount') < 3) return 'Awards Night nav (HOME / GALLERY / AWARDS) was not found'
			return true
		}
		checkStep(r, ev, '02-awards-gallery-photos') {
			CustomPagesForm.openUrl(CustomPagesForm.PAGE.awardsGallery + '#photos')
			CustomPagesForm.waitLandmark('awardsGallery')
			Map gallery = CustomPagesForm.probeAwardsNav()
			r.actual = (r.actual ?: '') + ' gallery=' + gallery
			if (!CustomPagesForm.flag(gallery, 'gallery') && !CustomPagesForm.flag(gallery, 'photos')) {
				return 'Gallery / Photos template did not render'
			}
			return true
		}
	}

	private static void assertAwardsDam(Map r, File ev) {
		CustomPagesForm.startOn('awardsGallery')
		CustomPagesForm.openUrl(CustomPagesForm.PAGE.awardsGallery + '#photos')
		CustomPagesForm.pause(1.2)
		if (!aliveOrFail(r, ev, 'Awards gallery did not load')) return
		checkStep(r, ev, '01-awards-photos-dam') {
			Map probe = CustomPagesForm.probeAwardsGallery()
			r.actual = probe.toString()
			if (!CustomPagesForm.flag(probe, 'hasPhotos') && CustomPagesForm.num(probe, 'damCount') < 1) {
				return 'PHOTOS cards / DAM images were not found'
			}
			if (CustomPagesForm.num(probe, 'brokenCount') > 0) return 'Broken DAM references: ' + probe.broken
			return true
		}
	}

	private static void assertAwardsPersistBadJson(Map r, File ev) {
		CustomPagesForm.startOn('awards')
		if (!aliveOrFail(r, ev, 'Awards Night did not load')) return
		checkStep(r, ev, '01-awards-nav') {
			Map nav = CustomPagesForm.probeAwardsNav()
			Map persist = CustomPagesForm.persistAfterReload()
			r.actual = 'nav=' + nav + ' persist=' + persist
			if (CustomPagesForm.num(nav, 'navCount') < 3) return 'Awards nav structure did not render'
			if (!CustomPagesForm.flag(persist, 'same')) return 'Awards headings did not persist across reload'
			return true
		}
		checkStep(r, ev, '02-awards-session-json') {
			Map bad = CustomPagesForm.sessionBadJson()
			r.actual = (r.actual ?: '') + ' bad=' + bad
			if (!CustomPagesForm.flag(bad, 'alive')) return 'Session bad JSON crashed Awards Night'
			return true
		}
	}

	private static void assertQuizPaths(Map r, File ev) {
		CustomPagesForm.startOn('shariah')
		if (!aliveOrFail(r, ev, 'Shariah quiz page did not load')) return
		checkStep(r, ev, '01-quiz-correct-path') {
			Map correct = CustomPagesForm.playShariahOneThenNext('first')
			r.actual = 'correct=' + correct
			if (!CustomPagesForm.flag(correct, 'started') && !CustomPagesForm.flag(correct, 'questions')) {
				return '#quiz-question-container did not become visible (AEM quiz start)'
			}
			if (CustomPagesForm.flag(correct, 'leftPage')) return 'Left the quiz page (must stay on the fund URL)'
			if (CustomPagesForm.num(correct, 'answerCount') < 1) return '.answer-opt cards were not in #quiz-question-container'
			if (!CustomPagesForm.flag(correct, 'submitted')) return '#submitQuizBtn stayed disabled after selecting .answer-opt'
			if (correct.feedbackKind != 'correct') return 'Correct path must show Great job! Got: ' + correct.feedbackKind
			if (!CustomPagesForm.flag(correct, 'sequenced')) return '#nextQuizBtn did not advance after Submit'
			return true
		}
		CustomPagesForm.startOn('shariah')
		checkStep(r, ev, '02-quiz-incorrect-path') {
			Map wrong = CustomPagesForm.playShariahOneThenNext('last')
			r.actual = (r.actual ?: '') + ' incorrect=' + wrong
			if (CustomPagesForm.flag(wrong, 'leftPage')) return 'Left the quiz page (must stay on the fund URL)'
			if (!CustomPagesForm.flag(wrong, 'submitted')) return '#submitQuizBtn stayed disabled after selecting .answer-opt'
			if (wrong.feedbackKind != 'incorrect') return 'Incorrect path must show Not quite right. Got: ' + wrong.feedbackKind
			return true
		}
	}

	private static void assertQuizScoreRestart(Map r, File ev) {
		CustomPagesForm.startOn('shariah')
		if (!aliveOrFail(r, ev, 'Shariah quiz page did not load')) return
		checkStep(r, ev, '01-quiz-result-tier') {
			Map first = CustomPagesForm.playShariahQuiz('first')
			r.quizPlay = first
			r.actual = first.toString()
			if (CustomPagesForm.flag(first, 'leftPage')) return 'Left the quiz for ' + first.url
			if (CustomPagesForm.num(first, 'answered') < 5) {
				return 'Sheet: complete the quiz. answered=' + first.answered + '/5 submits=' + first.submits + ' nexts=' + first.nexts
			}
			String extracted = (first.scoreText ?: CustomPagesForm.quizScoreText() ?: '').toString()
			String scoreUpper = extracted.toUpperCase()
			KeywordUtil.logInfo('CP-029 score container: ' + extracted)
			boolean acceptedTier = scoreUpper.contains('EXPERT LEVEL') || scoreUpper.contains('EXPERT') ||
				scoreUpper.contains('BEGINNER LEVEL') || scoreUpper.contains('BEGINNER') ||
				scoreUpper.contains('INTERMEDIATE LEVEL') || scoreUpper.contains('INTERMEDIATE') ||
				scoreUpper.contains('ADVANCED LEVEL') || scoreUpper.contains('ADVANCED')
			if (!CustomPagesForm.flag(first, 'hasScore') || !acceptedTier) {
				return 'Score tier must show in #quiz-score-container h3 (Beginner / Intermediate / Expert). Got: ' + extracted
			}
			return true
		}
		checkStep(r, ev, '02-quiz-restart-check') {
			try {
				Map rst = CustomPagesForm.restartShariahQuiz()
				r.actual = (r.actual ?: '') + ' restart=' + rst
				if (!CustomPagesForm.flag(rst, 'restart')) {
					return 'Sheet: restart must reset state. No Restart / Try again on #quiz-score-container'
				}
				if (!CustomPagesForm.flag(rst, 'reset')) {
					return 'Restart was clicked but #quiz-question-container / #quiz-intro did not return'
				}
				return true
			} catch (Throwable e) {
				return 'Restart check error: ' + (e.message ?: e.toString())
			}
		}
	}

	private static void assertQuizPartialA11y(Map r, File ev) {
		CustomPagesForm.startOn('shariah')
		if (!aliveOrFail(r, ev, 'Shariah quiz page did not load')) return
		checkStep(r, ev, '01-quiz-partial') {
			Map partial = CustomPagesForm.sessionPartialQuiz()
			Map keys = CustomPagesForm.keyboardFocusSample()
			r.actual = 'partial=' + partial + ' focus=' + keys
			if (!CustomPagesForm.flag(partial, 'alive')) return 'Partial quiz in this tab crashed or left the page'
			if (CustomPagesForm.num(keys, 'focusMoves') < 1) return 'Keyboard could not move inside the quiz card'
			return true
		}
	}

	private static boolean aliveOrFail(Map r, File ev, String msg) {
		if (CustomPagesForm.pageAlive()) return true
		checkStep(r, ev, '00-page-load') { return msg }
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
		CustomPagesForm.resetStartState()
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		try {
			body.call(result, ev)
		} catch (Throwable e) {
			checkStep(result, ev, '99-uncaught') { return (e.message ?: e.toString()) }
		} finally {
			CustomPagesForm.pause(0.8)
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

	/**
	 * Soft step for every Custom pages case.
	 * Name first. Return true to PASS this row, or a String reason to FAIL this row only.
	 * The next checkStep still runs. stampSteps is not used.
	 */
	private static boolean checkStep(Map r, File ev, String name, Closure verify) {
		r.currentStep = name
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
		recordStep(r, ev, name, ok ? 'PASS' : 'FAIL', detail)
		CustomPagesForm.markStepFinished()
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

	private static void recordStep(Map r, File ev, String name, String status, String detail) {
		((List) r.steps) << CustomPagesEvidence.captureAem(ev, name) + [
			name  : name,
			status: status,
			detail: detail,
		]
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

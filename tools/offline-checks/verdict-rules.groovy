import migration.StateMatch
import migration.checks.ContentCompare

/**
 * The matching rules and the score, against fixtures. This is where a tuning change is proved
 * before it is let anywhere near the live sites.
 */

int pass = 0, fail = 0
def check = { String what, boolean ok ->
	if (ok) { pass++ } else { fail++; println "  FAIL  ${what}" }
}
def item = { String text, Map extra = [:] ->
	[full: text, text: text, kind: 'para', path: '', stateId: '', href: ''] + extra
}
def snap = { List items, Map extra = [:] ->
	[items: items, rootText: items*.full.join(' \n '), states: [], skipped: [hidden: 0], formOptions: []] + extra
}

// ---------------------------------------------------------------- matching
Map r = ContentCompare.diff(
	snap([item('A sentence the new page kept entirely, word for word, in full.')]),
	snap([item('A sentence the new page kept entirely, word for word, in full.')]))
check('identical text produces no finding', r.findings.isEmpty())

r = ContentCompare.diff(
	snap([item('This whole paragraph disappeared from the new page during the migration.')]),
	snap([item('Something else entirely lives here now on the migrated page.')]))
check('lost text is MISSING_ON_AEM', r.counts.MISSING_ON_AEM == 1)

// the two CMSes break the same sentence at different points
r = ContentCompare.diff(
	snap([item('PRUShield and PRUExtra together cover hospitalisation costs in full.')]),
	snap([item('PRU'), item('Shield and PRUExtra together cover hospitalisation costs in full.')]))
check('a sentence split across elements still matches',
	(r.counts.MISSING_ON_AEM ?: 0) == 0)

// a short label must be answered by a label, not by an accident inside a sentence
r = ContentCompare.diff(
	snap([item('Compare Plans')]),
	snap([item('You can compare plans on our product comparison page whenever you like.')]))
check('a short label is not satisfied by a substring of a sentence', r.counts.MISSING_ON_AEM == 1)

// ...but AEM glues its accessibility copy on with no separator
r = ContentCompare.diff(snap([item('English')]), snap([item('Englishopens in a new tab')]))
check('a label with accessibility copy glued on still matches', (r.counts.MISSING_ON_AEM ?: 0) == 0)

// figures outrank word overlap
String live = 'Your family will need a sum assured of S$100,000 to stay where they are.'
String neu  = 'Your family will need a sum assured of S$200,000 to stay where they are.'
r = ContentCompare.diff(snap([item(live)]), snap([item(neu)]))
check('a changed figure is NUMBER_CHANGED, not TEXT_CHANGED', r.counts.NUMBER_CHANGED == 1)

// a footnote marker is not a figure: the two sites number them differently
r = ContentCompare.diff(
	snap([item('Protection against severe disability3 for the whole of your life.')]),
	snap([item('Protection against severe disability7 for the whole of your life.')]))
check('a footnote marker is not compared as a figure', (r.counts.NUMBER_CHANGED ?: 0) == 0)

// same wording, different destination
r = ContentCompare.diff(
	snap([item('Find out more', [kind: 'cta', href: '/we-do/shield'])]),
	snap([item('Find out more', [kind: 'cta', href: '/en/we-do/extra'])]))
check('a CTA pointing elsewhere is LINK_CHANGED', r.counts.LINK_CHANGED == 1)

// ...and the AEM /en prefix is not a change of destination
r = ContentCompare.diff(
	snap([item('Find out more', [kind: 'cta', href: '/we-do/shield'])]),
	snap([item('Find out more', [kind: 'cta', href: '/en/we-do/shield'])]))
check('the /en prefix alone is not a link change', (r.counts.LINK_CHANGED ?: 0) == 0)

// extra content on the new page never fails
r = ContentCompare.diff(snap([]), snap([item('A whole new paragraph the new page adds by itself.')]))
check('extra text on the new page is informational', r.counts.ONLY_ON_AEM == 1 &&
	!ContentCompare.ERRORS.contains('ONLY_ON_AEM'))

// the measurement checks itself
r = ContentCompare.diff(
	snap([item('One paragraph of ordinary length on the live page.')], [skipped: [hidden: 150]]),
	snap([item('One paragraph of ordinary length on the live page.')], [skipped: [hidden: 8]]))
check('lopsided hidden-content counts raise SCOPE_ASYMMETRY', r.counts.SCOPE_ASYMMETRY == 1)

// ---------------------------------------------------------------- state pairing
Map paired = StateMatch.pair(
	[[id: 's1', kind: 'tab', group: 'g', label: 'Medical & Accident', text: 'hospital surgery ward stay claim']],
	[[id: 'a1', kind: 'tab', group: 'g', label: 'Medical & accident', text: 'hospital surgery ward stay claim']])
check('tabs pair when the label agrees', paired.pairs.size() == 1)

paired = StateMatch.pair(
	[[id: 's1', kind: 'tab', group: 'g', label: 'Critical Illness', text: 'cancer stroke heart attack diagnosis payout']],
	[[id: 'a1', kind: 'tab', group: 'g', label: 'Protection', text: 'cancer stroke heart attack diagnosis payout']])
check('tabs pair on content when the two sites renamed them',
	paired.pairs.size() == 1 && paired.pairs[0].by == 'content')

paired = StateMatch.pair(
	[[id: 's1', kind: 'tab', group: 'g', label: 'Savings', text: 'endowment maturity capital guaranteed']],
	[[id: 'a1', kind: 'accordion', group: '', label: 'Savings', text: 'endowment maturity capital guaranteed']])
check('a tab is never paired with an accordion', paired.pairs.isEmpty())

// ---------------------------------------------------------------- score
Map sc = ContentCompare.score([counts: [:], scItems: 100])
check('a clean page scores 100', sc.score == 100.0d && sc.grade == 'PASS')

sc = ContentCompare.score([counts: [MISSING_ON_AEM: 10], scItems: 100])
check('ten misses out of a hundred items score 90', sc.score == 90.0d && sc.grade == 'FAIL')

sc = ContentCompare.score([counts: [WRONG_TAB: 10], scItems: 100])
check('a wrong tab costs half a miss', sc.score == 95.0d)

sc = ContentCompare.score([counts: [ONLY_ON_AEM: 50], scItems: 100])
check('extra content costs nothing', sc.score == 100.0d)

sc = ContentCompare.score([counts: [NUMBER_CHANGED: 1], scItems: 1000])
check('one changed figure grades FAIL however small its weight',
	sc.grade == 'FAIL' && sc.score > 99.0d && sc.hardFailures)

sc = ContentCompare.score([counts: [MISSING_ON_AEM: 500], scItems: 100])
check('the score floors at 0 rather than going negative', sc.score == 0.0d)

sc = ContentCompare.score([counts: [:], scItems: 6])
check('too few items is low confidence', sc.confidence == 'low' && sc.score == 100.0d)

sc = ContentCompare.score([counts: [SCOPE_ASYMMETRY: 1], scItems: 200])
check('scope asymmetry lowers confidence, never the score',
	sc.confidence == 'low' && sc.score == 100.0d)

// the weights in the CSV must be the weights the score used
double byHand = ContentCompare.weightOf('MISSING_ON_AEM') * 3 + ContentCompare.weightOf('TEXT_CHANGED') * 2
sc = ContentCompare.score([counts: [MISSING_ON_AEM: 3, TEXT_CHANGED: 2], scItems: 50])
check('the published weights reproduce the score exactly',
	Math.abs(sc.score - (100d * (1d - byHand / 50d))) < 0.05d)

println "  ${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)

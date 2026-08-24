package migration.checks

import com.kms.katalon.core.annotation.Keyword

import migration.AuditUtils
import migration.StateMatch

/**
 * Compares two content snapshots. PURE: no browser, no network — it only reads the
 * JSON files written by ContentSnapshot, so the matching rules can be re-tuned and
 * re-run over an existing crawl in seconds.
 *
 * Sitecore stays the subset baseline: whatever Sitecore has, AEM must have.
 *
 * Matching is presence-based on a normalized blob (the two CMSes break the same
 * sentence at different points), but scoped by the tab an item lives in, so text
 * that moved between tabs is reported once as WRONG_TAB instead of twice as a
 * MISSING + ONLY_ON_AEM pair.
 */
public class ContentCompare {

	static final int MIN_LEN = 4                 // ignore very short fragments (icons, single glyphs)
	static final int NOSPACE_FALLBACK_MIN = 20   // spaceless fallback only when accidental matches are negligible
	static final double REWORD_OVERLAP = 0.9     // token overlap above which a change is a reword, not a removal
	/**
	 * Below this length a text is treated as a LABEL, and a substring hit on the whole-page blob is
	 * not evidence that it survived: a page carrying "Find out more" once satisfies every "Find out
	 * more" the live page has, and a short label also turns up inside a longer sentence by accident.
	 * Such a text has to be answered by a label on the other side — see foundAsWholeItem.
	 *
	 * 30 is the label/sentence boundary and covers the CTAs this content actually uses ("Find out
	 * more", "Get in touch", "Compare Plans"). Swept over the 9 GCDP pages, every value from 12 to
	 * 40 produced the same 35 MISSING findings, all 35 independently confirmed absent from the raw
	 * AEM HTML — so the choice costs nothing here and buys the CTAs cover on pages where it would.
	 */
	static final int SHORT_TEXT_LEN = 30
	/**
	 * Occurrences are only counted for text at least this long.
	 *
	 * A repeated BLOCK is a real thing to lose — a product card, a myth-buster entry, a list item.
	 * A repeated WORD is not: counting substrings of the page text, "Protection" occurred 24 times
	 * on the live page and 23 on the new one, which is true, unactionable, and would have failed
	 * the page. Measured over the 9 GCDP pages, this threshold is what separates the two: it drops
	 * every "Protection"/"story"/"Contact us" row and keeps the repeated content blocks.
	 */
	static final int COUNT_MIN_LEN = 40
	/** Below this many skipped hidden elements the difference is ordinary page-to-page variation */
	static final int SCOPE_ASYMMETRY_MIN = 20

	/** Canonical form: NFKC (superscripts -> digits), lowercase, unified quotes/dashes, collapsed whitespace */
	static String norm(String s) {
		String t = java.text.Normalizer.normalize(s ?: '', java.text.Normalizer.Form.NFKC)
		t = t.toLowerCase()
			.replace('‘', "'").replace('’', "'")
			.replace('“', '"').replace('”', '"')
			.replace('–', '-').replace('—', '-')
		return t.replaceAll('\\s+', ' ').trim()
	}

	static boolean foundIn(String n, String blob, String blobNoSpace) {
		if (!n) return true
		if (blob.contains(n)) return true
		return n.length() >= NOSPACE_FALLBACK_MIN && blobNoSpace.contains(n.replace(' ', ''))
	}

	/**
	 * How many times the text occurs, not merely whether it does.
	 *
	 * Presence alone cannot see a lost duplicate: a live page with five identical product cards
	 * against a new page with one satisfied every check, because each of the five found the same
	 * single copy. Counting is what turns "the text exists somewhere" into "the text exists as
	 * often as it did".
	 */
	static int countIn(String n, String blob) {
		if (!n) return 1
		int c = 0, i = blob.indexOf(n)
		while (i >= 0) { c++; i = blob.indexOf(n, i + n.length()) }
		return c
	}

	/**
	 * A short label must be answered by a LABEL on the other side, not by an incidental occurrence
	 * inside a paragraph. See SHORT_TEXT_LEN.
	 *
	 * Three ways to qualify, in order of how exact they are:
	 *   - the item is the text;
	 *   - the item is the text with something glued to one end. AEM appends its accessibility copy
	 *     without a separator, so the live page's "English" is "Englishopens in a new tab" there —
	 *     a word-boundary test rejects it, and reported three of them per product-deck page as
	 *     missing content;
	 *   - the item contains the text on word boundaries AND is itself short. The length bound is
	 *     what does the actual work: without it "Find out more" is satisfied by any sentence that
	 *     happens to contain the phrase, which is the hole this method exists to close.
	 */
	static final int SHORT_LABEL_SLACK = 3

	/**
	 * Most characters an item may carry BEYOND the label it is answering, when it merely begins or
	 * ends with it rather than equalling it.
	 *
	 * 40 is set from the thing this allowance exists for and nothing else: AEM's glued
	 * accessibility copy, "opens in a new tab", is 18 characters, and the longest such suffix
	 * measured on the captured pages is under 30. 40 leaves headroom for a variant without letting
	 * a sentence qualify — the hole being closed is a paragraph of arbitrary length answering a
	 * short CTA because it happens to start with the same words.
	 */
	static final int AFFIX_EXTRA_MAX = 40

	/**
	 * A brand prefix the two CMSes disagree about where to break.
	 *
	 * Sitecore writes the product name as PRU + an inline element carrying the rest —
	 * `<h1>PRU<span>Shield</span> / PRU<span>Extra</span> Premium Rate Tables</h1>` — so the
	 * collector emits "Shield" and "Extra" as items in their own right. AEM writes "PRUShield"
	 * as one text node, and wraps "PRU" instead, which MIN_LEN then drops for being 3 characters.
	 * The live fragment therefore has no fragment to answer it, and the word-boundary test below
	 * rejects the only place it does occur, because the character to its left is the "u" of "PRU".
	 *
	 * Measured over the 192 captured page pairs, this is one defect and not a class of them:
	 * every one of the 9 findings it produces is the prefix "pru", on 4 pages. The bounds are
	 * what keep it that narrow. The LOWER bound is the load-bearing one — without it "here" is
	 * answered by "there are many ways to make or receive payment", which is a coincidence and
	 * not a counterpart, and it silently lifted en_claims_and_support_payments by two findings.
	 * The upper bound stops a whole word gluing itself to the label.
	 */
	static final int GLUE_PREFIX_MIN = 3
	static final int GLUE_PREFIX_MAX = 4

	static boolean foundAsWholeItem(String n, List items) {
		if (!n) return true
		return items.any { Object it ->
			String o = norm(textOf(it))
			// Exact equality needs no bound — the item IS the text.
			if (o == n) return true
			// The affix branch used to have no bound at all, so a paragraph of any length answered
			// a short label as long as it happened to begin or end with it.
			//
			// The bound is an ABSOLUTE number of extra characters, not the ratio used below, and
			// the difference matters: the case this branch exists for is AEM gluing its
			// accessibility copy onto a label with no separator, so the live page's "English" is
			// "Englishopens in a new tab" there. That is 18 extra characters on a 7-character
			// label — a 3x ratio rejects it, and doing so reported "English" as missing content
			// three times on every product-deck page. A ratio cannot express "plus a short fixed
			// suffix"; a character count can.
			if (o.startsWith(n) || o.endsWith(n)) return o.length() - n.length() <= AFFIX_EXTRA_MAX
			int at = o.indexOf(n)
			while (at >= 0) {
				boolean leftOk = (at == 0) || !Character.isLetterOrDigit(o.charAt(at - 1))
				int end = at + n.length()
				boolean rightOk = (end >= o.length()) || !Character.isLetterOrDigit(o.charAt(end))
				if (leftOk && rightOk) return true
				// The same word, with the brand prefix glued to its front and no separator to
				// break on: the live page's "Shield" against the new page's "PRUShield". The run
				// to the left has to be a whole short word of its own — it must itself start at a
				// boundary — so this reads "<prefix><label>" and not "somewhere inside a word".
				if (rightOk && at > 0 && Character.isLetter(o.charAt(at - 1))) {
					int j = at - 1
					while (j > 0 && Character.isLetter(o.charAt(j - 1))) j--
					int runLen = at - j
					if (runLen >= GLUE_PREFIX_MIN && runLen <= GLUE_PREFIX_MAX &&
						(j == 0 || !Character.isLetterOrDigit(o.charAt(j - 1)))) return true
				}
				at = o.indexOf(n, at + 1)
			}
			return false
		}
	}

	static Set tokens(String n) {
		return (n.replaceAll('[^a-z0-9 ]', ' ').split(' ') as List).findAll { it.length() > 2 } as Set
	}

	/**
	 * Every number the text states: bare figures, money and percentages, with thousands separators
	 * removed so "1,000" and "1000" are the same claim and a trailing "%" kept so "20" and "20%"
	 * are not.
	 *
	 * These are compared separately from the words around them because token overlap cannot see
	 * them: one figure changed inside a long paragraph scores ~0.98, lands in TEXT_CHANGED and
	 * never fails the page. On insurance content — sums assured, premiums, ages, policy terms —
	 * that is the most expensive thing this check can get wrong.
	 */
	static Set numbers(String n) {
		Set out = [] as Set
		def m = (n =~ /\d[\d,\.]*\s*%|\d[\d,]*(?:\.\d+)?/)
		while (m.find()) {
			// A digit glued to the end of a word or a bracket is a FOOTNOTE MARKER, not a figure:
			// "(Recommended)4", "severe disability3". The two CMSes number and render these
			// differently — Sitecore writes a plain digit in a child <sup>, AEM writes a superscript
			// that NFKC folds back to a plain digit — so comparing them reported "figures changed"
			// on text whose actual figures were identical. Both real findings on the first run over
			// the 9 GCDP pages were this. A real figure is separated from the word before it, or
			// carries a currency symbol.
			int st = m.start()
			if (st > 0) {
				char prev = n.charAt(st - 1)
				if (Character.isLetter(prev) || prev == (')' as char)) continue
			}
			String raw = m.group().replaceAll('\\s+', '').replace(',', '')
			if (raw.endsWith('.')) raw = raw[0..-2]
			// 1.0 and 1 are the same figure; 1.5 and 1.50 are too
			if (raw.contains('.') && !raw.endsWith('%')) {
				raw = raw.replaceAll('0+$', '').replaceAll('\\.$', '')
			}
			if (raw) out << raw
		}
		return out
	}

	static double overlap(Set a, Set b) {
		if (!a || !b) return 0d
		int inter = a.intersect(b).size()
		return inter / (double) Math.max(a.size(), b.size())
	}

	/** Whole-page matching text; falls back to joined items for snapshots taken before rootText existed */
	private static Map pageBlob(Map snap, List items) {
		String whole = (snap.rootText ?: '').toString()
		return whole ? blobOf([whole]) : blobOf(items.collect { textOf(it) })
	}

	/** Text used for BOTH matching and reporting: the element's whole text when available */
	static String textOf(Object item) {
		Map m = (Map) item
		String full = (m.full ?: '').toString()
		return full ? full : (m.text ?: '').toString()
	}

	private static Map blobOf(List texts) {
		String b = norm(texts.join(' \n '))
		return [blob: b, noSpace: b.replace(' ', '')]
	}

	/**
	 * Every element the collector discarded for being invisible, whichever mechanism hid it.
	 *
	 * `hidden` is "no client rects", `ariaHidden` is "inside [aria-hidden=true]". They are two
	 * counters because they are two different drops, and they are summed here because a page does
	 * not care how its content was hidden — see the SCOPE_ASYMMETRY block.
	 */
	private static int hiddenCount(Map skipped) {
		Map s = skipped ?: [:]
		return (((s.hidden ?: 0) as int) + ((s.ariaHidden ?: 0) as int))
	}

	/**
	 * "<a> against <b> <what>" when the two numbers are too far apart to have measured the same page,
	 * or '' when they are close enough.
	 *
	 * Both bars have to be cleared. The RATIO is what says the difference is structural rather than
	 * ordinary page-to-page variation; the FLOOR (SCOPE_ASYMMETRY_MIN) is what stops it firing on
	 * small numbers, where a 3x gap is 2 against 8 and means nothing.
	 */
	private static String gapBetween(int a, int b, String what) {
		int hi = Math.max(a, b), lo = Math.min(a, b)
		if (hi < SCOPE_ASYMMETRY_MIN || hi < lo * 3 + 10) return ''
		return "${a} ${what} on the live page against ${b} on the new page"
	}

	/**
	 * Compare two snapshots.
	 * Returns [findings: [[verdict, path, kind, text, note]], counts: [...], tabs: [...]]
	 */
	@Keyword
	static Map diff(Map sc, Map aem) {
		// Items are split per element and drive REPORTING (each one names its section).
		// Matching runs on the whole-element text captured alongside them, because the
		// split point is arbitrary across CMSes: Sitecore keeps the "PRU" of "PRUShield"
		// and its footnote markers in child elements, AEM writes them inline.
		List scAllItems = (sc.items ?: []) as List
		List aemAllItems = (aem.items ?: []) as List
		List scItems = scAllItems.findAll { textOf(it).length() >= MIN_LEN }
		List aemItems = aemAllItems.findAll { textOf(it).length() >= MIN_LEN }

		Map aemAll = pageBlob(aem, aemAllItems)
		Map scAll = pageBlob(sc, scAllItems)

		// Pair the interactive states first, then scope each item to the state it actually
		// belongs to. Keying by tab LABEL (what this used to do) matched nothing whenever the
		// two sites named their tabs differently, which is the normal case, so every item
		// silently fell through to whole-page presence and WRONG_TAB could never fire.
		Map states = StateMatch.pair((sc.states ?: []) as List, (aem.states ?: []) as List)
		Map aemBlobByScState = [:]      // sc stateId -> blob of the AEM state it was paired with
		Map aemStateLabel = [:]         // sc stateId -> that AEM state's label, for the note
		((List) states.pairs).each { p ->
			Map pair = (Map) p
			String key = ((Map) pair.sc).id.toString()
			aemBlobByScState[key] = blobOf([((Map) pair.aem).text])
			aemStateLabel[key] = ((Map) pair.aem).label
		}

		List findings = []
		List consumedAem = []

		// Occurrences are counted in the PAGE TEXT of both sides, never items on one side against
		// page text on the other: an item is a DOM split, and the two CMSes split differently, so
		// the two measures are not comparable and their difference is not a finding.
		Set countReported = [] as Set

		scItems.each { it ->
			Map item = (Map) it
			String n = norm(textOf(item))
			String stateKey = (item.stateId ?: '').toString()
			Map stateBlob = (Map) aemBlobByScState[stateKey]
			boolean shortText = n.length() < SHORT_TEXT_LEN

			// A short label is only accepted when a whole item on the new page carries it; a substring
			// hit inside a longer sentence is a coincidence, not a counterpart.
			//
			// The blob test stays in the conjunction rather than being replaced by the item test:
			// the blob is what carries the SCOPE (this state's text, or the whole page), while the
			// item test only says the label exists somewhere as a label. Dropping the blob test for
			// short texts silently un-scoped them — every short label found anywhere on the page
			// counted as found in the right tab, and WRONG_TAB stopped firing for them entirely.
			Closure present = { Map blob ->
				boolean inScope = foundIn(n, blob.blob, blob.noSpace)
				return shortText ? (inScope && foundAsWholeItem(n, aemItems)) : inScope
			}

			if (stateBlob != null && present(stateBlob)) return   // right text, right state
			// Not in a state, or in a state with no counterpart on the new page: "wrong tab" is
			// meaningless there, so presence anywhere is enough. The unpaired state is reported
			// once, on its own, rather than once per item inside it.
			boolean anywhere = present(aemAll)
			if ((stateKey == '' || stateBlob == null) && anywhere) {
				// Present, and in the only place it could be judged. Still check it is present as
				// OFTEN as it was — this is where a lost duplicate surfaces.
				if (n.length() >= COUNT_MIN_LEN && !countReported.contains(n)) {
					int want = countIn(n, scAll.blob)
					int got = countIn(n, aemAll.blob)
					if (want > 1 && got < want) {
						countReported << n
						findings << [verdict: 'COUNT_MISMATCH', path: item.path, kind: item.kind,
							text: textOf(item), note: "appears ${want} time(s) on the live page, ${got} on the new page"]
					}
				}
				return
			}
			if (anywhere) {
				String where = aemItems.find { norm(textOf(it)).contains(n) }?.path ?: ''
				findings << [verdict: 'WRONG_TAB', path: item.path, kind: item.kind, text: textOf(item),
					note: "should sit under \"${aemStateLabel[stateKey]}\" on the new page, but was found under: ${where ?: 'no named section'}"]
				return
			}
			// not present at all: reworded, or genuinely gone
			Set st = tokens(n)
			Map best = null
			int bestIndex = -1
			double bestScore = 0d
			aemItems.eachWithIndex { o, int ai ->
				// One counterpart, one claim. This scan used to run over every AEM item with no
				// exclusion — `consumedAem` was written on the line below and then read only by the
				// ONLY_ON_AEM pass — so a single AEM paragraph could be the "rewording" of any
				// number of live texts at once, quietly downgrading several genuine losses to one
				// shared counterpart.
				if (consumedAem.contains(ai)) return
				Map cand = (Map) o
				double s = overlap(st, tokens(norm(textOf(cand))))
				if (s > bestScore) { bestScore = s; best = cand; bestIndex = ai }
			}
			if (best != null && bestScore >= REWORD_OVERLAP) {
				consumedAem << bestIndex
				// Same sentence, different figures. Token overlap cannot separate these two cases —
				// it is exactly what makes a changed sum assured look like a rewording — so the
				// numbers are compared on their own and outrank the overlap score.
				Set want = numbers(n), got = numbers(norm(textOf(best)))
				// The note is the AEM wording and nothing else. The report's column header names the
				// side, so a "new page says:" label inside the cell only repeated it; NUMBER_CHANGED's
				// figure delta went the same way — the two figures now sit side by side in the two
				// columns. Both verdicts therefore write the same row, and only the verdict differs.
				findings << [verdict: want != got ? 'NUMBER_CHANGED' : 'TEXT_CHANGED', path: item.path,
					kind: item.kind, text: textOf(item), note: textOf(best)]
			} else {
				findings << [verdict: 'MISSING_ON_AEM', path: item.path, kind: item.kind, text: textOf(item), note: '']
			}
		}

		// AEM-only text is informational (subset rule); rewordings are not repeated here.
		// Indexed, not text-matched: `consumedAem` used to hold the counterpart's TEXT, so
		// consuming one item also silenced every other AEM item that happened to read the same.
		aemItems.eachWithIndex { it, int ai ->
			Map item = (Map) it
			if (consumedAem.contains(ai)) return
			String n = norm(textOf(item))
			if (foundIn(n, scAll.blob, scAll.noSpace)) return
			findings << [verdict: 'ONLY_ON_AEM', path: item.path, kind: item.kind, text: textOf(item), note: '']
		}

		// Structure: report the states that could not be paired, once each. This replaces the
		// old TAB_GROUP_ADDED / TAB_COUNT_MISMATCH pair, which compared whole groups by label
		// overlap and therefore fired on every page the moment the two sites renamed their tabs
		// — true, but useless, and it said nothing about which panel had no counterpart.
		((List) states.scOnly).each { s ->
			Map st = (Map) s
			findings << [verdict: 'STATE_ONLY_ON_LIVE', path: st.group ?: '', kind: st.kind,
				text: st.label, note: 'no matching ' + st.kind + ' on the new page — its content was compared against the whole page instead']
		}
		((List) states.aemOnly).each { s ->
			Map st = (Map) s
			findings << [verdict: 'STATE_ONLY_ON_NEW', path: st.group ?: '', kind: st.kind,
				text: st.label, note: st.kind + ' exists only on the new page']
		}

		// --- link destinations are NOT compared. Removed 2026-08-21, the day it first produced
		// numbers, by the decision of whoever reads this report.
		//
		// It worked, and what it found was real: 161 rows over 5 pages, 37 distinct destinations, 0 of
		// them a normalisation artefact. But they were all one thing — AEM restructured its paths
		// wholesale (/products/health-insurance/... -> /products/health/..., /wedo/wedohub/... ->
		// /knowledge-corner/...). The text, the tab and the card are identical on both sides; only the
		// URL prefix moved, deliberately, as part of the migration. A verdict that fires on every
		// product link of every page and means "the site was reorganised" is noise in a check whose
		// question is whether the TEXT survived.
		//
		// It was also the one verdict whose `path` was wrong: it set path to the label, so all 161 rows
		// showed the same string under "where on the live page" as under "text", while every other
		// verdict carries the real h1..h4 > tab breadcrumb.
		//
		// `href` is still captured on every item (@7) and `linkKey()` is still used by
		// ContentSnapshot.pathKeyOf to check the landed page, so restoring this costs a rule, not a
		// re-crawl. If it ever comes back it needs a way to ignore a known path migration.

		// --- dropdown contents are NOT compared. `<option>` text never renders, so the collector
		// in ContentScope takes it without asking whether the enclosing <select> is visible — and
		// that swept up Sitecore's hidden CRM fields. Every lifestage page reported the same three
		// Cold/Hot/Warm choices of the `.leadData` LeadRating select, which no visitor can see and
		// AEM has no reason to carry. The finding was noise on every page it fired on, so the
		// comparison is gone; `formOptions` is still captured in the snapshot, unread.

		// Did the two sides even read comparable content? Every finding above assumes they did.
		// When one side discards an order of magnitude more hidden elements than the other, that
		// assumption is false and the counts above are measuring the extraction, not the page —
		// which is the shape of the aria-hidden defect found on 2026-08-18, when AEM marked every
		// inactive tab panel hidden and Sitecore marked none. Nothing detected it; now something does.
		//
		// The two counters are ADDED because the CMSes hide content differently and the check must
		// not care which mechanism was used. Sitecore marks nothing aria-hidden, so its invisible
		// content is all in `hidden`; AEM marks every inactive panel, so its invisible content is
		// in `ariaHidden`. Reading `hidden` alone compared 83-160 against a flat 8 on all five
		// lifestage pages and fired on every one of them — a difference the extraction produced,
		// not the page. `ariaHidden` is absent from snapshots taken before 2026-08-21 and defaults
		// to 0, which reproduces the old reading rather than inventing a number for them.
		//
		// Three measures, not one. The hidden-element rule alone missed the worst pair in the repo:
		// en_lifestage compared 30 live items against 1 on the new side — an extraction that
		// collected a single element and left an intact rootText, so 12 of the 30 still "matched"
		// against a snapshot that had read nothing — and it stayed silent, because the hidden counts
		// were 12 against 0. What was asymmetric there was the ITEM COUNT, which nothing looked at.
		List asym = []
		String hiddenGap = gapBetween(hiddenCount((Map) sc.skipped), hiddenCount((Map) aem.skipped),
			'hidden element(s) skipped')
		if (hiddenGap) asym << hiddenGap
		String itemGap = gapBetween(scItems.size(), aemItems.size(), 'content item(s) collected')
		if (itemGap) asym << itemGap
		// scanned is absent from snapshots taken before the counter existed; compare only when both carry it
		Object scScanned = ((Map) (sc.skipped ?: [:])).scanned, aemScanned = ((Map) (aem.skipped ?: [:])).scanned
		if (scScanned != null && aemScanned != null) {
			String scanGap = gapBetween(scScanned as int, aemScanned as int, 'element(s) scanned')
			if (scanGap) asym << scanGap
		}
		if (asym) {
			findings << [verdict: 'SCOPE_ASYMMETRY', path: '', kind: 'scope',
				text: asym.join('; '),
				note: 'the two sides did not read comparable content, so every presence and occurrence ' +
					'result on this page is measuring the extraction rather than the page — check the ' +
					'content root and the hidden-content rules, and re-capture before reading any other finding']
		}

		Map counts = [:]
		findings.each { counts[it.verdict] = ((counts[it.verdict] ?: 0) as int) + 1 }
		return [findings: findings, counts: counts, scItems: scItems.size(), aemItems: aemItems.size(),
			statePairs: states.pairs]
	}

	/**
	 * Comparable form of a link target: the path, minus the AEM `/en` language prefix and minus any
	 * trailing slash, so only a real change of destination shows up. In-page anchors and
	 * javascript: handlers carry no destination to compare and are ignored.
	 */
	static String linkKey(String href) {
		String h = (href ?: '').trim()
		if (!h || h.startsWith('#') || h.startsWith('javascript:')) return ''
		return h.replaceFirst('^/en(/|$)', '/').replaceAll('/+$', '').toLowerCase()
	}


	/**
	 * Verdicts that fail the page; everything else is warning/info.
	 *
	 * LINK_CHANGED is gone (2026-08-21). This check asks one question — did the TEXT survive — and a
	 * changed URL prefix is not an answer to it. See the note where the comparison used to be.
	 *
	 * NUMBER_CHANGED is an error: a different figure is content the live page states and the new
	 * page does not, and plain rewording (mostly punctuation) stays a warning.
	 *
	 * COUNT_MISMATCH is an error: a text the live page states twice and the new page states once
	 * has lost one of its two appearances, and that is content the migration did not carry. The
	 * scopes the two sides read are still asymmetric (see SCOPE_ASYMMETRY below), so a page can
	 * fail on a count difference that the extraction, not the migration, produced — read the
	 * SCOPE_ASYMMETRY block on the page before acting on a COUNT_MISMATCH.
	 *
	 * TEXT_CHANGED is an error: the live wording is not what the new page shows, however small the
	 * edit. Rewording is a content difference, and this check exists to report content differences.
	 *
	 * SCOPE_ASYMMETRY is an error, and it is a different KIND of error from the six above it. Those
	 * say the migration lost content. This one says the check could not tell: the two sides did not
	 * read comparable content, so every other verdict on the page is measuring the extraction. It
	 * fails the page because the alternative is worse — as a warning it let a page whose new side
	 * collected one element out of the whole document read PASS, which is the single most expensive
	 * thing this check can do. A page that cannot be judged must not report that it passed.
	 *
	 * WRONG_TAB is NOT an error (2026-08-24). It only ever fires when the live text IS on the new
	 * page, under a different tab — so the answer to this check's one question, did the TEXT
	 * survive, is yes. What it reports is layout, and layout is out of scope here for the same
	 * reason LINK_CHANGED was removed. It stays as a warning, and weighs 0, because where a text
	 * sits is still worth reading: on en_lifestage_young_family 2 of its 17 rows were a
	 * product-filter CTA genuinely attached to the wrong product, and the other 15 were one block
	 * the live page repeats inside every tab and AEM renders once at page level.
	 */
	static final List ERRORS = ['MISSING_ON_AEM', 'NUMBER_CHANGED',
		'COUNT_MISMATCH', 'TEXT_CHANGED', 'SCOPE_ASYMMETRY']

	/**
	 * What each finding costs the page, as a fraction of one live item.
	 *
	 * ERRORS above says WHICH findings are content the migration did not carry. This says HOW MUCH
	 * of the page each one is, and the two are deliberately different questions: a text that is
	 * simply not on the new page is a whole item lost, the same text reworded is still readable and
	 * costs a tenth of one. Both are errors; only one of them should be able to fail a large page
	 * on its own.
	 *
	 * SCOPE_ASYMMETRY weighs 0 and is in HARD_FAIL instead — it does not say a fraction of the page
	 * was lost, it says the measurement is void, and there is no fraction that expresses that.
	 */
	static final Map WEIGHTS = [
		MISSING_ON_AEM    : 1.0d,  // not on the new page at all: a whole item gone
		NUMBER_CHANGED    : 1.0d,  // a different figure is a different statement — also HARD_FAIL
		STATE_ONLY_ON_LIVE: 0.5d,  // a live tab with no counterpart: everything under it is at risk
		COUNT_MISMATCH    : 0.3d,  // some of its appearances survived
		TEXT_CHANGED      : 0.1d,  // the text is there, reworded
		ONLY_ON_AEM       : 0.0d,  // the live page is a subset baseline: extra text is never a loss
		WRONG_TAB         : 0.0d,  // on the page, behind another tab: the text survived, only its place moved
		STATE_ONLY_ON_NEW : 0.0d,
		SCOPE_ASYMMETRY   : 0.0d]

	/**
	 * Verdicts that fail the page at any score.
	 *
	 * NUMBER_CHANGED: a wrong sum assured, premium or interest rate is not a small imperfection in
	 * an otherwise good page, it is the page stating something false. It must not be averaged away
	 * by the 300 texts around it that are fine.
	 *
	 * SCOPE_ASYMMETRY: its weight is 0 by design, so without this list a page whose ONLY finding is
	 * "the two sides did not read comparable content" would score a clean 100 and PASS. That is the
	 * en_lifestage regression the verdict was created to stop, re-entering through the score.
	 */
	static final List HARD_FAIL = ['NUMBER_CHANGED', 'SCOPE_ASYMMETRY']

	/**
	 * Findings that carry the verdict but are never written to findings.csv.
	 *
	 * findings.csv is the per-TEXT evidence: one row is one live text and what became of it, and the
	 * report tabulates it under "N of M live texts did not survive". SCOPE_ASYMMETRY is not a text.
	 * It is a statement about the crawl, and written into that file it rendered as a one-row "Fails
	 * the page" block on 27 pages — 19 of them URLs that 404 on BOTH sides, where the only thing the
	 * block reported was that the two error pages have different markup. That is noise in a report
	 * about migrated content.
	 *
	 * Suppressed from the CSV, NOT from the result: the finding stays in `result.findings`, so it
	 * still fails the page through HARD_FAIL, still counts in `summary()`, and its measurements are
	 * still recorded locally — in score.csv's `confidenceWhy` (see grade()) and in the content.txt
	 * summary line. Nothing is lost; it stops being tabulated as a failed text.
	 */
	static final List NO_CSV = ['SCOPE_ASYMMETRY']

	/** At or above this score the page passes */
	static final double PASS_SCORE = 95.0d
	/** At or above this score the page is through the gate but wants a human: WARN. Below it: FAIL. */
	static final double WARN_SCORE = 90.0d

	/**
	 * Fewer items than this and the score is too coarse to trust: one MISSING_ON_AEM out of 39
	 * moves it by more than 2.5 points, so a single finding can cross a whole band.
	 */
	static final int LOW_CONFIDENCE_ITEMS = 40

	/**
	 * The page's score and its grade.
	 *
	 * score = 100 x (1 - sum of finding weights / live items compared). Dividing by the number of
	 * live items is what makes two pages of different sizes comparable — and it is also exactly why
	 * the score cannot replace the findings: 99.2 on a 127-item page still means a real button is
	 * gone, and that button is in findings.csv either way. The score decides where the gate sits,
	 * not what is reported.
	 *
	 * Returns [score, grade, items, lost, hardFailures, confidence, confidenceWhy].
	 */
	@Keyword
	static Map grade(Map result) {
		List findings = (List) (result.findings ?: [])
		int items = (result.scItems ?: 0) as int

		// Nothing was compared, so nothing can be concluded. This must return before the arithmetic:
		// `1 - lost/0` is a division by zero, and reading "no items" as "no losses" is precisely the
		// failure mode that turns a broken crawl green. Callers already refuse these pages before
		// reaching diff(); this is the second line of defence, not the first.
		if (items <= 0) {
			return [score: 0.0d, grade: 'FAIL', items: 0, lost: 0.0d, hardFailures: 0,
				confidence: 'low', confidenceWhy: 'no live items were compared, so there is nothing to score']
		}

		double lost = 0.0d
		findings.each { lost += ((WEIGHTS[it.verdict] ?: 0.0d) as double) }
		// A page can carry more weight than it has items (every item missing, plus unmatched tabs).
		// Clamp rather than report a negative score: below zero there is nothing left to rank.
		double score = Math.max(0.0d, 100.0d * (1.0d - lost / items))

		int hard = findings.count { HARD_FAIL.contains(it.verdict) }
		// Banded first, then overridden — so a page that is 99% intact and states one wrong figure
		// still shows the 99 next to its FAIL. Hiding the score there would misdescribe the work.
		String g = score >= PASS_SCORE ? 'PASS' : score >= WARN_SCORE ? 'WARN' : 'FAIL'
		if (hard > 0) g = 'FAIL'

		String why = ''
		// The asymmetry finding is kept out of findings.csv (see NO_CSV), so this string is where its
		// measurements survive on disk. Carry the numbers, not just the conclusion: "9 element(s)
		// scanned on the live page against 38" is what tells a reader it is looking at two 404s.
		Map asym = (Map) findings.find { it.verdict == 'SCOPE_ASYMMETRY' }
		if (asym) {
			why = 'the two sides did not read comparable content, so the denominator is not the page — ' +
				(asym.text ?: '')
		} else if (items < LOW_CONFIDENCE_ITEMS) {
			why = "only ${items} live items were compared, so one finding moves the score by " +
				"${String.format('%.1f', 100.0d / items)} points"
		}

		return [score: score, grade: g, items: items, lost: lost, hardFailures: hard,
			confidence: why ? 'low' : 'normal', confidenceWhy: why]
	}

	/** Write findings.csv, score.csv and state_pairs.csv */
	@Keyword
	static void write(Map result, String outDir) {
		List csv = ['verdict,kind,path,text,note,weight']
		((List) result.findings).findAll { !NO_CSV.contains(it.verdict) }.each { f ->
			csv << [f.verdict, f.kind, AuditUtils.csvq(f.path), AuditUtils.csvq(f.text), AuditUtils.csvq(f.note),
				String.format('%.1f', (WEIGHTS[f.verdict] ?: 0.0d) as double)].join(',')
		}
		new File(outDir + '/findings.csv').setText(csv.join('\n'), 'UTF-8')

		// The score the verdict was banded from, so the report can show it without recomputing the
		// formula a second time. Two implementations of one formula drift; a file does not.
		Map g = grade(result)
		new File(outDir + '/score.csv').setText(['field,value',
			'score,' + String.format('%.1f', g.score as double),
			'grade,' + g.grade,
			'items,' + g.items,
			'lost,' + String.format('%.1f', g.lost as double),
			'hardFailures,' + g.hardFailures,
			'confidence,' + g.confidence,
			'confidenceWhy,' + AuditUtils.csvq(g.confidenceWhy)].join('\n'), 'UTF-8')

		List sp = ['pair,sc_id,sc_label,aem_id,aem_label,by,score']
		((List) (result.statePairs ?: [])).eachWithIndex { p, int i ->
			Map pair = (Map) p
			sp << [i, AuditUtils.csvq(((Map) pair.sc).id), AuditUtils.csvq(((Map) pair.sc).label),
				AuditUtils.csvq(((Map) pair.aem).id), AuditUtils.csvq(((Map) pair.aem).label),
				pair.by, String.format('%.2f', pair.score as double)].join(',')
		}
		new File(outDir + '/state_pairs.csv').setText(sp.join('\n'), 'UTF-8')

	}

	/** One-line summary used as the report's plain-language finding */
	@Keyword
	static String summary(Map result) {
		Map c = (Map) result.counts
		int paired = ((List) (result.statePairs ?: []))?.size() ?: 0
		// The leading clause is parsed by ReportBuilder.summaryOf with a literal regex; new counters
		// are appended after it so that regex keeps matching.
		Map g = grade(result)
		String hard = g.hardFailures > 0
			? ' (failed regardless of score: ' +
				((List) result.findings).findAll { HARD_FAIL.contains(it.verdict) }
					.collect { it.verdict }.unique().join(', ') + ')'
			: ''
		return "${result.scItems} live items compared: " +
			"${c.MISSING_ON_AEM ?: 0} missing, ${c.WRONG_TAB ?: 0} in the wrong tab, " +
			"${c.TEXT_CHANGED ?: 0} reworded, ${c.ONLY_ON_AEM ?: 0} only on the new page" +
			"; ${c.NUMBER_CHANGED ?: 0} with changed figures, ${c.COUNT_MISMATCH ?: 0} appearing fewer times" +
			"; ${paired} tab/section(s) paired, ${c.STATE_ONLY_ON_LIVE ?: 0} unmatched on the live page, " +
			"${c.STATE_ONLY_ON_NEW ?: 0} only on the new page" +
			"; score ${String.format('%.1f', g.score as double)}/100${hard}" +
			(g.confidence == 'low' ? " — low confidence: ${g.confidenceWhy}" : '')
	}
}

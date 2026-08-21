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
 *
 * Nested tabs are not a special page. Learn more about your needs
 * (Protection / Wealth / For your dependants) owns a different Find The Right
 * Plan on every lifestage page — that is just two tab levels. Each panel's
 * captured text is a claim, not only the DOM-split items: older snapshots
 * stored almost no items, so a product that swapped under Wealth > Savings
 * never became a row if we only walked items.
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

	static boolean foundAsWholeItem(String n, List items) {
		if (!n) return true
		return items.any { Object it ->
			String o = norm(textOf(it))
			if (o == n || o.startsWith(n) || o.endsWith(n)) return true
			if (o.length() > n.length() * SHORT_LABEL_SLACK) return false
			int at = o.indexOf(n)
			while (at >= 0) {
				boolean leftOk = (at == 0) || !Character.isLetterOrDigit(o.charAt(at - 1))
				int end = at + n.length()
				boolean rightOk = (end >= o.length()) || !Character.isLetterOrDigit(o.charAt(end))
				if (leftOk && rightOk) return true
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
				if (!shortText) return inScope
				// Headings and tab labels are compared as words. AEM tabs write #name into the
				// address bar; that is display, not missing text. Other short labels still need
				// a whole item so "Find out more" inside a sentence does not count.
				if (item.kind == 'heading' || isStateLabel(n, sc.states, aem.states)) return inScope
				return inScope && foundAsWholeItem(n, aemItems)
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
			double bestScore = 0d
			aemItems.each { o ->
				Map cand = (Map) o
				double s = overlap(st, tokens(norm(textOf(cand))))
				if (s > bestScore) { bestScore = s; best = cand }
			}
			if (best != null && bestScore >= REWORD_OVERLAP) {
				consumedAem << textOf(best)
				// Same sentence, different figures. Token overlap cannot separate these two cases —
				// it is exactly what makes a changed sum assured look like a rewording — so the
				// numbers are compared on their own and outrank the overlap score.
				Set want = numbers(n), got = numbers(norm(textOf(best)))
				if (want != got) {
					findings << [verdict: 'NUMBER_CHANGED', path: item.path, kind: item.kind, text: textOf(item),
						note: "figures changed: ${(want - got) ?: '(none)'} -> ${(got - want) ?: '(none)'}; new page says: " + textOf(best)]
				} else {
					findings << [verdict: 'TEXT_CHANGED', path: item.path, kind: item.kind, text: textOf(item),
						note: 'new page says: ' + textOf(best)]
				}
			} else {
				// Short CTAs are counted on the page blob (hero + each strip). One item-level
				// miss would screenshot the wrong Contact us. Defer to reportExtraCtaCopies.
				if (item.kind == 'cta' && shortText) return
				String near = nearContext(scAll.blob, n, 0)
				findings << [verdict: 'MISSING_ON_AEM', path: item.path, kind: item.kind,
					text: textOf(item),
					note: near ? ('at the content: “' + near + '”') : '']
			}
		}

		reportExtraCtaCopies(scItems, scAll, aemAll, findings)
		reportPairedStateClaims(sc, states, aemBlobByScState, aemStateLabel, aemAll, scItems, aemItems, findings)

		// AEM-only text is informational (subset rule); rewordings are not repeated here
		aemItems.each { it ->
			Map item = (Map) it
			if (consumedAem.contains(textOf(item))) return
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
			if (labelTextOnPage((String) st.label, aemAll)) {
				findings << [verdict: 'UI_DISPLAY', path: st.group ?: '', kind: st.kind,
					text: st.label,
					note: 'Sitecore shows this as a ' + st.kind + '. AEM has the same words in the page text (often a tab that adds #… to the URL). Compared as text only.']
			} else {
				findings << [verdict: 'STATE_ONLY_ON_LIVE', path: st.group ?: '', kind: st.kind,
					text: st.label, note: 'no matching ' + st.kind + ' on the new page — its content was compared against the whole page instead']
			}
		}
		((List) states.aemOnly).each { s ->
			Map st = (Map) s
			if (labelTextOnPage((String) st.label, scAll)) {
				findings << [verdict: 'UI_DISPLAY', path: st.group ?: '', kind: st.kind,
					text: st.label,
					note: 'AEM shows this as a ' + st.kind + ' (the URL gets #… when you click). Sitecore has the same words as page content. Compared as text only.']
			} else {
				findings << [verdict: 'STATE_ONLY_ON_NEW', path: st.group ?: '', kind: st.kind,
					text: st.label, note: st.kind + ' exists only on the new page']
			}
		}

		// --- links: same wording, different destination.
		// Matched on the LABEL, because that is what a visitor recognises, and only where the label
		// identifies exactly one link on each side — a "Find out more" appearing six times says
		// nothing about which six destinations should correspond.
		Map scLinks = [:], aemLinks = [:]
		scItems.each { Object o ->
			Map i = (Map) o
			if (i.kind == 'cta' && i.href) scLinks.get(norm(textOf(i)), []) << i.href.toString()
		}
		aemItems.each { Object o ->
			Map i = (Map) o
			if (i.kind == 'cta' && i.href) aemLinks.get(norm(textOf(i)), []) << i.href.toString()
		}
		scLinks.each { Object k, Object v ->
			String label = (String) k
			List hrefs = (List) v
			List other = (List) (aemLinks[label] ?: [])
			if (hrefs.size() != 1 || other.size() != 1) return
			String want = linkKey((String) hrefs[0]), got = linkKey((String) other[0])
			if (want && got && want != got) {
				findings << [verdict: 'LINK_CHANGED', path: label, kind: 'cta', text: label,
					note: "goes to ${got} on the new page, ${want} on the live page"]
			}
		}

		// Dropdown choices collected only from visible <select>s at capture time.
		Set scOpts = ((sc.formOptions ?: []) as List).collect { norm(it.toString()) }.findAll { it } as Set
		Set aemOpts = ((aem.formOptions ?: []) as List).collect { norm(it.toString()) }.findAll { it } as Set
		(scOpts - aemOpts).each { Object o ->
			findings << [verdict: 'OPTION_MISSING', path: '', kind: 'option', text: (String) o,
				note: 'a dropdown choice on the live page that the new page does not offer']
		}

		// Did the two sides even read comparable content? Every finding above assumes they did.
		// When one side discards an order of magnitude more hidden elements than the other, that
		// assumption is false and the counts above are measuring the extraction, not the page —
		// which is the shape of the aria-hidden defect found on 2026-08-18, when AEM marked every
		// inactive tab panel hidden and Sitecore marked none. Nothing detected it; now something does.
		int scHidden = ((((Map) (sc.skipped ?: [:])).hidden ?: 0) as int)
		int aemHidden = ((((Map) (aem.skipped ?: [:])).hidden ?: 0) as int)
		int hi = Math.max(scHidden, aemHidden), lo = Math.min(scHidden, aemHidden)
		if (hi >= SCOPE_ASYMMETRY_MIN && hi >= lo * 3 + 10) {
			findings << [verdict: 'SCOPE_ASYMMETRY', path: '', kind: 'scope',
				text: "${scHidden} hidden element(s) skipped on the live page against ${aemHidden} on the new page",
				note: 'the two sides did not read comparable content, so presence and occurrence counts ' +
					'on this page are weaker than they look — check the content root and the hidden-content rules']
		}

		Map counts = [:]
		findings.each { counts[it.verdict] = ((counts[it.verdict] ?: 0) as int) + 1 }
		return [findings: findings, counts: counts, scItems: scItems.size(), aemItems: aemItems.size(),
			statePairs: states.pairs]
	}

	/**
	 * Comparable form of a link target: the path only.
	 * AEM tabs append #tabname to the same page — that is UI, not a different destination.
	 * Hash-only hrefs and javascript: handlers are ignored.
	 */
	static String linkKey(String href) {
		String h = (href ?: '').trim()
		if (!h || h.startsWith('javascript:')) return ''
		int hash = h.indexOf('#')
		if (hash >= 0) h = h.substring(0, hash).trim()
		if (!h) return ''
		return h.replaceFirst('^/en(/|$)', '/').replaceAll('/+$', '').toLowerCase()
	}

	/** True when this text is the label of a tab or accordion on either snapshot. */
	static boolean isStateLabel(String n, Object scStates, Object aemStates) {
		if (!n) return false
		List all = []
		all.addAll((scStates ?: []) as List)
		all.addAll((aemStates ?: []) as List)
		return all.any { Object o ->
			norm(((Map) o).label?.toString()) == n
		}
	}

	/**
	 * Each paired Sitecore tab panel is a claim, not only the items collected
	 * inside it.
	 *
	 * Learn more about your needs (Protection / Wealth / For your dependants)
	 * swaps the whole Find The Right Plan. Those product names already live in
	 * `states[].text` on every lifestage snapshot. They never became items when
	 * Sitecore wrote <h4><b>PRU</b>Wealth Plus</h4> — ownText was empty — so
	 * walking items alone reported a clean page. Sentences from the Sitecore
	 * panel are checked against the paired AEM panel (same scope as items).
	 * After recapture the same sentences also become items and are skipped
	 * here so a row is not doubled.
	 */
	private static void reportPairedStateClaims(Map sc, Map states, Map aemBlobByScState,
			Map aemStateLabel, Map aemAll, List scItems, List aemItems, List findings) {
		Set already = [] as Set
		scItems.each { already << norm(textOf(it)) }
		findings.each { already << norm((((Map) it).text ?: '').toString()) }

		List scStates = (sc.states ?: []) as List
		((List) states.pairs).each { Object p ->
			Map pair = (Map) p
			Map scSt = (Map) pair.sc
			if (scSt.kind != 'tab') return
			Map stateBlob = (Map) aemBlobByScState[scSt.id.toString()]
			if (stateBlob == null) return
			String aemLabel = (aemStateLabel[scSt.id.toString()] ?: scSt.label)?.toString()
			String path = stateChain(scStates, scSt)
			List aemSentences = sentencesOf((String) ((Map) pair.aem).text)

			sentencesOf((String) scSt.text).each { Object raw ->
				String sentence = (String) raw
				String n = norm(sentence)
				if (!n || n.length() < MIN_LEN) return
				if (n == norm((String) scSt.label)) return
				if (coveredByCollectedText(n, already)) return

				if (foundIn(n, stateBlob.blob, stateBlob.noSpace)) {
					already << n
					return
				}
				already << n
				// Reword against THIS panel first. "A lifelong" vs "Lifelong" on the
				// same product card is not WRONG_TAB just because the other need-tab
				// still has the old wording.
				Set st = tokens(n)
				String bestText = ''
				double bestScore = 0d
				aemItems.each { Object o ->
					double s = overlap(st, tokens(norm(textOf(o))))
					if (s > bestScore) { bestScore = s; bestText = textOf(o) }
				}
				aemSentences.each { Object o ->
					String cand = (String) o
					double s = overlap(st, tokens(norm(cand)))
					if (s > bestScore) { bestScore = s; bestText = cand }
				}
				if (bestText && bestScore >= REWORD_OVERLAP) {
					Set want = numbers(n), got = numbers(norm(bestText))
					if (want != got) {
						findings << [verdict: 'NUMBER_CHANGED', path: path, kind: 'para', text: sentence,
							note: "figures changed: ${(want - got) ?: '(none)'} -> ${(got - want) ?: '(none)'}; new page says: " + bestText]
					} else {
						findings << [verdict: 'TEXT_CHANGED', path: path, kind: 'para', text: sentence,
							note: 'new page says: ' + bestText]
					}
					return
				}
				if (foundIn(n, aemAll.blob, aemAll.noSpace)) {
					findings << [verdict: 'WRONG_TAB', path: path, kind: 'para', text: sentence,
						note: "should sit under \"${aemLabel}\" on the new page, but was found under another section"]
					return
				}
				String near = nearContext(norm((String) scSt.text), n, 0)
				findings << [verdict: 'MISSING_ON_AEM', path: path, kind: 'para', text: sentence,
					note: near ? ('at the content: “' + near + '”') : ('under "' + path + '"')]
			}
		}
	}

	/** Ancestor tab labels, outermost first: "Wealth > Savings". */
	private static String stateChain(List states, Map st) {
		Map byId = [:]
		states.each { Object o ->
			Map s = (Map) o
			if (s.id != null) byId[s.id.toString()] = s
		}
		List labs = []
		Map cur = st
		Set seen = [] as Set
		while (cur != null && !seen.contains(cur.id.toString())) {
			seen << cur.id.toString()
			String lab = (cur.label ?: '').toString().trim()
			if (lab) labs.add(0, lab)
			String p = (cur.parent ?: '').toString()
			cur = p ? (Map) byId[p] : null
		}
		return labs.join(' > ')
	}

	static List sentencesOf(String raw) {
		if (!raw) return []
		String t = raw.replaceAll('\\s+', ' ').trim()
		List out = []
		int start = 0
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i)
			if ((c == ('.' as char) || c == ('?' as char) || c == ('!' as char))
					&& i + 1 < t.length() && t.charAt(i + 1) == (' ' as char)) {
				String s = t.substring(start, i).trim()
				if (s.length() >= MIN_LEN) out << s
				start = i + 2
			}
		}
		String tail = t.substring(start).trim()
		if (tail.length() >= MIN_LEN) out << tail
		return out
	}

	private static boolean coveredByCollectedText(String n, Set already) {
		if (already.contains(n)) return true
		return already.any { Object o ->
			String it = (o ?: '').toString()
			if (!it || it.length() < MIN_LEN) return false
			if (it == n || it.contains(n)) return true
			return n.startsWith(it) && it.length() >= 12
		}
	}

	/**
	 * Sitecore repeats the same short CTA (Contact us) beside each "Connect with a
	 * Financial Representative…" strip. Presence on AEM's hero must not hide those extras.
	 */
	private static void reportExtraCtaCopies(List scItems, Map scAll, Map aemAll, List findings) {
		Set labels = [] as Set
		scItems.each { Object o ->
			if (((Map) o).kind != 'cta') return
			String n = norm(textOf(o))
			if (n && n.length() < SHORT_TEXT_LEN) labels << n
		}
		labels.each { Object lab ->
			String n = (String) lab
			int want = countIn(n, scAll.blob)
			int got = countIn(n, aemAll.blob)
			if (got >= want) return
			String shown = n
			scItems.each { Object o ->
				if (((Map) o).kind == 'cta' && norm(textOf(o)) == n) shown = textOf(o)
			}
			// Same neighbour sentence on every tab → one finding, not three identical rows.
			Map byNear = [:]
			for (int i = got; i < want; i++) {
				String near = nearContext(scAll.blob, n, i) ?: ''
				byNear[near] = ((byNear[near] ?: 0) as int) + 1
			}
			byNear.each { Object nearObj, Object cntObj ->
				String near = nearObj?.toString() ?: ''
				int cnt = cntObj as int
				String note = near ? ('at the content: “' + near + '”') : ''
				if (cnt > 1 && note) note += ' (' + cnt + ' times on Sitecore)'
				findings << [verdict: 'MISSING_ON_AEM', path: near, kind: 'cta',
					text: shown, note: note]
			}
		}
	}

	/** Sentence immediately before the Nth occurrence of n in the page blob. */
	static String nearContext(String blob, String n, int occurrenceIndex) {
		if (!blob || !n) return ''
		int i = -n.length()
		for (int k = 0; k <= occurrenceIndex; k++) {
			i = blob.indexOf(n, i + n.length())
			if (i < 0) return ''
		}
		String pre = blob.substring(Math.max(0, i - 160), i).replaceAll('\\s+', ' ').trim()
		int cut = Math.max(pre.lastIndexOf('. '), pre.lastIndexOf('? '))
		if (cut >= 0 && cut < pre.length() - 6) pre = pre.substring(cut + 2).trim()
		if (pre.length() > 110) pre = pre.substring(pre.length() - 110).replaceFirst('^\\S{1,12}\\s', '')
		return pre.replaceAll(/^[“"'\s]+|[”"'\s]+$/, '')
	}

	/** Tab/section title appears as words on the other page (ignore #hash and widget type). */
	static boolean labelTextOnPage(String label, Map page) {
		String n = norm(label)
		if (!n || n.length() < 3) return false
		return foundIn(n, (page.blob ?: '').toString(), (page.noSpace ?: '').toString())
	}


	/**
	 * Verdicts that fail the page; everything else is warning/info.
	 *
	 * LINK_CHANGED is an error: a visitor pressing the same button arrives somewhere else, and
	 * nothing detected it before — items carried their wording but never their destination.
	 *
	 * NUMBER_CHANGED is an error: a different figure is content the live page states and the new
	 * page does not, and plain rewording (mostly punctuation) stays a warning.
	 *
	 * COUNT_MISMATCH is deliberately NOT an error yet. The counting is sound, but the two sides are
	 * not currently reading comparable content: on all six lifestage pages the live side discards
	 * 83-160 hidden elements where the new side discards exactly 8 (see SCOPE_ASYMMETRY below).
	 * Until the scopes are symmetric a count difference cannot be attributed to the page rather
	 * than to the extraction, and failing a page on it would repeat the alt_lost mistake — one
	 * reason firing everywhere and burying the real findings.
	 */
	static final List ERRORS = ['MISSING_ON_AEM', 'WRONG_TAB', 'NUMBER_CHANGED', 'LINK_CHANGED']

	/** Write findings.csv and state_pairs.csv */
	@Keyword
	static void write(Map result, String outDir) {
		List csv = ['verdict,kind,path,text,note']
		((List) result.findings).each { f ->
			csv << [f.verdict, f.kind, AuditUtils.csvq(f.path), AuditUtils.csvq(f.text), AuditUtils.csvq(f.note)].join(',')
		}
		new File(outDir + '/findings.csv').setText(csv.join('\n'), 'UTF-8')

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
		return "${result.scItems} live items compared: " +
			"${c.MISSING_ON_AEM ?: 0} missing, ${c.WRONG_TAB ?: 0} in the wrong tab, " +
			"${c.TEXT_CHANGED ?: 0} reworded, ${c.ONLY_ON_AEM ?: 0} only on the new page" +
			"; ${c.NUMBER_CHANGED ?: 0} with changed figures, ${c.COUNT_MISMATCH ?: 0} appearing fewer times" +
			"; ${paired} tab/section(s) paired, ${c.STATE_ONLY_ON_LIVE ?: 0} unmatched on the live page, " +
			"${c.STATE_ONLY_ON_NEW ?: 0} only on the new page"
	}
}

package migration

import com.kms.katalon.core.annotation.Keyword

/**
 * Pairs the interactive STATES of the two sites — one tab panel or accordion section each.
 *
 * Why this exists: the two CMSes do not organise the same content the same way. On
 * /en/lifestage/young-family the live site splits its plan finder by product family
 * (Medical & Accident | Critical Illness | Whole, Term Life & Maternity | Savings...) while
 * the new one splits by need (Protection | Wealth | For your dependants...). Scoping a
 * comparison by the tab LABEL therefore matched nothing, silently degraded to
 * "present anywhere on the page", and reported a TAB_GROUP_ADDED on every page.
 *
 * Pairing is scored, not keyed, on the same principle as ImageAssets.match: try the strong
 * identity first, fall back to the weaker signals, and never invent a counterpart — whatever
 * cannot be paired is reported as such.
 *
 *   label       1.00   the labels do agree sometimes ("Medical & Accident" / "Medical & accident")
 *   content     0.90 x token overlap of the panel text — the load-bearing signal, because the
 *                     labels are exactly the part the migration legitimately changed
 *   position    0.55   same group anchor and same ordinal, when nothing else distinguishes them
 *
 * Assignment runs over the whole score matrix, best pair first, so it is symmetric: it answers
 * "which AEM state does this Sitecore state correspond to" and "which Sitecore state does this
 * AEM state come from" in one pass rather than favouring whichever side is iterated.
 */
public class StateMatch {

	/** Below this, two states are not the same region — leaving both unpaired says more */
	static final double MIN_SCORE = 0.45

	static final double W_LABEL = 1.00d
	static final double W_CONTENT = 0.90d
	static final double W_POSITION = 0.55d

	/**
	 * @return [pairs: [[sc: <scState>, aem: <aemState>, score: d, by: 'label'|'content'|'position']],
	 *          scOnly: [<scState>], aemOnly: [<aemState>]]
	 */
	@Keyword
	static Map pair(List scStates, List aemStates) {
		List sc = (scStates ?: []) as List
		List aem = (aemStates ?: []) as List

		// Depth is deliberately NOT a constraint. Measured on /en/lifestage/caring-for-...:
		// the new page nests its widget two deep (Protection > Medical & accident | Critical
		// illness | Whole & term life; Wealth > Savings | Investments | Legacy planning) while
		// the live page keeps all nine panels flat. Pairing level by level therefore compared
		// the live "Medical & Accident" against the new "Protection" and matched nothing at all.
		// A state is free to pair with a state at any depth; the nesting is what the CAPTURE
		// needs, to open the ancestors before it can reach the panel.
		List pairs = assign(sc, aem)
		Set usedScIds = pairs.collect { ((Map) ((Map) it).sc).id } as Set
		Set usedAemIds = pairs.collect { ((Map) ((Map) it).aem).id } as Set

		List scOnly = sc.findAll { !usedScIds.contains(((Map) it).id) }
		List aemOnly = aem.findAll { !usedAemIds.contains(((Map) it).id) }
		return [pairs: pairs, scOnly: scOnly, aemOnly: aemOnly]
	}

	/** Best-first greedy assignment within one level. Nothing is paired below MIN_SCORE. */
	private static List assign(List sc, List aem) {
		List cand = []
		sc.eachWithIndex { s, int i ->
			aem.eachWithIndex { a, int j ->
				// A tab panel and an accordion section are different furniture; pairing them
				// would produce a picture pair nobody can read.
				if (((Map) s).kind != ((Map) a).kind) return
				Map sco = score((Map) s, (Map) a, i, j)
				if ((sco.score as double) >= MIN_SCORE) cand << [i: i, j: j, score: sco.score, by: sco.by]
			}
		}
		cand.sort { -(it.score as double) }

		Set usedSc = new HashSet(), usedAem = new HashSet()
		List out = []
		cand.each { Map c ->
			if (usedSc.contains(c.i) || usedAem.contains(c.j)) return
			usedSc << c.i
			usedAem << c.j
			out << [sc: sc[c.i as int], aem: aem[c.j as int], score: c.score, by: c.by]
		}
		return out
	}

	/** Best signal that links these two states, and how strong it is */
	static Map score(Map s, Map a, int i, int j) {
		double best = 0d
		String by = ''

		double ov = textOverlap((String) s.text, (String) a.text)

		// Labels win, but not flatly: this page carries the SAME label twice on each side
		// ("Critical illness" appears under both Protection and For your dependants on the new
		// page, and twice on the live page), so a flat 1.00 left the tie to sort order and the
		// two instances could be crossed. Scoring the label band by content keeps every label
		// match above every content-only match while still ordering the duplicates correctly.
		if (labelMatches((String) s.label, (String) a.label)) {
			best = 0.95d + 0.05d * ov
			by = 'label'
		}

		double content = W_CONTENT * ov
		if (content > best) { best = content; by = 'content' }

		// Same widget, same slot. Requires the group anchor to agree, so it cannot fire across
		// two structures of different depth where index i simply happens to equal index j.
		if (i == j && norm((String) s.group) && norm((String) s.group) == norm((String) a.group) && W_POSITION > best) {
			best = W_POSITION
			by = 'position'
		}
		return [score: best, by: by]
	}

	/**
	 * Labels agree when they normalize equal, or when one is the other plus a qualifier on a
	 * word boundary ("Savings" / "Savings & investments"). Substring alone is too loose — it
	 * would pair "Life" with "Whole & Term Life" and hide a genuine restructure.
	 */
	static boolean labelMatches(String x, String y) {
		String a = norm(x), b = norm(y)
		if (!a || !b) return false
		if (a == b) return true
		String longer = a.length() >= b.length() ? a : b
		String shorter = a.length() >= b.length() ? b : a
		if (shorter.length() < 4) return false
		return longer.startsWith(shorter + ' ') || longer.endsWith(' ' + shorter)
	}

	/**
	 * Token overlap of two panel texts. Deliberately a local copy rather than a call into
	 * migration.checks.ContentCompare: the comparison package already depends on this one, and
	 * pointing it back would make the two mutually dependent for six lines of set arithmetic.
	 * Words of 3 characters or less are dropped — "the", "you", "for" carry no identity.
	 */
	static double textOverlap(String x, String y) {
		Set a = words(x), b = words(y)
		if (!a || !b) return 0d
		int inter = a.intersect(b).size()
		return inter / (double) Math.max(a.size(), b.size())
	}

	private static Set words(String s) {
		return (norm(s).split(' ') as List).findAll { it.length() > 3 } as Set
	}

	static String norm(String s) {
		return (s ?: '').toLowerCase().replaceAll('[^a-z0-9 ]', ' ').replaceAll('\\s+', ' ').trim()
	}
}

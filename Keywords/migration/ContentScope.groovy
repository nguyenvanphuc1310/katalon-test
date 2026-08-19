package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory

import groovy.json.JsonSlurper

import org.openqa.selenium.JavascriptExecutor

/**
 * Resolves WHAT counts as page content on a given site, and reads it out of the
 * rendered DOM as structured content items.
 *
 * The old collector defined content by subtraction (body minus header/nav/footer),
 * which only works when both CMSes mark their chrome with the same tags — they do
 * not. AEM keeps its skip-link, back-to-top and external-link modal outside
 * <header>/<footer>, so all of it leaked into the comparison; Sitecore hides its
 * form-builder <label>s inside <main>, so those leaked too.
 *
 * Content is therefore defined POSITIVELY: a content root selector per host, plus
 * shared noise rules. No root -> the check fails loudly instead of silently
 * falling back to <body>.
 *
 * Selectors live in Data Files/site-profiles.json, keyed by host, so a new CMS is
 * a config block rather than a code change.
 *
 * Each item carries the tab/accordion it lives in, so "right text, wrong tab" can
 * be reported as WRONG_TAB instead of a bogus MISSING + ONLY_ON_AEM pair.
 */
public class ContentScope {

	private static Map profileCache = null

	/** Profile for the host of the given URL, falling back to the generic one */
	@Keyword
	static Map profileFor(String url) {
		if (profileCache == null) {
			File f = new File(RunConfiguration.getProjectDir() + '/Data Files/site-profiles.json')
			if (!f.exists()) throw new IllegalStateException('Missing Data Files/site-profiles.json')
			profileCache = (Map) new JsonSlurper().parseText(f.getText('UTF-8'))
		}
		String host = (url ?: '').replaceAll('^https?://', '').split('/')[0].toLowerCase()
		Map p = (Map) ((Map) profileCache.profiles)[host]
		if (p == null) {
			KeywordUtil.logInfo("No site profile for host '${host}' — using the generic profile")
			p = (Map) profileCache.default
		}
		return p
	}

	/**
	 * Read the content of the page currently open in the browser.
	 *
	 * NOTE: do not name this `collect`. `collect` is a Groovy extension method on Object,
	 * and Katalon's Groovy-Eclipse compiler emits a stub class for the whole file when a
	 * keyword shadows it — the run then dies with a misleading
	 * `UnsupportedClassVersionError: migration/ContentScope`.
	 * Returns [root: <selector used>, tabGroups: [[label, tabs[]]], items: [...], skipped: [...]]
	 * or [error: '...'] when the content root cannot be resolved.
	 */
	@Keyword
	static Map readPage(String url) {
		JavascriptExecutor js = (JavascriptExecutor) DriverFactory.getWebDriver()
		return (Map) js.executeScript(COLLECT_JS, profileFor(url))
	}

	/** Same as readPage() but raises the failure instead of returning it */
	@Keyword
	static Map readPageOrFail(String url) {
		Map r = readPage(url)
		if (r != null && r.notHtml) return r   // handled by the caller, not an error
		if (r == null || r.error) {
			throw new IllegalStateException("Content root not resolved on ${url}: ${r?.error} " +
				'— fix the contentRoot selectors in Data Files/site-profiles.json')
		}
		return r
	}

	static final String COLLECT_JS = '''
		var P = arguments[0];

		// Not every mapped URL is a page: one entry of the master mapping serves the same
		// PDF on both sides. Say so plainly instead of failing on a missing content root.
		var ct = document.contentType || '';
		if (ct.indexOf('html') < 0) return { notHtml: ct || 'unknown' };

		// ---- content root: positive scoping, no <body> fallback
		var root = null, rootSel = null;
		for (var i = 0; i < P.contentRoot.length && !root; i++) {
			var e = document.querySelector(P.contentRoot[i]);
			if (e) { root = e; rootSel = P.contentRoot[i]; }
		}
		if (!root) return { error: 'none of ' + P.contentRoot.join(', ') + ' matched' };

		var noiseSel = P.noise.join(',');

		// ---- tab groups: panel -> tab label, without clicking anything.
		// Both CMSes keep every panel in the DOM, so the panels can simply be read.
		//
		// Each panel is also recorded as a STATE: one option of one interactive region, the
		// unit the two sites are paired on. The two CMSes organise these widgets differently
		// (Sitecore splits by product family, AEM by need), so a state cannot be identified by
		// its label alone — it carries its group anchor and its text so StateMatch can pair it.
		var panelLabel = [];   // [element, label, stateId]
		var tabGroups = [];
		var states = [];       // { id, kind, group, label, el }
		function tabEntryOf(el) {
			for (var i = 0; i < panelLabel.length; i++) {
				if (panelLabel[i][0] === el || panelLabel[i][0].contains(el)) return panelLabel[i];
			}
			return null;
		}
		function txt(e) { return (e.textContent || '').replace(/\\s+/g, ' ').trim(); }
		function panelKeyMatches(v, key) {
			if (v === key) return true;
			if (v.length <= key.length || v.slice(-key.length) !== key) return false;
			var b = v.charAt(v.length - key.length - 1);
			return b === '-' || b === '_';
		}

		var T = P.tabs || {};
		var groups = T.group ? root.querySelectorAll(T.group) : [];
		for (var g = 0; g < groups.length; g++) {
			var tabs = groups[g].querySelectorAll(T.tab);
			var labels = [];
			var groupAnchor = txt(groups[g].previousElementSibling || groups[g]).slice(0, 80);
			for (var t = 0; t < tabs.length; t++) {
				var tab = tabs[t], label = txt(tab), panel = null;
				if (T.mode === 'aria') {
					var id = tab.getAttribute('aria-controls');
					if (id) panel = document.getElementById(id);
				} else {
					var key = tab.getAttribute(T.tabAttr);
					var box = groups[g].closest(T.container) || groups[g].parentElement;
					if (box && key) {
						panel = box.querySelector('[' + T.panelAttr + '="' + key + '"]');
						// Sitecore authors the same component two ways: data-tab="content-tab-1"
						// pointing at data-content="content-tab-1", and data-tab="tab1" pointing at
						// data-content="content-tab1". An exact-only lookup found no panel on the
						// second kind, so every inactive tab's text was dropped as hidden and then
						// reported as ONLY_ON_AEM. Fall back to a prefixed key, on a separator
						// boundary so "tab1" cannot claim "content-tab11".
						if (!panel) {
							var cands = box.querySelectorAll('[' + T.panelAttr + ']');
							for (var c = 0; c < cands.length; c++) {
								if (panelKeyMatches(cands[c].getAttribute(T.panelAttr) || '', key)) { panel = cands[c]; break; }
							}
						}
					}
				}
				if (label) labels.push(label);
				if (panel && label) {
					var sid = 'tab:' + g + ':' + t;
					panelLabel.push([panel, label, sid]);
					states.push({ id: sid, kind: 'tab', group: groupAnchor, label: label, el: panel });
				}
			}
			if (labels.length) tabGroups.push({ label: groupAnchor, tabs: labels });
		}

		// ---- accordion regions: region -> trigger text, so collapsed content stays comparable
		var accRegion = [];
		var trigSel = (P.accordionTrigger || []).join(',');
		if (trigSel) {
			var trigs = root.querySelectorAll(trigSel);
			for (var k = 0; k < trigs.length; k++) {
				var tr = trigs[k], reg = null;
				var aid = tr.getAttribute('aria-controls');
				var tgt = tr.getAttribute('data-target') || tr.getAttribute('href');
				if (aid) reg = document.getElementById(aid);
				if (!reg && tgt && tgt.charAt(0) === '#' && tgt.length > 1) { try { reg = root.querySelector(tgt); } catch (e) { } }
				if (!reg && tr.tagName === 'SUMMARY') reg = tr.parentElement;
				// Sitecore's accordion carries no aria-controls, no data-target and no href: the
				// panel is simply another child of the same wrapper. Profiles name it explicitly
				// rather than guessing at nextElementSibling, which on AEM would turn ordinary
				// [aria-expanded] buttons into fake accordion regions.
				if (!reg && P.accordionPanel && tr.parentElement) reg = tr.parentElement.querySelector(P.accordionPanel);
				if (reg) {
					var asid = 'acc:' + accRegion.length;
					var alabel = txt(tr).slice(0, 60);
					accRegion.push([reg, alabel, asid]);
					// An accordion's trigger text IS its label, so unlike a tab it usually pairs by
					// label; it is still scored on content, because a renamed section is common.
					states.push({ id: asid, kind: 'accordion', group: '', label: alabel, el: reg });
				}
			}
		}
		function accEntryOf(el) {
			for (var i = 0; i < accRegion.length; i++) {
				if (accRegion[i][0] === el || accRegion[i][0].contains(el)) return accRegion[i];
			}
			return null;
		}

		// ---- items: own text of each element, so parent and child never duplicate
		// Whole text of an element, noise pruned. Items are split per element so a finding
		// can name its section, but that split is arbitrary across CMSes: Sitecore keeps
		// the "PRU" of "PRUShield" and its footnote markers in child elements while AEM
		// writes them inline. Comparing element text instead of own text removes the
		// difference entirely.
		function cleanText(el) {
			var c = el.cloneNode(true);
			var kill = c.querySelectorAll((noiseSel ? noiseSel + ',' : '') + '[data-km-drop]');
			for (var i = 0; i < kill.length; i++) kill[i].remove();
			return (c.textContent || '').replace(/\\s+/g, ' ').trim();
		}
		function ownText(e) {
			var s = '';
			for (var n = e.firstChild; n; n = n.nextSibling) if (n.nodeType === 3) s += n.nodeValue;
			return s.replace(/\\s+/g, ' ').trim();
		}
		function hiddenFormLabel(e) {
			if (e.tagName !== 'LABEL') return false;
			var f = e.getAttribute('for');
			var c = f ? document.getElementById(f) : e.querySelector('input,select,textarea');
			if (!c) return false;
			return c.type === 'hidden' || c.getClientRects().length === 0;
		}

		// Every path that discards an element increments a counter, including the one for
		// "this element has no text of its own". That branch used to be the only silent one,
		// and silence there is expensive: `skipped` is the ONLY evidence anyone has that the
		// two sides read comparable content, and a run that collected 3 items out of 386
		// candidates reported all-zero skips, which reads as a clean page rather than a
		// broken extraction. `scanned` is kept for the same reason: without it there is no
		// way to tell "the root was tiny" from "everything in it was dropped".
		var items = [], skipped = { noise: 0, formLabel: 0, hidden: 0, tooShort: 0, noText: 0, scanned: 0 };
		var h = ['', '', '', ''];   // running h1..h4 context, document order
		var all = root.querySelectorAll('*');
		for (var a = 0; a < all.length; a++) {
			var el = all[a], tag = el.tagName;
			if (tag === 'H1' || tag === 'H2' || tag === 'H3' || tag === 'H4') {
				var lvl = parseInt(tag.charAt(1)) - 1;
				h[lvl] = txt(el);
				for (var z = lvl + 1; z < 4; z++) h[z] = '';
			}
			skipped.scanned++;
			var text = ownText(el);
			if (!text) { skipped.noText++; continue; }
			if (noiseSel && el.closest(noiseSel)) { skipped.noise++; continue; }
			if (hiddenFormLabel(el)) { skipped.formLabel++; el.setAttribute('data-km-drop', '1'); continue; }
			// A 1-char fragment is usually a footnote marker in its own <sup>: Sitecore
			// splits "What you already have" + "1" where AEM writes it as one string with
			// a superscript. Dropping it desynchronises the two sides, so only pure
			// punctuation is discarded here.
			if (!/[a-z0-9]/i.test(text)) { skipped.tooShort++; continue; }

			var tabEntry = tabEntryOf(el);
			var tabLabel = tabEntry ? tabEntry[1] : '';
			var accEntry = tabEntry ? null : accEntryOf(el);
			var accLabel = accEntry ? accEntry[1] : '';
			var stateId = tabEntry ? tabEntry[2] : (accEntry ? accEntry[2] : '');
			var visible = el.getClientRects().length > 0;
			// aria-hidden is NOT blanket noise: AEM marks every inactive tab panel with it,
			// while Sitecore marks none, so treating it as noise deleted one CMS's tab
			// content and kept the other's. Only decorative aria-hidden outside any tab or
			// accordion is dropped.
			if (!tabLabel && !accLabel && el.closest('[aria-hidden=true]')) { skipped.noise++; continue; }
			// Hidden and not reachable by any tab/accordion = not page content
			// (this is what removes Sitecore's hidden form-builder labels).
			if (!visible && !tabLabel && !accLabel) { skipped.hidden++; continue; }

			var path = [];
			for (var p = 0; p < 4; p++) if (h[p]) path.push(h[p]);
			if (tabLabel) path.push('tab:' + tabLabel);
			else if (accLabel) path.push('section:' + accLabel);

			var kind = (tag.charAt(0) === 'H' && tag.length === 2) ? 'heading'
				: el.closest('a,button') ? 'cta'
				: el.closest('li') ? 'bullet' : 'para';

			// Where a link actually goes. Nothing compared this before, so a CTA that kept its
			// wording and changed its destination passed every check on the page.
			var href = '';
			if (kind === 'cta') {
				var a = el.closest('a');
				if (a && a.getAttribute('href')) {
					try {
						var u = new URL(a.href, document.baseURI);
						// path only: the domain legitimately changes between the two systems
						href = u.pathname.replace(/\\/+$/, '') + (u.hash || '');
					} catch (e) { href = a.getAttribute('href') || ''; }
				}
			}

			items.push({
				text: text, full: cleanText(el), kind: kind, path: path.join(' > '),
				tab: tabLabel, stateId: stateId, href: href,
				reach: visible ? 'visible' : (tabLabel ? 'tab' : 'accordion')
			});
		}
		// <option> texts. They are in the noise list and are never rendered, so the item loop drops
		// them as hidden — which means the contents of every dropdown (product lists, terms,
		// branches) were outside the comparison entirely. Collected as a set of their own.
		var formOptions = [];
		var opts = root.querySelectorAll('option');
		for (var o = 0; o < opts.length; o++) {
			var ot = txt(opts[o]);
			if (ot && formOptions.indexOf(ot) < 0) formOptions.push(ot);
		}

		var rootText = cleanText(root);
		// States carry their own text: this is what a comparison is scoped to once the two
		// sides have been paired, and what the pairing itself is scored on.
		// Nesting: a tab panel often contains further tabs or accordions, and a child can only
		// be opened once its parent is open. Recording the innermost enclosing state lets the
		// pairing keep children inside paired parents, and lets the capture walk the chain from
		// the outside in instead of clicking at a control that is still hidden.
		for (var i = 0; i < states.length; i++) {
			var best = null;
			for (var j = 0; j < states.length; j++) {
				if (i === j || states[j].el === states[i].el) continue;
				if (!states[j].el.contains(states[i].el)) continue;
				if (!best || best.el.contains(states[j].el)) best = states[j];
			}
			states[i].parent = best ? best.id : '';
		}
		var stateOut = [];
		for (var i = 0; i < states.length; i++) {
			stateOut.push({ id: states[i].id, kind: states[i].kind, group: states[i].group,
				label: states[i].label, parent: states[i].parent, text: cleanText(states[i].el) });
			// Left on the element on purpose (unlike data-km-drop below, which is cleaned up):
			// the evidence pass re-runs this enumeration on a freshly loaded page and then finds
			// a panel by [data-km-state="<id>"], so the state ids never have to be re-derived by
			// a second, drifting copy of this logic.
			try { states[i].el.setAttribute('data-km-state', states[i].id); } catch (e) { }
		}
		var marked = root.querySelectorAll('[data-km-drop]');
		for (var i = 0; i < marked.length; i++) marked[i].removeAttribute('data-km-drop');

		return { root: rootSel, tabGroups: tabGroups, items: items, skipped: skipped,
			rootText: rootText, states: stateOut, formOptions: formOptions };
	'''
}

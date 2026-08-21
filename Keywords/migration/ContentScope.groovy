package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory

import groovy.json.JsonSlurper

import java.time.Duration

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.TimeoutException
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait

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
		WebDriver driver = DriverFactory.getWebDriver()
		waitForContentRoot(driver, url)
		WebActions.waitUntilTabPanelsReady(8)
		WebActions.openMissingTabPanels()
		WebActions.stopBackgroundLoad()
		return collectViaSelenium(driver, url)
	}

	private static Map collectViaSelenium(WebDriver driver, String url) {
		JavascriptExecutor js = (JavascriptExecutor) driver
		def timeouts = driver.manage().timeouts()
		Duration previous = null
		try {
			try { previous = timeouts.getScriptTimeout() } catch (Exception ignore) { }
			// Each call must stay short. The old 30s one-shot cloned main for every
			// heading/link; a full AEM DOM (all tab panels) blew that budget and
			// looked like "a lot of errors".
			timeouts.scriptTimeout(Duration.ofSeconds(12))
			KeywordUtil.logInfo('Reading page text from main after tab panels are present')
			Map scope = runCollectSetup(js, url)
			if (scope == null || scope.error || scope.notHtml) return scope
			return scanItemsAndPull(js, scope)
		} catch (TimeoutException first) {
			KeywordUtil.logInfo('Content extract timed out on ' + url + ' — stop leftover loads and retry once')
			WebActions.stopBackgroundLoad()
			try {
				Map scope = runCollectSetup(js, url)
				if (scope == null || scope.error || scope.notHtml) return scope
				return scanItemsAndPull(js, scope)
			} catch (TimeoutException second) {
				KeywordUtil.markWarning('Content extract still timing out on ' + url + ' — keeping rootText only')
				return fallbackCollect(js, url)
			}
		} finally {
			if (previous != null) {
				try { timeouts.scriptTimeout(previous) } catch (Exception ignore) { }
			}
		}
	}

	private static Map scanItemsAndPull(JavascriptExecutor js, Map scope) {
		int allCount = (scope.allCount ?: 0) as int
		int from = 0
		while (from < allCount) {
			int to = Math.min(from + ITEM_SCAN_CHUNK, allCount)
			js.executeScript(COLLECT_ITEMS_JS, from, to)
			from = to
		}
		Object n = js.executeScript('return (window.__kmItems||[]).length')
		scope.itemCount = (n instanceof Number) ? ((Number) n).intValue() : 0
		try {
			Object sk = js.executeScript('return JSON.stringify(window.__kmSkipped||{})')
			if (sk instanceof String) scope.skipped = (Map) new JsonSlurper().parseText((String) sk)
		} catch (Exception ignore) { }
		scope.remove('allCount')
		return pullItems(js, scope)
	}

	private static Map runCollectSetup(JavascriptExecutor js, String url) {
		Object raw = js.executeScript(COLLECT_SETUP_JS, profileFor(url))
		return (raw instanceof String)
			? (Map) new JsonSlurper().parseText((String) raw)
			: (Map) raw
	}

	private static Map fallbackCollect(JavascriptExecutor js, String url) {
		try {
			Object raw = js.executeScript('''
				var sels = arguments[0] || [];
				var root = null, rootSel = null;
				for (var i = 0; i < sels.length && !root; i++) {
					var e = document.querySelector(sels[i]);
					if (e) { root = e; rootSel = sels[i]; }
				}
				if (!root) return JSON.stringify({ error: 'no content root' });
				var t = (root.textContent || '').replace(/\\s+/g, ' ').trim();
				return JSON.stringify({
					root: rootSel, tabGroups: [], items: [], itemCount: 0,
					skipped: { noise: 0, formLabel: 0, hidden: 0, tooShort: 0, noText: 0, scanned: 0 },
					rootText: t, states: [], formOptions: []
				});
			''', profileFor(url).contentRoot)
			return (raw instanceof String)
				? (Map) new JsonSlurper().parseText((String) raw)
				: (Map) raw
		} catch (Exception e) {
			return [error: 'collect timed out: ' + (e.message ?: e)]
		}
	}

	/**
	 * Items are pulled in slices. Returning 200 item maps in one executeScript
	 * is what left AEM snapshots with scanned=2 and two "Home" rows — Chrome
	 * keeps the long rootText string and drops the array.
	 */
	private static final int ITEM_CHUNK = 30
	/** How many DOM nodes one executeScript may scan. Keep this small on AEM. */
	private static final int ITEM_SCAN_CHUNK = 40

	private static Map pullItems(JavascriptExecutor js, Map scope) {
		if (scope == null) return scope
		int n = (scope.itemCount ?: 0) as int
		List have = (scope.items instanceof List) ? new ArrayList((List) scope.items) : []
		if (n <= 0) {
			scope.items = have
			return scope
		}
		int from = have.size()
		while (from < n) {
			int to = Math.min(from + ITEM_CHUNK, n)
			Object chunk = js.executeScript(
				'var a=window.__kmItems||[]; return JSON.stringify(a.slice(arguments[0], arguments[1]));',
				from, to)
			List part = (chunk instanceof String)
				? (List) new JsonSlurper().parseText((String) chunk)
				: ((chunk instanceof List) ? (List) chunk : [])
			have.addAll(part ?: [])
			from = to
		}
		try {
			js.executeScript('''
				window.__kmItems=null; window.__kmItemCount=0; window.__kmAll=null;
				var m=document.querySelectorAll("[data-km-sid]");
				for (var i=0;i<m.length;i++) m[i].removeAttribute("data-km-sid");
			''')
		} catch (Exception ignore) { }
		scope.items = have
		scope.remove('itemCount')
		KeywordUtil.logInfo('Collected ' + have.size() + ' content item(s) in slices (itemCount=' + n + ')')
		return scope
	}

	private static void waitForContentRoot(WebDriver driver, String url) {
		Map p = profileFor(url)
		List sels = (p.contentRoot instanceof List) ? (List) p.contentRoot : []
		String joined = sels.collect { it?.toString()?.trim() }.findAll { it }.join(', ')
		if (!joined) return
		try {
			new WebDriverWait(driver, Duration.ofSeconds(8))
				.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(joined)))
			// Product Deck has far fewer than 12 tags — waiting for 12 just burned 10s
			// on a page that was already complete. Lifestage tab panels are waited
			// separately in waitUntilTabPanelsReady.
			JavascriptExecutor js = (JavascriptExecutor) driver
			new WebDriverWait(driver, Duration.ofSeconds(5)).until {
				Object n = js.executeScript(
					'var r=document.querySelector(arguments[0]);' +
					'return r ? r.querySelectorAll("h1,h2,h3,h4,p,li,a").length : 0',
					joined)
				return (n instanceof Number) && ((Number) n).intValue() >= 1
			}
		} catch (Exception e) {
			KeywordUtil.logInfo('Content root not present yet on ' + url + ': ' + (e.message ?: e))
		}
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

	/**
	 * Discover root / tabs / accordions and stash the node list. Does not scan
	 * every heading. cloneNode(true) of AEM main per item is what hit the 30s
	 * script timeout once the full tab-panel DOM was allowed to finish.
	 */
	static final String COLLECT_SETUP_JS = '''
		var P = arguments[0];
		var ct = document.contentType || '';
		if (ct.indexOf('html') < 0) return JSON.stringify({ notHtml: ct || 'unknown' });

		var root = null, rootSel = null;
		for (var i = 0; i < P.contentRoot.length && !root; i++) {
			var e = document.querySelector(P.contentRoot[i]);
			if (e) { root = e; rootSel = P.contentRoot[i]; }
		}
		if (!root) return JSON.stringify({ error: 'none of ' + P.contentRoot.join(', ') + ' matched' });

		var noiseSel = (P.noise || []).join(',');
		function walkText(el) {
			if (!el) return '';
			var out = [];
			function rec(n) {
				if (n.nodeType === 3) { out.push(n.nodeValue); return; }
				if (n.nodeType !== 1) return;
				if (n.getAttribute && n.getAttribute('data-km-drop')) return;
				if (noiseSel) { try { if (n.matches(noiseSel)) return; } catch (e) {} }
				for (var c = n.firstChild; c; c = c.nextSibling) rec(c);
			}
			rec(el);
			return out.join('').replace(/\\s+/g, ' ').trim();
		}
		function txt(e) { return walkText(e); }
		function panelKeyMatches(v, key) {
			if (v === key) return true;
			if (v.length <= key.length || v.slice(-key.length) !== key) return false;
			var b = v.charAt(v.length - key.length - 1);
			return b === '-' || b === '_';
		}
		function shortLabel(el) {
			if (!el) return '';
			var tag = el.tagName;
			if (tag === 'H1' || tag === 'H2' || tag === 'H3' || tag === 'H4' || tag === 'H5' || tag === 'P' || tag === 'A' || tag === 'SPAN' || tag === 'BUTTON') {
				return walkText(el).slice(0, 80);
			}
			var h = el.querySelector && el.querySelector('h1,h2,h3,h4,h5,p');
			return h ? walkText(h).slice(0, 80) : '';
		}

		var tabGroups = [];
		var states = [];
		var tabSpecs = [];
		if (P.tabs) {
			if (P.tabs.length !== undefined && P.tabs[0] && (P.tabs[0].mode || P.tabs[0].group)) {
				for (var si = 0; si < P.tabs.length; si++) tabSpecs.push(P.tabs[si]);
			} else {
				tabSpecs.push(P.tabs);
			}
		}
		var groupOffset = 0;
		for (var si = 0; si < tabSpecs.length; si++) {
			var T = tabSpecs[si] || {};
			var groups = T.group ? root.querySelectorAll(T.group) : [];
			for (var g = 0; g < groups.length; g++) {
				var tabs = groups[g].querySelectorAll(T.tab);
				var labels = [];
				var groupAnchor = (groups[g].getAttribute('aria-label') || '').trim();
				if (!groupAnchor) groupAnchor = shortLabel(groups[g].previousElementSibling);
				for (var t = 0; t < tabs.length; t++) {
					var tab = tabs[t], label = walkText(tab).slice(0, 80), panel = null;
					if (T.mode === 'aria') {
						var id = tab.getAttribute('aria-controls');
						if (id) panel = document.getElementById(id);
					} else if (T.mode === 'hash') {
						var href = (tab.getAttribute('href') || '').replace(/^#/, '');
						if (href && T.panelAttr) {
							panel = root.querySelector('[' + T.panelAttr + '="' + href + '"]');
						}
					} else {
						var key = tab.getAttribute(T.tabAttr);
						var box = groups[g].closest(T.container) || groups[g].parentElement;
						if (box && key) {
							panel = box.querySelector('[' + T.panelAttr + '="' + key + '"]');
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
						var sid = 'tab:' + (groupOffset + g) + ':' + t;
						panel.setAttribute('data-km-sid', sid);
						states.push({ id: sid, kind: 'tab', group: groupAnchor, label: label, el: panel });
					}
				}
				if (labels.length) tabGroups.push({ label: groupAnchor, tabs: labels });
			}
			groupOffset += groups.length;
		}

		var trigSel = (P.accordionTrigger || []).join(',');
		if (trigSel) {
			var trigs = root.querySelectorAll(trigSel);
			var accN = 0;
			for (var k = 0; k < trigs.length && accN < 80; k++) {
				var tr = trigs[k], reg = null;
				if (tr.closest && tr.closest('header,nav,footer,[role=banner],[role=navigation],[role=contentinfo]')) continue;
				var aid = tr.getAttribute('aria-controls');
				var tgt = tr.getAttribute('data-target') || tr.getAttribute('href');
				if (aid) reg = document.getElementById(aid);
				if (!reg && tgt && tgt.charAt(0) === '#' && tgt.length > 1) { try { reg = root.querySelector(tgt); } catch (e) { } }
				if (!reg && tr.tagName === 'SUMMARY') reg = tr.parentElement;
				if (!reg && P.accordionPanel && tr.parentElement) reg = tr.parentElement.querySelector(P.accordionPanel);
				if (reg && !reg.getAttribute('data-km-sid')) {
					var asid = 'acc:' + accN;
					var alabel = walkText(tr).slice(0, 60);
					reg.setAttribute('data-km-sid', asid);
					states.push({ id: asid, kind: 'accordion', group: '', label: alabel, el: reg });
					accN++;
				}
			}
		}

		for (var i = 0; i < states.length; i++) {
			var best = null;
			for (var j = 0; j < states.length; j++) {
				if (i === j || states[j].el === states[i].el) continue;
				if (!states[j].el.contains(states[i].el)) continue;
				if (!best || best.el.contains(states[j].el)) best = states[j];
			}
			states[i].parent = best ? best.id : '';
		}
		function ancestorLabels(sid) {
			var labs = [], seen = {};
			while (sid && !seen[sid]) {
				seen[sid] = 1;
				var st = null;
				for (var i = 0; i < states.length; i++) if (states[i].id === sid) { st = states[i]; break; }
				if (!st) break;
				labs.unshift(st.label);
				sid = st.parent;
			}
			return labs;
		}

		var stateMeta = {};
		var chainById = {};
		var stateOut = [];
		for (var i = 0; i < states.length; i++) {
			var st = states[i];
			chainById[st.id] = ancestorLabels(st.id);
			stateMeta[st.id] = { kind: st.kind, label: st.label };
			stateOut.push({ id: st.id, kind: st.kind, group: st.group,
				label: st.label, parent: st.parent, text: walkText(st.el) });
		}

		var formOptions = [];
		var selects = root.querySelectorAll('select');
		for (var s = 0; s < selects.length; s++) {
			var sel = selects[s];
			if ((sel.offsetWidth || 0) < 16 || (sel.offsetHeight || 0) < 16) continue;
			var opts = sel.options || [];
			for (var o = 0; o < opts.length; o++) {
				var ot = (opts[o].textContent || '').replace(/\\s+/g, ' ').trim();
				if (ot && formOptions.indexOf(ot) < 0) formOptions.push(ot);
			}
		}

		var all = root.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,a,button');
		window.__kmRoot = root;
		window.__kmAll = all;
		window.__kmItems = [];
		window.__kmHeads = ['', '', '', ''];
		window.__kmNoise = noiseSel;
		window.__kmStateMeta = stateMeta;
		window.__kmChain = chainById;
		window.__kmSkipped = { noise: 0, formLabel: 0, hidden: 0, tooShort: 0, noText: 0, scanned: 0 };

		return JSON.stringify({
			root: rootSel, tabGroups: tabGroups, items: [], itemCount: 0,
			allCount: all.length, skipped: window.__kmSkipped,
			rootText: walkText(root), states: stateOut, formOptions: formOptions
		});
	'''

	static final String COLLECT_ITEMS_JS = '''
		var from = arguments[0] | 0;
		var to = arguments[1] | 0;
		var all = window.__kmAll;
		if (!all) return 0;
		var noiseSel = window.__kmNoise || '';
		var h = window.__kmHeads || ['', '', '', ''];
		var skipped = window.__kmSkipped || { noise: 0, formLabel: 0, hidden: 0, tooShort: 0, noText: 0, scanned: 0 };
		var items = window.__kmItems || [];
		var stateMeta = window.__kmStateMeta || {};
		var chainById = window.__kmChain || {};

		function walkText(el) {
			if (!el) return '';
			var out = [];
			function rec(n) {
				if (n.nodeType === 3) { out.push(n.nodeValue); return; }
				if (n.nodeType !== 1) return;
				if (n.getAttribute && n.getAttribute('data-km-drop')) return;
				if (noiseSel) { try { if (n.matches(noiseSel)) return; } catch (e) {} }
				for (var c = n.firstChild; c; c = c.nextSibling) rec(c);
			}
			rec(el);
			return out.join('').replace(/\\s+/g, ' ').trim();
		}
		function ownText(e) {
			var s = '';
			for (var n = e.firstChild; n; n = n.nextSibling) if (n.nodeType === 3) s += n.nodeValue;
			return s.replace(/\\s+/g, ' ').trim();
		}

		var end = Math.min(to, all.length);
		for (var a = from; a < end; a++) {
			var el = all[a], tag = el.tagName;
			if (tag === 'H1' || tag === 'H2' || tag === 'H3' || tag === 'H4') {
				var lvl = parseInt(tag.charAt(1)) - 1;
				h[lvl] = walkText(el);
				for (var z = lvl + 1; z < 4; z++) h[z] = '';
			}
			skipped.scanned++;
			var full = walkText(el);
			var text = ownText(el) || full;
			if (!text) { skipped.noText++; continue; }
			if (noiseSel && el.closest(noiseSel)) { skipped.noise++; continue; }
			if (!/[a-z0-9]/i.test(text)) { skipped.tooShort++; continue; }

			var host = el.closest && el.closest('[data-km-sid]');
			var stateId = host ? (host.getAttribute('data-km-sid') || '') : '';
			var meta = stateId ? (stateMeta[stateId] || {}) : {};
			var tabLabel = (meta.kind === 'tab') ? (meta.label || '') : '';
			var accLabel = (meta.kind === 'accordion') ? (meta.label || '') : '';
			var visible = !!stateId;
			if (!stateId) {
				if (el.closest('[aria-hidden=true]')) { skipped.noise++; continue; }
				visible = !!(el.offsetParent) || (el.getClientRects && el.getClientRects().length > 0);
				if (!visible) { skipped.hidden++; continue; }
			}

			var path = [];
			for (var p = 0; p < 4; p++) if (h[p]) path.push(h[p]);
			if (stateId) {
				var chain = chainById[stateId] || [];
				if (chain.length) path.push((tabLabel ? 'tab:' : 'section:') + chain.join(' > '));
			}

			var kind = (tag.charAt(0) === 'H' && tag.length === 2) ? 'heading'
				: el.closest('a,button') ? 'cta'
				: el.closest('li') ? 'bullet' : 'para';
			var href = '';
			if (kind === 'cta') {
				var link = el.closest('a');
				if (link && link.getAttribute('href')) {
					try {
						var u = new URL(link.href, document.baseURI);
						href = u.pathname.replace(/\\/+$/, '');
					} catch (e) { href = link.getAttribute('href') || ''; }
				}
			}
			items.push({
				text: text, full: full, kind: kind, path: path.join(' > '),
				tab: tabLabel, stateId: stateId, href: href,
				reach: visible ? 'visible' : (tabLabel ? 'tab' : 'accordion')
			});
		}
		window.__kmHeads = h;
		window.__kmSkipped = skipped;
		window.__kmItems = items;
		return items.length;
	'''
}

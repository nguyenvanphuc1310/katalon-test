package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory

import migration.checks.ContentCompare

import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.WebDriver

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * One picture per finding: crop the viewport around the smallest visible text
 * that matches the finding. No page-specific CSS. If the words are not on
 * screen, skip — a missing picture is better than a wrong one.
 *
 * Files: Reports/ContentAudit/<slug>/evidence/000-sitecore.png, 000-aem.png
 */
public class ContentEvidence {

	static final int MAX = 15
	static final int PAD = 16
	static final int MAX_CROP_H = 240

	@Keyword
	static void capture(String sitecoreurl, String aemUrl, List findings) {
		List all = (findings ?: []) as List
		if (!all || !sitecoreurl?.trim() || !aemUrl?.trim()) return

		String outDir = AuditUtils.reportDir('ContentAudit', aemUrl) + '/evidence'
		File dir = new File(outDir)
		if (dir.exists()) dir.listFiles()?.each { it.delete() }
		dir.mkdirs()

		List shoot = pick(all)
		KeywordUtil.logInfo("Content evidence: ${shoot.size()} finding(s) → ${outDir}")

		shootSide(aemUrl, 'aem', dir, shoot)
		shootSide(sitecoreurl, 'sitecore', dir, shoot)
		// Tabs were clicked to reveal hidden cards — next check must not reuse this DOM.
		WebActions.pageMutated = true
	}

	static String fileName(int idx, String side) {
		return String.format('%03d-%s.png', idx, side)
	}

	private static List pick(List findings) {
		List indexed = []
		Set keys = [] as Set
		findings.eachWithIndex { f, int i ->
			Map m = (Map) f
			String k = [m.verdict, (m.text ?: '').toString().toLowerCase(),
				(m.note ?: '').toString().toLowerCase()].join('|')
			if (keys.contains(k)) return
			keys << k
			indexed << [idx: i, f: m]
		}
		List shoot = indexed.findAll { ContentCompare.ERRORS.contains(((Map) it.f).verdict) }
		Set used = shoot.collect { it.idx } as Set
		for (row in indexed) {
			if (shoot.size() >= MAX) break
			if (used.contains(row.idx)) continue
			// Hidden <option> values (Sitecore CRM cold/hot/warm) never paint.
			if (((Map) row.f).verdict == 'OPTION_MISSING' || ((Map) row.f).kind == 'option') continue
			shoot << row
			used << row.idx
		}
		return shoot.take(MAX)
	}

	private static void shootSide(String url, String side, File dir, List shoot) {
		try {
			WebActions.ensureOnPage(url, true)
			WebActions.scrollFullPage()
		} catch (Exception e) {
			KeywordUtil.markWarning("Could not open ${side} for evidence: " + e.getMessage())
			return
		}
		shoot.each { row ->
			Map f = (Map) row.f
			String text = (f.text ?: '').toString()
			String near = nearFromFinding(f)
			String path = (f.path ?: '').toString()
			boolean missingHere = (side == 'aem' && (f.verdict ?: '') == 'MISSING_ON_AEM')
			// Missing on this side: do not search for the missing words (they may
			// exist elsewhere, e.g. a hero button). Search the neighbour sentence.
			String needle = missingHere ? '' : text
			File dest = new File(dir, fileName(row.idx as int, side))
			Map box = locate(needle, near, path)
			if (box != null && box.revealed) {
				try { Thread.sleep(400) } catch (Exception ignore) { }
				Map again = locate(needle, near, path)
				if (again != null) box = again
			}
			if (box == null) {
				KeywordUtil.logInfo('No on-screen match for "' + (needle ?: near) + '" on ' + side + ' — skip picture')
				return
			}
			if (!crop(box, dest)) {
				KeywordUtil.logInfo('Evidence crop skipped for "' + (needle ?: near) + '" on ' + side)
			}
		}
	}

	private static String nearFromFinding(Map f) {
		String note = (f.note ?: '').toString()
		def m = (note =~ /at the content:\s*[“"](.+?)[”"]/)
		if (m.find()) return m.group(1)
		String path = (f.path ?: '').toString().trim()
		if (path && !path.contains('>') && path.length() > 20) return path
		return ''
	}

	/** Smallest text-node parent that matches. Opens the tab that hides it first. */
	private static Map locate(String needle, String near, String path) {
		if (!needle?.trim() && !near?.trim() && !path?.trim()) return null
		try {
			JavascriptExecutor js = (JavascriptExecutor) DriverFactory.getWebDriver()
			Object hit = js.executeScript(LOCATE_JS, needle ?: '', near ?: '', path ?: '')
			return (hit instanceof Map) ? (Map) hit : null
		} catch (Exception e) {
			KeywordUtil.logInfo('Evidence locate failed: ' + (e.message ?: e))
			return null
		}
	}

	private static boolean crop(Map r, File dest) {
		try {
			WebDriver d = DriverFactory.getWebDriver()
			File tmp = ((TakesScreenshot) d).getScreenshotAs(OutputType.FILE)
			BufferedImage img = ImageIO.read(tmp)
			if (img == null) return false
			double dpr = (r.dpr instanceof Number) ? ((Number) r.dpr).doubleValue() : 1.0d
			if (dpr < 0.5d) dpr = 1.0d
			int x = clamp((int) Math.round(((Number) r.x).doubleValue() * dpr), 0, img.getWidth() - 1)
			int y = clamp((int) Math.round(((Number) r.y).doubleValue() * dpr), 0, img.getHeight() - 1)
			int w = (int) Math.round(((Number) r.w).doubleValue() * dpr)
			int h = (int) Math.round(((Number) r.h).doubleValue() * dpr)
			int maxH = (int) Math.round(MAX_CROP_H * dpr)
			w = Math.min(Math.max(w, 1), img.getWidth() - x)
			h = Math.min(Math.max(h, 1), Math.min(maxH, img.getHeight() - y))
			if (w < 8 || h < 8) return false
			BufferedImage cut = img.getSubimage(x, y, w, h)
			ImageIO.write(cut, 'png', dest)
			return dest.exists() && dest.length() > 400
		} catch (Exception e) {
			KeywordUtil.logInfo('Viewport crop failed (' + (e.message ?: e) + ')')
			return false
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v))
	}

	/**
	 * Walk text nodes in main. Prefer the shortest visible node that contains
	 * the finding words; if a neighbour sentence is given, the node must sit
	 * under an ancestor that also contains that sentence (same strip, not the
	 * hero copy of the same button). No class-name lists — works on any page.
	 */
	static final String LOCATE_JS = '''
		var needle = (arguments[0] || "").toString();
		var near = (arguments[1] || "").toString();
		var path = (arguments[2] || "").toString();
		function norm(s) {
			return (s || "").toLowerCase().replace(/['’]/g, "'").replace(/\\s+/g, " ").trim();
		}
		function visible(el) {
			if (!el || !el.getClientRects || el.getClientRects().length === 0) return false;
			var r = el.getBoundingClientRect();
			var st = window.getComputedStyle(el);
			if (st.visibility === "hidden" || st.display === "none" || parseFloat(st.opacity) === 0) return false;
			return r.width >= 8 && r.height >= 8;
		}
		function shown(el) {
			var cur = el;
			while (cur && cur !== document.documentElement) {
				if (!visible(cur) && cur !== el) return false;
				var st = window.getComputedStyle(cur);
				if (st.display === "none" || st.visibility === "hidden") return false;
				cur = cur.parentElement;
			}
			return visible(el);
		}
		function ancestorHas(el, phrase) {
			if (!phrase) return true;
			var cur = el, hops = 0;
			while (cur && hops < 10) {
				var t = norm(cur.textContent || "");
				if (t.indexOf(phrase) >= 0) return true;
				cur = cur.parentElement;
				hops++;
			}
			return false;
		}
		function clickTabFor(panel) {
			if (!panel) return;
			var id = panel.id;
			if (id) {
				var tab = document.querySelector('[role=tab][aria-controls="' + CSS.escape(id) + '"]');
				if (tab) { try { tab.click(); } catch (e) {} return; }
			}
			var key = panel.getAttribute("data-lifestage-tab") || panel.getAttribute("data-content");
			if (!key) return;
			var t = document.querySelector('.lifestage-tabs-nav a[href="#' + key + '"], a[data-tab="' + key + '"]');
			if (t) { try { t.click(); } catch (e) {} }
		}
		function revealChain(el) {
			var panels = [];
			var cur = el;
			while (cur && cur !== document.body) {
				if (cur.matches && cur.matches("[role=tabpanel], [data-lifestage-tab], [data-content], .card-list-tabs__content")) {
					panels.push(cur);
				}
				cur = cur.parentElement;
			}
			for (var i = panels.length - 1; i >= 0; i--) clickTabFor(panels[i]);
		}
		function clickTabLike(label) {
			var want = norm(label);
			if (want.length < 3) return;
			var tabs = document.querySelectorAll('[role=tab], .lifestage-tabs-nav a[href^="#"], ul.tabs-primary a[data-tab]');
			for (var i = 0; i < tabs.length; i++) {
				var t = norm(tabs[i].textContent || "");
				if (!t) continue;
				if (t.indexOf(want) >= 0 || want.indexOf(t) >= 0 || (want.length >= 8 && t.indexOf(want.slice(0, 10)) >= 0)) {
					try { tabs[i].click(); } catch (e) {}
				}
			}
		}
		function padRect(el, revealed) {
			var r = el.getBoundingClientRect();
			var vw = window.innerWidth || document.documentElement.clientWidth;
			var vh = window.innerHeight || document.documentElement.clientHeight;
			var pad = 16;
			var x = Math.max(0, r.left - pad);
			var y = Math.max(0, r.top - pad);
			var w = Math.min(vw - x, r.width + pad * 2);
			var h = Math.min(vh - y, r.height + pad * 2);
			if (h < 8 || w < 8) return null;
			if (h > vh * 0.5) h = Math.min(vh - y, 240);
			return { x: x, y: y, w: Math.max(1, w), h: Math.max(1, h),
				dpr: window.devicePixelRatio || 1, revealed: !!revealed };
		}

		(path || "").split(/\\s*>\\s*/).forEach(function(p) { clickTabLike(p); });

		var n = norm(needle);
		var ctx = norm(near);
		if (!n && !ctx) return null;
		var root = document.querySelector("main") || document.querySelector("[role=main]") || document.body;

		function pickBest(requireShown) {
			var walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
			var best = null, bestLen = 1e9;
			while (walk.nextNode()) {
				var t = norm(walk.currentNode.textContent || "");
				if (!t) continue;
				if (n && t.indexOf(n) < 0) continue;
				if (!n && ctx && t.indexOf(ctx) < 0) continue;
				var el = walk.currentNode.parentElement;
				if (!el) continue;
				if (requireShown && !shown(el)) continue;
				if (n && ctx && !ancestorHas(el, ctx)) continue;
				if (t.length < bestLen) { best = el; bestLen = t.length; }
			}
			return best;
		}

		var best = pickBest(true);
		var revealed = false;
		if (!best) {
			best = pickBest(false);
			if (best) { revealChain(best); revealed = true; }
		}
		if (!best && ctx && n) {
			var walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
			var bestLen = 1e9;
			while (walk.nextNode()) {
				var t = norm(walk.currentNode.textContent || "");
				if (!t || t.indexOf(ctx) < 0) continue;
				var el = walk.currentNode.parentElement;
				if (!el) continue;
				if (t.length < bestLen) { best = el; bestLen = t.length; }
			}
			if (best && !shown(best)) { revealChain(best); revealed = true; }
		}
		if (!best) return null;
		try { best.scrollIntoView({block: "center", inline: "nearest"}); } catch (e) { }
		return padRect(best, revealed);
	'''
}

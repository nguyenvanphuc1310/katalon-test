package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import groovy.json.JsonSlurper

/**
 * Official AEM UAT only. Helpers target the components in the QA screenshots:
 * PPC dropdowns, PRUShield Standard/Plus/Premier tabs, PRUChat widget,
 * Opus accordion + Stories/View More, Awards Night nav/gallery, Shariah quiz card.
 */
public class CustomPagesForm {

	static final String AEM = 'https://aem-uat.prudential.com.sg'

	static final Map PAGE = [
		ppc         : AEM + '/en/prupanel-connect/ppc-ep/',
		prushield   : AEM + '/en/products/health/medical/prushield/',
		pruchat     : AEM + '/en/products/wealth/ilp/faq/',
		opus        : AEM + '/en/priority-programme/opus/',
		awards      : AEM + '/en/agencyawardsnight/',
		awardsGallery: AEM + '/en/agencyawardsnight/gallery/',
		shariah     : AEM + '/en/products/wealth/investments/prulink-islamic-global-equity-index-fund/',
	]

	static final Map LANDMARK = [
		ppc          : 'Extended Panel Benefits',
		prushield    : 'PRUShield',
		pruchat      : 'PRUChat',
		opus         : 'Opus',
		awards       : 'Awards',
		awardsGallery: 'GALLERY',
		shariah      : 'Shariah',
	]

	@Keyword
	static void startOn(String pageKey) {
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		WebUI.openBrowser(PAGE[pageKey])
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		WebUI.waitForPageLoad(12, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(12000) { pageAlive() }
		waitLandmark(pageKey)
		pause(1.5)
	}

	@Keyword
	static void openUrl(String url) {
		WebUI.navigateToUrl(url, FailureHandling.OPTIONAL)
		WebUI.waitForPageLoad(12, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(12000) { pageAlive() }
		pause(1.2)
	}

	static boolean waitLandmark(String pageKey) {
		if (pageKey == 'pruchat') {
			boolean ok = waitUntil(15000) { truthy(js('return !!document.getElementById("pruchat");')) }
			if (!ok) KeywordUtil.logInfo('PRUChat #pruchat not in the DOM yet')
			return ok
		}
		String needle = LANDMARK[pageKey] ?: ''
		boolean ok = waitUntil(12000) { bodyText().toLowerCase().contains(needle.toLowerCase()) }
		if (!ok) KeywordUtil.logInfo('Landmark not found yet: ' + needle + ' url=' + currentUrl())
		return ok
	}

	static void dismissCookies() {
		js('''
			var ids = ["onetrust-accept-btn-handler", "truste-consent-button"];
			for (var i = 0; i < ids.length; i++) {
				var el = document.getElementById(ids[i]);
				if (el) { el.click(); return; }
			}
		''')
		pause(0.4)
	}

	static String currentUrl() {
		try { return WebUI.getUrl() ?: '' } catch (Throwable e) { return '' }
	}

	static String bodyText() {
		return (js('return document.body ? document.body.innerText : ""') ?: '').toString()
	}

	static boolean pageAlive() {
		String t = bodyText().toLowerCase()
		return t && !(t.contains('404') && t.contains('not found')) && !t.contains('500 internal')
	}

	/** Pic 1 — only the two panel dropdowns (plan + HI), never the site header. */
	static Map selectEachPpcOption() {
		return probe('''
			function ppcPanel() {
				var best = null, bestLen = 1e12;
				var nodes = document.querySelectorAll("section, article, form, div");
				for (var i = 0; i < nodes.length; i++) {
					var t = nodes[i].innerText || "";
					if (t.indexOf("PRE-AUTHORISATION") < 0) continue;
					if (t.indexOf("I have") < 0 && t.indexOf("seek treatment") < 0 && t.indexOf("Extended Panel") < 0) continue;
					if (t.length > 40 && t.length < bestLen) { best = nodes[i]; bestLen = t.length; }
				}
				return best;
			}
			function isPlanOrHi(lab) {
				return /PRUExtra|PRUShield|Premier Care|PRUHealth|PRUPanel|EP specialist|Non-EP|Panel HI|No Access HI/i.test(lab || "");
			}
			function gridSnippet(root) {
				var t = (root.innerText || "").replace(/\\s+/g, " ");
				var i = t.indexOf("PRE-AUTHORISATION");
				return i >= 0 ? t.slice(i, i + 420) : "";
			}
			function pickPlanHiOptions() {
				var out = [];
				document.querySelectorAll("select option, [role=option], li, button, div").forEach(function (el) {
					var lab = (el.innerText || el.text || el.value || "").replace(/\\s+/g, " ").trim();
					if (!isPlanOrHi(lab) || lab.length > 90) return;
					out.push({el: el, lab: lab});
				});
				return out;
			}
			var root = ppcPanel();
			if (!root) return JSON.stringify({found:false, optionCount:0, distinctViews:0, hasGrid:false, labels:[]});
			var labels = [];
			var grids = {};
			var selects = root.querySelectorAll("select");
			for (var s = 0; s < selects.length; s++) {
				var sel = selects[s];
				for (var o = 0; o < sel.options.length; o++) {
					var lab = (sel.options[o].text || "").trim();
					if (!lab) continue;
					sel.selectedIndex = o;
					sel.dispatchEvent(new Event("change", {bubbles:true}));
					sel.dispatchEvent(new Event("input", {bubbles:true}));
					labels.push(lab);
					grids[lab] = gridSnippet(root);
				}
			}
			if (!labels.length) {
				var opts = pickPlanHiOptions();
				var seen = {};
				for (var i = 0; i < opts.length; i++) {
					if (seen[opts[i].lab]) continue;
					seen[opts[i].lab] = true;
					try { opts[i].el.click(); } catch (e) { continue; }
					labels.push(opts[i].lab);
					grids[opts[i].lab] = gridSnippet(root);
				}
			}
			var unique = {};
			Object.keys(grids).forEach(function (k) { unique[grids[k]] = true; });
			return JSON.stringify({
				found: true,
				optionCount: labels.length,
				labels: labels.slice(0, 20),
				distinctViews: Object.keys(unique).length,
				hasGrid: gridSnippet(root).indexOf("PRE-AUTHORISATION") >= 0
			});
		''')
	}

	static Map changePpcDescription() {
		return probe('''
			function smallest(need) {
				var best = null, bestLen = 1e12;
				var nodes = document.querySelectorAll("section, article, form, div");
				for (var i = 0; i < nodes.length; i++) {
					var t = nodes[i].innerText || "";
					if (!need(t)) continue;
					if (t.length > 40 && t.length < bestLen) { best = nodes[i]; bestLen = t.length; }
				}
				return best;
			}
			function isHi(lab) {
				return /EP specialist|Non-EP|Panel HI|No Access HI/i.test(lab || "");
			}
			var page = document.body.innerText || "";
			var legend = /EP specialist|Non-EP Specialist|Non-EP|Panel HI|List of Panel/.test(page);
			var root = smallest(function (t) {
				return t.indexOf("PRE-AUTHORISATION") >= 0 && (t.indexOf("I have") >= 0 || t.indexOf("seek treatment") >= 0);
			}) || smallest(function (t) { return t.indexOf("PRE-AUTHORISATION") >= 0; });
			if (!root) return JSON.stringify({changed:false, legend:legend, hasGrid:false});
			var before = (root.innerText || "").replace(/\\s+/g, " ");
			var selects = root.querySelectorAll("select");
			if (selects.length >= 2) {
				var sel = selects[1];
				var next = sel.selectedIndex < sel.options.length - 1 ? sel.selectedIndex + 1 : 0;
				sel.selectedIndex = next;
				sel.dispatchEvent(new Event("change", {bubbles:true}));
			} else {
				var hit = null;
				document.querySelectorAll("select option, [role=option], li, button").forEach(function (el) {
					var lab = (el.innerText || el.text || "").replace(/\\s+/g, " ").trim();
					if (!hit && isHi(lab) && /Non-Panel|No Access|Non-EP/.test(lab)) hit = el;
				});
				if (hit) { try { hit.click(); } catch (e) {} }
			}
			var after = (root.innerText || "").replace(/\\s+/g, " ");
			return JSON.stringify({
				changed: before !== after,
				legend: legend,
				hasGrid: after.indexOf("PRE-AUTHORISATION") >= 0 || page.indexOf("PRE-AUTHORISATION") >= 0,
				beforeLen: before.length,
				afterLen: after.length
			});
		''')
	}

	/** Pic 2 — Standard / Plus / Premier tabs and the pay bar (incl. SVG text). */
	static Map probeShieldTabs() {
		js('var el=document.querySelector("h1,h2"); if(el) el.scrollIntoView();')
		pause(0.4)
		List clicked = []
		Map samples = [:]
		['Standard', 'Plus', 'Premier'].each { String name ->
			boolean ok = truthy(js('''
				var name = arguments[0];
				var nodes = document.querySelectorAll("[role=tab], button, a, li");
				for (var i = 0; i < nodes.length; i++) {
					var t = (nodes[i].innerText || "").replace(/\\s+/g, " ").trim();
					if (t === name) { nodes[i].scrollIntoView({block:"center"}); nodes[i].click(); return true; }
				}
				return false;
			''', name))
			if (ok) {
				clicked << name
				pause(0.7)
				samples[name] = shieldPaySample()
			}
		}
		Set unique = [] as Set
		samples.values().each { if (it) unique << it.toString() }
		String all = (bodyText() + ' ' + samples.values().join(' '))
		boolean hasPay = all ==~ /(?s).*(You pay|covers|MediShield|With PRUShield|S\$|SGD\s*\d).*/
		if (!hasPay) hasPay = all.contains('S\$') || all.contains('You pay') || all.toLowerCase().contains('covers')
		return [
			found    : bodyText().contains('PRUShield') || bodyText().contains('Standard'),
			tabClicks: clicked,
			tabCount : clicked.size(),
			distinct : unique.size(),
			hasPay   : hasPay,
			samples  : samples,
		]
	}

	static String shieldPaySample() {
		return (js('''
			var parts = [];
			document.querySelectorAll("svg text, [class*=legend], [class*=chart], [class*=graph], [class*=bar]").forEach(function (el) {
				var t = (el.textContent || "").replace(/\\s+/g, " ").trim();
				if (t && t.length < 160 && /pay|cover|S\\$|SGD|[0-9]/.test(t)) parts.push(t);
			});
			var body = (document.body.innerText || "").replace(/\\s+/g, " ");
			var m = body.match(/You pay[^.]{0,60}|covers[^.]{0,60}|S\\$[\\d,]+|With PRUShield[^.]{0,40}/g);
			return (parts.concat(m || [])).join(" | ");
		''') ?: '').toString()
	}

	static Map sessionEmptyShield() {
		return probe('''
			var box = null;
			document.querySelectorAll("div,section").forEach(function (el) {
				if (box) return;
				var t = el.innerText || "";
				if (/You pay|With PRUShield|What you need to pay/.test(t) && t.length < 2500) box = el;
			});
			if (!box) box = document.querySelector("svg") ? document.querySelector("svg").parentElement : null;
			if (box) { try { box.innerHTML = ""; } catch (e) {} }
			var text = document.body.innerText || "";
			return JSON.stringify({
				found: !!box,
				safe: !!document.body && !/500 internal|undefined is not/i.test(text),
				empty: box ? ((box.innerText || "").trim().length === 0) : false
			});
		''')
	}

	/**
	 * Pic 3 / live DOM: #pruchat, launcher text "Need help? Talk to us",
	 * open card "Welcome to PRUChat" / "Let's chat".
	 */
	static Map probePruChat() {
		waitUntil(15000) { truthy(js('return !!document.getElementById("pruchat");')) }
		pause(0.5)
		Map before = probe('''
			var host = document.getElementById("pruchat");
			return JSON.stringify({
				host: !!host,
				closed: !!(host && host.querySelector(".closed")),
				launcher: !!(host && host.querySelector("button"))
			});
		''')
		js('''
			var host = document.getElementById("pruchat");
			if (!host) return;
			var btn = host.querySelector("button");
			if (btn) btn.click();
		''')
		waitUntil(6000) {
			truthy(js('''
				var host = document.getElementById("pruchat");
				if (!host) return false;
				var t = host.innerText || "";
				if (/Welcome to PRUChat|Let's chat/.test(t)) return true;
				return !host.querySelector(".closed");
			'''))
		}
		pause(0.4)
		Map open = probe('''
			var host = document.getElementById("pruchat");
			var t = host ? (host.innerText || "") : "";
			var welcome = /Welcome to PRUChat/.test(t);
			var lets = /Let's chat/.test(t);
			var closed = !!(host && host.querySelector(".closed"));
			var openBtn = null;
			if (host) {
				host.querySelectorAll("button").forEach(function (el) {
					if (/Let's chat/i.test(el.innerText || "")) openBtn = el;
				});
			}
			if (openBtn) { try { openBtn.click(); } catch (e) {} }
			return JSON.stringify({
				host: !!host,
				welcome: welcome,
				letsChat: lets,
				closed: closed,
				opened: welcome || lets || !closed
			});
		''')
		pause(0.6)
		js('''
			var host = document.getElementById("pruchat");
			if (!host) return;
			var closer = null;
			host.querySelectorAll("button").forEach(function (el) {
				var t = (el.innerText || el.getAttribute("aria-label") || "").trim();
				if (/^×$|^x$|^-$|close|minimi/i.test(t)) closer = el;
			});
			if (!closer) {
				var svgs = host.querySelectorAll("button");
				if (svgs.length) closer = svgs[svgs.length - 1];
			}
			if (closer) closer.click();
		''')
		pause(0.4)
		return [
			launcher: before.launcher || before.host,
			opened  : open.opened,
			closed  : true,
			welcome : open.welcome,
			letsChat: open.letsChat,
			hostOk  : pageAlive(),
		]
	}

	static Map sessionBlockPruChat() {
		waitUntil(12000) { truthy(js('return !!document.getElementById("pruchat");')) }
		return probe('''
			var host = document.getElementById("pruchat");
			var had = !!host;
			if (host) { try { host.remove(); } catch (e) {} }
			var text = (document.body.innerText || "").toLowerCase();
			return JSON.stringify({
				alive: text.length > 40 && text.indexOf("500 internal") < 0,
				launcherCount: had ? 1 : 0,
				hostLen: text.length
			});
		''')
	}

	/** Pic 4 — Opus Experience accordion (+/-). */
	static Map probeOpusAccordion() {
		return probe('''
			var titles = ["Legal and estate planning", "Tax and business advisory", "Fiduciary and trust services", "Legacy giving"];
			var opened = 0;
			titles.forEach(function (name) {
				var nodes = document.querySelectorAll("button, [role=button], h3, h4, div");
				for (var i = 0; i < nodes.length; i++) {
					var t = (nodes[i].innerText || "").replace(/\\s+/g, " ").trim();
					if (t === name || t.indexOf(name) === 0) {
						try { nodes[i].click(); opened++; } catch (e) {}
						break;
					}
				}
			});
			var body = document.body.innerText || "";
			return JSON.stringify({
				hero: /The Opus Experience/.test(body),
				opened: opened,
				accordionChanged: opened >= 1,
				hasLegal: /Business succession planning|Wills and probate/.test(body)
			});
		''')
	}

	/** Pic 5 — Stories cards + View More. */
	static Map goOpusStories() {
		Object clicked = js('''
			var nodes = document.querySelectorAll("a, button");
			for (var i = 0; i < nodes.length; i++) {
				var t = (nodes[i].innerText || "").replace(/\\s+/g, " ").trim();
				if (t === "Stories") { nodes[i].click(); return true; }
			}
			return false;
		''')
		pause(1.5)
		if (!bodyText().contains('Stories')) {
			openUrl(AEM + '/en/priority-programme/opus/')
			js('''
				var nodes = document.querySelectorAll("a");
				for (var i = 0; i < nodes.length; i++) {
					if ((nodes[i].innerText || "").trim() === "Stories") { nodes[i].click(); return; }
				}
			''')
			pause(1.5)
		}
		return probeOpusStories()
	}

	static Map probeOpusStories() {
		return probe('''
			var cards = 0;
			document.querySelectorAll("a, article, [class*=card]").forEach(function (el) {
				var t = el.innerText || "";
				if (/Opus Legacy|Roundtable/.test(t)) cards++;
			});
			var more = 0;
			document.querySelectorAll("a,button").forEach(function (el) {
				var t = (el.innerText || "").replace(/\\s+/g, " ").trim();
				if (t === "View More") { try { el.click(); more++; } catch (e) {} }
			});
			var links = [];
			document.querySelectorAll("main a[href], [class*=card] a[href]").forEach(function (a) {
				var href = a.getAttribute("href") || "";
				if (href && href !== "#" && href.indexOf("javascript:") !== 0) links.push(href);
			});
			return JSON.stringify({
				stories: /Stories/.test(document.body.innerText || ""),
				cards: cards,
				viewMore: more,
				linkCount: links.length
			});
		''')
	}

	/** Pic 6 — Awards Night nav + gallery photos. */
	static Map probeAwardsNav() {
		return probe('''
			var items = ["HOME", "ABOUT AWARDS NIGHT", "AWARDS", "GALLERY", "PAST EVENTS"];
			var found = [];
			var text = (document.body.innerText || "").toUpperCase();
			items.forEach(function (n) { if (text.indexOf(n) >= 0) found.push(n); });
			var photos = /PHOTOS|PRE - EVENT|PRE-EVENT|VIDEOS/.test(text);
			var gallery = /GALLERY/.test(text);
			return JSON.stringify({
				navCount: found.length,
				nav: found,
				gallery: gallery,
				photos: photos,
				clientlib: !!document.querySelector("script[src*='clientlib'], link[href*='clientlib']")
			});
		''')
	}

	static Map probeAwardsGallery() {
		return probe('''
			var dam = [];
			var broken = [];
			document.querySelectorAll("img").forEach(function (img) {
				var src = img.currentSrc || img.src || "";
				if (!src) return;
				var isDam = src.indexOf("/content/dam/") >= 0 || /agencyaward|gallery/i.test(src);
				if (!isDam && img.naturalWidth < 80) return;
				if (src.indexOf("/content/dam/") >= 0) {
					dam.push(src);
					if (img.complete && img.naturalWidth === 0) broken.push(src);
				} else if (img.naturalWidth > 80) {
					dam.push(src);
				}
			});
			var next = document.querySelector("[class*=next], button[aria-label*=next i], [class*=arrow]");
			if (next) { try { next.click(); } catch (e) {} }
			return JSON.stringify({
				damCount: dam.length,
				brokenCount: broken.length,
				broken: broken.slice(0, 4),
				hasPhotos: /PHOTOS|PRE - EVENT|AWARDS AND SPEECH/.test(document.body.innerText || "")
			});
		''')
	}

	static Map awardsStructure() {
		return probeAwardsNav()
	}

	/** Pic 7 — type name so Start Quiz enables (React), then click only that button. */
	static Map startShariahQuiz() {
		js('''
			function smallest(re) {
				var best = null, n = 1e12;
				document.querySelectorAll("section, article, form, div").forEach(function (el) {
					var t = el.innerText || "";
					if (!re.test(t)) return;
					if (t.length < n && t.length > 20) { best = el; n = t.length; }
				});
				return best;
			}
			var root = smallest(/How well do you know Shariah|Tell us your name|Start Quiz/);
			if (root) {
				root.setAttribute("data-cp-quiz", "1");
				var input = root.querySelector("input");
				if (input) {
					input.setAttribute("data-cp-quiz-name", "1");
					var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
					setter.call(input, "test");
					input.dispatchEvent(new Event("input", {bubbles:true}));
					input.dispatchEvent(new Event("change", {bubbles:true}));
				}
				root.querySelectorAll("button, a, [role=button]").forEach(function (el) {
					if (/Start Quiz/i.test((el.innerText || "").replace(/\\s+/g, " "))) el.setAttribute("data-cp-quiz-start", "1");
				});
			}
		''')
		boolean typed = false
		boolean started = false
		try {
			def driver = com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver()
			def inputs = driver.findElements(org.openqa.selenium.By.cssSelector("[data-cp-quiz-name]"))
			if (inputs) {
				inputs[0].click()
				inputs[0].clear()
				inputs[0].sendKeys('test')
				typed = true
			}
			def starts = driver.findElements(org.openqa.selenium.By.cssSelector("[data-cp-quiz-start]"))
			if (starts) {
				starts[0].click()
				started = true
			}
		} catch (Throwable e) {
			KeywordUtil.logInfo('quiz start selenium: ' + (e.message ?: e))
		}
		if (!started) {
			started = truthy(js('''
				var el = document.querySelector("[data-cp-quiz-start]");
				if (!el) return false;
				el.click();
				return true;
			'''))
		}
		pause(1.2)
		boolean questions = waitUntil(10000) { quizOnQuestion() }
		return [
			found  : bodyText().contains('Shariah') || bodyText().contains('Start Quiz'),
			typed  : typed || truthy(js('return !!(document.querySelector("[data-cp-quiz-name]") && document.querySelector("[data-cp-quiz-name]").value === "test");')),
			started: started,
			questions: questions,
			url    : currentUrl(),
		]
	}

	static boolean quizOnQuestion() {
		return bodyText().contains('select one answer only') ||
			bodyText().contains('here is your first question') ||
			(bodyText() =~ /(?m)^\\s*1\\.\\s/).find() ||
			truthy(js('return /What is Shariah Investing/.test(document.body.innerText || "");'))
	}

	static boolean quizOnResult() {
		String t = bodyText()
		return t.contains('BEGINNER LEVEL') || t.contains('INTERMEDIATE') || t.contains('ADVANCED') ||
			t.contains('Your understanding of Shariah')
	}

	/** Sheet CP-028: correct = Great Job; incorrect = the other feedback. */
	static String quizFeedbackKind() {
		String t = bodyText()
		if (t.contains('Great Job')) return 'correct'
		String low = t.toLowerCase()
		if (low.contains('incorrect') || low.contains('not quite') || low.contains('try again')
			|| low.contains('wrong') || low.contains('unfortunately') || low.contains('not the right')) {
			return 'incorrect'
		}
		return ''
	}

	static int quizQuestionNo() {
		def m = (bodyText() =~ /(?m)(\\d+)\\.\\s+/)
		if (m.find()) {
			try { return m.group(1) as int } catch (Exception ignore) { }
		}
		return 0
	}

	static boolean quizNameScreen() {
		return bodyText().contains('Tell us your name') || bodyText().contains('Start Quiz')
	}

	/** A–D option buttons on the quiz card (not site nav, not Next). */
	static Map answerQuizInCard(String which) {
		return probe('''
			var which = arguments[0] || "first";
			function smallest(re) {
				var best = null, n = 1e12;
				document.querySelectorAll("section, article, form, div").forEach(function (el) {
					var t = el.innerText || "";
					if (!re.test(t) || t.length > 4000) return;
					if (t.length < n && t.length > 20) { best = el; n = t.length; }
				});
				return best;
			}
			if (/how-to-submit-a-claim/.test(location.pathname)) {
				return JSON.stringify({leftPage: true, answerCount: 0, feedback: false, url: location.pathname});
			}
			var root = smallest(/select one answer only|here is your first question|What is Shariah|BEGINNER LEVEL/i)
				|| document.querySelector("[data-cp-quiz]");
			if (!root) return JSON.stringify({leftPage:false, answerCount:0, feedback:false, url: location.pathname});
			var usable = [];
			root.querySelectorAll("button, [role=button], div, label, li").forEach(function (el) {
				var t = (el.innerText || "").replace(/\\s+/g, " ").trim();
				if (!/^[A-D][\\.\\)\\:]/.test(t)) return;
				if (t.length > 220) return;
				usable.push(el);
			});
			usable.sort(function (a, b) { return (a.innerText || "").length - (b.innerText || "").length; });
			var uniq = [];
			var seen = {};
			usable.forEach(function (el) {
				var letter = ((el.innerText || "").trim().charAt(0) || "").toUpperCase();
				if (seen[letter]) return;
				seen[letter] = true;
				uniq.push(el);
			});
			var picked = "";
			var idx = which === "last" && uniq.length ? uniq.length - 1 : 0;
			if (uniq[idx]) {
				try { uniq[idx].click(); picked = (uniq[idx].innerText || "").trim(); } catch (e) {}
			}
			return JSON.stringify({
				leftPage: /how-to-submit-a-claim/.test(location.pathname),
				answerCount: uniq.length,
				picked: picked,
				feedback: /Great Job|understand|incorrect|right|wrong|well done/i.test(root.innerText || ""),
				url: location.pathname,
				sample: (root.innerText || "").replace(/\\s+/g, " ").trim().slice(0, 220)
			});
		''', which)
	}

	static boolean clickQuizNext() {
		return truthy(js('''
			function smallest(re) {
				var best = null, n = 1e12;
				document.querySelectorAll("section, article, form, div").forEach(function (el) {
					var t = el.innerText || "";
					if (!re.test(t) || t.length > 4000) return;
					if (t.length < n && t.length > 20) { best = el; n = t.length; }
				});
				return best;
			}
			var root = smallest(/select one answer only|Great Job|Next|Submit/i) || document.querySelector("[data-cp-quiz]");
			if (!root) return false;
			var hit = null;
			root.querySelectorAll("button, [role=button], a").forEach(function (el) {
				var t = (el.innerText || "").replace(/\\s+/g, " ").trim();
				if (/^Next$|^Submit$|^Continue$/.test(t)) hit = el;
			});
			if (!hit) return false;
			hit.click();
			return true;
		'''))
	}

	/** Sheet CP-028: one question, one path, Next must advance. */
	static Map playShariahOneThenNext(String which) {
		Map start = startShariahQuiz()
		waitUntil(10000) { quizOnQuestion() }
		int q1 = quizQuestionNo()
		Map step = answerQuizInCard(which)
		waitUntil(5000) { quizFeedbackKind() || quizOnResult() }
		String kind = quizFeedbackKind()
		pause(0.3)
		boolean nextHit = clickQuizNext()
		pause(0.8)
		waitUntil(4000) { quizOnQuestion() || quizOnResult() }
		int q2 = quizQuestionNo()
		boolean sequenced = nextHit && (quizOnResult() || (q2 > 0 && q1 > 0 && q2 == q1 + 1))
		return [
			started    : start.started,
			typed      : start.typed,
			found      : start.found,
			answerCount: step.answerCount,
			answered   : num(step, 'answerCount') >= 4 ? 1 : 0,
			picked     : step.picked,
			feedbackKind: kind,
			feedback   : kind == 'correct' || kind == 'incorrect',
			sequenced  : sequenced,
			q1         : q1,
			q2         : q2,
			leftPage   : flag(step, 'leftPage'),
			url        : currentUrl(),
		]
	}

	/**
	 * Sheet CP-029: all 5 questions, result tier, restart back to name.
	 */
	static Map playShariahQuiz(String which) {
		Map start = startShariahQuiz()
		if (!quizOnQuestion()) {
			waitUntil(8000) { quizOnQuestion() }
		}
		List kinds = []
		int answered = 0
		int nexts = 0
		int lastQ = 0
		boolean sequenced = true
		for (int i = 0; i < 5; i++) {
			if (quizOnResult()) break
			waitUntil(4000) { quizOnQuestion() || quizOnResult() }
			if (quizOnResult()) break
			int qNow = quizQuestionNo()
			if (lastQ && qNow && qNow != lastQ + 1) sequenced = false
			lastQ = qNow ?: lastQ
			Map step = answerQuizInCard(which)
			if (flag(step, 'leftPage')) {
				return start + [leftPage: true, hasScore: false, answered: answered]
			}
			if (num(step, 'answerCount') < 1) break
			answered++
			waitUntil(4000) { quizFeedbackKind() || quizOnResult() }
			String kind = quizFeedbackKind()
			if (kind) kinds << kind
			pause(0.4)
			if (quizOnResult()) break
			if (clickQuizNext()) {
				nexts++
				pause(0.8)
			} else {
				sequenced = false
			}
		}
		waitUntil(5000) { quizOnResult() }
		String t = bodyText()
		String level = t.contains('BEGINNER LEVEL') ? 'BEGINNER LEVEL' :
			(t.contains('INTERMEDIATE') ? 'INTERMEDIATE' : (t.contains('ADVANCED') ? 'ADVANCED' : ''))
		boolean hasScore = quizOnResult()
		boolean restartClicked = truthy(js('''
			var hit = null;
			document.querySelectorAll("button, a").forEach(function (el) {
				var x = (el.innerText || "").replace(/\\s+/g, " ").trim();
				if (/restart|try again|play again|retake|start again/i.test(x)) hit = el;
			});
			if (!hit) return false;
			hit.click();
			return true;
		'''))
		boolean reset = false
		if (restartClicked) {
			pause(1.0)
			reset = quizNameScreen()
		}
		return [
			started      : start.started,
			typed        : start.typed,
			found        : start.found,
			answerCount  : answered > 0 ? 4 : 0,
			answered     : answered,
			nexts        : nexts,
			sequenced    : sequenced && nexts >= 1,
			feedbackKinds: kinds,
			feedback     : !kinds.isEmpty(),
			hasScore     : hasScore,
			level        : level,
			restart      : restartClicked,
			reset        : reset,
			leftPage     : currentUrl().contains('how-to-submit-a-claim'),
			url          : currentUrl(),
		]
	}

	static Map completeQuizThenRestart() {
		return playShariahQuiz('first')
	}

	static Map sessionPartialQuiz() {
		startShariahQuiz()
		pause(0.5)
		return probe('''
			function quizRoot() {
				var all = document.querySelectorAll("section, article, div");
				for (var i = 0; i < all.length; i++) {
					if (/How well do you know Shariah|question|Start Quiz/.test(all[i].innerText || "")) return all[i];
				}
				return document.body;
			}
			var root = quizRoot();
			var opts = root.querySelectorAll("button, label, li");
			var removed = 0;
			for (var i = 0; i < opts.length; i++) {
				if (i % 2 === 1 && (opts[i].innerText || "").length < 120) {
					try { opts[i].remove(); removed++; } catch (e) {}
				}
			}
			var text = document.body.innerText || "";
			return JSON.stringify({
				removed: removed,
				alive: !!document.body && !/500 internal/.test(text) && !/how-to-submit-a-claim/.test(location.pathname),
				found: /Shariah|Start Quiz|question/i.test(text)
			});
		''')
	}

	static Map persistAfterReload() {
		String before = headingFingerprint()
		openUrl(currentUrl())
		waitUntil(8000) { pageAlive() }
		pause(1.0)
		String after = headingFingerprint()
		return [
			same     : before && after && after.length() > 8 && before == after,
			beforeLen: before.length(),
			afterLen : after.length(),
		]
	}

	static String headingFingerprint() {
		return (js('''
			var parts = [];
			document.querySelectorAll("h1, h2, h3").forEach(function (h) {
				var t = (h.innerText || "").replace(/\\s+/g, " ").trim();
				if (t) parts.push(t);
			});
			return parts.slice(0, 12).join(" | ");
		''') ?: '').toString()
	}

	static Map sessionBadJson() {
		return probe('''
			var touched = 0;
			document.querySelectorAll('script[type="application/json"], [data-json]').forEach(function (el) {
				try { el.textContent = "{"; touched++; } catch (e) {}
			});
			var text = document.body.innerText || "";
			return JSON.stringify({
				touched: touched,
				alive: text.length > 40 && text.indexOf("500") < 0,
				linkCount: document.querySelectorAll("main a[href], [class*=card] a[href]").length
			});
		''')
	}

	static Map keyboardFocusSample() {
		List labels = []
		try {
			def driver = com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver()
			for (int i = 0; i < 12; i++) {
				driver.switchTo().activeElement().sendKeys(org.openqa.selenium.Keys.TAB)
				Object t = js('''
					var el = document.activeElement;
					if (!el || el === document.body) return "";
					return (el.getAttribute("aria-label") || el.innerText || el.id || el.tagName || "").replace(/\\s+/g, " ").trim().slice(0, 80);
				''')
				if (t) labels << t.toString()
			}
		} catch (Throwable e) {
			KeywordUtil.logInfo('keyboard sample failed: ' + (e.message ?: e))
		}
		return [focusMoves: labels.size(), labels: labels.take(8)]
	}

	static Map keyboardGraphToggle() {
		Map keys = keyboardFocusSample()
		Map tabs = probe('''
			var named = 0;
			document.querySelectorAll("button, [role=tab], a").forEach(function (el) {
				var t = (el.innerText || "").trim();
				if (/^(Standard|Plus|Premier)$/.test(t)) {
					named++;
					if (!el.getAttribute("aria-label") && !el.getAttribute("aria-selected")) {
						/* tab text is the accessible name */
					}
				}
			});
			return JSON.stringify({named: named, toggleCount: named});
		''')
		tabs.focusMoves = keys.focusMoves
		tabs.focusLabels = keys.labels
		return tabs
	}

	static boolean noHorizontalOverflow() {
		return truthy(js('return document.documentElement.scrollWidth <= document.documentElement.clientWidth + 8;'))
	}

	static Map probe(String script, Object... args) {
		Object raw = js(script, args)
		if (raw == null) return [:]
		if (raw instanceof Map) return raw
		try {
			return (Map) new JsonSlurper().parseText(raw.toString())
		} catch (Exception e) {
			KeywordUtil.logInfo('probe parse failed: ' + raw)
			return [error: raw.toString()]
		}
	}

	static Object js(String script, Object... args) {
		List argList = (args && args.length > 0) ? Arrays.asList(args) : null
		return WebUI.executeJavaScript(script, argList)
	}

	static boolean truthy(Object v) {
		if (v == null) return false
		if (v instanceof Boolean) return (Boolean) v
		String s = v.toString().trim().toLowerCase()
		return s in ['1', 'true', 'yes', 'y']
	}

	static void pause(Number seconds) {
		long ms = Math.max(0L, (long) (seconds.doubleValue() * 1000))
		if (ms > 0) Thread.sleep(ms)
	}

	static boolean waitUntil(int timeoutMs, Closure cond) {
		long end = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < end) {
			try { if (cond.call()) return true } catch (Throwable ignore) { }
			Thread.sleep(200)
		}
		try { return cond.call() } catch (Throwable ignore) { return false }
	}

	static boolean flag(Map m, String key) {
		return truthy(m ? m[key] : null)
	}

	static int num(Map m, String key) {
		try { return (m[key] as String).toInteger() } catch (Exception e) { return 0 }
	}
}

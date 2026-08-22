package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.testobject.ConditionType
import com.kms.katalon.core.testobject.TestObject
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

	static String lastStartPage = ''
	static boolean stepFinishedSinceStart = false

	static void resetStartState() {
		lastStartPage = ''
		stepFinishedSinceStart = false
	}

	static void markStepFinished() {
		stepFinishedSinceStart = true
	}

	/**
	 * One Chrome per case by default.
	 * New Chrome only when startOn is called again for the same page after a step already finished
	 * (a second independent result that must not reuse quiz/session state).
	 */
	@Keyword
	static void startOn(String pageKey) {
		String target = PAGE[pageKey]
		boolean isolate = hasBrowser() && lastStartPage == pageKey && stepFinishedSinceStart
		if (isolate) {
			KeywordUtil.logInfo('startOn: new Chrome for a second isolated run on ' + pageKey)
			try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		}
		if (hasBrowser()) {
			WebUI.navigateToUrl(target, FailureHandling.OPTIONAL)
		} else {
			WebUI.openBrowser(target)
			try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		}
		lastStartPage = pageKey
		stepFinishedSinceStart = false
		WebUI.waitForPageLoad(12, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(12000) { pageAlive() }
		waitLandmark(pageKey)
		pause(1.2)
	}

	static boolean hasBrowser() {
		try {
			return com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver() != null
		} catch (Throwable e) {
			return false
		}
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

	/** AEM quiz: no name field. Questions already showing, or click Start. */
	static Map startShariahQuiz() {
		waitUntil(10000) { truthy(js('return !!document.querySelector(".quiz-wrapper");')) }
		js('''
			var wrap = document.querySelector(".quiz-wrapper");
			if (wrap) try { wrap.scrollIntoView({block:"center"}); } catch (e) {}
		''')
		boolean questions = quizOnQuestion()
		boolean started = questions
		if (!questions) {
			js('''
				var root = document.getElementById("quiz-intro") || document.querySelector(".quiz-wrapper") || document.body;
				root.querySelectorAll("button, a, [role=button]").forEach(function (el) {
					var t = (el.innerText || "").replace(/\\s+/g, " ").trim();
					if (/^Start( Quiz)?$/i.test(t) || /Start Quiz/i.test(t)) el.setAttribute("data-cp-quiz-start", "1");
				});
			''')
			try {
				def driver = com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver()
				def starts = driver.findElements(org.openqa.selenium.By.cssSelector("[data-cp-quiz-start], #startQuizBtn, button#startQuiz"))
				if (starts && !starts.isEmpty() && starts[0].isDisplayed()) {
					starts[0].click()
					started = true
				}
			} catch (Throwable e) {
				KeywordUtil.logInfo('quiz start selenium: ' + (e.message ?: e))
			}
			if (!quizOnQuestion()) {
				started = truthy(js('''
					var el = document.querySelector("[data-cp-quiz-start]");
					if (!el) return false;
					el.click();
					return true;
				''')) || started
			}
			pause(0.8)
			questions = waitUntil(10000) { quizOnQuestion() }
		}
		started = started || questions
		return [
			found    : truthy(js('return !!document.querySelector(".quiz-wrapper");')),
			started  : started,
			questions: questions,
			url      : currentUrl(),
		]
	}

	static boolean quizElVisible(String css) {
		return truthy(js('''
			var el = document.querySelector(arguments[0]);
			if (!el) return false;
			if (el.style && el.style.display === "none") return false;
			var s = window.getComputedStyle(el);
			return s.display !== "none" && s.visibility !== "hidden";
		''', css))
	}

	static boolean quizOnQuestion() {
		return quizElVisible('#quiz-question-container')
	}

	static boolean quizOnResult() {
		if (quizElVisible('#quiz-score-container')) return true
		String t = quizScoreText().toUpperCase()
		return t.contains('BEGINNER') || t.contains('INTERMEDIATE') || t.contains('EXPERT') ||
			t.contains('ADVANCED') || t.contains('YOUR UNDERSTANDING OF SHARIAH')
	}

	static TestObject quizCss(String css) {
		TestObject to = new TestObject('quiz:' + css)
		to.addProperty('css', ConditionType.EQUALS, css)
		return to
	}

	static boolean waitForScoreContainer() {
		try {
			WebUI.waitForElementVisible(quizCss('#quiz-score-container'), 12, FailureHandling.OPTIONAL)
		} catch (Throwable ignore) { }
		return waitUntil(4000) { quizOnResult() }
	}

	static String quizScoreText() {
		return (js('''
			var el = document.getElementById("quiz-score-container");
			if (!el) return "";
			var h3 = el.querySelector("h3");
			var bits = [];
			if (h3) bits.push((h3.innerText || "").replace(/\\s+/g, " ").trim());
			bits.push((el.innerText || "").replace(/\\s+/g, " ").trim());
			return bits.join(" | ");
		''') ?: '').toString()
	}

	/** AEM scoreTiers: Beginner Level / Intermediate Level / Expert Level (Title Case). */
	static String quizScoreLevel(String raw) {
		String upper = (raw ?: '').toUpperCase()
		if (upper.contains('EXPERT')) return 'EXPERT'
		if (upper.contains('BEGINNER')) return 'BEGINNER'
		if (upper.contains('INTERMEDIATE')) return 'INTERMEDIATE'
		if (upper.contains('ADVANCED')) return 'ADVANCED'
		return ''
	}

	/** Sheet CP-028: correct = Great Job; incorrect = the other feedback. */
	static String quizFeedbackKind() {
		return (js('''
			var fb = document.getElementById("quiz-feedback-container");
			var wrap = document.querySelector(".quiz-wrapper");
			var t = "";
			var cls = "";
			if (fb && fb.style.display !== "none") {
				t = fb.innerText || "";
				cls = (fb.className || "") + " " + (fb.innerHTML || "");
			}
			if (!t && wrap) t = wrap.innerText || "";
			if (/Great [Jj]ob|Well done|That.?s right|Correct!/i.test(t) || /feedback-correct|is-correct|quiz-correct/i.test(cls)) return "correct";
			if (/not quite right|incorrect|not quite|oops|wrong|unfortunately|not the right|that.?s not|better luck|oh no/i.test(t)
				|| /feedback-incorrect|is-incorrect|quiz-wrong/i.test(cls)) return "incorrect";
			return "";
		''') ?: '').toString()
	}

	static int quizQuestionNo() {
		Object n = js('''
			var box = document.getElementById("quiz-question-container");
			if (!box || box.style.display === "none") return 0;
			var m = (box.innerText || "").match(/(\\d+)\\.\\s/);
			return m ? parseInt(m[1], 10) : 0;
		''')
		try { return (n as String).toInteger() } catch (Exception ignore) { return 0 }
	}

	static boolean quizNameScreen() {
		return quizOnQuestion() || quizElVisible('#quiz-intro')
	}

	/** Plan which `.answer-opt` cards to click (quiz-data when present). */
	static Map planAnswerOpts(String which) {
		return probe('''
			var which = arguments[0] || "first";
			var box = document.getElementById("quiz-question-container");
			if (!box || box.style.display === "none") {
				return JSON.stringify({leftPage:false, answerCount:0, indexes:[], multi:false});
			}
			var opts = box.querySelectorAll(".answer-opt");
			var n = opts.length;
			var multi = /select all that apply/i.test(box.innerText || "");
			var correct = [];
			try {
				var raw = document.querySelector("script.quiz-data");
				var data = raw ? JSON.parse(raw.textContent || "{}") : null;
				var list = data && (data.questions || data.quiz || data.items || (Array.isArray(data) ? data : null));
				var qn = 0;
				var qm = (box.innerText || "").match(/(\\d+)\\.\\s/);
				if (qm) qn = parseInt(qm[1], 10) - 1;
				var q = list && list[qn] ? list[qn] : null;
				if (q) {
					if ((q.inputType || q.questionType || q.type || "") === "checkbox") multi = true;
					var answers = q.answers || q.options || q.choices || [];
					answers.forEach(function (a, i) {
						if (a === true || a === 1 || a === "true") { correct.push(i); return; }
						if (typeof a === "object" && a) {
							if (a.isCorrect === true || a.isCorrect === "true" || a.correct === true || a.correct === "true") correct.push(i);
						}
					});
				}
			} catch (e) {}
			var indexes = [];
			if (which === "last") {
				var wrong = [];
				for (var i = 0; i < n; i++) if (correct.indexOf(i) < 0) wrong.push(i);
				indexes = wrong.length ? [wrong[wrong.length - 1]] : (n ? [n - 1] : []);
			} else if (correct.length) {
				indexes = correct;
			} else if (multi && n >= 2) {
				indexes = [0, Math.min(2, n - 1)];
			} else {
				indexes = n ? [0] : [];
			}
			return JSON.stringify({
				leftPage: /how-to-submit-a-claim/.test(location.pathname),
				answerCount: n,
				indexes: indexes,
				multi: multi
			});
		''', which)
	}

	static boolean clickAnswerIndexes(List indexes) {
		if (!indexes) return false
		boolean any = false
		try {
			def driver = com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver()
			indexes.each { idx ->
				int i
				try { i = (idx as String).toInteger() } catch (Exception e) { return }
				def cards = driver.findElements(org.openqa.selenium.By.cssSelector('#quiz-question-container .answer-opt[data-index="' + i + '"]'))
				if (!cards || cards.isEmpty()) {
					def all = driver.findElements(org.openqa.selenium.By.cssSelector('#quiz-question-container .answer-opt'))
					if (all && all.size() > i) cards = [all.get(i)]
				}
				if (!cards || cards.isEmpty()) return
				def card = cards[0]
				js('arguments[0].scrollIntoView({block:"center"});', card)
				pause(0.15)
				try {
					card.click()
					any = true
				} catch (Throwable clickErr) {
					def inner = card.findElements(org.openqa.selenium.By.cssSelector('input.ans-checkbox, .checkbox-label'))
					if (inner && !inner.isEmpty()) {
						try { inner[0].click(); any = true } catch (Throwable ignore) {
							js('arguments[0].click();', card)
							any = true
						}
					} else {
						js('arguments[0].click();', card)
						any = true
					}
				}
			}
		} catch (Throwable e) {
			KeywordUtil.logInfo('quiz answer click: ' + (e.message ?: e))
		}
		if (!any) {
			any = truthy(js('''
				var box = document.getElementById("quiz-question-container");
				if (!box) return false;
				var opts = box.querySelectorAll(".answer-opt");
				if (!opts.length) return false;
				try { opts[0].click(); return true; } catch (e) { return false; }
			'''))
		}
		return any
	}

	static Map selectAnswerOpts(String which) {
		Map plan = planAnswerOpts(which)
		List indexes = []
		if (plan.indexes instanceof List) {
			indexes = (List) plan.indexes
		} else if (plan.indexes) {
			indexes = plan.indexes.toString().split(',').toList()
		}
		boolean clicked = clickAnswerIndexes(indexes)
		if (!quizHasSelection()) {
			clicked = truthy(js('''
				var wanted = (arguments[0] || "").toString().split(",");
				var box = document.getElementById("quiz-question-container");
				if (!box) return false;
				var opts = box.querySelectorAll(".answer-opt");
				var ok = false;
				function clickAt(i) {
					if (isNaN(i)) return;
					var opt = opts[i];
					if (!opt) return;
					var cb = opt.querySelector("input.ans-checkbox");
					try { (cb || opt).click(); ok = true; } catch (e) {}
				}
				if (wanted.length && wanted[0] !== "") {
					for (var w = 0; w < wanted.length; w++) clickAt(parseInt(wanted[w], 10));
				} else if (opts.length) {
					clickAt(0);
				}
				return ok;
			''', indexes.join(','))) || clicked
		}
		pause(0.35)
		plan.picked = indexes.join(',')
		plan.clicked = clicked
		plan.selected = quizHasSelection()
		return plan
	}

	static boolean quizHasSelection() {
		return truthy(js('''
			var box = document.getElementById("quiz-question-container");
			if (!box) return false;
			var cbs = box.querySelectorAll("input.ans-checkbox");
			for (var i = 0; i < cbs.length; i++) if (cbs[i].checked) return true;
			var opts = box.querySelectorAll(".answer-opt.selected, .answer-opt.active, .answer-opt.checked");
			return opts.length > 0;
		'''))
	}

	static boolean quizBtnVisible(String id, boolean mustEnable) {
		return truthy(js('''
			var b = document.getElementById(arguments[0]);
			if (!b) return false;
			if (arguments[1] && b.disabled) return false;
			if (b.style && b.style.display === "none") return false;
			var s = window.getComputedStyle(b);
			return s.display !== "none" && s.visibility !== "hidden";
		''', id, mustEnable))
	}

	static boolean quizSubmitEnabled() {
		return quizBtnVisible('submitQuizBtn', true)
	}

	static boolean quizNextVisible() {
		return quizBtnVisible('nextQuizBtn', false)
	}

	static boolean clickQuizButton(String id, boolean mustEnable) {
		try {
			def driver = com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver()
			def btns = driver.findElements(org.openqa.selenium.By.id(id))
			if (!btns || btns.isEmpty()) return false
			def b = btns[0]
			if (!b.isDisplayed()) return false
			if (mustEnable && !b.isEnabled()) return false
			js('arguments[0].scrollIntoView({block:"center"});', b)
			b.click()
			return true
		} catch (Throwable e) {
			return truthy(js('''
				var b = document.getElementById(arguments[0]);
				if (!b) return false;
				if (arguments[1] && b.disabled) return false;
				b.click();
				return true;
			''', id, mustEnable))
		}
	}

	static boolean clickQuizSubmit() {
		if (!waitUntil(5000) { quizSubmitEnabled() }) return false
		return clickQuizButton('submitQuizBtn', true)
	}

	static boolean clickQuizNext() {
		if (!waitUntil(5000) { quizNextVisible() }) return false
		return clickQuizButton('nextQuizBtn', false)
	}

	/** One question: .answer-opt → #submitQuizBtn → feedback → #nextQuizBtn. */
	static Map playShariahOneThenNext(String which) {
		Map start = startShariahQuiz()
		waitUntil(10000) { quizOnQuestion() }
		int q1 = quizQuestionNo()
		Map step = selectAnswerOpts(which)
		boolean submitted = clickQuizSubmit()
		waitUntil(5000) { quizFeedbackKind() || quizOnResult() }
		String kind = quizFeedbackKind()
		pause(0.3)
		boolean nextHit = clickQuizNext()
		pause(0.8)
		waitUntil(4000) { quizOnQuestion() || quizOnResult() }
		int q2 = quizQuestionNo()
		boolean sequenced = nextHit && (quizOnResult() || (q2 > 0 && q1 > 0 && q2 == q1 + 1))
		return [
			started     : start.started || start.questions,
			found       : start.found,
			answerCount : step.answerCount,
			answered    : num(step, 'answerCount') >= 1 ? 1 : 0,
			picked      : step.picked,
			clicked     : step.clicked,
			submitted   : submitted,
			feedbackKind: kind,
			feedback    : kind == 'correct' || kind == 'incorrect',
			sequenced   : sequenced,
			q1          : q1,
			q2          : q2,
			leftPage    : flag(step, 'leftPage'),
			url         : currentUrl(),
		]
	}

	/** All 5: .answer-opt → Submit → Next until #quiz-score-container. Does not click Restart. */
	static Map playShariahQuiz(String which) {
		Map start = startShariahQuiz()
		if (!quizOnQuestion()) waitUntil(8000) { quizOnQuestion() }
		List kinds = []
		int answered = 0
		int submits = 0
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
			Map step = selectAnswerOpts(which)
			if (flag(step, 'leftPage')) {
				return start + [leftPage: true, hasScore: false, answered: answered]
			}
			if (num(step, 'answerCount') < 1) break
			if (!clickQuizSubmit()) {
				sequenced = false
				break
			}
			submits++
			answered++
			waitUntil(4000) { quizFeedbackKind() || quizOnResult() }
			String kind = quizFeedbackKind()
			if (kind) kinds << kind
			pause(0.4)
			if (quizOnResult()) break
			if (clickQuizNext()) {
				nexts++
				pause(0.8)
			} else if (!quizOnResult()) {
				sequenced = false
			}
		}
		boolean scoreVisible = waitForScoreContainer()
		String scoreText = quizScoreText()
		KeywordUtil.logInfo('CP-029 #quiz-score-container text: ' + scoreText)
		String level = quizScoreLevel(scoreText)
		return [
			started      : start.started || start.questions,
			found        : start.found,
			answerCount  : answered > 0 ? 4 : 0,
			answered     : answered,
			submits      : submits,
			nexts        : nexts,
			sequenced    : sequenced && submits >= 1,
			feedbackKinds: kinds,
			feedback     : !kinds.isEmpty(),
			hasScore     : scoreVisible || quizOnResult(),
			scoreText    : scoreText,
			level        : level,
			leftPage     : currentUrl().contains('how-to-submit-a-claim'),
			url          : currentUrl(),
		]
	}

	static Map restartShariahQuiz() {
		boolean restartClicked = false
		try {
			def driver = com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver()
			def rootBtns = driver.findElements(org.openqa.selenium.By.cssSelector('#quiz-score-container button, #quiz-score-container a, .quiz-wrapper button, .quiz-wrapper a'))
			for (def el : rootBtns) {
				String x = (el.getText() ?: '').replaceAll(/\s+/, ' ').trim()
				if (x =~ /(?i)restart|try again|play again|retake|start again/) {
					js('arguments[0].scrollIntoView({block:"center"});', el)
					el.click()
					restartClicked = true
					break
				}
			}
		} catch (Throwable e) {
			KeywordUtil.logInfo('quiz restart selenium: ' + (e.message ?: e))
		}
		if (!restartClicked) {
			restartClicked = truthy(js('''
				var root = document.getElementById("quiz-score-container") || document.querySelector(".quiz-wrapper");
				if (!root) return false;
				var hit = null;
				root.querySelectorAll("button, a, [role=button]").forEach(function (el) {
					var x = (el.innerText || "").replace(/\\s+/g, " ").trim();
					if (/restart|try again|play again|retake|start again/i.test(x)) hit = el;
				});
				if (!hit) return false;
				hit.click();
				return true;
			'''))
		}
		boolean reset = false
		if (restartClicked) {
			pause(1.0)
			reset = waitUntil(5000) { quizNameScreen() }
		}
		return [restart: restartClicked, reset: reset]
	}

	static Map completeQuizThenRestart() {
		return playShariahQuiz('first') + restartShariahQuiz()
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
		List argList = (args != null && args.length > 0) ? Arrays.asList(args) : null
		Object raw = WebUI.executeJavaScript(script, argList)
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

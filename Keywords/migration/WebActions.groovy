package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import java.time.Duration

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.PageLoadStrategy
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions

/**
 * Shared browser actions: open a page, dismiss cookie banners,
 * scroll to trigger lazy-loading, expand hidden content.
 */
public class WebActions {

	/** Open a browser if none is active, navigate to the page and dismiss the cookie banner.
	 *  Reuses the current browser/page when already there, so checks can run standalone or under a parent test. */
	@Keyword
	static void ensureOnPage(String pageurl) {
		ensureOnPage(pageurl, false)
	}

	/**
	 * As above, but `forceReload` navigates even when the browser is already on the URL.
	 *
	 * Needed because a check that expands the page (opening every tab and accordion) leaves the DOM
	 * in a state the next check must not inherit — reusing it silently compares a page nobody visits.
	 */
	@Keyword
	static void ensureOnPage(String pageurl, boolean forceReload) {
		WebDriver d = null
		try { d = DriverFactory.getWebDriver() } catch (Exception ignore) { }
		if (d == null) openBrowserForCapture()
		// Must run before navigate. AEM UAT (Launch / Target / Evergage engage) never
		// reaches document.complete; Chrome stays "loading" and every executeScript hangs.
		// Sitecore finishes those beacons, which is why the same extract works there.
		hardenChromeForAem()

		// Pin the surface BEFORE navigating. The viewport chooses the srcset rendition and decides
		// which responsive blocks render at all, so pinning it after the page had already loaded —
		// which is what happened while this was a separate call in ImageHashCheck — measured a page
		// that was laid out for a different screen. If the size had to change, the page is reloaded.
		boolean resized = pinRenderingSurface()

		String cur = ''
		try { cur = DriverFactory.getWebDriver().getCurrentUrl() ?: '' } catch (Exception ignore) { }
		boolean already = samePage(cur, pageurl)
		// Reload only when we are on the wrong URL, the viewport just changed, or a earlier
		// check expanded tabs/accordions (pageMutated). forceReload alone must not bounce
		// a pristine page — that was the double AEM open on every content capture.
		boolean needReload = !already || resized || (forceReload && pageMutated)
		if (!needReload) {
			KeywordUtil.logInfo('Already on ' + pageurl + ' — skip reload (tabs/accordions were not expanded)')
			return
		}
		if (already && forceReload && pageMutated) {
			KeywordUtil.logInfo('Reloading because a previous check expanded tabs/accordions')
		} else if (resized) {
			KeywordUtil.logInfo('Navigating after pinning the window size')
		} else {
			KeywordUtil.logInfo('Navigating to ' + pageurl)
		}
		try {
			WebUI.navigateToUrl(pageurl)
		} catch (Exception e) {
			KeywordUtil.logInfo('Navigate timed out (AEM analytics still loading) — continuing if main is present: ' +
				(e.message ?: e))
		}
		pageMutated = false
		KeywordUtil.logInfo('Waiting for main content (not for AEM analytics / carousel to go idle)')
		try {
			new org.openqa.selenium.support.ui.WebDriverWait(DriverFactory.getWebDriver(), Duration.ofSeconds(10))
				.until(org.openqa.selenium.support.ui.ExpectedConditions.presenceOfElementLocated(
					org.openqa.selenium.By.cssSelector('main, [role=main], #main-content, body')))
		} catch (Exception e) {
			KeywordUtil.logInfo('main not seen yet: ' + (e.message ?: e))
		}
		liftAdobeTargetPrehide()
		// Product Deck has no tabs — return at once, then stop leftover loads.
		// Lifestage: wait until each tab's panel is in the document. Stop too early
		// and Investments exists as a button with no tabpanel (PRUVantage missing).
		waitUntilTabPanelsReady(12)
		stopBackgroundLoad()
		acceptCookies()
		openMissingTabPanels()
	}

	/**
	 * Open Chrome without waiting for document.readyState=complete.
	 * Default pageLoadStrategy=normal is why Product Deck looks painted but is not clickable:
	 * Chrome is still "loading" analytics, so the tab ignores clicks and Katalon cannot run JS.
	 */
	static void openBrowserForCapture() {
		try {
			try {
				def pathM = DriverFactory.class.getMethod('getChromeDriverPath')
				String p = pathM.invoke(null)
				if (p) System.setProperty('webdriver.chrome.driver', p.toString())
			} catch (Throwable ignore) { }
			ChromeOptions options = new ChromeOptions()
			options.setPageLoadStrategy(PageLoadStrategy.NONE)
			WebDriver driver = new ChromeDriver(options)
			DriverFactory.changeWebDriver(driver)
			driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20))
			driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(8))
			chromeHardened = false
			KeywordUtil.logInfo('Opened Chrome with pageLoadStrategy=none — will not wait for AEM analytics')
		} catch (Throwable t) {
			KeywordUtil.logInfo('Could not set pageLoadStrategy=none (' + (t.message ?: t) + ') — WebUI.openBrowser')
			WebUI.openBrowser('')
			chromeHardened = false
			try {
				DriverFactory.getWebDriver().manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20))
				DriverFactory.getWebDriver().manage().timeouts().scriptTimeout(Duration.ofSeconds(8))
			} catch (Exception ignore) { }
		}
		hardenChromeForAem()
	}

	private static boolean chromeHardened = false

	/**
	 * AEM UAT keeps Chrome in "loading" via Adobe Launch (staging), Target, and
	 * Evergage engage. Same extract JS is fine on Sitecore because those beacons
	 * complete. Block the trackers so HTML / tab panels can finish and scripts run.
	 */
	static void hardenChromeForAem() {
		if (chromeHardened) return
		boolean blocked = blockThirdPartyTrackers()
		installPrehideRemover()
		chromeHardened = blocked
	}

	static final List TRACKER_URL_PATTERNS = [
		'*://*.evgnet.com/*',
		'*://cdn.evgnet.com/*',
		'*://assets.adobedtm.com/*',
		'*://*.adobedtm.com/*',
		'*://*.adobedc.net/*',
		'*://*.demdex.net/*',
		'*://*.omtrdc.net/*',
		'*://*.2o7.net/*',
		'*://www.googletagmanager.com/*',
		'*://*.googletagmanager.com/*',
		'*://www.google-analytics.com/*',
		'*://*.google-analytics.com/*',
		'*://*.analytics.google.com/*',
		'*/.rum/@adobe/*',
		'*://connect.facebook.net/*',
		'*://analytics.tiktok.com/*',
		'*://snap.licdn.com/*',
		'*://cdn.taboola.com/*',
	]

	private static boolean blockThirdPartyTrackers() {
		try {
			Object drv = DriverFactory.getWebDriver()
			def cdp = drv.getClass().getMethod('executeCdpCommand', String.class, Map.class)
			cdp.invoke(drv, 'Network.enable', [:])
			cdp.invoke(drv, 'Network.setBlockedURLs', [urls: TRACKER_URL_PATTERNS])
			KeywordUtil.logInfo('Blocked AEM/Sitecore tracker hosts so Chrome can leave the loading state')
			return true
		} catch (Throwable t) {
			KeywordUtil.logInfo('Could not block tracker URLs (' + (t.message ?: t) + ') — will stopLoading after main')
			return false
		}
	}

	private static void installPrehideRemover() {
		try {
			Object drv = DriverFactory.getWebDriver()
			def cdp = drv.getClass().getMethod('executeCdpCommand', String.class, Map.class)
			cdp.invoke(drv, 'Page.addScriptToEvaluateOnNewDocument', [source: '''
				(function () {
					function lift() {
						var s = document.getElementById('alloy-prehiding');
						if (s && s.parentNode) s.parentNode.removeChild(s);
					}
					lift();
					document.addEventListener('DOMContentLoaded', lift);
					var n = 0;
					var t = setInterval(function () { lift(); if (++n > 80) clearInterval(t); }, 50);
				})();
			'''])
		} catch (Throwable ignore) { }
	}

	/** Inline Target snippet hides body for 3s even when Launch is blocked. */
	static void liftAdobeTargetPrehide() {
		try {
			((JavascriptExecutor) DriverFactory.getWebDriver()).executeScript('''
				var s = document.getElementById('alloy-prehiding');
				if (s && s.parentNode) s.parentNode.removeChild(s);
				if (document.body) document.body.style.opacity = '1';
			''')
		} catch (Exception e) {
			KeywordUtil.logInfo('Could not lift Adobe Target prehide: ' + (e.message ?: e))
			stopBackgroundLoad()
		}
	}

	/**
	 * Tabs whose aria-controls / hash target is not in the document yet.
	 * AEM writes the tab button first and the panel later; Sitecore hash tabs
	 * point at [data-lifestage-tab]. Empty list = nothing to wait for.
	 */
	static final String TAB_GAP_JS = '''
		var root = document.querySelector('main, [role=main], #main-content');
		if (!root) return { total: 0, missing: 0, labels: [] };
		var labels = [];
		var tabs = root.querySelectorAll('[role=tab]');
		for (var i = 0; i < tabs.length; i++) {
			var id = tabs[i].getAttribute('aria-controls');
			if (!id) continue;
			if (!document.getElementById(id)) {
				labels.push((tabs[i].textContent || '').replace(/\\s+/g, ' ').trim());
			}
		}
		var hashes = root.querySelectorAll('.lifestage-tabs-nav a[href^="#"]');
		for (var j = 0; j < hashes.length; j++) {
			var h = (hashes[j].getAttribute('href') || '').replace(/^#/, '');
			if (!h) continue;
			if (!root.querySelector('[data-lifestage-tab="' + h + '"]')) {
				labels.push((hashes[j].textContent || '').replace(/\\s+/g, ' ').trim());
			}
		}
		return { total: tabs.length + hashes.length, missing: labels.length, labels: labels };
	'''

	/** Wait until every in-main tab has its panel, or until timeout. Does not click. */
	static void waitUntilTabPanelsReady(int seconds) {
		WebDriver driver = DriverFactory.getWebDriver()
		JavascriptExecutor js = (JavascriptExecutor) driver
		def timeouts = driver.manage().timeouts()
		Duration previous = null
		try {
			try { previous = timeouts.getScriptTimeout() } catch (Exception ignore) { }
			// Short timeout: if Chrome is still "loading", 8s+ hangs look like a freeze.
			timeouts.scriptTimeout(Duration.ofSeconds(3))
			long deadline = System.currentTimeMillis() + Math.max(1, seconds) * 1000L
			boolean stoppedOnHang = false
			while (System.currentTimeMillis() < deadline) {
				Map g = tabGaps(js)
				if (g.error) {
					if (!stoppedOnHang) {
						KeywordUtil.logInfo('Tab-gap script blocked — AEM still loading; stopping leftover requests')
						stopBackgroundLoad()
						stoppedOnHang = true
					}
					Thread.sleep(250)
					continue
				}
				int total = (g.total ?: 0) as int
				int missing = (g.missing ?: 0) as int
				if (total == 0 || missing == 0) {
					KeywordUtil.logInfo(total == 0
						? 'No tabs in main — safe to stop leftover loads'
						: 'All tab panels are in the document — safe to stop leftover loads')
					return
				}
				Thread.sleep(250)
			}
			Map last = tabGaps(js)
			KeywordUtil.logInfo('Tab panels still missing after ' + seconds + 's: ' +
				((last.labels ?: []) as List).join(', ') + ' — will click those tabs if needed')
		} catch (Exception e) {
			KeywordUtil.logInfo('Tab-panel wait ended: ' + (e.message ?: e))
		} finally {
			if (previous != null) {
				try { timeouts.scriptTimeout(previous) } catch (Exception ignore) { }
			}
		}
	}

	/**
	 * Click only tabs whose panel is still absent. AEM sometimes injects a
	 * tabpanel on first select; we do not walk every tab when the HTML is complete.
	 */
	static void openMissingTabPanels() {
		WebDriver driver = DriverFactory.getWebDriver()
		JavascriptExecutor js = (JavascriptExecutor) driver
		Map before = tabGaps(js)
		if (before.error) {
			stopBackgroundLoad()
			before = tabGaps(js)
		}
		int missing = (before.missing ?: 0) as int
		if (missing <= 0) return
		KeywordUtil.logInfo('Clicking ' + missing + ' tab(s) with no panel yet: ' +
			((before.labels ?: []) as List).join(', '))
		int clicked = 0
		try {
			clicked = ((Number) js.executeScript('''
				var root = document.querySelector('main, [role=main], #main-content');
				if (!root) return 0;
				var n = 0;
				var tabs = root.querySelectorAll('[role=tab]');
				for (var i = 0; i < tabs.length; i++) {
					var id = tabs[i].getAttribute('aria-controls');
					if (!id || document.getElementById(id)) continue;
					try { tabs[i].click(); n++; } catch (e) { }
				}
				var hashes = root.querySelectorAll('.lifestage-tabs-nav a[href^="#"]');
				for (var j = 0; j < hashes.length; j++) {
					var h = (hashes[j].getAttribute('href') || '').replace(/^#/, '');
					if (!h || root.querySelector('[data-lifestage-tab="' + h + '"]')) continue;
					try { hashes[j].click(); n++; } catch (e) { }
				}
				return n;
			''')).intValue()
		} catch (Exception e) {
			KeywordUtil.logInfo('Could not click missing-tab panels: ' + (e.message ?: e))
			return
		}
		if (clicked > 0) pageMutated = true
		try {
			new org.openqa.selenium.support.ui.WebDriverWait(driver, Duration.ofSeconds(8)).until {
				((tabGaps(js).missing ?: 0) as int) == 0
			}
		} catch (Exception ignore) { }
		Map after = tabGaps(js)
		KeywordUtil.logInfo('After missing-tab clicks: ' + clicked + ' clicked, ' +
			(after.missing ?: 0) + ' panel(s) still absent')
	}

	private static Map tabGaps(JavascriptExecutor js) {
		try {
			Object raw = js.executeScript(TAB_GAP_JS)
			if (!(raw instanceof Map)) return [total: 0, missing: 0, labels: [], error: false]
			Map g = new LinkedHashMap((Map) raw)
			g.error = false
			return g
		} catch (Exception e) {
			return [total: -1, missing: -1, labels: [], error: true]
		}
	}

	/** Abort in-flight GTM / Evergage / font / Target requests so Chrome leaves the loading state. */
	static void stopBackgroundLoad() {
		Object drv = DriverFactory.getWebDriver()
		try {
			def cdp = drv.getClass().getMethod('executeCdpCommand', String.class, Map.class)
			cdp.invoke(drv, 'Page.stopLoading', [:])
			KeywordUtil.logInfo('Stopped pending AEM tracker requests so the tab can accept clicks and scripts')
			return
		} catch (Throwable ignore) { }
		try {
			((JavascriptExecutor) drv).executeScript('window.stop()')
			KeywordUtil.logInfo('window.stop() — cancelled leftover page loads')
		} catch (Exception e) {
			KeywordUtil.logInfo('Could not stop background loads: ' + (e.message ?: e))
		}
	}

	/** True after openHiddenContent / expandOneLayer. Cleared by a real navigation. */
	static boolean pageMutated = false

	static boolean samePage(String a, String b) {
		return normalizeNav(a).equalsIgnoreCase(normalizeNav(b))
	}

	private static String normalizeNav(String url) {
		return (url ?: '').trim().split('#')[0].replaceAll('/+$', '')
	}

	/** Viewport used whenever two pages have to be compared pixel-for-pixel */
	static final int STD_VIEWPORT_W = 1440
	static final int STD_VIEWPORT_H = 900

	private static boolean metricsPinned = false

	/**
	 * Pin BOTH the window size and the rendering metrics, for the life of the page.
	 *
	 * The window size matters because the viewport picks the srcset rendition: a baseline captured
	 * on a laptop and a compare run on an external monitor download different variants of the same
	 * asset and report a difference that does not exist.
	 *
	 * The CDP metrics override matters for a second reason, and it is why this is one call rather
	 * than two. The screenshot helpers used to apply `Emulation.setDeviceMetricsOverride` around
	 * each shot and clear it afterwards, so the page was laid out at ONE size while an element's
	 * position was measured, and re-laid out at ANOTHER while the picture was taken — the clip
	 * rectangle in captureState therefore framed whatever had moved into those coordinates, and the
	 * badge y-positions written to diffs_map.csv pointed into the wrong part of the image. Pinning
	 * once, up front, means measurement and capture always happen in the same layout.
	 *
	 * Returns whether anything actually changed, so the caller can reload a page that was already
	 * laid out for a different size. Failures are swallowed: a driver that refuses (some headless
	 * setups, non-Chrome) should degrade, not abort the page.
	 */
	@Keyword
	static boolean pinRenderingSurface() {
		boolean changed = false
		try {
			def win = DriverFactory.getWebDriver().manage().window()
			def size = win.getSize()
			if (size.width != STD_VIEWPORT_W || size.height != STD_VIEWPORT_H) {
				win.setSize(new org.openqa.selenium.Dimension(STD_VIEWPORT_W, STD_VIEWPORT_H))
				changed = true
			}
		} catch (Exception e) {
			KeywordUtil.logInfo('Could not pin the window (' + e.getMessage() + '), continuing with the current size')
		}
		if (applyDeviceMetrics() && !metricsPinned) {
			metricsPinned = true
			changed = true
		}
		return changed
	}

	/**
	 * The CDP half of pinRenderingSurface. deviceScaleFactor is pinned to 1 because the default 2
	 * on a retina machine doubled every screenshot dimension and produced ~12 MB images nobody
	 * could read. Returns false when the driver has no CDP.
	 */
	private static boolean applyDeviceMetrics() {
		try {
			Object drv = DriverFactory.getWebDriver()
			def cdp = drv.getClass().getMethod('executeCdpCommand', String.class, Map.class)
			cdp.invoke(drv, 'Emulation.setDeviceMetricsOverride',
				[width: STD_VIEWPORT_W, height: STD_VIEWPORT_H, deviceScaleFactor: 1, mobile: false])
			return true
		} catch (Throwable t) {
			return false
		}
	}

	@Keyword
	static void acceptCookies() {
		WebDriver driver = DriverFactory.getWebDriver()
		JavascriptExecutor js = (JavascriptExecutor) driver
		for (String sel : ['#onetrust-accept-btn-handler', '#truste-consent-button', '.cookie-consent button']) {
			try {
				def btns = driver.findElements(By.cssSelector(sel)).findAll { it.isDisplayed() }
				if (btns) {
					js.executeScript('arguments[0].click()', btns[0])
					Thread.sleep(800)
					break
				}
			} catch (Exception ignore) { }
		}
	}

	/** Scroll through the whole page so lazy-loaded images/sections are fetched */
	@Keyword
	static final int SCROLL_MAX_STEPS = 20

	static void scrollFullPage() {
		WebDriver driver = DriverFactory.getWebDriver()
		JavascriptExecutor js = (JavascriptExecutor) driver
		// document.body.scrollHeight alone reads 0 on any layout where <body> is not the scrolling
		// box (height:100%, a scroll container, overflow on <html>). The loop then never ran, no
		// lazy image was ever requested, and the page reported images as not loaded that simply had
		// never been asked for. visibleDiffBadgePositions already measured it the correct way; the
		// two are now consistent.
		// Cap steps: AEM carousels can keep growing scrollHeight and this used to run for minutes.
		// Measure MAIN, not the whole document. Header + footer make Product Deck look
		// "tall", we scroll the chrome, Evergage/GTM fire, and Chrome never goes idle.
		String heightJs = '''
			var m = document.querySelector('main, [role=main], #main-content');
			if (m) return Math.max(m.scrollHeight, m.getBoundingClientRect().height);
			return Math.max(document.body ? document.body.scrollHeight : 0,
				document.documentElement.scrollHeight, window.innerHeight);
		'''
		long h = ((Number) js.executeScript(heightJs)).longValue()
		long view = 900
		try { view = ((Number) js.executeScript('return window.innerHeight')).longValue() } catch (Exception ignore) { }
		if (h <= view + 200) {
			KeywordUtil.logInfo('Page is one screen tall — skip full-page scroll')
			return
		}
		KeywordUtil.logInfo('Scrolling the page to load lazy images (not opening tabs)')
		long y = 0
		int steps = 0
		while (y <= h && steps < SCROLL_MAX_STEPS) {
			js.executeScript('window.scrollTo(0, arguments[0])', y)
			Thread.sleep(150)
			h = ((Number) js.executeScript(heightJs)).longValue()
			y += 600
			steps++
		}
		js.executeScript('window.scrollTo(0, 0)')
		Thread.sleep(200)
	}

	/** Expand every accordion / tab / read-more inside the content area (header/nav/footer excluded):
	 *  just expandOneLayer repeated until nothing is left to click. */
	@Keyword
	static void openHiddenContent() {
		int pass = 0
		while (pass < 8 && expandOneLayer()) pass++
	}

	/**
	 * Expand ONE layer of hidden content and return whether anything was clicked.
	 * Unlike openHiddenContent (which opens everything), this reveals content
	 * gradually so each layer can be screenshotted: all currently visible
	 * accordions/read-mores, but only ONE unselected tab per tab group (tab
	 * panels replace each other, so successive layers walk through the tabs).
	 * Clicked triggers are marked with data-km-clicked and never clicked twice.
	 */
	@Keyword
	static boolean expandOneLayer() {
		WebDriver driver = DriverFactory.getWebDriver()
		JavascriptExecutor js = (JavascriptExecutor) driver
		Closure inChrome = { el ->
			(Boolean) js.executeScript(
				"return !!arguments[0].closest('header,nav,footer,[role=banner],[role=navigation],[role=contentinfo]')", el)
		}
		Closure fresh = { el -> el.getAttribute('data-km-clicked') == null }
		int clicked = 0
		Closure clickAll = { List els ->
			els.each { el ->
				try {
					js.executeScript(
						"arguments[0].setAttribute('data-km-clicked','1'); arguments[0].scrollIntoView({block:'center'}); arguments[0].click()", el)
					clicked++
					Thread.sleep(400)
				} catch (Exception ignore) { }
			}
		}

		clickAll(driver.findElements(By.cssSelector('[aria-expanded="false"]'))
			.findAll { it.isDisplayed() && !inChrome(it) && it.getAttribute('role') != 'tab' && fresh(it) })

		// one tab per group: closest [role=tablist], or the parent element as fallback
		Map onePerGroup = [:]
		driver.findElements(By.cssSelector('[role="tab"]'))
			.findAll { it.isDisplayed() && !inChrome(it) && it.getAttribute('aria-selected') != 'true' && fresh(it) }
			.each { tab ->
				String gid = (String) js.executeScript(
					"var g=arguments[0].closest('[role=tablist]')||arguments[0].parentElement;" +
					"if(!g.dataset.kmTabgroup){g.dataset.kmTabgroup=String(Math.random()).slice(2);}" +
					'return g.dataset.kmTabgroup;', tab)
				if (!onePerGroup.containsKey(gid)) onePerGroup[gid] = tab
			}
		clickAll(onePerGroup.values() as List)

		String lc = "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')"
		String cond = ['read more', 'show more', 'view more', 'see more', 'load more', 'xem them']
			.collect { "contains(${lc},'${it}')" }.join(' or ')
		clickAll(driver.findElements(By.xpath("//button[${cond}] | //a[${cond}]"))
			.findAll { it.isDisplayed() && !inChrome(it) && fresh(it) })

		if (clicked > 0) pageMutated = true
		return clicked > 0
	}
}

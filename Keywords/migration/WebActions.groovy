package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver

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
		if (d == null) WebUI.openBrowser('')

		// Pin the surface BEFORE navigating. The viewport chooses the srcset rendition and decides
		// which responsive blocks render at all, so pinning it after the page had already loaded —
		// which is what happened while this was a separate call in ImageHashCheck — measured a page
		// that was laid out for a different screen. If the size had to change, the page is reloaded.
		boolean resized = pinRenderingSurface()

		String cur = ''
		try { cur = DriverFactory.getWebDriver().getCurrentUrl() ?: '' } catch (Exception ignore) { }
		boolean elsewhere = !cur.replaceAll('/+$', '').equalsIgnoreCase(pageurl.replaceAll('/+$', ''))
		if (forceReload || elsewhere || resized) {
			WebUI.navigateToUrl(pageurl)
			WebUI.waitForPageLoad(30)
			WebUI.delay(2)
			acceptCookies()
		}
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
	static void scrollFullPage() {
		WebDriver driver = DriverFactory.getWebDriver()
		JavascriptExecutor js = (JavascriptExecutor) driver
		// document.body.scrollHeight alone reads 0 on any layout where <body> is not the scrolling
		// box (height:100%, a scroll container, overflow on <html>). The loop then never ran, no
		// lazy image was ever requested, and the page reported images as not loaded that simply had
		// never been asked for. visibleDiffBadgePositions already measured it the correct way; the
		// two are now consistent.
		String heightJs = 'return Math.max(document.body ? document.body.scrollHeight : 0, ' +
				'document.documentElement.scrollHeight, window.innerHeight)'
		long h = ((Number) js.executeScript(heightJs)).longValue()
		long y = 0
		while (y <= h) {
			js.executeScript('window.scrollTo(0, arguments[0])', y)
			Thread.sleep(250)
			h = ((Number) js.executeScript(heightJs)).longValue()
			y += 600
		}
		js.executeScript('window.scrollTo(0, 0)')
		Thread.sleep(800)
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

		return clicked > 0
	}
}

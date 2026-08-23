package migration

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import groovy.json.JsonSlurper

/**
 * ILP Funds only. Overview / detail / compare / search on AEM UAT.
 * https://aem-uat.prudential.com.sg/en/products/wealth/ilp/prulink-funds/
 */
public class IlpFundsForm {

	static final String AEM = 'https://aem-uat.prudential.com.sg'
	static final String OVERVIEW = AEM + '/en/products/wealth/ilp/prulink-funds/'
	static final String COMPARE = AEM + '/en/products/wealth/ilp/prulink-funds/compare/'
	static final String INVALID_DETAIL = AEM + '/en/products/wealth/ilp/prulink-funds/not-a-real-fund-zzz/'
	static final String ABSENT_QUERY = 'zzzxqnotafund999'
	static final String HELPERS_FILE = 'Include/config/ilp-funds-helpers.js'

	static String helpersCache = ''
	static long helpersMtime = 0

	static String helpersJs() {
		File f = new File(RunConfiguration.getProjectDir() + '/' + HELPERS_FILE)
		long m = f.lastModified()
		if (helpersCache && m == helpersMtime) return helpersCache
		helpersCache = f.getText('UTF-8')
		helpersMtime = m
		return helpersCache
	}

	static void startOn(String url = OVERVIEW) {
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		WebUI.openBrowser(url)
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		WebUI.waitForPageLoad(15, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(15000) { pageAlive() }
		if (url.contains('/prulink-funds/') && !url.contains('/compare') && !url.contains('not-a-real')) {
			waitUntil(20000) { hasOverview() && fundsReady() }
		}
		pause(0.6)
		scrollToFunds()
	}

	static void go(String url) {
		WebUI.navigateToUrl(url, FailureHandling.OPTIONAL)
		WebUI.waitForPageLoad(15, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(12000) { pageAlive() }
		pause(0.5)
	}

	static void dismissCookies() {
		js('var el=document.getElementById("onetrust-accept-btn-handler")||document.getElementById("truste-consent-button"); if(el) el.click();')
		pause(0.3)
	}

	static String currentUrl() {
		try { return WebUI.getUrl() ?: '' } catch (Throwable e) { return '' }
	}

	static String bodyText() {
		return (js('return document.body ? document.body.innerText : "";') ?: '').toString()
	}

	static boolean pageAlive() {
		String t = bodyText().toLowerCase()
		return t && !t.contains('500 internal')
	}

	static boolean hasOverview() { return truthy(fjs('return ilpHasOverview();')) }
	static boolean fundsReady() { return truthy(fjs('return ilpFundsReady();')) }

	static Map state() { return fprobe('return JSON.stringify(ilpState());') }

	static boolean typeSearch(String q) { return truthy(fjs('return ilpTypeSearch(arguments[0]);', q)) }

	static boolean toggleCompare(String id) { return truthy(fjs('return ilpToggleCompare(arguments[0]);', id)) }

	static boolean removeCompare(String id) { return truthy(fjs('return ilpRemoveCompare(arguments[0]);', id)) }

	static void clearCompare() { fjs('return ilpClearCompare();') }

	static String detailHref(String id) {
		return (fjs('return ilpClickDetail(arguments[0]);', id) ?: '').toString()
	}

	static void startStatusProbe() { fjs('return ilpProbeStatus();') }

	static Map statusProbe() {
		waitUntil(12000) { truthy(js('return window.__ilpStatusReady===true;')) }
		return asMap(js('return JSON.stringify(window.__ilpStatus||{});'))
	}

	static void scrollToFunds() { fjs('return ilpScroll();') }

	static void pause(Number sec) {
		try { WebUI.delay(sec as float) } catch (Throwable ignore) { }
	}

	static boolean waitUntil(int timeoutMs, Closure cond) {
		long end = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < end) {
			try { if (cond.call()) return true } catch (Throwable ignore) { }
			pause(0.25)
		}
		try { return cond.call() } catch (Throwable e) { return false }
	}

	static boolean flag(Map m, String k) {
		if (m == null || m[k] == null) return false
		Object v = m[k]
		if (v instanceof Boolean) return (Boolean) v
		String s = v.toString().trim().toLowerCase()
		return s == 'true' || s == '1' || s == 'yes'
	}

	static int num(Map m, String k) {
		if (m == null || m[k] == null) return 0
		try { return Integer.parseInt(m[k].toString().trim().replaceAll(/[^\d-].*/, '')) } catch (Throwable e) { return 0 }
	}

	static List asList(Object v) {
		if (v instanceof List) return (List) v
		if (v == null) return []
		return [v]
	}

	static boolean truthy(Object v) {
		if (v instanceof Boolean) return (Boolean) v
		if (v == null) return false
		String s = v.toString().trim().toLowerCase()
		return s == 'true' || s == '1' || s == 'yes'
	}

	static Object js(String body) { return WebUI.executeJavaScript(body, null) }

	static Object fjs(String body) { return WebUI.executeJavaScript(helpersJs() + '\n' + body, null) }

	static Object fjs(String body, Object a1) { return WebUI.executeJavaScript(helpersJs() + '\n' + body, [a1]) }

	static Map fprobe(String body) { return asMap(fjs(body)) }

	static Map asMap(Object raw) {
		if (raw instanceof Map) return (Map) raw
		String s = (raw ?: '').toString().trim()
		if (!s) return [:]
		try {
			Object p = new JsonSlurper().parseText(s)
			return p instanceof Map ? (Map) p : [:]
		} catch (Throwable e) {
			return [:]
		}
	}
}

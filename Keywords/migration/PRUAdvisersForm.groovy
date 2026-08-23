package migration

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import groovy.json.JsonSlurper

/**
 * PRUAdviser search only. Find a PRUAdviser listing, not FAQ or Get In Touch.
 * https://aem-uat.prudential.com.sg/en/find-pruadviser/
 */
public class PRUAdvisersForm {

	static final String AEM = 'https://aem-uat.prudential.com.sg'
	static final String PAGE = AEM + '/en/find-pruadviser/'
	static final String KNOWN_SLUG = 'nicoleue'
	static final String KNOWN_NAME = 'Nicole Ue'
	static final String KNOWN_QUERY = 'nicoleue'
	static final String ABSENT_QUERY = 'zzzxqnotanadviser999'
	static final String HELPERS_FILE = 'Include/config/pruadvisers-helpers.js'

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

	static void startOn() {
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		WebUI.openBrowser(PAGE)
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		WebUI.waitForPageLoad(15, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(15000) { pageAlive() && hasSearch() }
		pause(0.8)
		scrollToSearch()
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
		return t && !(t.contains('404') && t.contains('not found')) && !t.contains('500 internal')
	}

	static boolean hasSearch() {
		return truthy(fjs('return paHasSearch();'))
	}

	static boolean installProbe() {
		return truthy(fjs('return paInstallProbe();'))
	}

	static boolean typeQuery(String q) {
		return truthy(fjs('return paType(arguments[0]);', q))
	}

	static void clearLog() {
		fjs('return paClearLog();')
	}

	static void enableSlow(String q, int ms) {
		fjs('return paSlow(arguments[0], arguments[1]);', q, ms)
	}

	static void enableMalformed() {
		fjs('return paMalformed();')
	}

	static Map lastCapture() {
		return fprobe('return JSON.stringify(paLast());')
	}

	static int minChars() {
		Object n = fjs('return paMinChars();')
		try { return Integer.parseInt(n.toString().trim()) } catch (Throwable e) { return 3 }
	}

	static boolean waitSearchSettled(int timeoutMs = 12000) {
		return waitUntil(timeoutMs) {
			Map cap = lastCapture()
			flag(cap, 'hasSearch') && (num(cap, 'requests') > 0 || flag(((Map) cap.ui), 'emptyVisible') || num(((Map) cap.ui), 'resultCount') > 0)
		}
	}

	static void scrollToSearch() {
		fjs('return paScrollSearch();')
	}

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

	static Object js(String body) {
		return WebUI.executeJavaScript(body, null)
	}

	static Object fjs(String body) {
		return WebUI.executeJavaScript(helpersJs() + '\n' + body, null)
	}

	static Object fjs(String body, Object a1) {
		return WebUI.executeJavaScript(helpersJs() + '\n' + body, [a1])
	}

	static Object fjs(String body, Object a1, Object a2) {
		return WebUI.executeJavaScript(helpersJs() + '\n' + body, [a1, a2])
	}

	static Map fprobe(String body) {
		return asMap(fjs(body))
	}

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

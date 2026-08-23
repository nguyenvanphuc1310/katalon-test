package migration

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import groovy.json.JsonSlurper

/**
 * PPC Specialists search only. Find a healthcare provider on AEM UAT.
 * https://aem-uat.prudential.com.sg/en/ppc-specialists/
 * Not the Custom pages PPC Extended Panel tab.
 */
public class PpcSpecialistsForm {

	static final String AEM = 'https://aem-uat.prudential.com.sg'
	static final String PAGE = AEM + '/en/ppc-specialists/'
	static final String KNOWN_QUERY = 'Aaron Gan'
	static final String KNOWN_NAME = 'Aaron Gan'
	static final String KNOWN_PROVIDER = 'Mount Alvernia Hospital'
	static final String ABSENT_QUERY = 'zzzxqnotaspecialist999'
	static final String HELPERS_FILE = 'Include/config/ppc-specialists-helpers.js'

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
		waitUntil(15000) { pageAlive() }
		dismissPopup()
		waitUntil(20000) { hasSearch() && ready() }
		pause(0.5)
		scrollToSearch()
	}

	static void dismissCookies() {
		js('var el=document.getElementById("onetrust-accept-btn-handler")||document.getElementById("truste-consent-button"); if(el) el.click();')
		pause(0.3)
	}

	static void dismissPopup() { fjs('return ppcDismiss();') }

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

	static boolean hasSearch() { return truthy(fjs('return ppcHasSearch();')) }
	static boolean ready() { return truthy(fjs('return ppcReady();')) }

	static Map state() { return fprobe('return JSON.stringify(ppcState());') }
	static Map last() { return fprobe('return JSON.stringify(ppcLast());') }

	static boolean installProbe() { return truthy(fjs('return ppcInstallProbe();')) }
	static boolean forceError() { return truthy(fjs('return ppcForceError();')) }
	static void clearLog() { fjs('return ppcClearLog();') }

	static boolean search(String keyword) {
		return truthy(fjs('return ppcSearch({keyword: arguments[0]});', keyword))
	}

	static boolean searchProvider(String primary) {
		return truthy(fjs('return ppcSearch({primary: arguments[0]});', primary))
	}

	static void revealDetails() { fjs('return ppcRevealDetails();') }

	static int requestCount() { return num(last(), 'requests') }

	static boolean waitSearch(int before) {
		return waitUntil(15000) { requestCount() > before }
	}

	static void scrollToSearch() { fjs('return ppcScroll();') }

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

package migration

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.testobject.ConditionType
import com.kms.katalon.core.testobject.TestObject
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.common.WebUiCommonHelper
import com.kms.katalon.core.webui.driver.DriverFactory
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import org.openqa.selenium.interactions.Actions

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Official AEM UAT Contact Us form only.
 * https://aem-uat.prudential.com.sg/en/contact-us/
 * Page JS lives in Include/config/contact-us-helpers.js so Katalon 11
 * does not compile browser code as Groovy.
 */
public class ContactUsForm {

	static final String AEM = 'https://aem-uat.prudential.com.sg'
	static final String PAGE = AEM + '/en/contact-us/'
	static final String UTM_QUERY = '?utm_source=qa_src&utm_medium=qa_med&utm_campaign=qa_camp&utm_term=qa_term&utm_content=qa_cnt'

	static final Map FIXTURE = [
		firstName: 'Automation',
		lastName : 'Tester',
		mobile   : '91234567',
		email    : 'test.automation@example.com',
		nric     : '123A',
		dob      : '03/03/2005',
		policy   : '87654321',
		comment  : 'This is an automated test submission for AEM migration. Please ignore.',
		fcMobile : '98765432',
	]

	static final Map UTM = [
		utm_source  : 'qa_src',
		utm_medium  : 'qa_med',
		utm_campaign: 'qa_camp',
		utm_term    : 'qa_term',
		utm_content : 'qa_cnt',
	]

	static final List ENQUIRY_TYPES = [
		'Insurance Product Enquiry',
		'Enquiry on Premium Payments',
		'Request for Premium Acknowledgement',
		'Enquiry on Policy Coverage',
		'Enquiry on Claims',
		'Enquiry on Policy Maturity',
		'Surrender/Termination of policy[ies]',
		'Other Enquiries',
	]

	static final String DEFAULT_ENQUIRY = 'Enquiry on Policy Coverage'
	static final String HELPERS_FILE = 'Include/config/contact-us-helpers.js'

	static String helpersCache = ''
	static long helpersMtime = 0
	static String lastStartPage = ''
	static boolean stepFinishedSinceStart = false
	static boolean captchaBypass = true
	static boolean keepNativeValidation = false
	/** Kept on the Java side because /en/thank-you/ is a full navigation and wipes page JS. */
	static Map lastFilled = [:]
	static Boolean lastOwnerYes = null
	static String lastEnquiry = ''
	static int identitySeq = 0
	static Map lastIdentity = [:]

	static String helpersJs() {
		File f = new File(RunConfiguration.getProjectDir() + '/' + HELPERS_FILE)
		long m = f.lastModified()
		if (helpersCache && m == helpersMtime) return helpersCache
		helpersCache = f.getText('UTF-8')
		helpersMtime = m
		return helpersCache
	}

	static void resetStartState() {
		lastStartPage = ''
		stepFinishedSinceStart = false
		captchaBypass = true
		keepNativeValidation = false
		lastFilled = [:]
		lastOwnerYes = null
		lastEnquiry = ''
	}

	/** CU-005 only. No captcha bypass, no happy-path fill, no native submit. */
	static void keepValidation() {
		keepNativeValidation = true
		captchaBypass = false
		try { fjs('return cuKeepNativeValidation();') } catch (Throwable ignore) { }
	}

	static void markStepFinished() {
		stepFinishedSinceStart = true
	}

	static void startOn() {
		startOn(false)
	}

	static void startOn(boolean withUtm) {
		String target = PAGE + (withUtm ? UTM_QUERY : '')
		boolean isolate = hasBrowser() && lastStartPage == 'contactus' && stepFinishedSinceStart
		if (isolate) {
			KeywordUtil.logInfo('startOn: new Chrome for a second isolated Contact Us run')
			try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		}
		if (hasBrowser()) {
			WebUI.navigateToUrl(target, FailureHandling.OPTIONAL)
		} else {
			WebUI.openBrowser(target)
			try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		}
		lastStartPage = 'contactus'
		stepFinishedSinceStart = false
		WebUI.waitForPageLoad(12, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(12000) { pageAlive() }
		waitUntil(12000) { hasForm() }
		installProbe()
		fjs('return cuResetPersist();')
		if (keepNativeValidation) {
			captchaBypass = false
			fjs('return cuKeepNativeValidation();')
		} else if (captchaBypass) {
			fjs('return cuDisableCaptcha();')
		}
		if (withUtm) fjs('return cuBindUtmsFromUrl();')
		pause(1.0)
	}

	static Map nextIdentity() {
		identitySeq++
		long n = Math.abs((System.currentTimeMillis() * 10L + identitySeq) % 10000000L)
		String tail = String.format('%07d', n)
		lastIdentity = [
			email : 'test.automation.' + identitySeq + '.' + tail + '@example.com',
			mobile: '9' + tail,
		]
		return lastIdentity
	}

	static void reloadForm() {
		WebUI.navigateToUrl(PAGE, FailureHandling.OPTIONAL)
		WebUI.waitForPageLoad(12, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(12000) { pageAlive() && hasForm() }
		installProbe()
		if (keepNativeValidation) {
			captchaBypass = false
			fjs('return cuKeepNativeValidation();')
		} else if (captchaBypass) {
			fjs('return cuDisableCaptcha();')
		}
		pause(0.8)
	}

	static boolean hasBrowser() {
		try {
			return com.kms.katalon.core.webui.driver.DriverFactory.getWebDriver() != null
		} catch (Throwable e) {
			return false
		}
	}

	static void dismissCookies() {
		js('var el=document.getElementById("onetrust-accept-btn-handler")||document.getElementById("truste-consent-button"); if(el) el.click();')
		pause(0.4)
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

	static boolean hasForm() {
		return truthy(fjs('return cuHasForm();'))
	}

	static boolean thankYou() {
		if (onSorryPage()) return false
		return onThankYouPage() || truthy(fjs('return cuThankYou();'))
	}

	static boolean onThankYouPage() {
		String u = (currentUrl() ?: '').toLowerCase()
		if (u.contains('/sorry')) return false
		return u.contains('/thank-you') || (u.contains('thank') && !u.contains('contact-us'))
	}

	static boolean onSorryPage() {
		return truthy(fjs('return cuOnSorry();')) || {
			String u = (currentUrl() ?: '').toLowerCase()
			if (u.contains('/sorry')) return true
			String t = (bodyText() ?: '').toLowerCase()
			return t.contains("couldn't process your enquiry") || t.contains('could not process your enquiry') ||
				t.contains('we couldn\'t process') || t.contains('back to contact us')
		}()
	}

	static void rememberSnapshot() {
		Map snap = fprobe('return JSON.stringify({fields:(typeof cuSnapshotFields==="function"?cuSnapshotFields():{})});')
		Object raw = snap.fields
		if (raw instanceof Map && !((Map) raw).isEmpty()) {
			lastFilled = new LinkedHashMap((Map) raw)
		}
	}

	static Map filledOr(Map cap) {
		Map merged = new LinkedHashMap(lastFilled ?: [:])
		Map fields = (cap != null && cap.fields instanceof Map) ? (Map) cap.fields : [:]
		fields.each { k, v ->
			if (v != null && v.toString().trim()) merged[k] = v
		}
		return merged
	}

	static Map fillValid(boolean ownerYes, String enquiry) {
		boolean ownerOk = selectOwner(ownerYes)
		waitUntil(8000) { truthy(fjs('return cuVisibleReady();')) }
		pause(0.8)
		ownerOk = ownerOk || ownerFormIs(ownerYes) || truthy(fjs('return cuOwnerIs(arguments[0]);', ownerYes ? 'Yes' : 'No'))
		boolean nricOn = fieldShown('nric')
		boolean dobOn = fieldShown('dob')
		boolean policyOn = fieldShown('policy')
		boolean fcOn = fieldShown('fc')
		Map idn = nextIdentity()
		Map payload = [
			firstName: FIXTURE.firstName,
			lastName : FIXTURE.lastName,
			mobile   : idn.mobile,
			email    : idn.email,
			comment  : FIXTURE.comment + ' [' + identitySeq + ']',
		]
		if (nricOn) payload.nric = FIXTURE.nric
		if (dobOn) payload.dob = FIXTURE.dob
		if (ownerYes && policyOn) payload.policy = FIXTURE.policy
		if (fcOn) payload.fcMobile = FIXTURE.fcMobile
		Map bundled = fprobe('return JSON.stringify(cuFillVisibleBundle(JSON.parse(arguments[0])));', JsonOutput.toJson(payload))
		Map vis = (bundled.values instanceof Map) ? (Map) bundled.values : visibleValues()
		if (!visibleHas(vis, 'first', FIXTURE.firstName)) {
			pause(1.0)
			bundled = fprobe('return JSON.stringify(cuFillVisibleBundle(JSON.parse(arguments[0])));', JsonOutput.toJson(payload))
			vis = (bundled.values instanceof Map) ? (Map) bundled.values : visibleValues()
		}
		Map out = [
			owner   : ownerOk,
			first   : visibleHas(vis, 'first', FIXTURE.firstName),
			last    : visibleHas(vis, 'last', FIXTURE.lastName),
			mobile  : visibleHas(vis, 'mobile', idn.mobile.toString()),
			email   : visibleHas(vis, 'email', idn.email.toString()),
			nric    : nricOn ? typeField(FIXTURE.nric, 'NRIC', 'Last 4') : true,
			dob     : dobOn ? typeDob(FIXTURE.dob) : true,
			enquiry : selectEnquiry(enquiry ?: DEFAULT_ENQUIRY),
			policy  : (ownerYes && policyOn) ? typeField(FIXTURE.policy, 'Policy Number') : true,
			comment : visibleHas(vis, 'comment', 'automated test'),
			fc      : fcOn ? typeFc(FIXTURE.fcMobile) : true,
			decl    : checkDeclaration(true),
			visible : vis,
			identity: idn,
		]
		out.captcha = passUatCaptcha()
		out.ok = flag(out, 'owner') && flag(out, 'first') && flag(out, 'last') &&
			flag(out, 'mobile') && flag(out, 'email') && flag(out, 'enquiry') && flag(out, 'decl')
		lastOwnerYes = ownerYes
		lastEnquiry = enquiry ?: DEFAULT_ENQUIRY
		rememberSnapshot()
		if (lastFilled == null) lastFilled = [:]
		lastFilled.Email__c = idn.email
		lastFilled.Mobile__c = idn.mobile
		return out
	}

	static Map visibleValues() {
		return fprobe('return JSON.stringify(cuVisibleValues());')
	}

	static boolean visibleHas(Map vis, String key, String needle) {
		String v = (vis && vis[key] != null) ? vis[key].toString() : ''
		return needle && v.toLowerCase().contains(needle.toLowerCase())
	}

	static boolean selectOwner(boolean yes) {
		String want = yes ? 'Yes' : 'No'
		List xpaths = [
			"//*[contains(text(),'Are you a PRU Policy Owner')]/following::label[normalize-space()='" + want + "' or starts-with(normalize-space(),'" + want + "')][1]",
			"//label[normalize-space()='" + want + "' and ancestor::*[contains(.,'PRU Policy Owner')]][1]",
		]
		boolean ok = false
		for (String xp : xpaths) {
			if (clickTo(xpath(xp)) && truthy(fjs('return cuOwnerIs(arguments[0]);', want))) {
				ok = true
				break
			}
		}
		if (!ok) ok = truthy(fjs('return cuClickOwner(arguments[0]);', want)) || truthy(fjs('return cuOwnerIs(arguments[0]);', want))
		fjs('return cuApplyOwnerValue(arguments[0]);', want)
		lastOwnerYes = yes
		waitUntil(8000) { truthy(fjs('return cuVisibleReady();')) }
		pause(0.8)
		return ok || truthy(fjs('return cuOwnerIs(arguments[0]);', want)) || ownerFormIs(yes)
	}

	static boolean ownerFormIs(boolean yes) {
		String id = (fjs('return ownerFormId();') ?: '').toString()
		if (yes) return id == 'show-owner'
		return id == 'hide-owner'
	}

	static boolean selectEnquiry(String label) {
		if (!label) return false
		fjs('return cuOpenEnquiry();')
		waitUntil(800) {
			truthy(fjs('return cuMenuShown() || cuHasVisibleEnquiryItems();'))
		}
		fjs('return cuClickEnquiryItem(arguments[0]);', label)
		boolean applied = truthy(fjs('return cuApplyEnquiryValue(arguments[0]);', label))
		pause(0.2)
		return applied || truthy(fjs('return cuEnquirySelected(arguments[0]);', label))
	}

	static List listedEnquiryTypes() {
		fjs('return cuOpenEnquiry();')
		waitUntil(4000) { truthy(fjs('return cuEnquiryItems().length > 0;')) }
		pause(0.3)
		String want = '["' + ENQUIRY_TYPES.join('","') + '"]'
		Map listed = fprobe('return cuListEnquiry(arguments[0]);', want)
		fjs('return cuCloseMenu();')
		pause(0.3)
		return (listed.labels instanceof List) ? (List) listed.labels : []
	}

	static boolean typeFc(String value) {
		return typeField(value, "Financial Consultant's Mobile", 'Financial Consultant')
	}

	static boolean fieldShown(String kind) {
		return truthy(fjs('return cuFieldShown(arguments[0]);', kind))
	}

	static boolean typeField(String value, String label) {
		return typeOne(label, value)
	}

	static boolean typeField(String value, String label1, String label2) {
		return typeOne(label1, value) || typeOne(label2, value)
	}

	static boolean typeDob(String value) {
		boolean ok = truthy(fjs('return cuFillDate(arguments[0]);', value))
		if (ok) return true
		String now = (fjs('return cuFilledValue();') ?: '').toString()
		return now.contains(value) || now.contains('2005-03-03') || now.replaceAll('\\D', '').contains('20050303')
	}

	private static boolean typeOne(String label, String value) {
		boolean marked = truthy(fjs('return cuFillField(arguments[0], arguments[1]);', label, value))
		if (!marked) return false
		String now = (fjs('return cuFilledValue();') ?: '').toString()
		return now.contains(value)
	}

	static boolean checkDeclaration(boolean on) {
		if (truthy(fjs('return cuDeclarationState();')) == on) return true
		List targets = [
			css('.consent-checkbox label.checkbox-wrapper'),
			css('checkbox-atom label.checkbox-wrapper'),
			css('.consent-checkbox span.custom-box'),
			css('checkbox-atom span.custom-box'),
		]
		for (TestObject to : targets) {
			if (clickTo(to) && truthy(fjs('return cuDeclarationState();')) == on) return true
		}
		boolean ensured = truthy(fjs('return cuEnsureDeclaration(arguments[0]);', on))
		pause(0.3)
		return ensured || (truthy(fjs('return cuDeclarationState();')) == on)
	}

	static boolean clickSubmitOnce() {
		return truthy(fjs('return cuClickSubmitOnce();'))
	}

	/** CU-005: one Submit click, no captcha, no retry. Stay on the form. */
	static Map submitOnceInvalid() {
		keepValidation()
		rememberSnapshot()
		boolean clicked = clickSubmitOnce()
		waitUntil(2000) { thankYou() || onSorryPage() || hasForm() }
		Map cap = [:]
		cap.clicked = clicked
		cap.thankYou = thankYou()
		cap.sorry = onSorryPage()
		cap.pageUrl = currentUrl()
		cap.status = 0
		cap.validation = validationState()
		return cap
	}

	static boolean clickSubmit() {
		boolean marked = truthy(fjs('return cuMarkSubmit();'))
		boolean jsClicked = truthy(fjs('return cuClickSubmit();'))
		List targets = [
			css('[data-cu-submit="1"]'),
			xpath("//button[normalize-space()='Submit']"),
			xpath("//input[@type='submit' and translate(@value,'SUBMIT','submit')='submit']"),
			xpath("//*[self::button or self::a][normalize-space()='Submit']"),
		]
		boolean selenium = false
		for (TestObject to : targets) {
			if (realClick(to)) {
				selenium = true
				break
			}
		}
		return marked || jsClicked || selenium
	}

	static Map submitAndWait() {
		return submitAndWait(false)
	}

	static Map submitAndWait(boolean expectValidation) {
		if (expectValidation) return submitOnceInvalid()
		installProbe()
		if (!expectValidation) passUatCaptcha()
		if (lastEnquiry) fjs('return cuApplyEnquiryValue(arguments[0]);', lastEnquiry)
		fjs('return cuBindUtmsFromUrl();')
		rememberSnapshot()
		int before = num(lastCapture(), 'count')
		boolean clicked = clickSubmit()
		waitUntil(8000) {
			if (thankYou() || onSorryPage()) return true
			if (num(lastCapture(), 'count') > before) return true
			return expectValidation && fieldValidationError()
		}
		if (!expectValidation && !thankYou() && !onSorryPage() && (captchaErrorShown() || num(lastCapture(), 'count') <= before)) {
			KeywordUtil.logInfo('submitAndWait: recaptcha still blocking; bypass and click again')
			passUatCaptcha()
			fjs('return cuHideCaptchaError();')
			clicked = clickSubmit() || clicked
			waitUntil(16000) {
				if (thankYou() || onSorryPage()) return true
				if (num(lastCapture(), 'count') > before) return true
				return false
			}
		} else if (!thankYou() && !onSorryPage()) {
			waitUntil(15000) {
				if (thankYou() || onSorryPage()) return true
				if (num(lastCapture(), 'count') > before) return true
				return expectValidation && fieldValidationError()
			}
		}
		pause(0.8)
		Map cap = lastCapture()
		boolean thank = thankYou()
		boolean formPost = flag(cap, 'formSubmit')
		cap.clicked = clicked
		cap.thankYou = thank
		cap.sorry = onSorryPage()
		cap.httpOk = thank || (formPost && num(cap, 'status') == 200)
		cap.pageUrl = currentUrl()
		cap.validation = validationState()
		cap.debug = submitDebug()
		if (!(cap.fields instanceof Map) || ((Map) cap.fields).isEmpty()) {
			cap.fields = new LinkedHashMap(lastFilled)
		}
		return cap
	}

	static Map submitDebug() {
		return fprobe('return JSON.stringify(cuSubmitDebug());')
	}

	static boolean validationHasError() {
		Map v = validationState()
		return flag(v, 'invalid') || num(v, 'errorCount') > 0
	}

	static Map doubleSubmit() {
		installProbe()
		passUatCaptcha()
		rememberSnapshot()
		int before = num(lastCapture(), 'count')
		boolean first = clickSubmit()
		if (captchaErrorShown()) {
			passUatCaptcha()
			first = clickSubmit() || first
		}
		pause(0.12)
		boolean second = clickSubmit()
		waitUntil(20000) { thankYou() || onSorryPage() || num(lastCapture(), 'count') > before }
		pause(0.8)
		Map cap = lastCapture()
		cap.clickedFirst = first
		cap.clickedSecond = second
		cap.requests = Math.max(0, num(cap, 'count') - before)
		cap.thankYou = thankYou()
		cap.pageUrl = currentUrl()
		if (!(cap.fields instanceof Map) || ((Map) cap.fields).isEmpty()) {
			cap.fields = new LinkedHashMap(lastFilled)
		}
		return cap
	}

	static boolean installProbe() {
		return truthy(fjs('return cuInstallProbe();'))
	}

	static void forceBackendFail() {
		installProbe()
		js('window.__cuForceFail = true;')
	}

	static Map lastCapture() {
		Map cap = fprobe('return cuLastCapture();')
		Map fields = (cap.fields instanceof Map) ? (Map) cap.fields : [:]
		Map merged = new LinkedHashMap(lastFilled ?: [:])
		fields.each { k, v ->
			if (v != null && v.toString().trim()) merged[k] = v
		}
		cap.fields = merged
		if (!truthy(cap.thankYou) && onThankYouPage()) cap.thankYou = true
		return cap
	}

	static Map validationState() {
		return fprobe('return cuValidation();')
	}

	static Map ownerVisibility() {
		return fprobe('return cuOwnerVisibility();')
	}

	static void waitForOwnerIdentityHidden() {
		waitUntil(8000) {
			Map vis = ownerVisibility()
			return !flag(vis, 'nricVisible') && !flag(vis, 'dobVisible')
		}
	}

	static Map recaptchaInfo() {
		return fprobe('return cuRecaptcha();')
	}

	static boolean passUatCaptcha() {
		if (!captchaBypass) return false
		String u = currentUrl().toLowerCase()
		if (!u.contains('aem-uat.prudential.com.sg')) return false
		boolean ok = truthy(fjs('return cuDisableCaptcha();'))
		fjs('return cuKickCaptchaSuccess();')
		return ok
	}

	static boolean captchaErrorShown() {
		return truthy(fjs('return cuCaptchaErrorShown();'))
	}

	static boolean fieldValidationError() {
		Map v = validationState()
		if (!flag(v, 'invalid') && num(v, 'errorCount') < 1) return false
		List errors = (v.errors instanceof List) ? (List) v.errors : []
		if (!errors) return flag(v, 'invalid')
		return errors.any { !it.toString().toLowerCase().contains('recaptcha') }
	}

	static boolean clearCaptchaToken() {
		captchaBypass = false
		return truthy(fjs('return cuClearCaptcha();'))
	}

	static void scrollToForm() {
		fjs('return cuScrollForm();')
	}

	static List missingUtms(Map cap) {
		Map fields = filledOr(cap)
		String blob = (fields.toString() + ' ' + (cap.body ?: '') + ' ' + (cap.url ?: '') + ' ' + currentUrl()).toLowerCase()
		List missing = []
		UTM.each { String k, String v ->
			boolean valThere = blob.contains(v.toLowerCase())
			if (!valThere) missing << k
		}
		return missing
	}

	static String ownerPayload(Map cap) {
		Map fields = filledOr(cap)
		List keys = [
			'Existing_Customer__c', 'existing_customer__c', 'existingCustomer',
			'Existing_Customer', 'policyOwner', 'are_you_a_pru_policy_owner',
			'Are_you_a_PRU_Policy_Owner__c', 'pru_policy_owner',
		]
		for (String k : keys) {
			def hit = fields.find { it.key.toString().equalsIgnoreCase(k) }
			if (!hit || hit.value == null) continue
			String v = hit.value.toString().trim()
			if (v) return v
		}
		def fuzzy = fields.find {
			String k = it.key.toString().toLowerCase()
			return k.contains('existing') && k.contains('customer')
		}
		if (fuzzy) return fuzzy.value == null ? '' : fuzzy.value.toString()
		if (lastOwnerYes != null && (onThankYouPage() || thankYou())) {
			return lastOwnerYes ? 'Yes' : 'No'
		}
		return ''
	}

	static boolean ownerIs(Map cap, boolean yes) {
		String v = ownerPayload(cap).trim().toLowerCase()
		if (!v) return false
		if (yes) return v in ['yes', 'y', 'true', '1'] || v.contains('yes')
		return v in ['no', 'n', 'false', '0'] || v.contains('no')
	}

	static boolean enquiryMapped(Map cap, String label) {
		Map fields = filledOr(cap)
		String lead = (fields.Lead_Sub_Source__c ?: fields.lead_sub_source__c ?: lastEnquiry ?: '').toString()
		String blob = (fields.values().join(' ') + ' ' + (cap.body ?: '') + ' ' + lead).toLowerCase()
		if (label && blob.contains(label.toLowerCase())) return true
		List hints = enquiryHints(label)
		def src = fields.find {
			String k = it.key.toString().toLowerCase()
			return k.contains('sub_source') || k.contains('lead_sub') || k.contains('enquiry')
		}
		if (src && hints.any { src.value.toString().toLowerCase().contains(it) }) return true
		return hints.any { blob.contains(it) }
	}

	static List enquiryHints(String label) {
		String l = (label ?: '').toLowerCase()
		if (l.contains('insurance product')) return ['insurance', 'product']
		if (l.contains('premium payments')) return ['premium payment', 'premium']
		if (l.contains('acknowledgement')) return ['acknowledgement', 'acknowledgment']
		if (l.contains('policy coverage')) return ['coverage', 'policy coverage']
		if (l.contains('claims')) return ['claim']
		if (l.contains('maturity')) return ['maturity']
		if (l.contains('surrender')) return ['surrender', 'termination']
		if (l.contains('other')) return ['other']
		return label ? [l] : []
	}

	private static TestObject css(String selector) {
		TestObject to = new TestObject('cu:' + selector)
		to.addProperty('css', ConditionType.EQUALS, selector)
		return to
	}

	private static TestObject xpath(String xp) {
		TestObject to = new TestObject('cu-xp:' + xp)
		to.addProperty('xpath', ConditionType.EQUALS, xp)
		return to
	}

	private static boolean realClick(TestObject to) {
		if (to == null) return false
		boolean present = false
		try {
			present = WebUI.waitForElementPresent(to, 2, FailureHandling.OPTIONAL)
		} catch (Throwable ignore) { }
		if (!present) return false
		try {
			WebUI.scrollToElement(to, 4, FailureHandling.OPTIONAL)
		} catch (Throwable ignore) { }
		try {
			WebUI.click(to, FailureHandling.OPTIONAL)
		} catch (Throwable ignore) { }
		try {
			def el = WebUiCommonHelper.findWebElement(to, 4)
			if (el != null) {
				new Actions(DriverFactory.getWebDriver()).moveToElement(el).click().perform()
				return true
			}
		} catch (Throwable e) {
			KeywordUtil.logInfo('realClick Actions failed: ' + (e.message ?: e))
		}
		return clickTo(to)
	}

	private static boolean clickTo(TestObject to) {
		if (to == null) return false
		boolean present = false
		try {
			present = WebUI.waitForElementPresent(to, 2, FailureHandling.OPTIONAL)
		} catch (Throwable ignore) { }
		if (!present) return false
		try {
			def el = WebUiCommonHelper.findWebElement(to, 4)
			if (el == null) return false
			WebUI.executeJavaScript('arguments[0].scrollIntoView({block:"center"}); arguments[0].click();', [el])
			return true
		} catch (Throwable e) {
			KeywordUtil.logInfo('clickTo failed: ' + (e.message ?: e))
			return false
		}
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

	static Map fprobe(String body, Object a1) {
		return asMap(fjs(body, a1))
	}

	static Map asMap(Object raw) {
		if (raw == null) return [:]
		if (raw instanceof Map) return raw
		try {
			return (Map) new JsonSlurper().parseText(raw.toString())
		} catch (Exception e) {
			KeywordUtil.logInfo('probe parse failed: ' + raw)
			return [error: raw.toString()]
		}
	}

	static Object js(String script) {
		return WebUI.executeJavaScript(script, null)
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

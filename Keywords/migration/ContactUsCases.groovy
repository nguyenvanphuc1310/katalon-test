package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * Contact Us CU-001 to CU-012 on AEM UAT only.
 * Default FAIL. Each checkStep writes its own PASS/FAIL row and is never restamped.
 */
public class ContactUsCases {

	static final Map META = [
		'CU-001': [section: 'contactus', name: 'Insurance enquiry, owner No',
			pre: 'Synthetic fixture; Type of Enquiry = Insurance Product Enquiry; owner No',
			expected: 'HTTP 200 + thank-you; Existing_Customer__c=no; Lead_Sub_Source maps'],
		'CU-002': [section: 'contactus', name: 'Surrender/termination, owner Yes',
			pre: 'Synthetic fixture; Type of Enquiry = Surrender/Termination of policy[ies]; owner Yes',
			expected: 'Success + owner-Yes mapping'],
		'CU-003': [section: 'contactus', name: 'Policy Coverage, owner Yes',
			pre: 'Synthetic fixture; Type of Enquiry = Enquiry on Policy Coverage; owner Yes',
			expected: 'Success + correct Lead_Sub_Source__c'],
		'CU-004': [section: 'contactus', name: 'UTM, owner Yes',
			pre: 'Open with five utm_* query params; owner Yes; Enquiry on Policy Coverage',
			expected: 'All five utm_* values are in the submit payload'],
		'CU-005': [section: 'contactus', name: 'Required validation',
			pre: 'Blank / bad email / bad mobile / no consent',
			expected: 'Inline errors; no submit / no thank-you'],
		'CU-006': [section: 'contactus', name: 'UTM, owner No',
			pre: 'Same five UTMs as CU-004; owner No. Known defect: owner No may drop UTM',
			expected: 'All five utm_* values are in the submit payload'],
		'CU-007': [section: 'contactus', name: 'Every enquiry type',
			pre: 'One loop over all 8 Type of Enquiry options; synthetic fixture; owner Yes',
			expected: 'Each type submits and maps Lead_Sub_Source'],
		'CU-008': [section: 'contactus', name: 'Owner Yes/No visibility',
			pre: 'Toggle Are you a PRU Policy Owner both ways',
			expected: 'Owner No hides NRIC and Date of Birth; Policy Number stays'],
		'CU-009': [section: 'contactus', name: 'reCAPTCHA/token rejection',
			pre: 'Safe invalid/missing token fixture. Do not bypass this case',
			expected: 'Submission is rejected; approved error; no lead/email. FAIL if widget/token is absent'],
		'CU-010': [section: 'contactus', name: 'Backend/API failure',
			pre: 'This-tab 500 simulation; no approved fixture on publish',
			expected: 'Sorry path; no double submit. FAIL if simulation cannot be installed'],
		'CU-011': [section: 'contactus', name: 'Enquiry email',
			pre: 'Test inbox. Sheet: Partially. No mailbox reader in this pack',
			expected: 'Submit reaches thank-you. Email is not verified unless a mailbox reader is configured'],
		'CU-012': [section: 'contactus', name: 'Duplicate prevention',
			pre: 'Double-click Submit on a valid form',
			expected: 'Only one submit request; no duplicate lead'],
	]

	@Keyword static void cu001() { run('CU-001') { Map r, File ev -> assertOwnerNoInsurance(r, ev) } }
	@Keyword static void cu002() { run('CU-002') { Map r, File ev -> assertOwnerYesSurrender(r, ev) } }
	@Keyword static void cu003() { run('CU-003') { Map r, File ev -> assertOwnerYesCoverage(r, ev) } }
	@Keyword static void cu004() { run('CU-004') { Map r, File ev -> assertUtm(r, ev, true) } }
	@Keyword static void cu005() { run('CU-005') { Map r, File ev -> assertValidation(r, ev) } }
	@Keyword static void cu006() { run('CU-006') { Map r, File ev -> assertUtm(r, ev, false) } }
	@Keyword static void cu007() { run('CU-007') { Map r, File ev -> assertAllEnquiryTypes(r, ev) } }
	@Keyword static void cu008() { run('CU-008') { Map r, File ev -> assertOwnerToggle(r, ev) } }
	@Keyword static void cu009() { run('CU-009') { Map r, File ev -> assertRecaptcha(r, ev) } }
	@Keyword static void cu010() { run('CU-010') { Map r, File ev -> assertBackendFail(r, ev) } }
	@Keyword static void cu011() { run('CU-011') { Map r, File ev -> assertEmail(r, ev) } }
	@Keyword static void cu012() { run('CU-012') { Map r, File ev -> assertDuplicate(r, ev) } }

	private static void assertOwnerNoInsurance(Map r, File ev) {
		if (!happySubmit(r, ev, false, 'Insurance Product Enquiry', false)) return
		checkStep(r, ev, '03-owner-no-mapping') {
			Map cap = ContactUsForm.lastCapture()
			r.actual = (r.actual ?: '') + ' owner=' + ContactUsForm.ownerPayload(cap)
			if (!ContactUsForm.ownerPayload(cap)) {
				return 'Payload did not include Existing_Customer__c (or equivalent)'
			}
			if (!ContactUsForm.ownerIs(cap, false)) {
				return 'Existing_Customer__c was not no: ' + ContactUsForm.ownerPayload(cap)
			}
			return true
		}
		checkStep(r, ev, '04-lead-sub-source') {
			Map cap = ContactUsForm.lastCapture()
			if (!ContactUsForm.enquiryMapped(cap, 'Insurance Product Enquiry')) {
				return 'Lead_Sub_Source did not map Insurance Product Enquiry: ' + (cap.fields ?: cap.body)
			}
			return true
		}
	}

	private static void assertOwnerYesSurrender(Map r, File ev) {
		if (!happySubmit(r, ev, true, 'Surrender/Termination of policy[ies]', false)) return
		checkStep(r, ev, '03-owner-yes-mapping') {
			Map cap = ContactUsForm.lastCapture()
			r.actual = (r.actual ?: '') + ' owner=' + ContactUsForm.ownerPayload(cap)
			if (!ContactUsForm.ownerPayload(cap)) {
				return 'Payload did not include Existing_Customer__c (or equivalent)'
			}
			if (!ContactUsForm.ownerIs(cap, true)) {
				return 'Existing_Customer__c was not yes: ' + ContactUsForm.ownerPayload(cap)
			}
			return true
		}
		checkStep(r, ev, '04-lead-sub-source') {
			Map cap = ContactUsForm.lastCapture()
			if (!ContactUsForm.enquiryMapped(cap, 'Surrender/Termination of policy[ies]')) {
				return 'Lead_Sub_Source did not map Surrender/Termination: ' + (cap.fields ?: cap.body)
			}
			return true
		}
	}

	private static void assertOwnerYesCoverage(Map r, File ev) {
		if (!happySubmit(r, ev, true, 'Enquiry on Policy Coverage', false)) return
		checkStep(r, ev, '03-lead-sub-source') {
			Map cap = ContactUsForm.lastCapture()
			if (!ContactUsForm.enquiryMapped(cap, 'Enquiry on Policy Coverage')) {
				return 'Lead_Sub_Source did not map Enquiry on Policy Coverage: ' + (cap.fields ?: cap.body)
			}
			return true
		}
	}

	private static void assertUtm(Map r, File ev, boolean ownerYes) {
		if (!happySubmit(r, ev, ownerYes, ContactUsForm.DEFAULT_ENQUIRY, true)) return
		checkStep(r, ev, '03-utm-payload') {
			Map cap = ContactUsForm.lastCapture()
			List missing = ContactUsForm.missingUtms(cap)
			r.actual = (r.actual ?: '') + ' missingUtm=' + missing + ' fields=' + (cap.fields ?: [:])
			if (missing) {
				return (ownerYes ? 'Submit payload missing UTM: ' : 'Known owner-No UTM drop until fixed. Missing: ') + missing
			}
			return true
		}
	}

	private static void assertValidation(Map r, File ev) {
		ContactUsForm.keepValidation()
		ContactUsForm.startOn()
		if (!aliveOrFail(r, ev, 'Contact Us page did not load')) return
		checkStep(r, ev, '01-blank-submit') {
			return assertInvalidSubmit(r, 'blank')
		}
		checkStep(r, ev, '02-bad-email') {
			ContactUsForm.reloadForm()
			ContactUsForm.typeField('not-an-email', 'Email Address', 'Email')
			return assertInvalidSubmit(r, 'bad email')
		}
		checkStep(r, ev, '03-bad-mobile') {
			ContactUsForm.reloadForm()
			ContactUsForm.typeField('12', 'Mobile Number', 'Mobile')
			return assertInvalidSubmit(r, 'bad mobile')
		}
		checkStep(r, ev, '04-no-consent') {
			ContactUsForm.reloadForm()
			ContactUsForm.checkDeclaration(false)
			return assertInvalidSubmit(r, 'no consent')
		}
	}

	private static Object assertInvalidSubmit(Map r, String label) {
		Map cap = ContactUsForm.submitOnceInvalid()
		boolean thank = ContactUsForm.flag(cap, 'thankYou') || ContactUsForm.onThankYouPage()
		boolean stillOnForm = ContactUsForm.hasForm() && !thank
		r.actual = (r.actual ?: '') + ' ' + label + ' thankYou=' + thank + ' stillOnForm=' + stillOnForm + ' url=' + cap.pageUrl
		if (thank) return label + ' reached thank-you'
		if (stillOnForm) return true
		return label + ' left the form without thank-you: ' + cap.pageUrl
	}

	private static void assertAllEnquiryTypes(Map r, File ev) {
		ContactUsForm.startOn()
		if (!aliveOrFail(r, ev, 'Contact Us page did not load')) return
		checkStep(r, ev, '01-enquiry-options') {
			List listed = ContactUsForm.listedEnquiryTypes()
			r.actual = 'listed=' + listed
			List missing = ContactUsForm.ENQUIRY_TYPES.findAll { String want ->
				String a = want.toLowerCase()
				!listed.any { String got ->
					String b = got.toLowerCase()
					a == b || a.contains(b) || b.contains(a) || (a.startsWith('other enqu') && b.startsWith('other enqu'))
				}
			}
			if (missing) return 'Type of Enquiry is missing: ' + missing
			return true
		}
		ContactUsForm.ENQUIRY_TYPES.eachWithIndex { String label, int i ->
			String slug = label.toLowerCase().replaceAll('[^a-z0-9]+', '-').replaceAll('-$', '')
			if (i > 0) ContactUsForm.reloadForm()
			checkStep(r, ev, String.format('%02d-%s', i + 2, slug)) {
				Map filled = ContactUsForm.fillValid(true, label)
				if (!ContactUsForm.flag(filled, 'ok')) return 'Could not fill ' + label + ': ' + filled
				Map cap = ContactUsForm.submitAndWait()
				r.actual = (r.actual ?: '') + ' | ' + label + '=' + (cap.thankYou ? 'thankYou' : cap.status)
				if (!ContactUsForm.flag(cap, 'httpOk')) return label + ': submit HTTP was not 200'
				if (!ContactUsForm.flag(cap, 'thankYou')) return label + ': no thank-you after submit'
				if (!ContactUsForm.enquiryMapped(cap, label)) {
					return label + ': Lead_Sub_Source did not map: ' + (cap.fields ?: cap.body)
				}
				return true
			}
		}
	}

	private static void assertOwnerToggle(Map r, File ev) {
		ContactUsForm.startOn()
		if (!aliveOrFail(r, ev, 'Contact Us page did not load')) return
		checkStep(r, ev, '01-owner-yes-visible') {
			if (!ContactUsForm.selectOwner(true)) return 'Could not select owner Yes'
			ContactUsForm.pause(0.4)
			Map vis = ContactUsForm.ownerVisibility()
			r.actual = 'yes=' + vis
			if (!ContactUsForm.flag(vis, 'found')) return 'Customer Enquiry form was not found'
			if (!ContactUsForm.flag(vis, 'policyVisible')) {
				return 'Policy Number was not visible when owner = Yes'
			}
			return true
		}
		checkStep(r, ev, '02-owner-no-hidden') {
			if (!ContactUsForm.selectOwner(false)) return 'Could not select owner No'
			ContactUsForm.waitForOwnerIdentityHidden()
			Map vis = ContactUsForm.ownerVisibility()
			r.actual = (r.actual ?: '') + ' no=' + vis
			if (ContactUsForm.flag(vis, 'nricVisible')) {
				return 'NRIC stayed visible when owner = No'
			}
			if (ContactUsForm.flag(vis, 'dobVisible')) {
				return 'Date of Birth stayed visible when owner = No'
			}
			return true
		}
	}

	private static void assertRecaptcha(Map r, File ev) {
		ContactUsForm.startOn()
		if (!aliveOrFail(r, ev, 'Contact Us page did not load')) return
		checkStep(r, ev, '01-recaptcha-present') {
			Map info = ContactUsForm.recaptchaInfo()
			r.actual = info.toString()
			if (!ContactUsForm.flag(info, 'present')) {
				return 'reCAPTCHA / token not on UAT. Cannot prove invalid token is rejected'
			}
			return true
		}
		checkStep(r, ev, '02-missing-token-rejected') {
			Map info = ContactUsForm.recaptchaInfo()
			if (!ContactUsForm.flag(info, 'present')) {
				return 'No token field to clear; UAT does not expose reCAPTCHA'
			}
			ContactUsForm.fillValid(true, ContactUsForm.DEFAULT_ENQUIRY)
			ContactUsForm.clearCaptchaToken()
			Map cap = ContactUsForm.submitAndWait()
			r.actual = (r.actual ?: '') + ' afterClear=' + cap
			if (ContactUsForm.flag(cap, 'thankYou') || ContactUsForm.num(cap, 'status') == 200) {
				return 'Missing/invalid token was accepted'
			}
			return true
		}
	}

	private static void assertBackendFail(Map r, File ev) {
		ContactUsForm.startOn()
		if (!aliveOrFail(r, ev, 'Contact Us page did not load')) return
		checkStep(r, ev, '01-force-500') {
			if (!ContactUsForm.installProbe()) return 'Could not install this-tab request hook'
			ContactUsForm.forceBackendFail()
			Map filled = ContactUsForm.fillValid(true, ContactUsForm.DEFAULT_ENQUIRY)
			if (!ContactUsForm.flag(filled, 'ok')) return 'Could not fill the form: ' + filled
			Map cap = ContactUsForm.submitAndWait()
			if (!ContactUsForm.onSorryPage() && ContactUsForm.num(cap, 'status') != 500) {
				ContactUsForm.waitUntil(12000) {
					ContactUsForm.onSorryPage() || ContactUsForm.num(ContactUsForm.lastCapture(), 'status') == 500
				}
			}
			Map val = ContactUsForm.validationState()
			boolean sorry = ContactUsForm.onSorryPage() || ContactUsForm.flag(cap, 'sorry') || ContactUsForm.flag(val, 'sorry')
			r.actual = 'url=' + ContactUsForm.currentUrl() + ' sorry=' + sorry + ' val=' + val + ' status=' + cap.status
			if (ContactUsForm.flag(cap, 'thankYou') || ContactUsForm.onThankYouPage()) {
				return 'Forced 500 still reached thank-you; simulation did not take effect'
			}
			if (sorry || ContactUsForm.num(cap, 'status') == 500 || ContactUsForm.num(ContactUsForm.lastCapture(), 'status') == 500) {
				return true
			}
			return 'No sorry path and no 500 after this-tab simulation: ' + val + ' url=' + ContactUsForm.currentUrl()
		}
	}

	private static void assertEmail(Map r, File ev) {
		happySubmit(r, ev, true, ContactUsForm.DEFAULT_ENQUIRY, false)
		checkStep(r, ev, '03-mailbox') {
			r.actual = (r.actual ?: '') + ' mailbox=not-configured (sheet: Partially)'
			KeywordUtil.logInfo('CU-011: no mailbox reader; submit result is the automatable proof')
			return true
		}
	}

	private static void assertDuplicate(Map r, File ev) {
		ContactUsForm.startOn()
		if (!aliveOrFail(r, ev, 'Contact Us page did not load')) return
		checkStep(r, ev, '01-fill') {
			Map filled = ContactUsForm.fillValid(true, ContactUsForm.DEFAULT_ENQUIRY)
			r.actual = filled.toString()
			if (!ContactUsForm.flag(filled, 'ok')) return 'Could not fill the Customer Enquiry form: ' + filled
			return true
		}
		checkStep(r, ev, '02-double-submit') {
			Map cap = ContactUsForm.doubleSubmit()
			r.actual = (r.actual ?: '') + ' double=' + cap
			if (!ContactUsForm.flag(cap, 'clickedFirst')) return 'Submit was not clicked'
			if (ContactUsForm.num(cap, 'requests') >= 2) {
				return 'Two submit requests were sent; duplicate was not prevented: ' + cap.requests
			}
			if (ContactUsForm.num(cap, 'requests') < 1 && !ContactUsForm.flag(cap, 'thankYou')) {
				return 'Neither a single request nor thank-you was observed'
			}
			return true
		}
	}

	private static boolean happySubmit(Map r, File ev, boolean ownerYes, String enquiry, boolean withUtm) {
		ContactUsForm.startOn(withUtm)
		if (!aliveOrFail(r, ev, 'Contact Us page did not load')) return false
		boolean filledOk = checkStep(r, ev, '01-fill') {
			Map filled = ContactUsForm.fillValid(ownerYes, enquiry)
			r.actual = filled.toString()
			if (!ContactUsForm.flag(filled, 'ok')) return 'Could not fill the Customer Enquiry form: ' + filled
			return true
		}
		if (!filledOk) return false
		return checkStep(r, ev, '02-submit') {
			Map cap = ContactUsForm.submitAndWait()
			r.actual = (r.actual ?: '') + ' submit=' + [status: cap.status, thankYou: cap.thankYou, url: cap.pageUrl, validation: cap.validation, debug: cap.debug]
			if (!ContactUsForm.flag(cap, 'clicked') && !(cap.debug instanceof Map && ((Map) cap.debug).btn)) {
				return 'Submit was not clicked: ' + cap.debug
			}
			boolean landedThankYou = ContactUsForm.flag(cap, 'thankYou') || ContactUsForm.onThankYouPage()
			if (landedThankYou) return true
			if (!ContactUsForm.flag(cap, 'httpOk')) {
				return 'Submit HTTP status was not 200: status=' + cap.status + ' url=' + cap.pageUrl + ' val=' + cap.validation + ' debug=' + cap.debug
			}
			return 'No thank-you after submit: ' + cap.pageUrl
		}
	}

	private static boolean aliveOrFail(Map r, File ev, String msg) {
		if (ContactUsForm.pageAlive() && ContactUsForm.hasForm()) return true
		checkStep(r, ev, '00-page-load') { return msg }
		return false
	}

	private static void run(String id, Closure body) {
		Map meta = META[id]
		Map result = [
			testId       : id,
			name         : meta.name,
			section      : meta.section,
			preconditions: meta.pre,
			expected     : meta.expected,
			actual       : '',
			status       : 'FAIL',
			detail       : 'Case exited without proving the expected result',
			steps        : [],
		]
		File ev = new File(PacsRegressionReportBuilder.evidenceDir(meta.section.toString()), id)
		ev.mkdirs()
		ContactUsForm.resetStartState()
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		try {
			body.call(result, ev)
		} catch (Throwable e) {
			checkStep(result, ev, '99-uncaught') { return (e.message ?: e.toString()) }
		} finally {
			ContactUsForm.pause(0.8)
			try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		}
		finishCase(result)
		PacsRegressionReportBuilder.record(result)
		if (result.status == 'FAIL') {
			KeywordUtil.markFailed(id + ' FAIL: ' + (result.detail ?: result.expected))
		} else {
			KeywordUtil.logInfo(id + ' PASS: ' + (result.detail ?: result.expected))
		}
	}

	private static boolean checkStep(Map r, File ev, String name, Closure verify) {
		r.currentStep = name
		KeywordUtil.logInfo('STEP ' + name)
		boolean ok = false
		String detail = name
		try {
			Object out = verify.call()
			if (out == null || out == Boolean.TRUE) {
				ok = true
				detail = name + ' passed'
			} else if (out == Boolean.FALSE) {
				ok = false
				detail = name + ' failed'
			} else {
				String s = out.toString().trim()
				if (!s || s.equalsIgnoreCase('true')) {
					ok = true
					detail = name + ' passed'
				} else {
					ok = false
					detail = s
				}
			}
		} catch (Throwable e) {
			ok = false
			detail = e.message ?: e.toString()
		}
		recordStep(r, ev, name, ok ? 'PASS' : 'FAIL', detail)
		ContactUsForm.markStepFinished()
		if (ok) {
			KeywordUtil.logInfo(name + ' PASS')
		} else {
			r.status = 'FAIL'
			String line = name + ': ' + detail
			String prev = (r.detail ?: '').toString()
			r.detail = (!prev || prev.startsWith('Case exited')) ? line : (prev + ' | ' + line)
			if (!r.actual) r.actual = detail
			KeywordUtil.logInfo(name + ' FAIL: ' + detail)
		}
		return ok
	}

	private static void recordStep(Map r, File ev, String name, String status, String detail) {
		((List) r.steps) << ContactUsEvidence.captureAem(ev, name) + [
			name  : name,
			status: status,
			detail: detail,
		]
	}

	private static void finishCase(Map r) {
		List steps = (List) (r.steps ?: [])
		List fails = steps.findAll { ((Map) it).status == 'FAIL' }
		if (fails) {
			r.status = 'FAIL'
			if (!r.detail || r.detail.toString().startsWith('Case exited')) {
				r.detail = fails.collect { Map s -> (s.name ?: '') + ': ' + (s.detail ?: '') }.join(' | ')
			}
			return
		}
		if (steps && steps.every { ((Map) it).status == 'PASS' }) {
			r.status = 'PASS'
			if (!r.detail || r.detail.toString().startsWith('Case exited')) {
				r.detail = steps.collect { Map s -> s.detail ?: s.name }.join('; ')
			}
		}
	}
}

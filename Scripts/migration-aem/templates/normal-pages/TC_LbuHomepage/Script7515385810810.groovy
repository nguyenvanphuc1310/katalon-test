import static com.kms.katalon.core.testcase.TestCaseFactory.findTestCase

import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/*
 * Parent test for the "LBU Homepage" page type (normal-pages).
 *
 * The live Sitecore page is the reference: mode=baseline captures it, mode=capture captures the
 * AEM page, mode=compare does both and diffs, mode=recompare re-diffs the snapshots on disk.
 *
 * The suite binds Data Files/url-mapping/normal-pages/lbu-homepage.csv; the pagetype guard below is a
 * safety net for the case where a wider data file is ever bound to this test case.
 */

if (pagetype != 'lbu-homepage') {
	KeywordUtil.logInfo("SKIP ${pageurl} — page type '${pagetype}' is not covered by this test case")
	return
}
KeywordUtil.logInfo("=== ${mode.toUpperCase()} | LBU Homepage | ${pageurl} ===")

WebUI.callTestCase(findTestCase('migration-aem/checks/TC_Check_Content_Text'),
	[sitecoreurl: sitecoreurl, pageurl: pageurl, mode: mode], FailureHandling.CONTINUE_ON_FAILURE)

// recompare needs no browser at all; closing one that was never opened is harmless
WebUI.closeBrowser()

import com.kms.katalon.core.annotation.SetUp
import com.kms.katalon.core.annotation.TearDown
import com.kms.katalon.core.annotation.SetupTestCase
import com.kms.katalon.core.annotation.TearDownTestCase

import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import migration.PacsRegressionReportBuilder

@SetUp(skipped = false)
def setUp() {
	// Publish UAT only. Clears the Contact Us tab. Leaves Discovery and Custom pages in place.
	PacsRegressionReportBuilder.startContactUsRun()
}

@TearDown(skipped = false)
def tearDown() {
	try { WebUI.closeBrowser() } catch (Throwable ignore) { }
}

@SetupTestCase(skipped = true)
def setupTestCase() {
}

@TearDownTestCase(skipped = true)
def tearDownTestCase() {
}

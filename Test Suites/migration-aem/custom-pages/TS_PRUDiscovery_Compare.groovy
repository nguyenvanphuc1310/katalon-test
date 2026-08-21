import com.kms.katalon.core.annotation.SetUp
import com.kms.katalon.core.annotation.TearDown
import com.kms.katalon.core.annotation.SetupTestCase
import com.kms.katalon.core.annotation.TearDownTestCase

import migration.PRUDiscoveryForm
import migration.PRUDiscoveryReportBuilder

@SetUp(skipped = false)
def setUp() {
	PRUDiscoveryReportBuilder.startRun()
}

@TearDown(skipped = false)
def tearDown() {
	PRUDiscoveryForm.endSession()
}

@SetupTestCase(skipped = true)
def setupTestCase() {
}

@TearDownTestCase(skipped = true)
def tearDownTestCase() {
}

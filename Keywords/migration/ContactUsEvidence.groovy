package migration

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot

/**
 * Full-page AEM shots for Contact Us. Scroll to the Customer Enquiry form first.
 */
public class ContactUsEvidence {

	static Map captureAem(File evidenceDir, String stem) {
		evidenceDir.mkdirs()
		File dest = new File(evidenceDir, stem + '-aem.png')
		String url = ContactUsForm.currentUrl()
		try {
			ContactUsForm.pause(0.6)
			ContactUsForm.scrollToForm()
			ContactUsForm.pause(0.4)
			fullPagePng(dest)
			if (!dest.exists() || dest.length() < 8000) {
				ContactUsForm.pause(1.0)
				fullPagePng(dest)
			}
			KeywordUtil.logInfo('Contact Us AEM shot: ' + dest.name + ' bytes=' + dest.length() + ' <- ' + url)
		} catch (Exception e) {
			KeywordUtil.markWarning('AEM shot failed: ' + (e.message ?: e))
		}
		return [
			aemUrl : url,
			aemShot: rel(dest.exists() && dest.length() > 400 ? dest.absolutePath : ''),
		]
	}

	private static void fullPagePng(File dest) {
		dest.parentFile?.mkdirs()
		try {
			WebUI.takeFullPageScreenshot(dest.absolutePath)
			if (dest.exists() && dest.length() > 8000) return
		} catch (Throwable ignore) { }
		try {
			File tmp = ((TakesScreenshot) DriverFactory.getWebDriver()).getScreenshotAs(OutputType.FILE)
			java.nio.file.Files.copy(tmp.toPath(), dest.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		} catch (Exception e) {
			KeywordUtil.logInfo('Viewport screenshot also failed: ' + (e.message ?: e))
		}
	}

	private static String rel(String abs) {
		if (!abs) return ''
		String root = RunConfiguration.getProjectDir().replace('\\', '/')
		return abs.replace('\\', '/').replace(root + '/', '')
	}
}

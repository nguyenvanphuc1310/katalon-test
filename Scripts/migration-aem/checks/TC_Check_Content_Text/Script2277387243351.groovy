import migration.checks.ContentTextCheck
import migration.checks.StructureCheck

// Sitecore is the reference; whatever it has, AEM must have. Extra content on AEM is not an error.
ContentTextCheck.run(sitecoreurl, pageurl, mode)

// Second check: tag counts + images. Live-vs-live, so only on a compare run that has both URLs.
if (mode in ['compare', 'check']) {
	StructureCheck.run(sitecoreurl, pageurl)
}

import migration.checks.ContentTextCheck

// Sitecore is the reference; whatever it has, AEM must have. Extra content on AEM is not an error.
ContentTextCheck.run(sitecoreurl, pageurl, mode)

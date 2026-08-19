import migration.ReportBuilder

// Reports/parity-report/ — reads the results the checks wrote to disk
ReportBuilder.build()

// Reports/publish/ — the same site with no local paths, ready to copy to a report server
ReportBuilder.buildPublishBundle()

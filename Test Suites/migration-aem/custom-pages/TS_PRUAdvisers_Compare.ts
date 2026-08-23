<?xml version="1.0" encoding="UTF-8"?>
<TestSuiteEntity>
   <description>PRUAdvisers API-001 to API-004 on official AEM UAT Find a PRUAdviser only. Search from UI. Default FAIL. Then builds the shared PACS HTML. Does not re-run Discovery, Custom pages, Contact Us, or Custom form preflight. Needs VPN.</description>
   <name>TS_PRUAdvisers_Compare</name>
   <tag>custom,pruadvisers,aem</tag>
   <isRerun>false</isRerun>
   <mailRecipient></mailRecipient>
   <maxConcurrentInstances>1</maxConcurrentInstances>
   <numberOfRerun>0</numberOfRerun>
   <orchestration>CLASSIC</orchestration>
   <pageLoadTimeout>30</pageLoadTimeout>
   <pageLoadTimeoutDefault>false</pageLoadTimeoutDefault>
   <rerunFailedTestCasesOnly>false</rerunFailedTestCasesOnly>
   <rerunImmediately>false</rerunImmediately>
   <testSuiteGuid>e55f6607-1899-4399-caab-122334455677</testSuiteGuid>
   <testCaseLink>
      <guid>f66a7718-29aa-44aa-dbbc-233445566788</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/pruadvisers/TC_API_001</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>077b8829-3abb-45bb-eccd-344556677899</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/pruadvisers/TC_API_002</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>188c993a-4bcc-46cc-fdde-455667788900</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/pruadvisers/TC_API_003</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>299daa4b-5cdd-47dd-0eef-566778899011</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/pruadvisers/TC_API_004</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>3aaebb5c-6dee-48ee-1ff0-677889900122</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/checks/TC_Build_PRUDiscovery_Report</testCaseId>
   </testCaseLink>
</TestSuiteEntity>

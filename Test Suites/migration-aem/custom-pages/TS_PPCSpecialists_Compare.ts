<?xml version="1.0" encoding="UTF-8"?>
<TestSuiteEntity>
   <description>PPC Specialists API-011 and API-012 on official AEM UAT /en/ppc-specialists/ only. Search from UI. Default FAIL. Then builds the shared PACS HTML. Does not re-run Discovery, Custom pages PPC Extended Panel, Contact Us, preflight, PRUAdvisers, or ILP Funds. Needs VPN.</description>
   <name>TS_PPCSpecialists_Compare</name>
   <tag>custom,ppcspecialists,aem</tag>
   <isRerun>false</isRerun>
   <mailRecipient></mailRecipient>
   <maxConcurrentInstances>1</maxConcurrentInstances>
   <numberOfRerun>0</numberOfRerun>
   <orchestration>CLASSIC</orchestration>
   <pageLoadTimeout>30</pageLoadTimeout>
   <pageLoadTimeoutDefault>false</pageLoadTimeoutDefault>
   <rerunFailedTestCasesOnly>false</rerunFailedTestCasesOnly>
   <rerunImmediately>false</rerunImmediately>
   <testSuiteGuid>c03d33e4-f556-4177-a889-90011c013003</testSuiteGuid>
   <testCaseLink>
      <guid>d04e44f5-0667-4288-b99a-01122d014004</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/ppc-specialists/TC_API_011</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>e05f5506-1778-4399-caab-12233e015005</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/ppc-specialists/TC_API_012</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>f0606617-2889-44aa-dbbc-23344f016006</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/checks/TC_Build_PRUDiscovery_Report</testCaseId>
   </testCaseLink>
</TestSuiteEntity>

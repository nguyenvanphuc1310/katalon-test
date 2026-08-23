<?xml version="1.0" encoding="UTF-8"?>
<TestSuiteEntity>
   <description>Custom form preflight FORM-PF-001 to 003 on official AEM UAT publish only. Inspect pre-submit contract. Do not submit. No lead or email is sent. Default FAIL. Then builds the shared PACS HTML. Does not re-run Discovery, Custom pages, or Contact Us. Needs VPN.</description>
   <name>TS_CustomForm_Preflight</name>
   <tag>custom,formpreflight,aem</tag>
   <isRerun>false</isRerun>
   <mailRecipient></mailRecipient>
   <maxConcurrentInstances>1</maxConcurrentInstances>
   <numberOfRerun>0</numberOfRerun>
   <orchestration>CLASSIC</orchestration>
   <pageLoadTimeout>30</pageLoadTimeout>
   <pageLoadTimeoutDefault>false</pageLoadTimeoutDefault>
   <rerunFailedTestCasesOnly>false</rerunFailedTestCasesOnly>
   <rerunImmediately>false</rerunImmediately>
   <testSuiteGuid>d4f6b8c0-5e70-419d-bf30-4b5c6d7e8f90</testSuiteGuid>
   <testCaseLink>
      <guid>e5a7c9d1-6f81-42ae-c041-5c6d7e8f9012</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/custom-form-preflight/TC_FORM_PF_001</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>f6b8d0e2-7092-43bf-d152-6d7e8f901223</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/custom-form-preflight/TC_FORM_PF_002</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>07c9e1f3-81a3-44c0-e263-7e8f90122334</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/templates/custom-pages/custom-form-preflight/TC_FORM_PF_003</testCaseId>
   </testCaseLink>
   <testCaseLink>
      <guid>18dae204-92b4-45d1-f374-8f9012234455</guid>
      <isReuseDriver>false</isReuseDriver>
      <isRun>true</isRun>
      <testCaseId>Test Cases/migration-aem/checks/TC_Build_PRUDiscovery_Report</testCaseId>
   </testCaseLink>
</TestSuiteEntity>

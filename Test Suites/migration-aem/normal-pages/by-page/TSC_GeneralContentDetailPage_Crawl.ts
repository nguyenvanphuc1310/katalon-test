<?xml version="1.0" encoding="UTF-8"?>
<TestSuiteCollectionEntity>
   <description>Crawl both sides of the General Content Detail pages at the same time: the live Sitecore baseline and the AEM capture run side by side in two browsers, so the wall-clock cost is the slower of the two rather than their sum. The two sides write different files (&lt;slug>.sitecore.json vs &lt;slug>.aem.json) and share no state, so nothing is lost by overlapping them. Needs VPN for the whole run, because the capture half does. Judge afterwards with TS_GeneralContentDetailPage_Recompare — never add a compare suite here, TC_Build_Parity_Report is a single writer over the whole report.</description>
   <name>TSC_GeneralContentDetailPage_Crawl</name>
   <tag>normal,general-content-detail-page,parallel,collection,crawl</tag>
   <browserType></browserType>
   <delayBetweenInstances>0</delayBetweenInstances>
   <executionMode>PARALLEL</executionMode>
   <maxConcurrentInstances>2</maxConcurrentInstances>
   <profileName>default</profileName>
   <testSuiteRunConfigurations>
      <TestSuiteRunConfiguration>
         <configuration>
            <groupName>Web Desktop</groupName>
            <profileName>default</profileName>
            <requireConfigurationData>false</requireConfigurationData>
            <runConfigurationId>Chrome</runConfigurationId>
         </configuration>
         <runEnabled>true</runEnabled>
         <testSuiteEntity>Test Suites/migration-aem/normal-pages/by-page/TS_GeneralContentDetailPage_Baseline</testSuiteEntity>
      </TestSuiteRunConfiguration>
      <TestSuiteRunConfiguration>
         <configuration>
            <groupName>Web Desktop</groupName>
            <profileName>default</profileName>
            <requireConfigurationData>false</requireConfigurationData>
            <runConfigurationId>Chrome</runConfigurationId>
         </configuration>
         <runEnabled>true</runEnabled>
         <testSuiteEntity>Test Suites/migration-aem/normal-pages/by-page/TS_GeneralContentDetailPage_Capture</testSuiteEntity>
      </TestSuiteRunConfiguration>
   </testSuiteRunConfigurations>
</TestSuiteCollectionEntity>

# Load Testing CI/CD Jenkins Plugin by LoadFocus
<p align="center">
<a href="https://loadfocus.com">
<img src="https://d2woeiihr4s5r6.cloudfront.net/loadfocus.png" align="right"
     alt="cloud testing tool" width="220"></a>
</p>

[Load Testing](https://loadfocus.com/load-testing) CI/CD plugin is a Jenkins plugin for running load tests continuously for Websites and APIs
 provided by <a href="https://loadfocus.com">LoadFocus</a>. 
 
Helps you run load tests as a Post-build Action marking the Build as Passed, Unstable or Failed based on:

* **error percentage** and **response times**.
* all URLs from the test are considered when marking the status of the build.

<p align="center">
<a href="https://loadfocus.com">
<img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/load-testing-ci-cd-plugin-configuration-loadfocus.jpeg"
  alt="Load Testing CI/CD plugin configuration Jenkins"
 height="389"></a>
</p>

With **Load Testing CI/CD plugin** you can run load test with thousands of parallel users periodically.

## How It Works

### Installation Steps
1. Create your load testing account on [LoadFocus](https://loadfocus.com)
2. Copy your **LoadFocus.com API key** from https://loadfocus.com/account
3. Go to Jenkins Dashboard and click go to **Manage Jenkins > Manage Plugins > Available**
4. Locate and install **LoadFocus Load Test plugin**
5. Go to **Manage Jenkins > Manage Credentials** and add your **LoadFocus.com API key** to the stored credentials
<p align="center">
<img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/load-testing-ci-cd-plugin-add-credentials-loadfocus.png"
  alt="Load Testing Add Credentials API key"
 height="189">
</p>
6. Click Test LoadFocus API key button to make sure the API key is working properly.
<p align="center">
<img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/load-testing-ci-cd-plugin-API-key-loadfocus.png"
  alt="Load Testing API key Test"
 height="189"></p>

### Usage
How to use LoadFocus Load Testing Plugin for Post-build load tests:
* Note: All Completed load tests from your [LoadFocus](https://loadfocus.com) account will be available in the plugin.

1. Create a New Job or Configure an exiting one. 
2. In the Post-build Section, look for the **Load Testing by LoadFocus.com** option and select the checkbox. See the screenshot below:
<p align="center">
<img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/load-testing-ci-cd-plugin-add-load-testing-test-loadfocus.png"
  alt="Load Testing Add Post Build Action"
 height="289"></p>
3. Choose the load test, and enter both the Error % and Response Time thresholds. Then click Save.
<p align="center"><img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/load-testing-ci-cd-plugin-configuration-loadfocus.jpeg"
  alt="Load Testing CI/CD Plugin Configuration LoadFocus"
 height="289"></p>
4. Run the Job and View Load Test Results in the job log
<p align="center"><img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/load-testing-ci-cd-plugin-console-log-success-loadfocus.jpeg"
  alt="Load Testing CI/CD Plugin Job Log Results"
 height="289"></p>
    * View the Console output and monitor the progress of your running load tests during job's Post build actions.
    * View the complete load test report of the LoadFocus.com when the job has finished.
    ```
   loadfocus.com: Test Started: Jan_19_2021_11_35_AM
   loadfocus.com: Test Config: Build UNSTABLE if errors percentage greater than or equal to 3%
   loadfocus.com: Test Config: Build FAILURE if errors percentage greater than or equal to 5%
   loadfocus.com: Test Config: Build UNSTABLE if response time greater than or equal to 500ms
   loadfocus.com: Test Config: Build FAILURE if response time greater than or equal to 1000ms
   loadfocus.com: Test Starting: waiting for test to start 0 sec
   loadfocus.com: Test Starting: waiting for test to start 5 sec
   loadfocus.com: Test Starting: waiting for test to start 10 sec
   loadfocus.com: Test Starting: waiting for test to start 15 sec
   loadfocus.com: Test Starting: waiting for test to start 20 sec
   loadfocus.com: Test Starting: waiting for test to start 25 sec
   loadfocus.com: Test Starting: waiting for test to start 30 sec
   loadfocus.com: Test Starting: waiting for test to start 35 sec
   loadfocus.com: Test Running: waiting for test results 40 sec
   loadfocus.com: Test Running: waiting for test results 45 sec
   loadfocus.com: Test Running: waiting for test results 50 sec
   loadfocus.com: Test Running: waiting for test results 55 sec
   loadfocus.com: Test Results: response time 59.667 ms, error percentage 0.0%, for https://example.com/. 
   ```
5. View the load test report
<p align="center"><img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/whitelabel-reports-test-presets-loadfocus.jpeg"
  alt="Load Testing CI/CD Plugin Whitelabel Results"
 height="389"></p>
6. Print the load test report to a PDF file
<p align="center">
<img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/whitelabel-reports-test-print.jpeg"
  alt="Load Testing CI/CD Plugin PDF report"
 height="389"></p>
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
3. Go to **Manage Jenkins > Plugins > Available plugins**
4. Search for and install **Load Testing CI/CD Plugin by LoadFocus**
5. Go to **Manage Jenkins > Credentials** and add a credential of kind **LoadFocus.com API key**
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
3. Choose the load test and how the build should be judged (any combination; the worst result wins):
   * **Error percentage** and **average response time** thresholds, checked per request. Leave a field empty to skip it.
   * **Use LoadFocus verdict**: fail the build when the run misses the pass/fail thresholds configured for the test on loadfocus.com (P95/P99, error rate, throughput, Core Web Vitals budgets).

   Then click Save.
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
    loadfocus.com: Test: checkout
    loadfocus.com: Config: build UNSTABLE if error percentage is greater than 3%
    loadfocus.com: Config: build FAILURE if the LoadFocus verdict (thresholds set on loadfocus.com) fails
    loadfocus.com: Run #42 started: https://loadfocus.com/tests?testrunname=checkout&testrunid=42
    loadfocus.com: Run state: initializing (0s)
    loadfocus.com: Run state: running (35s)
    loadfocus.com: Run #42 finished
    loadfocus.com: Result: https://example.com/: average response time 59.7 ms, errors 0.0%
    loadfocus.com: Verdict check PASS: P95 response time 310 ms (target <= 500 ms)
    loadfocus.com: Verdict: PASS
    ```
 
### Pipeline

The step `loadfocusLoadTest` runs a test, gates the build on it and returns the result. It does not need a `node` block.

```groovy
pipeline {
  agent any
  stages {
    stage('Load test') {
      steps {
        script {
          // Thresholds live in the Jenkinsfile: saved to the test on loadfocus.com, then checked after the run
          def lt = loadfocusLoadTest testId: 'checkout', apiKey: 'loadfocus-api-key',
                                     p95Ms: 500, errorRatePct: 1, releaseTag: "${env.GIT_COMMIT?.take(8)}"
          echo "LoadFocus run #${lt.testrunid}: ${lt.verdict}, p95 ${lt.metrics.p95Ms} ms, report ${lt.reportUrl}"
        }
      }
    }
  }
}
```

The step returns a map: `testrunname`, `testrunid`, `result` (`SUCCESS`/`UNSTABLE`), `verdict` (`pass`/`fail`/`none`), `reportUrl` and `metrics` (`p95Ms`, `p99Ms`, `errorRatePct`, `rps`, plus `meanMs` when per-request thresholds are used).

All options:

| Option | Default | Meaning |
|---|---|---|
| `testId` | (required) | Name of the LoadFocus cloud load test |
| `apiKey` | the default key | ID of a **LoadFocus.com API key** credential or of a standard **Secret text** credential holding the key |
| `p95Ms`, `p99Ms`, `errorRatePct`, `minRps` | not set | Pass/fail thresholds for the whole run. When any is set, they replace the test's thresholds on loadfocus.com before the run (unset ones are cleared) and the verdict is checked |
| `useVerdict` | `false` | Check the LoadFocus verdict against the thresholds configured for the test on loadfocus.com. No thresholds enabled marks the build UNSTABLE; a threshold that could not be evaluated fails it |
| `errorUnstableThreshold`, `errorFailedThreshold` | not set | Per-request error percentage (0-100) above which the build is UNSTABLE / FAILURE |
| `responseTimeUnstableThreshold`, `responseTimeFailedThreshold` | not set | Per-request average response time in ms above which the build is UNSTABLE / FAILURE |
| `tagRun` | `true` | Label the LoadFocus run with this build (shown on its results and trend pages) |
| `releaseTag` | `Jenkins <job> #<build>` | Custom label for the run, e.g. a version or commit (max 64 characters) |
| `timeoutMinutes` | `120` | Fail if the run has not finished in time |
| `shareReport` | `false` | Create a public share link for the run (anyone with the link can view it) |

At least one threshold or `useVerdict` must be set.

How the step ends:
* **FAILURE** (a failed threshold or verdict, a run that fails, a timeout, or a test that cannot be started) fails the step, so later stages do not run. Wrap it in `catchError` to continue anyway.
* **UNSTABLE** marks the build and the stage, and the pipeline continues.
* The step never reports on an older run. If another build starts the same test at the same moment, it fails rather than guess which run is its own.
* Short LoadFocus API outages (for example during a LoadFocus deploy) are retried for about 5 minutes.
* Aborting the Jenkins build does not stop the cloud run; the log prints its link.

### Load Test Results & Reports  
1. View the load test report
<p align="center"><img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/whitelabel-reports-test-presets-loadfocus.jpeg"
  alt="Load Testing CI/CD Plugin Whitelabel Results"
 height="389"></p>
2. Print the load test report to a PDF file
<p align="center">
<img src="https://d2woeiihr4s5r6.cloudfront.net/jenkins/whitelabel-reports-test-print.jpeg"
  alt="Load Testing CI/CD Plugin PDF report"
 height="389"></p>
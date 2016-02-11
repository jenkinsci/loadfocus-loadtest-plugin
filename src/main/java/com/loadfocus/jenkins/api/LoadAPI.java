package com.loadfocus.jenkins.api;

import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoadAPI {
    static final String baseApiUri = "https://loadfocus.com/";

    PrintStream logger = new PrintStream(System.out);
    String apiKey;

    public LoadAPI(String apiKey) {
        logger.println("apiKey: " + apiKey);
        this.apiKey = apiKey;
    }

    public List<Map<String, String>> getTestList() {
        JSONArray list = getTests();
        if (list == null) {
            return null;
        }
        List<Map<String, String>> tests = new ArrayList<Map<String, String>>();

        for (Object test : list) {
            JSONObject t = (JSONObject) test;
            String testrunname = t.getString("testrunname");
            String testrunid = t.getString("testrunid");
            Map <String, String> m = new HashMap<String, String>();
            m.put("testrunname", testrunname);
            m.put("testrunid", testrunid);
            tests.add(m);
        }

        return tests;
    }

    protected boolean isEmptyString(String string) {
        return string == null || string.trim().isEmpty();
    }

    public boolean isValidApiKey() {
        if (isEmptyString(apiKey)) {
            logger.println("getTestApi apiKey is empty");
            return false;
        }
        boolean isValid = validateAPIKey("api/v1/key/validate");
        if (!isValid){
            logger.println("invalid ApiKey");
            return false;
        }
        return isValid;
    }

    public boolean validateAPIKey(String path){
        Result result = doGetRequest(path);
        logger.println("Result " + result.code + "\n" +
                (result.body.length() > 1000 ? result.body.substring(0, 1000) : result.body));
        if (result.isFail()) {
            return false;
        }
        return true;
    }

    public JSONArray getTests() {
        logger.println("get api/v1/loadtests");
        return getListData("api/v1/loadtests");
    }

    private JSONArray getListData(String path) {
        Result result = doGetRequest(path);
        logger.println("Result " + result.code + "\n" +
            (result.body.length() > 1000 ? result.body.substring(0, 1000) : result.body));
        if (result.isFail()) {
            return null;
        }
        try {
            JSON list = JSONSerializer.toJSON(result.body);
            if (list.isArray()) {
                return (JSONArray) list;
            } else {
                return null;
            }
        } catch (RuntimeException ex) {
            logger.println("Got Exception: " + ex);
            return null;
        }
    }

    public JSONObject getRemainLimits() {
        logger.println("in #getTest");
        Result result = doGetRequest("api/v1/account/user/remainlimit");
        logger.println("Result :::" + result.code + "\n" + result.body);
        if (result.isFail()) {
            return null;
        }
        JSONObject json = (JSONObject) JSONSerializer.toJSON(result.body);
        return  json;
    }

    public JSONObject getState(String testrunname, String testrunid) {
        logger.println("in #getTest");
        Result result = doGetRequest("api/v1/loadtests/state?testrunname=" + testrunname + "&testrunid=" + testrunid);
        logger.println("Result :::" + result.code + "\n" + result.body);
        if (result.isFail()) {
            return null;
        }
        JSONObject json = (JSONObject) JSONSerializer.toJSON(result.body);
        return  json;
    }

    public List<Map<String, String>> getTestConfig(String testrunname, String testrunid) {
        logger.println("in #getConfig");
        Result result = doGetRequest("api/v1/loadtests/result/config?testrunname=" + testrunname + "&testrunid=" + testrunid);
        logger.println("Result :::" + result.code + "\n" + result.body);
        if (result.isFail()) {
            return null;
        }

        JSONArray configList = (JSONArray) JSONSerializer.toJSON(result.body);
        if (configList == null) {
            return null;
        }
        List<Map<String, String>> configs = new ArrayList<Map<String, String>>();

        for (Object config : configList) {
            JSONObject t = (JSONObject) config;
            String location = t.getString("location");
            String testmachinedns = t.getString("testmachinedns");
            String httprequest = t.getString("httprequest");
            Map <String, String> m = new HashMap<String, String>();
            m.put("testrunname", testrunname);
            m.put("location", location);
            m.put("testmachinedns", testmachinedns);
            m.put("httprequest", httprequest);
            configs.add(m);
        }

        return configs;
    }

    public List<Map<String, String>> getTestSummaryResultAll(String testrunname, String testrunid, String location, String url, String testmachinedns) {
        logger.println("in #getSummaryResult");
        Result result = doGetRequest("api/v1/loadtests/result/summaryResult?testrunname=" + testrunname + "&testrunid="  + testrunid + "&location=" + location + "&httprequest=" + url + "&testmachinedns=" + testmachinedns);
        logger.println("Result :::" + result.code + "\n" + result.body);
        if (result.isFail()) {
            return null;
        }

        JSONArray resultList = (JSONArray) JSONSerializer.toJSON(result.body);
        if (resultList == null) {
            return null;
        }
        List<Map<String, String>> results = new ArrayList<Map<String, String>>();

        for (Object config : resultList) {
            JSONObject t = (JSONObject) config;
            String time = t.getString("time");
            String errPercentTotal = t.getString("errPercentTotal");
            String errTotal = t.getString("errTotal");
            String hitsTotal = t.getString("hitsTotal");
            String httprequest = t.getString("httprequest");
            Map <String, String> m = new HashMap<String, String>();
            m.put("time", time);
            m.put("errPercentTotal", errPercentTotal);
            m.put("errTotal", errTotal);
            m.put("hitsTotal", hitsTotal);
            m.put("httprequest", httprequest);
            results.add(m);
        }

        return results;
    }

    private Result doGetRequest(String path) {
        return doRequest(new HttpGet(), path);
    }

    public Map<String, String> runTest(String testId) {
        logger.println("in #getTests");

        String path  = "api/v1/loadtests/newtest/execute?testrunname=" + testId;

        Result result = doPostRequest(path);
        logger.println("Result :::" + result.code + "\n" + result.body);
        if (result.isFail()) {
            return null;
        }

        JSONObject body = (JSONObject) JSONSerializer.toJSON(result.body);

        Map<String, String> resultDetails = new HashMap<String, String>();
        resultDetails.put("success", body.get("success").toString());

        if(result.body != null && body.get("success").equals(true)){
            resultDetails.put("testrunname", body.get("testrunname").toString());
            resultDetails.put("testrunid", body.get("testrunid").toString());

            return resultDetails;
        }else{
            resultDetails.put("testrunname", "");
            resultDetails.put("testrunid", "");
            return resultDetails;
        }

    }

    private Result doPostRequest(String path) {
        return doRequest(new HttpPost(), path);
    }

    private Result doRequest(HttpRequestBase request, String path) {
        stuffHttpRequest(request, path);
        DefaultHttpClient client = new DefaultHttpClient();
        HttpResponse response;
        try {
            response = client.execute(request);
        } catch (IOException ex) {
            logger.format("Error during remote call to API. Exception received: %s", ex);
            return new Result("Network error during remote call to API");
        }
        return new Result(response);
    }

    private void stuffHttpRequest(HttpRequestBase request, String path) {
        URI fullUri = null;
        try {
            fullUri = new URI(baseApiUri + path);
        } catch (java.net.URISyntaxException ex) {
            throw new RuntimeException("Incorrect URI format: %s", ex);
        }
        request.setURI(fullUri);
        request.addHeader("Content-Type", "application/json");
        request.addHeader("loadfocus-auth", apiKey);
    }

    static class Result {
        public int code;
        public String errorMessage;
        public String body;

        static final String badResponseError = "Bad response from API.";
        static final String formatError = "Invalid error format in response.";

        public Result(String error) {
            code = -1;
            errorMessage = error;
        }

        public Result(HttpResponse response) {
            code = response.getStatusLine().getStatusCode();
            try {
                body = EntityUtils.toString(response.getEntity());
            } catch (IOException ex) {
                code = -1;
                errorMessage = badResponseError;
            }

            if (code != 200) {
                errorMessage = "An Error Occurred!";
            }
        }

        public boolean isOk() {
            return 200 == code;
        }

        public boolean isFail() {
            return !isOk();
        }
    }
}

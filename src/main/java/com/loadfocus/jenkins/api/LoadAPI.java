package com.loadfocus.jenkins.api;

import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
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

    PrintStream logger = System.out;
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
        List<Map<String, String>> tests = new ArrayList<>();

        for (Object test : list) {
            JSONObject t = (JSONObject) test;
            String testrunname = t.getString("testrunname");
            String testrunid = t.getString("testrunid");
            Map <String, String> m = new HashMap<>();
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
        String result = doGetRequest(path);
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return false;
        }

        return true;
    }

    public JSONArray getTests() {
        logger.println("get api/v1/loadtests");
        return getListData("api/v1/loadtests");
    }

    private JSONArray getListData(String path) {
        String result = doGetRequest(path);
        logger.println("Result " + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        try {
            JSON list = JSONSerializer.toJSON(result);
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
        logger.println("in #getRemainLimits");
        String result = doGetRequest("api/v1/account/user/remainlimit");
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        JSONObject json = (JSONObject) JSONSerializer.toJSON(result);
        return  json;
    }

    public JSONObject getState(String testrunname, String testrunid) {
        logger.println("in #getState");
        String result = doGetRequest("api/v1/loadtests/state?testrunname=" + testrunname + "&testrunid=" + testrunid);
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        JSONObject json = (JSONObject) JSONSerializer.toJSON(result);
        return  json;
    }

    public List<Map<String, String>> getTestConfig(String testrunname, String testrunid) {
        logger.println("in #getTestConfig");
        String result = doGetRequest("api/v1/loadtests/result/config?testrunname=" + testrunname + "&testrunid=" + testrunid);
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        JSONArray configList = (JSONArray) JSONSerializer.toJSON(result);
        if (configList == null) {
            return null;
        }
        List<Map<String, String>> configs = new ArrayList<>();

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
        String result = doGetRequest("api/v1/loadtests/result/summaryResult?testrunname=" + testrunname + "&testrunid="  + testrunid + "&location=" + location + "&httprequest=" + url + "&testmachinedns=" + testmachinedns);
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        JSONArray resultList = (JSONArray) JSONSerializer.toJSON(result);
        if (resultList == null) {
            return null;
        }
        List<Map<String, String>> results = new ArrayList<>();

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

    public Map<String, String> runTest(String testId) {
        logger.println("in #runTest");

        String path = "api/v1/loadtests/newtest/execute?testrunname=" + testId;

        String result = doPostRequest(path);
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        JSONObject body = (JSONObject) JSONSerializer.toJSON(result);

        Map<String, String> resultDetails = new HashMap<String, String>();
        resultDetails.put("success", body.get("success").toString());

        if(result != null && body.get("success").equals(true)){
            resultDetails.put("testrunname", body.get("testrunname").toString());
            resultDetails.put("testrunid", body.get("testrunid").toString());

            return resultDetails;
        }else{
            resultDetails.put("testrunname", "");
            resultDetails.put("testrunid", "");
            return resultDetails;
        }

    }

    private String doGetRequest(String path) {
        URI fullUri;
        try {
            fullUri = new URI(baseApiUri + path);
        } catch (java.net.URISyntaxException ex) {
            throw new RuntimeException("Incorrect URI format: %s", ex);
        }

        HttpClient client = new HttpClient();

        GetMethod method = new GetMethod(fullUri.toString());
        method.addRequestHeader("Content-Type", "application/json");
        method.addRequestHeader("loadfocus-auth", apiKey);

        try {
            int statusCode = client.executeMethod(method);
            if (statusCode != HttpStatus.SC_OK) {
                logger.format("Method failed: " + method.getStatusLine());
            }

            byte[] responseBody = method.getResponseBody();
//            logger.format(new String(responseBody), "UTF-8");

            return new String(responseBody);

        } catch (HttpException e) {
            logger.format("Fatal protocol violation: " + e.getMessage());
        } catch (IOException e) {
            logger.format("Fatal transport error: " + e.getMessage());
        } finally {
            method.releaseConnection();
        }

        return "NOTRUNNING";
    }

    private String doPostRequest(String path) {
        URI fullUri;
        try {
            fullUri = new URI(baseApiUri + path);
        } catch (java.net.URISyntaxException ex) {
            throw new RuntimeException("Incorrect URI format: %s", ex);
        }

        HttpClient client = new HttpClient();

        PostMethod method = new PostMethod(fullUri.toString());
        method.addRequestHeader("Content-Type", "application/json");
        method.addRequestHeader("loadfocus-auth", apiKey);

        try {
            int statusCode = client.executeMethod(method);
            if (statusCode != HttpStatus.SC_OK) {
                logger.format("Method failed: " + method.getStatusLine());
            }

            byte[] responseBody = method.getResponseBody();
//            logger.format(new String(responseBody), "UTF-8");

            return new String(responseBody);

        } catch (HttpException e) {
            logger.format("Fatal protocol violation: " + e.getMessage());
        } catch (IOException e) {
            logger.format("Fatal transport error: " + e.getMessage());
        } finally {
            method.releaseConnection();
        }

        return "NOTRUNNING";
    }
}

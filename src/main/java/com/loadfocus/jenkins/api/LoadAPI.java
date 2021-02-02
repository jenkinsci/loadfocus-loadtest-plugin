package com.loadfocus.jenkins.api;

import com.google.gson.Gson;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoadAPI {
//    static final String baseApiUri = "https://loadfocus.com/";
    static final String baseApiUri = "http://localhost:8065/";

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
//        logger.println("Result " + (result.length() > 100 ? result.substring(0, 100) : result));
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

    public JSONObject retrieveConfig(String testrunname) {
        logger.println("in #retrieveConfig");
        String result = doGetRequest("api/v1/loadtests/retrieveconfig?testrunname=" + testrunname);
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        return (JSONObject) JSONSerializer.toJSON(result);
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

    public JSONObject runTest(String testId) {
        logger.println("in #runTest");
        logger.println(baseApiUri);

        String path = "api/v1/loadtests/newtest/execute?testrunname=" + testId;

        String result = doPostRequest(path);
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        JSONObject resultBody = (JSONObject) JSONSerializer.toJSON(result);

        return resultBody;
    }

    public JSONArray getLabels(String testrunname, String testrunid, String apikey) throws UnsupportedEncodingException {
        logger.println("in #runTest");
        logger.println(baseApiUri);

        String path = "api/v1/loadtests/labels-noform?apikey=" + apikey;

        JSONObject jsonObject = new JSONObject();
        jsonObject.accumulate("testrunname", testrunname);
        jsonObject.accumulate("testrunid", testrunid);
        String result = doPostRequest(path, jsonObject, "application/x-www-form-urlencoded");
        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        JSONArray resultBody = (JSONArray) JSONSerializer.toJSON(result);

        return resultBody;
    }

    public JSONArray getResultsFinal(String testrunname, String testrunid, JSONObject state, String label, String apikey) throws UnsupportedEncodingException {
        logger.println("in #runTest");
        logger.println(baseApiUri);

        String path = "api/v1/loadtests/aggregate/results-noform?apikey=" + apikey;

        JSONObject jsonObject = new JSONObject();
        jsonObject.accumulate("testrunname", testrunname);
        jsonObject.accumulate("testrunid", testrunid);
        jsonObject.accumulate("teststarttime", state.get("teststarttime").toString());
        jsonObject.accumulate("teststoptime",  state.get("teststoptime").toString());
        jsonObject.accumulate("machinenumber", 1);


        jsonObject.accumulate("filter[]", label);
        jsonObject.accumulate("sortasc[]", "timestamp");
        jsonObject.accumulate("batchsize", 1);
        jsonObject.accumulate("granularity", "none");

        String result = doPostRequest(path, jsonObject, "application/x-www-form-urlencoded");
//        logger.println("Result " + result + "\n" + (result.length() > 100 ? result.substring(0, 100) : result));
        if (result.equalsIgnoreCase("NOTRUNNING")) {
            return null;
        }

        return (JSONArray) JSONSerializer.toJSON(result);
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
        method.addRequestHeader("accept", "application/json");
        method.addRequestHeader("content-type", "application/json");
        method.addRequestHeader("loadfocus-auth", apiKey);

        try {
            int statusCode = client.executeMethod(method);
            if (statusCode != HttpStatus.SC_OK) {
                logger.format("Method failed: " + method.getStatusLine());
            }

            byte[] responseBody = method.getResponseBody();
          
            return new String(responseBody, StandardCharsets.UTF_8);

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

            return new String(responseBody, StandardCharsets.UTF_8);

        } catch (HttpException e) {
            logger.format("Fatal protocol violation: " + e.getMessage());
        } catch (IOException e) {
            logger.format("Fatal transport error: " + e.getMessage());
        } finally {
            method.releaseConnection();
        }

        return "NOTRUNNING";
    }

    private String doPostRequest(String path, JSONObject jsonObject) throws UnsupportedEncodingException {
        URI fullUri;
        try {
            fullUri = new URI(baseApiUri + path);
        } catch (java.net.URISyntaxException ex) {
            throw new RuntimeException("Incorrect URI format: %s", ex);
        }

        CloseableHttpClient httpclient = HttpClients.createDefault();

        String JSON_STRING = jsonObject.toString();

        StringEntity requestEntity = new StringEntity(
                JSON_STRING,
                ContentType.APPLICATION_JSON);

        HttpPost httpPost = new HttpPost(fullUri.toString());
        httpPost.addHeader("Content-Type", "application/json");
        httpPost.addHeader("loadfocus-auth", apiKey);
        httpPost.setEntity(requestEntity);

        try {
            CloseableHttpResponse response = httpclient.execute(httpPost);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity);
            return result;
        } catch (HttpException e) {
            logger.format("Fatal protocol violation: " + e.getMessage());
            return null;
        } catch (IOException e) {
            logger.format("Fatal transport error: " + e.getMessage());
            return null;
        }
    }

    private String doPostRequest(String path, JSONObject jsonObject, String contentType) throws UnsupportedEncodingException {
        URI fullUri;
        try {
            fullUri = new URI(baseApiUri + path);
        } catch (java.net.URISyntaxException ex) {
            throw new RuntimeException("Incorrect URI format: %s", ex);
        }

        CloseableHttpClient httpclient = HttpClients.createDefault();

        HashMap<String, Object> paramsFromJSON = new Gson().fromJson(jsonObject.toString(), HashMap.class);

        List<NameValuePair> formparams = new ArrayList<>();
        for (Map.Entry<String, Object> entry : paramsFromJSON.entrySet()) {
            formparams.add(new BasicNameValuePair(entry.getKey(), entry.getValue().toString()));
        }

        UrlEncodedFormEntity requestEntity = new UrlEncodedFormEntity(formparams, Consts.UTF_8);

        HttpPost httpPost = new HttpPost(fullUri.toString());
        httpPost.addHeader("Content-Type", contentType);
        httpPost.addHeader("loadfocus-auth", apiKey);
        httpPost.setEntity(requestEntity);

        try {
            CloseableHttpResponse response = httpclient.execute(httpPost);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity);
            return result;
        } catch (HttpException e) {
            logger.format("Fatal protocol violation: " + e.getMessage());
            return null;
        } catch (IOException e) {
            logger.format("Fatal transport error: " + e.getMessage());
            return null;
        }
    }

    private String getDataString(HashMap<String, String> params) throws UnsupportedEncodingException{
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for(Map.Entry<String, String> entry : params.entrySet()){
            if (first)
                first = false;
            else
                result.append("&");
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            result.append("=");
            result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return result.toString();
    }
}

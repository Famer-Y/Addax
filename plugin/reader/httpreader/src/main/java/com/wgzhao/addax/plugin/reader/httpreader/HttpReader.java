/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.plugin.reader.httpreader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;
import com.alibaba.fastjson2.JSONWriter;
import com.wgzhao.addax.common.element.Record;
import com.wgzhao.addax.common.element.StringColumn;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.plugin.RecordSender;
import com.wgzhao.addax.common.spi.Reader;
import com.wgzhao.addax.common.util.Configuration;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.fluent.Content;
import org.apache.hc.client5.http.fluent.Executor;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.ssl.TrustStrategy;

import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.wgzhao.addax.common.spi.ErrorCode.ILLEGAL_VALUE;
import static com.wgzhao.addax.common.spi.ErrorCode.REQUIRED_VALUE;

public class HttpReader
        extends Reader
{
    public static class Job
            extends Reader.Job
    {
        private Configuration originConfig = null;

        @Override
        public void init()
        {
            this.originConfig = this.getPluginJobConf();
        }

        @Override
        public void destroy()
        {
            //
        }

        @Override
        public List<Configuration> split(int adviceNumber)
        {
            List<Configuration> result = new ArrayList<>();
            result.add(this.originConfig);
            return result;
        }
    }

    public static class Task
            extends Reader.Task
    {
        private Configuration readerSliceConfig = null;
        private URIBuilder uriBuilder;
        private String username;
        private String password;
        private final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        private HttpHost proxy = null;
        private Request request;
        private String method;

        @Override
        public void init()
        {
            this.readerSliceConfig = this.getPluginJobConf();
            this.username = readerSliceConfig.getString(HttpKey.USERNAME, null);
            this.password = readerSliceConfig.getString(HttpKey.PASSWORD, null);
            this.method = readerSliceConfig.getString(HttpKey.METHOD, "get");
            Configuration conn = readerSliceConfig.getConfiguration(HttpKey.CONNECTION);
            uriBuilder = new URIBuilder(URI.create(conn.getString(HttpKey.URL)));
            if (conn.getString(HttpKey.PROXY, null) != null) {
                // set proxy
                setProxy(conn.getConfiguration(HttpKey.PROXY));
            }

            Map<String, Object> requestParams = readerSliceConfig.getMap(HttpKey.REQUEST_PARAMETERS, new HashMap<>());
            requestParams.forEach((k, v) -> uriBuilder.setParameter(k, v.toString()));
        }

        @Override
        public void destroy()
        {
            //
        }

        @Override
        public void startRead(RecordSender recordSender)
        {
            boolean isPage = readerSliceConfig.getBool(HttpKey.IS_PAGE, false);
            String paramPageSize = HttpKey.PAGE_SIZE;
            int valPageSize = 20;
            String paramPageIndex = HttpKey.PAGE_INDEX;
            int valPageIndex = 1;
            if (isPage) {
                Map<String, Object> pageParams = readerSliceConfig.getMap(HttpKey.PAGE_PARAMS);
                if (pageParams != null) {
                    if (pageParams.containsKey(HttpKey.PAGE_INDEX)) {
                        Map<String, Object> p = (Map<String, Object>) pageParams.get(HttpKey.PAGE_INDEX);
                        paramPageIndex = p.get("key").toString();
                        valPageIndex = Integer.parseInt(p.get("value").toString());
                    }
                    if (pageParams.containsKey(HttpKey.PAGE_SIZE)) {
                        Map<String, Object> p = (Map<String, Object>) pageParams.get(HttpKey.PAGE_SIZE);
                        paramPageSize = p.get("key").toString();
                        valPageSize = Integer.parseInt(p.get("value").toString());
                    }
                }
            }
            if (isPage) {
                int realPageSize;
                while (true) {
                    uriBuilder.setParameter(paramPageIndex, String.valueOf(valPageIndex));
                    uriBuilder.setParameter(paramPageSize, String.valueOf(valPageSize));
                    realPageSize = getRecords(recordSender);
                    if (realPageSize < valPageSize) {
                        // means no more data
                        break;
                    }
                    valPageIndex++;
                }
            }
            else {
                getRecords(recordSender);
            }
        }

        private int getRecords(RecordSender recordSender)
        {
            String encoding = readerSliceConfig.getString(HttpKey.ENCODING, null);
            Charset charset;
            if (encoding != null) {
                charset = Charset.forName(encoding);
            }
            else {
                charset = StandardCharsets.UTF_8;
            }

            try {
                request = Request.create(method, uriBuilder.build());
            }
            catch (URISyntaxException e) {
                throw AddaxException.asAddaxException(
                        ILLEGAL_VALUE, e.getMessage()
                );
            }

            String body = createCloseableHttpResponse().asString(charset);
            JSONArray jsonArray = null;
            String key = readerSliceConfig.get(HttpKey.RESULT_KEY, null);
            Object object;
            if (key != null) {
                object = JSON.parseObject(body).get(key);
            }
            else {
                object = JSON.parse(body);
            }
            // 需要判断返回的结果仅仅是一条记录还是多条记录，如果是一条记录，则是一个map
            // 否则是一个array
            if (object instanceof JSONArray) {
                // 有空值的情况下, toString会过滤掉，所以不能简单的使用 object.toString()方式
                // https://github.com/wgzhao/Addax/issues/171
                jsonArray = JSON.parseArray(JSONObject.toJSONString(object, JSONWriter.Feature.WriteMapNullValue));
            }
            else if (object instanceof JSONObject) {
                jsonArray = new JSONArray();
                jsonArray.add(object);
            }
            if (jsonArray == null || jsonArray.isEmpty()) {
                // empty result
                return 0;
            }

            List<String> columns = readerSliceConfig.getList(HttpKey.COLUMN, String.class);
            if (columns == null || columns.isEmpty()) {
                throw AddaxException.asAddaxException(REQUIRED_VALUE,
                        "The parameter [" + HttpKey.COLUMN + "] is not set."
                );
            }
            Record record;
            JSONObject jsonObject = jsonArray.getJSONObject(0);
            if (columns.size() == 1 && "*".equals(columns.get(0))) {
                // 没有给定key的情况下，提取JSON的第一层key作为字段处理
                columns.remove(0);
                columns.addAll((Collection<String>) JSONPath.eval(jsonObject, "$.e.keySet()"));
            }
            int i;
            for (i = 0; i < jsonArray.size(); i++) {
                jsonObject = jsonArray.getJSONObject(i);
                record = recordSender.createRecord();
                for (String k : columns) {
                    Object v = JSONPath.eval(jsonObject, k);
                    if (v == null) {
                        record.addColumn(new StringColumn());
                    }
                    else {
                        record.addColumn(new StringColumn(v.toString()));
                    }
                }
                recordSender.sendToWriter(record);
            }
            return i;
        }

        private void setProxy(Configuration proxyConf)
        {
            URI host;
            try {
                host = new URI(proxyConf.getString(HttpKey.HOST));
            }
            catch (URISyntaxException e) {
                throw AddaxException.asAddaxException(
                        ILLEGAL_VALUE, e.getMessage()
                );
            }

            this.proxy = new HttpHost(host.getScheme(), host.getHost(), host.getPort());
            if (proxyConf.getString(HttpKey.AUTH, null) != null) {
                String[] auth = proxyConf.getString(HttpKey.AUTH).split(":");
                credsProvider.setCredentials(
                        new AuthScope(proxy),
                        new UsernamePasswordCredentials(auth[0], auth[1].toCharArray())
                );
            }
        }

        private Content createCloseableHttpResponse()
        {
            Map<String, Object> headers = readerSliceConfig.getMap(HttpKey.HEADERS, new HashMap<>());
            CloseableHttpClient httpClient;
            headers.forEach((k, v) -> request.addHeader(k, v.toString()));
            httpClient = createCloseableHttpClient();
            try {
                return Executor.newInstance(httpClient)
                        .execute(request)
                        .returnContent();
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private CloseableHttpClient createCloseableHttpClient()
        {
            HttpClientBuilder httpClientBuilder = HttpClients.custom();
            if (proxy != null) {
                httpClientBuilder.setProxy(proxy);
            }
            if (this.password != null) {
                // setup BasicAuth
                // Create the authentication scope
                HttpHost target = new HttpHost(uriBuilder.getScheme(), uriBuilder.getHost(), uriBuilder.getPort());
                // Create credential pair
                UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(username, password.toCharArray());
                // Inject the credentials
                credsProvider.setCredentials(new AuthScope(target), credentials);
            }
            if (credsProvider != null) {
                httpClientBuilder.setDefaultCredentialsProvider(credsProvider);
            }
            Map<String, Object> headers = readerSliceConfig.getMap(HttpKey.HEADERS, new HashMap<>());
            headers.forEach((k, v) -> request.addHeader(k, v.toString()));
            if (Objects.equals(uriBuilder.getScheme(), "https")) {
                PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                        .setTlsSocketStrategy(customTlsStrategy())
                        .build();
                return httpClientBuilder
                        .setConnectionManager(cm)
                        .build();
            }
            else {
                return httpClientBuilder.build();
            }
        }

        private DefaultClientTlsStrategy customTlsStrategy()
        {
            TrustStrategy trustStrategy = (x509Certificates, s) -> true;
            try {
                // use the TrustSelfSignedStrategy to allow Self Signed Certificates
                SSLContext ssl = SSLContexts.custom()
                        .loadTrustMaterial(null, trustStrategy)
                        .build();
                // ignore hostname verification
                return new DefaultClientTlsStrategy(ssl, NoopHostnameVerifier.INSTANCE);
            }
            catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
                throw AddaxException.asAddaxException(
                        ILLEGAL_VALUE, e.getMessage()
                );
            }
        }
    }
}

/*
 * ====================================================================
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
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.examples;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.sync.CloseableHttpClient;
import org.apache.hc.client5.http.impl.sync.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.sync.HttpClients;
import org.apache.hc.client5.http.sync.methods.HttpGet;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.pool.PoolStats;

/**
 * Example demonstrating how to evict expired and idle connections
 * from the connection pool.
 */
public class ClientEvictExpiredConnections {

    public static void main(String[] args) throws Exception {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(100);
        try (CloseableHttpClient httpclient = HttpClients.custom()
                .setConnectionManager(cm)
                .evictExpiredConnections()
                .evictIdleConnections(5L, TimeUnit.SECONDS)
                .build()) {
            // create an array of URIs to perform GETs on
            String[] urisToGet = {
                    "http://hc.apache.org/",
                    "http://hc.apache.org/httpcomponents-core-ga/",
                    "http://hc.apache.org/httpcomponents-client-ga/",
            };

            for (int i = 0; i < urisToGet.length; i++) {
                String requestURI = urisToGet[i];
                HttpGet request = new HttpGet(requestURI);

                System.out.println("Executing request " + request.getMethod() + " " + request.getRequestUri());

                try (CloseableHttpResponse response = httpclient.execute(request)) {
                    System.out.println("----------------------------------------");
                    System.out.println(response.getCode() + " " + response.getReasonPhrase());
                    EntityUtils.consume(response.getEntity());
                }
            }

            PoolStats stats1 = cm.getTotalStats();
            System.out.println("Connections kept alive: " + stats1.getAvailable());

            // Sleep 10 sec and let the connection evictor do its job
            Thread.sleep(10000);

            PoolStats stats2 = cm.getTotalStats();
            System.out.println("Connections kept alive: " + stats2.getAvailable());

        }
    }

}
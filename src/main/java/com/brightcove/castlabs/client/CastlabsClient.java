/**
 * Copyright 2015 Brightcove Inc. All rights reserved.
 */
package com.brightcove.castlabs.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

import com.brightcove.castlabs.client.request.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Lists;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;

import com.brightcove.castlabs.client.response.IngestAssetsResponse;
import com.brightcove.castlabs.client.response.AddSubMerchantAccountResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for interacting with the Castlabs API.
 */
public class CastlabsClient {

    private static final String CASTLABS_AUTH_BASE_URL = "https://auth.drmtoday.com/";
    private static final String CASTLABS_INGESTION_BASE_URL = "https://fe.drmtoday.com/";
    private String authBaseUrl;
    private String ingestionBaseUrl;
    private String username;
    private String password;
    private int connectionTimeoutSeconds = -1;
    private ObjectMapper objectMapper;

    public CastlabsClient(final String username, final String password) {
        this(username, password, CASTLABS_AUTH_BASE_URL, CASTLABS_INGESTION_BASE_URL, -1);
    }

    public CastlabsClient(final String username, final String password, final String authBaseUrl, final String ingestionBaseUrl, final int connectionTimeoutSeconds) {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.username = username;
        this.password = password;
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;

        if (authBaseUrl.endsWith("/")) {
            this.authBaseUrl = authBaseUrl;
        } else {
            this.authBaseUrl = authBaseUrl + "/";
        }

        if (ingestionBaseUrl.endsWith("/")) {
            this.ingestionBaseUrl = ingestionBaseUrl;
        } else {
            this.ingestionBaseUrl = ingestionBaseUrl + "/";
        }
    }

    /**
     * Login to the Castlabs API endpoint.
     *
     * @return a ticket URL
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       communication error when interacting with Castlabs API
     */
    protected String login() throws CastlabsException, IOException {
        final HttpPost loginRequest = new HttpPost(this.authBaseUrl + "cas/v1/tickets");
        loginRequest.addHeader("Content-Type", "application/x-www-form-urlencoded");
        loginRequest.setHeader("Accept", "*/*");

        final List<NameValuePair> entityParts = Lists.newArrayList();
        entityParts.add(new BasicNameValuePair("username", this.username));
        entityParts.add(new BasicNameValuePair("password", this.password));

        if (this.connectionTimeoutSeconds > 0) {
            final int connectionTimeout = connectionTimeoutSeconds * 1000;
            final RequestConfig requestConfig =
                    RequestConfig.custom().setConnectionRequestTimeout(connectionTimeout)
                            .setConnectTimeout(connectionTimeout)
                            .setSocketTimeout(connectionTimeout).build();
            loginRequest.setConfig(requestConfig);
        }
        loginRequest.setEntity(new UrlEncodedFormEntity(entityParts));

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse loginResponse = httpclient.execute(loginRequest)) {
            if (loginResponse != null) {
                final int statusCode = loginResponse.getStatusLine().getStatusCode();
                final String reason = loginResponse.getStatusLine().getReasonPhrase();
                if (201 != statusCode) {
                    throw new CastlabsException(
                            "Login failed: Response code=" + statusCode + ", Reason=" + reason);
                }
            } else {
                throw new CastlabsException("No response when attempting login to Castlabs");
            }
            final Header locationResponseHeader = loginResponse.getFirstHeader("location");
            if (locationResponseHeader != null
                    && locationResponseHeader.getValue().trim().length() > 0) {
                return locationResponseHeader.getValue();
            } else {
                throw new CastlabsException("No location header provided in API response");
            }
        }
    }


    /**
     * Retrieve an authentication ticket with the given URL and return the URL with token appended
     *
     * @param url URL to request the token for
     * @return ticket that can be used to ingest encryption keys
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       communication error when interacting with Castlabs API
     */
    protected String getUrlWithTicket(final String url) throws CastlabsException, IOException {
        final HttpPost ticketRequest = new HttpPost(this.login());
        ticketRequest.addHeader("Content-Type", "application/x-www-form-urlencoded");
        ticketRequest.setHeader("Accept", "*/*");

        final List<NameValuePair> entityParts = Lists.newArrayList();
        entityParts.add(new BasicNameValuePair("service", url));

        if (this.connectionTimeoutSeconds > 0) {
            final int connectionTimeout = connectionTimeoutSeconds * 1000;
            final RequestConfig requestConfig =
                    RequestConfig.custom().setConnectionRequestTimeout(connectionTimeout)
                            .setConnectTimeout(connectionTimeout)
                            .setSocketTimeout(connectionTimeout).build();
            ticketRequest.setConfig(requestConfig);
        }
        ticketRequest.setEntity(new UrlEncodedFormEntity(entityParts));

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse ticketResponse = httpclient.execute(ticketRequest)) {
            if (ticketResponse != null) {
                final int statusCode = ticketResponse.getStatusLine().getStatusCode();
                if (200 != statusCode) {
                    final String reason = ticketResponse.getStatusLine().getReasonPhrase();
                    final String responseBody =
                            IOUtils.toString(ticketResponse.getEntity().getContent());
                    throw new CastlabsException("Ticket retrieval failed: Response code="
                            + statusCode + ", Reason=" + reason + ", Body=" + responseBody);
                }
            } else {
                throw new CastlabsException("No response when retrieving Castlabs ticket");
            }
            return url + "?ticket=" + IOUtils.toString(ticketResponse.getEntity().getContent());
        }
    }

    /**
     * Ingest one or more keys into the Castlabs keystore.
     *
     * @param request    Request parameters to pass to Castlabs
     * @param merchantId
     * @return response from Castlabs
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       network error while communicating with Castlabs REST API
     */
    public IngestAssetsResponse ingestKeys(final IngestKeysRequest request, final String merchantId)
            throws CastlabsException, IOException {

        final String uri = this.getUrlWithTicket(this.ingestionBaseUrl + "frontend/api/keys/v2/ingest/" + merchantId);
        final HttpPost httpRequest = createHttpPostRequest(uri, request);

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse ingestResponse = httpclient.execute(httpRequest)) {
            if (ingestResponse != null) {
                final int statusCode = ingestResponse.getStatusLine().getStatusCode();
                if (200 != statusCode) {
                    final String reason = ingestResponse.getStatusLine().getReasonPhrase();
                    final String responseBody =
                            IOUtils.toString(ingestResponse.getEntity().getContent());
                    throw new CastlabsException("Ingest failed: Response code=" + statusCode
                            + ", Reason=" + reason + ", Body=" + responseBody);
                }
                final HttpEntity responseEntity = ingestResponse.getEntity();
                if (responseEntity != null) {
                    return objectMapper.readValue(responseEntity.getContent(), IngestAssetsResponse.class);
                } else {
                    throw new CastlabsException("Empty response entity from Castlabs");
                }
            } else {
                throw new CastlabsException("No response when ingesting keys into Castlabs");
            }
        }
    }

    /**
     * Add a sub merchant account to Castlabs.
     *
     * @param request      Request parameters to pass to Castlabs
     * @param merchantUuid UUID for the merchant that the sub-merchant is being created off
     * @return response from Castlabs
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       network error while communicating with Castlabs REST API
     */
    public AddSubMerchantAccountResponse addSubMerchantAccount(final AddSubMerchantAccountRequest request, final String merchantUuid)
            throws IOException, CastlabsException {

        final String uri = this.getUrlWithTicket(this.ingestionBaseUrl + "frontend/rest/reselling/v1/reseller/" + merchantUuid + "/submerchant/add");
        final HttpPost httpRequest = createHttpPostRequest(uri, request);

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse httpResponse = httpclient.execute(httpRequest)) {
            final HttpEntity responseEntity = httpResponse.getEntity();
            if (responseEntity == null) {
                throw new CastlabsException("Empty response entity from Castlabs. HTTP Status: " + httpResponse.getStatusLine().getStatusCode());
            }

            final String responseBody = IOUtils.toString(responseEntity.getContent());
            if (StringUtils.isBlank(responseBody)) {
                throw new CastlabsException("Empty response entity from Castlabs. HTTP Status: " + httpResponse.getStatusLine().getStatusCode());
            }

            final AddSubMerchantAccountResponse response = objectMapper.readValue(responseBody, AddSubMerchantAccountResponse.class);
            if (response.getSubMerchantUuid() == null) {
                throw new CastlabsException("Unexpected response from Castlabs: " + responseBody);
            }
            return response;
        }
    }

    /**
     * Link an existing user/API account to a sub-merchant account.
     *
     * @param request      Request parameters to pass to Castlabs
     * @param resellerUuid UUID for the merchant that the sub-merchant was created off
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       network error while communicating with Castlabs REST API
     */
    public void linkAccountToSubMerchant(final LinkAccountToSubMerchantRequest request, final String resellerUuid)
            throws IOException, CastlabsException {

        final String uri = this.getUrlWithTicket(this.ingestionBaseUrl + "frontend/rest/reselling/v1/reseller/" + resellerUuid + "/submerchant/linkAccount");
        final HttpPost httpRequest = createHttpPostRequest(uri, request);

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse httpResponse = httpclient.execute(httpRequest)) {
            final int statusCode = httpResponse.getStatusLine().getStatusCode();

            if (statusCode != HttpStatus.SC_NO_CONTENT) {
                final HttpEntity responseEntity = httpResponse.getEntity();

                String responseBody = "";
                if (responseEntity != null) {
                    responseBody = IOUtils.toString(responseEntity.getContent());
                }

                throw new CastlabsException("Unexpected status code from Castlabs: " + statusCode + ". Response body: " + responseBody);
            }
        }
    }

    /**
     * Update Account Authorization Setting
     *
     * @param request      Request parameters to pass to Castlabs
     * @param merchantUuid UUID for the merchant
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       network error while communicating with Castlabs REST API
     */
    public void updateAuthorizationSettings(final UpdateAuthorizationSettingsRequest request, final String merchantUuid)
            throws IOException, CastlabsException {

        final String uri = this.getUrlWithTicket(this.ingestionBaseUrl + "frontend/rest/config/v1/" + merchantUuid + "/auth/settings");
        final HttpPost httpRequest = createHttpPostRequest(uri, request);

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse httpResponse = httpclient.execute(httpRequest)) {
            final int statusCode = httpResponse.getStatusLine().getStatusCode();

            if (statusCode != HttpStatus.SC_OK) {
                final HttpEntity responseEntity = httpResponse.getEntity();

                String responseBody = "";
                if (responseEntity != null) {
                    responseBody = IOUtils.toString(responseEntity.getContent());
                }

                throw new CastlabsException("Unexpected status code from Castlabs: " + statusCode + ". Response body: " + responseBody);
            }
        }
    }

    /**
     * Add a Shared Secret to the Castlabs Account
     *
     * @param request      Request parameters to pass to Castlabs
     * @param merchantUuid UUID for the merchant
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       network error while communicating with Castlabs REST API
     */
    public void addSharedSecret(final SharedSecretRequest request, final String merchantUuid)
            throws IOException, CastlabsException {

        final String uri = this.getUrlWithTicket(this.ingestionBaseUrl + "frontend/rest/config/v1/" + merchantUuid + "/upfront/secret/add");
        final HttpPost httpRequest = createHttpPostRequest(uri, request);

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse httpResponse = httpclient.execute(httpRequest)) {
            final int statusCode = httpResponse.getStatusLine().getStatusCode();

            if (statusCode != HttpStatus.SC_OK) {
                final HttpEntity responseEntity = httpResponse.getEntity();

                String responseBody = "";
                if (responseEntity != null) {
                    responseBody = IOUtils.toString(responseEntity.getContent());
                }

                throw new CastlabsException("Unexpected status code from Castlabs: " + statusCode + ". Response body: " + responseBody);
            }
        }
    }

    /**
     * Add Fairplay configuration to the Castlabs Account
     *
     * @param request      Request parameters to pass to Castlabs
     * @param merchantUuid UUID for the merchant
     * @throws CastlabsException error reported by Castlabs
     * @throws IOException       network error while communicating with Castlabs REST API
     */
    public void setFairplayConfiguration(final FairplayRequest request, final String merchantUuid)
            throws IOException, CastlabsException {

        final String uri = this.getUrlWithTicket(this.ingestionBaseUrl + "frontend/rest/config/v1/" + merchantUuid + "/drm/fairplay");
        final HttpPost httpRequest = createHttpPostRequest(uri, request);

        final CloseableHttpClient httpclient = HttpClients.createDefault();
        try (final CloseableHttpResponse httpResponse = httpclient.execute(httpRequest)) {
            final int statusCode = httpResponse.getStatusLine().getStatusCode();

            if (statusCode != HttpStatus.SC_OK) {
                final HttpEntity responseEntity = httpResponse.getEntity();

                String responseBody = "";
                if (responseEntity != null) {
                    responseBody = IOUtils.toString(responseEntity.getContent());
                }

                throw new CastlabsException("Unexpected status code from Castlabs: " + statusCode + ". Response body: " + responseBody);
            }
        }
    }

    private HttpPost createHttpPostRequest(final String uri, final Object body) throws JsonProcessingException, UnsupportedEncodingException {
        final HttpPost request = new HttpPost(uri);
        request.addHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        request.setEntity(new StringEntity(objectMapper.writeValueAsString(body)));

        if (this.connectionTimeoutSeconds > 0) {
            final int connectionTimeout = connectionTimeoutSeconds * 1000;
            final RequestConfig requestConfig =
                    RequestConfig.custom().setConnectionRequestTimeout(connectionTimeout)
                            .setConnectTimeout(connectionTimeout)
                            .setSocketTimeout(connectionTimeout).build();
            request.setConfig(requestConfig);
        }

        return request;
    }

}

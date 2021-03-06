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
package org.apache.hc.client5.http.impl.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.async.methods.SimpleBody;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.cache.CacheResponseStatus;
import org.apache.hc.client5.http.cache.HeaderConstants;
import org.apache.hc.client5.http.cache.HttpCacheContext;
import org.apache.hc.client5.http.cache.HttpCacheEntry;
import org.apache.hc.client5.http.cache.HttpCacheStorage;
import org.apache.hc.client5.http.cache.ResourceFactory;
import org.apache.hc.client5.http.cache.ResourceIOException;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.impl.classic.ClassicRequestCopier;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpMessage;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.apache.hc.core5.util.VersionInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * <p>
 * Request executor in the request execution chain that is responsible for
 * transparent client-side caching.
 * </p>
 * <p>
 * The current implementation is conditionally
 * compliant with HTTP/1.1 (meaning all the MUST and MUST NOTs are obeyed),
 * although quite a lot, though not all, of the SHOULDs and SHOULD NOTs
 * are obeyed too.
 * </p>
 * <p>
 * Folks that would like to experiment with alternative storage backends
 * should look at the {@link HttpCacheStorage} interface and the related
 * package documentation there. You may also be interested in the provided
 * {@link org.apache.hc.client5.http.impl.cache.ehcache.EhcacheHttpCacheStorage
 * EhCache} and {@link
 * org.apache.hc.client5.http.impl.cache.memcached.MemcachedHttpCacheStorage
 * memcached} storage backends.
 * </p>
 * <p>
 * Further responsibilities such as communication with the opposite
 * endpoint is delegated to the next executor in the request execution
 * chain.
 * </p>
 *
 * @since 4.3
 */
@Contract(threading = ThreadingBehavior.SAFE) // So long as the responseCache implementation is threadsafe
public class CachingExec implements ExecChainHandler {

    private final static boolean SUPPORTS_RANGE_AND_CONTENT_RANGE_HEADERS = false;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong cacheUpdates = new AtomicLong();

    private final Map<ProtocolVersion, String> viaHeaders = new HashMap<>(4);

    private final CacheConfig cacheConfig;
    private final HttpCache responseCache;
    private final CacheValidityPolicy validityPolicy;
    private final CachedHttpResponseGenerator responseGenerator;
    private final CacheableRequestPolicy cacheableRequestPolicy;
    private final CachedResponseSuitabilityChecker suitabilityChecker;
    private final ConditionalRequestBuilder<ClassicHttpRequest> conditionalRequestBuilder;
    private final ResponseProtocolCompliance responseCompliance;
    private final RequestProtocolCompliance requestCompliance;
    private final ResponseCachingPolicy responseCachingPolicy;

    private final AsynchronousValidator asynchRevalidator;

    private final Logger log = LogManager.getLogger(getClass());

    public CachingExec(
            final HttpCache cache,
            final CacheConfig config) {
        this(cache, config, null);
    }

    public CachingExec(
            final HttpCache cache,
            final CacheConfig config,
            final AsynchronousValidator asynchRevalidator) {
        super();
        Args.notNull(cache, "HttpCache");
        this.cacheConfig = config != null ? config : CacheConfig.DEFAULT;
        this.responseCache = cache;
        this.validityPolicy = new CacheValidityPolicy();
        this.responseGenerator = new CachedHttpResponseGenerator(this.validityPolicy);
        this.cacheableRequestPolicy = new CacheableRequestPolicy();
        this.suitabilityChecker = new CachedResponseSuitabilityChecker(this.validityPolicy, this.cacheConfig);
        this.conditionalRequestBuilder = new ConditionalRequestBuilder<>(ClassicRequestCopier.INSTANCE);
        this.responseCompliance = new ResponseProtocolCompliance();
        this.requestCompliance = new RequestProtocolCompliance(this.cacheConfig.isWeakETagOnPutDeleteAllowed());
        this.responseCachingPolicy = new ResponseCachingPolicy(
                this.cacheConfig.getMaxObjectSize(), this.cacheConfig.isSharedCache(),
                this.cacheConfig.isNeverCacheHTTP10ResponsesWithQuery(), this.cacheConfig.is303CachingEnabled());
        this.asynchRevalidator = asynchRevalidator;
    }

    public CachingExec(
            final ResourceFactory resourceFactory,
            final HttpCacheStorage storage,
            final CacheConfig config) {
        this(new BasicHttpCache(resourceFactory, storage), config);
    }

    public CachingExec() {
        this(new BasicHttpCache(), CacheConfig.DEFAULT);
    }

    CachingExec(
            final HttpCache responseCache,
            final CacheValidityPolicy validityPolicy,
            final ResponseCachingPolicy responseCachingPolicy,
            final CachedHttpResponseGenerator responseGenerator,
            final CacheableRequestPolicy cacheableRequestPolicy,
            final CachedResponseSuitabilityChecker suitabilityChecker,
            final ConditionalRequestBuilder<ClassicHttpRequest> conditionalRequestBuilder,
            final ResponseProtocolCompliance responseCompliance,
            final RequestProtocolCompliance requestCompliance,
            final CacheConfig config,
            final AsynchronousValidator asynchRevalidator) {
        this.cacheConfig = config != null ? config : CacheConfig.DEFAULT;
        this.responseCache = responseCache;
        this.validityPolicy = validityPolicy;
        this.responseCachingPolicy = responseCachingPolicy;
        this.responseGenerator = responseGenerator;
        this.cacheableRequestPolicy = cacheableRequestPolicy;
        this.suitabilityChecker = suitabilityChecker;
        this.conditionalRequestBuilder = conditionalRequestBuilder;
        this.responseCompliance = responseCompliance;
        this.requestCompliance = requestCompliance;
        this.asynchRevalidator = asynchRevalidator;
    }

    /**
     * Reports the number of times that the cache successfully responded
     * to an {@link HttpRequest} without contacting the origin server.
     * @return the number of cache hits
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Reports the number of times that the cache contacted the origin
     * server because it had no appropriate response cached.
     * @return the number of cache misses
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Reports the number of times that the cache was able to satisfy
     * a response by revalidating an existing but stale cache entry.
     * @return the number of cache revalidations
     */
    public long getCacheUpdates() {
        return cacheUpdates.get();
    }

    @Override
    public ClassicHttpResponse execute(
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain) throws IOException, HttpException {
        Args.notNull(request, "HTTP request");
        Args.notNull(scope, "Scope");

        final HttpRoute route = scope.route;
        final HttpClientContext context = scope.clientContext;

        final URIAuthority authority = request.getAuthority();
        final String scheme = request.getScheme();
        final HttpHost target = authority != null ? new HttpHost(authority, scheme) : route.getTargetHost();;
        final String via = generateViaHeader(request);

        // default response context
        setResponseStatus(context, CacheResponseStatus.CACHE_MISS);

        if (clientRequestsOurOptions(request)) {
            setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            return new BasicClassicHttpResponse(HttpStatus.SC_NOT_IMPLEMENTED);
        }

        final SimpleHttpResponse fatalErrorResponse = getFatallyNoncompliantResponse(request, context);
        if (fatalErrorResponse != null) {
            return convert(fatalErrorResponse);
        }

        requestCompliance.makeRequestCompliant(request);
        request.addHeader("Via",via);

        if (!cacheableRequestPolicy.isServableFromCache(request)) {
            log.debug("Request is not servable from cache");
            flushEntriesInvalidatedByRequest(target, request);
            return callBackend(target, request, scope, chain);
        }

        final HttpCacheEntry entry = satisfyFromCache(target, request);
        if (entry == null) {
            log.debug("Cache miss");
            return handleCacheMiss(target, request, scope, chain);
        } else {
            try {
                return handleCacheHit(target, request, scope, chain, entry);
            } catch (final ResourceIOException ex) {
                log.debug("Cache resource I/O error");
                return handleCacheFailure(target, request, scope, chain);
            }
        }
    }

    private static ClassicHttpResponse convert(final SimpleHttpResponse cacheResponse) {
        if (cacheResponse == null) {
            return null;
        }
        final ClassicHttpResponse response = new BasicClassicHttpResponse(cacheResponse.getCode(), cacheResponse.getReasonPhrase());
        for (final Iterator<Header> it = cacheResponse.headerIterator(); it.hasNext(); ) {
            response.addHeader(it.next());
        }
        response.setVersion(cacheResponse.getVersion() != null ? cacheResponse.getVersion() : HttpVersion.DEFAULT);
        final SimpleBody body = cacheResponse.getBody();
        if (body != null) {
            if (body.isText()) {
                response.setEntity(new StringEntity(body.getBodyText(), body.getContentType()));
            } else {
                response.setEntity(new ByteArrayEntity(body.getBodyBytes(), body.getContentType()));
            }
        }
        return response;
    }

    ClassicHttpResponse callBackend(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain) throws IOException, HttpException  {

        final Date requestDate = getCurrentDate();

        log.trace("Calling the backend");
        final ClassicHttpResponse backendResponse = chain.proceed(request, scope);
        try {
            backendResponse.addHeader("Via", generateViaHeader(backendResponse));
            return handleBackendResponse(target, request, scope, requestDate, getCurrentDate(), backendResponse);
        } catch (final IOException | RuntimeException ex) {
            backendResponse.close();
            throw ex;
        }
    }

    private ClassicHttpResponse handleCacheHit(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain,
            final HttpCacheEntry entry) throws IOException, HttpException {
        final HttpRoute route = scope.route;
        final HttpClientContext context  = scope.clientContext;
        recordCacheHit(target, request);
        ClassicHttpResponse out;
        final Date now = getCurrentDate();
        if (suitabilityChecker.canCachedResponseBeUsed(target, request, entry, now)) {
            log.debug("Cache hit");
            out = convert(generateCachedResponse(request, context, entry, now));
        } else if (!mayCallBackend(request)) {
            log.debug("Cache entry not suitable but only-if-cached requested");
            out = convert(generateGatewayTimeout(context));
        } else if (!(entry.getStatus() == HttpStatus.SC_NOT_MODIFIED
                && !suitabilityChecker.isConditional(request))) {
            log.debug("Revalidating cache entry");
            return revalidateCacheEntry(target, request, scope, chain, entry, now);
        } else {
            log.debug("Cache entry not usable; calling backend");
            return callBackend(target, request, scope, chain);
        }
        context.setAttribute(HttpClientContext.HTTP_ROUTE, route);
        context.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
        context.setAttribute(HttpCoreContext.HTTP_RESPONSE, out);
        return out;
    }

    private ClassicHttpResponse revalidateCacheEntry(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain,
            final HttpCacheEntry entry,
            final Date now) throws HttpException, IOException {

        final HttpClientContext context = scope.clientContext;
        try {
            if (asynchRevalidator != null
                && !staleResponseNotAllowed(request, entry, now)
                && validityPolicy.mayReturnStaleWhileRevalidating(entry, now)) {
                log.trace("Serving stale with asynchronous revalidation");
                final SimpleHttpResponse resp = generateCachedResponse(request, context, entry, now);
                asynchRevalidator.revalidateCacheEntry(this, target, request, scope, chain, entry);
                return convert(resp);
            }
            return revalidateCacheEntry(target, request, scope, chain, entry);
        } catch (final IOException ioex) {
            return convert(handleRevalidationFailure(request, context, entry, now));
        }
    }

    ClassicHttpResponse revalidateCacheEntry(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain,
            final HttpCacheEntry cacheEntry) throws IOException, HttpException {

        final ClassicHttpRequest conditionalRequest = conditionalRequestBuilder.buildConditionalRequest(
                scope.originalRequest, cacheEntry);

        Date requestDate = getCurrentDate();
        ClassicHttpResponse backendResponse = chain.proceed(conditionalRequest, scope);
        try {
            Date responseDate = getCurrentDate();

            if (revalidationResponseIsTooOld(backendResponse, cacheEntry)) {
                backendResponse.close();
                final ClassicHttpRequest unconditional = conditionalRequestBuilder.buildUnconditionalRequest(
                        scope.originalRequest);
                requestDate = getCurrentDate();
                backendResponse = chain.proceed(unconditional, scope);
                responseDate = getCurrentDate();
            }

            backendResponse.addHeader(HeaderConstants.VIA, generateViaHeader(backendResponse));

            final int statusCode = backendResponse.getCode();
            if (statusCode == HttpStatus.SC_NOT_MODIFIED || statusCode == HttpStatus.SC_OK) {
                recordCacheUpdate(scope.clientContext);
            }

            if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
                final HttpCacheEntry updatedEntry = responseCache.updateCacheEntry(
                        target, request, cacheEntry,
                        backendResponse, requestDate, responseDate);
                if (suitabilityChecker.isConditional(request)
                        && suitabilityChecker.allConditionalsMatch(request, updatedEntry, new Date())) {
                    return convert(responseGenerator.generateNotModifiedResponse(updatedEntry));
                }
                return convert(responseGenerator.generateResponse(request, updatedEntry));
            }

            if (staleIfErrorAppliesTo(statusCode)
                    && !staleResponseNotAllowed(request, cacheEntry, getCurrentDate())
                    && validityPolicy.mayReturnStaleIfError(request, cacheEntry, responseDate)) {
                try {
                    final SimpleHttpResponse cachedResponse = responseGenerator.generateResponse(request, cacheEntry);
                    cachedResponse.addHeader(HeaderConstants.WARNING, "110 localhost \"Response is stale\"");
                    return convert(cachedResponse);
                } finally {
                    backendResponse.close();
                }
            }
            return handleBackendResponse(target, conditionalRequest, scope, requestDate, responseDate, backendResponse);
        } catch (final IOException | RuntimeException ex) {
            backendResponse.close();
            throw ex;
        }
    }

    ClassicHttpResponse handleBackendResponse(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final Date requestDate,
            final Date responseDate,
            final ClassicHttpResponse backendResponse) throws IOException {

        log.trace("Handling Backend response");
        responseCompliance.ensureProtocolCompliance(scope.originalRequest, request, backendResponse);

        final boolean cacheable = responseCachingPolicy.isResponseCacheable(request, backendResponse);
        responseCache.flushInvalidatedCacheEntriesFor(target, request, backendResponse);
        if (cacheable && !alreadyHaveNewerCacheEntry(target, request, backendResponse)) {
            storeRequestIfModifiedSinceFor304Response(request, backendResponse);
            return cacheAndReturnResponse(target, request, backendResponse, requestDate, responseDate);
        }
        if (!cacheable) {
            try {
                responseCache.flushCacheEntriesFor(target, request);
            } catch (final IOException ioe) {
                log.warn("Unable to flush invalid cache entries", ioe);
            }
        }
        return backendResponse;
    }

    ClassicHttpResponse cacheAndReturnResponse(
            final HttpHost target,
            final HttpRequest request,
            final ClassicHttpResponse backendResponse,
            final Date requestSent,
            final Date responseReceived) throws IOException {        final ByteArrayBuffer buf;
        final HttpEntity entity = backendResponse.getEntity();
        if (entity != null) {
            buf = new ByteArrayBuffer(1024);
            final InputStream instream = entity.getContent();
            final byte[] tmp = new byte[2048];
            long total = 0;
            int l;
            while ((l = instream.read(tmp)) != -1) {
                buf.append(tmp, 0, l);
                total += l;
                if (total > cacheConfig.getMaxObjectSize()) {
                    backendResponse.setEntity(new CombinedEntity(entity, buf));
                    return backendResponse;
                }
            }
        } else {
            buf = null;
        }
        if (buf != null && isIncompleteResponse(backendResponse, buf)) {
            final Header h = backendResponse.getFirstHeader(HttpHeaders.CONTENT_LENGTH);
            final ClassicHttpResponse error = new BasicClassicHttpResponse(HttpStatus.SC_BAD_GATEWAY, "Bad Gateway");
            final String msg = String.format("Received incomplete response " +
                            "with Content-Length %s but actual body length %d",
                    h != null ? h.getValue() : null, buf.length());
            error.setEntity(new StringEntity(msg, ContentType.TEXT_PLAIN));
            backendResponse.close();
            return error;
        }
        backendResponse.close();
        final HttpCacheEntry entry = responseCache.createCacheEntry(target, request, backendResponse, buf, requestSent, responseReceived);
        return convert(responseGenerator.generateResponse(request, entry));
    }

    private ClassicHttpResponse handleCacheMiss(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain) throws IOException, HttpException {
        recordCacheMiss(target, request);

        if (!mayCallBackend(request)) {
            return new BasicClassicHttpResponse(HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout");
        }

        final Map<String, Variant> variants = getExistingCacheVariants(target, request);
        if (variants != null && !variants.isEmpty()) {
            return negotiateResponseFromVariants(target, request, scope, chain, variants);
        }

        return callBackend(target, request, scope, chain);
    }

    ClassicHttpResponse negotiateResponseFromVariants(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain,
            final Map<String, Variant> variants) throws IOException, HttpException {
        final ClassicHttpRequest conditionalRequest = conditionalRequestBuilder.buildConditionalRequestFromVariants(
                request, variants);

        final Date requestDate = getCurrentDate();
        final ClassicHttpResponse backendResponse = chain.proceed(conditionalRequest, scope);
        try {
            final Date responseDate = getCurrentDate();

            backendResponse.addHeader("Via", generateViaHeader(backendResponse));

            if (backendResponse.getCode() != HttpStatus.SC_NOT_MODIFIED) {
                return handleBackendResponse(target, request, scope, requestDate, responseDate, backendResponse);
            }

            final Header resultEtagHeader = backendResponse.getFirstHeader(HeaderConstants.ETAG);
            if (resultEtagHeader == null) {
                log.warn("304 response did not contain ETag");
                EntityUtils.consume(backendResponse.getEntity());
                backendResponse.close();
                return callBackend(target, request, scope, chain);
            }

            final String resultEtag = resultEtagHeader.getValue();
            final Variant matchingVariant = variants.get(resultEtag);
            if (matchingVariant == null) {
                log.debug("304 response did not contain ETag matching one sent in If-None-Match");
                EntityUtils.consume(backendResponse.getEntity());
                backendResponse.close();
                return callBackend(target, request, scope, chain);
            }

            final HttpCacheEntry matchedEntry = matchingVariant.getEntry();

            if (revalidationResponseIsTooOld(backendResponse, matchedEntry)) {
                EntityUtils.consume(backendResponse.getEntity());
                backendResponse.close();
                final ClassicHttpRequest unconditional = conditionalRequestBuilder.buildUnconditionalRequest(request);
                return callBackend(target, unconditional, scope, chain);
            }

            recordCacheUpdate(scope.clientContext);

            HttpCacheEntry responseEntry = matchedEntry;
            try {
                responseEntry = responseCache.updateVariantCacheEntry(target, conditionalRequest,
                        matchedEntry, backendResponse, requestDate, responseDate, matchingVariant.getCacheKey());
            } catch (final IOException ioe) {
                log.warn("Could not processChallenge cache entry", ioe);
            } finally {
                backendResponse.close();
            }

            final SimpleHttpResponse resp = responseGenerator.generateResponse(request, responseEntry);
            tryToUpdateVariantMap(target, request, matchingVariant);

            if (shouldSendNotModifiedResponse(request, responseEntry)) {
                return convert(responseGenerator.generateNotModifiedResponse(responseEntry));
            }
            return convert(resp);
        } catch (final IOException | RuntimeException ex) {
            backendResponse.close();
            throw ex;
        }
    }

    private ClassicHttpResponse handleCacheFailure(
            final HttpHost target,
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain) throws IOException, HttpException {
        recordCacheFailure(target, request);

        if (!mayCallBackend(request)) {
            return new BasicClassicHttpResponse(HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout");
        }

        setResponseStatus(scope.clientContext, CacheResponseStatus.FAILURE);
        return chain.proceed(request, scope);
    }

    private HttpCacheEntry satisfyFromCache(final HttpHost target, final HttpRequest request) {
        HttpCacheEntry entry = null;
        try {
            entry = responseCache.getCacheEntry(target, request);
        } catch (final IOException ioe) {
            log.warn("Unable to retrieve entries from cache", ioe);
        }
        return entry;
    }

    private SimpleHttpResponse getFatallyNoncompliantResponse(
            final HttpRequest request,
            final HttpContext context) {
        final List<RequestProtocolError> fatalError = requestCompliance.requestIsFatallyNonCompliant(request);
        if (fatalError != null && !fatalError.isEmpty()) {
            setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            return responseGenerator.getErrorForRequest(fatalError.get(0));
        } else {
            return null;
        }
    }

    private Map<String, Variant> getExistingCacheVariants(final HttpHost target, final HttpRequest request) {
        Map<String,Variant> variants = null;
        try {
            variants = responseCache.getVariantCacheEntriesWithEtags(target, request);
        } catch (final IOException ioe) {
            log.warn("Unable to retrieve variant entries from cache", ioe);
        }
        return variants;
    }

    private void recordCacheMiss(final HttpHost target, final HttpRequest request) {
        cacheMisses.getAndIncrement();
        if (log.isTraceEnabled()) {
            log.trace("Cache miss [host: " + target + "; uri: " + request.getRequestUri() + "]");
        }
    }

    private void recordCacheHit(final HttpHost target, final HttpRequest request) {
        cacheHits.getAndIncrement();
        if (log.isTraceEnabled()) {
            log.trace("Cache hit [host: " + target + "; uri: " + request.getRequestUri() + "]");
        }
    }

    private void recordCacheFailure(final HttpHost target, final HttpRequest request) {
        cacheMisses.getAndIncrement();
        if (log.isTraceEnabled()) {
            log.trace("Cache failure [host: " + target + "; uri: " + request.getRequestUri() + "]");
        }
    }

    private void recordCacheUpdate(final HttpContext context) {
        cacheUpdates.getAndIncrement();
        setResponseStatus(context, CacheResponseStatus.VALIDATED);
    }

    private void flushEntriesInvalidatedByRequest(final HttpHost target, final HttpRequest request) {
        try {
            responseCache.flushInvalidatedCacheEntriesFor(target, request);
        } catch (final IOException ioe) {
            log.warn("Unable to flush invalidated entries from cache", ioe);
        }
    }

    private SimpleHttpResponse generateCachedResponse(
            final HttpRequest request,
            final HttpContext context,
            final HttpCacheEntry entry,
            final Date now) throws IOException {
        final SimpleHttpResponse cachedResponse;
        if (request.containsHeader(HeaderConstants.IF_NONE_MATCH)
                || request.containsHeader(HeaderConstants.IF_MODIFIED_SINCE)) {
            cachedResponse = responseGenerator.generateNotModifiedResponse(entry);
        } else {
            cachedResponse = responseGenerator.generateResponse(request, entry);
        }
        setResponseStatus(context, CacheResponseStatus.CACHE_HIT);
        if (validityPolicy.getStalenessSecs(entry, now) > 0L) {
            cachedResponse.addHeader(HeaderConstants.WARNING,"110 localhost \"Response is stale\"");
        }
        return cachedResponse;
    }

    private SimpleHttpResponse handleRevalidationFailure(
            final HttpRequest request,
            final HttpContext context,
            final HttpCacheEntry entry,
            final Date now) throws IOException {
        if (staleResponseNotAllowed(request, entry, now)) {
            return generateGatewayTimeout(context);
        } else {
            return unvalidatedCacheHit(request, context, entry);
        }
    }

    private SimpleHttpResponse generateGatewayTimeout(
            final HttpContext context) {
        setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
        return SimpleHttpResponse.create(HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout");
    }

    private SimpleHttpResponse unvalidatedCacheHit(
            final HttpRequest request,
            final HttpContext context,
            final HttpCacheEntry entry) throws IOException {
        final SimpleHttpResponse cachedResponse = responseGenerator.generateResponse(request, entry);
        setResponseStatus(context, CacheResponseStatus.CACHE_HIT);
        cachedResponse.addHeader(HeaderConstants.WARNING, "111 localhost \"Revalidation failed\"");
        return cachedResponse;
    }

    private boolean staleResponseNotAllowed(final HttpRequest request, final HttpCacheEntry entry, final Date now) {
        return validityPolicy.mustRevalidate(entry)
            || (cacheConfig.isSharedCache() && validityPolicy.proxyRevalidate(entry))
            || explicitFreshnessRequest(request, entry, now);
    }

    private boolean mayCallBackend(final HttpRequest request) {
        final Iterator<HeaderElement> it = MessageSupport.iterate(request, HeaderConstants.CACHE_CONTROL);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            if ("only-if-cached".equals(elt.getName())) {
                log.trace("Request marked only-if-cached");
                return false;
            }
        }
        return true;
    }

    private boolean explicitFreshnessRequest(final HttpRequest request, final HttpCacheEntry entry, final Date now) {
        final Iterator<HeaderElement> it = MessageSupport.iterate(request, HeaderConstants.CACHE_CONTROL);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            if (HeaderConstants.CACHE_CONTROL_MAX_STALE.equals(elt.getName())) {
                try {
                    final int maxstale = Integer.parseInt(elt.getValue());
                    final long age = validityPolicy.getCurrentAgeSecs(entry, now);
                    final long lifetime = validityPolicy.getFreshnessLifetimeSecs(entry);
                    if (age - lifetime > maxstale) {
                        return true;
                    }
                } catch (final NumberFormatException nfe) {
                    return true;
                }
            } else if (HeaderConstants.CACHE_CONTROL_MIN_FRESH.equals(elt.getName())
                    || HeaderConstants.CACHE_CONTROL_MAX_AGE.equals(elt.getName())) {
                return true;
            }
        }
        return false;
    }

    private String generateViaHeader(final HttpMessage msg) {

        if (msg.getVersion() == null) {
            msg.setVersion(HttpVersion.DEFAULT);
        }
        final ProtocolVersion pv = msg.getVersion();
        final String existingEntry = viaHeaders.get(msg.getVersion());
        if (existingEntry != null) {
            return existingEntry;
        }

        final VersionInfo vi = VersionInfo.loadVersionInfo("org.apache.hc.client5", getClass().getClassLoader());
        final String release = (vi != null) ? vi.getRelease() : VersionInfo.UNAVAILABLE;

        final String value;
        final int major = pv.getMajor();
        final int minor = pv.getMinor();
        if ("http".equalsIgnoreCase(pv.getProtocol())) {
            value = String.format("%d.%d localhost (Apache-HttpClient/%s (cache))", major, minor,
                    release);
        } else {
            value = String.format("%s/%d.%d localhost (Apache-HttpClient/%s (cache))", pv.getProtocol(), major,
                    minor, release);
        }
        viaHeaders.put(pv, value);

        return value;
    }

    private void setResponseStatus(final HttpContext context, final CacheResponseStatus value) {
        if (context != null) {
            context.setAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS, value);
        }
    }

    /**
     * Reports whether this {@code CachingHttpClient} implementation
     * supports byte-range requests as specified by the {@code Range}
     * and {@code Content-Range} headers.
     * @return {@code true} if byte-range requests are supported
     */
    public boolean supportsRangeAndContentRangeHeaders() {
        return SUPPORTS_RANGE_AND_CONTENT_RANGE_HEADERS;
    }

    Date getCurrentDate() {
        return new Date();
    }

    boolean clientRequestsOurOptions(final HttpRequest request) {
        if (!HeaderConstants.OPTIONS_METHOD.equals(request.getMethod())) {
            return false;
        }

        if (!"*".equals(request.getRequestUri())) {
            return false;
        }

        if (!"0".equals(request.getFirstHeader(HeaderConstants.MAX_FORWARDS).getValue())) {
            return false;
        }

        return true;
    }

    private boolean revalidationResponseIsTooOld(final HttpResponse backendResponse,
            final HttpCacheEntry cacheEntry) {
        final Header entryDateHeader = cacheEntry.getFirstHeader(HttpHeaders.DATE);
        final Header responseDateHeader = backendResponse.getFirstHeader(HttpHeaders.DATE);
        if (entryDateHeader != null && responseDateHeader != null) {
            final Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
            final Date respDate = DateUtils.parseDate(responseDateHeader.getValue());
            if (entryDate == null || respDate == null) {
                // either backend response or cached entry did not have a valid
                // Date header, so we can't tell if they are out of order
                // according to the origin clock; thus we can skip the
                // unconditional retry recommended in 13.2.6 of RFC 2616.
                return false;
            }
            if (respDate.before(entryDate)) {
                return true;
            }
        }
        return false;
    }

    private void tryToUpdateVariantMap(
            final HttpHost target,
            final HttpRequest request,
            final Variant matchingVariant) {
        try {
            responseCache.reuseVariantEntryFor(target, request, matchingVariant);
        } catch (final IOException ioe) {
            log.warn("Could not processChallenge cache entry to reuse variant", ioe);
        }
    }

    private boolean shouldSendNotModifiedResponse(final HttpRequest request, final HttpCacheEntry responseEntry) {
        return (suitabilityChecker.isConditional(request)
                && suitabilityChecker.allConditionalsMatch(request, responseEntry, new Date()));
    }

    private boolean staleIfErrorAppliesTo(final int statusCode) {
        return statusCode == HttpStatus.SC_INTERNAL_SERVER_ERROR
                || statusCode == HttpStatus.SC_BAD_GATEWAY
                || statusCode == HttpStatus.SC_SERVICE_UNAVAILABLE
                || statusCode == HttpStatus.SC_GATEWAY_TIMEOUT;
    }

    boolean isIncompleteResponse(final HttpResponse resp, final ByteArrayBuffer buffer) {
        if (buffer == null) {
            return false;
        }
        final int status = resp.getCode();
        if (status != HttpStatus.SC_OK && status != HttpStatus.SC_PARTIAL_CONTENT) {
            return false;
        }
        final Header hdr = resp.getFirstHeader(HttpHeaders.CONTENT_LENGTH);
        if (hdr == null) {
            return false;
        }
        final int contentLength;
        try {
            contentLength = Integer.parseInt(hdr.getValue());
        } catch (final NumberFormatException nfe) {
            return false;
        }
        return buffer.length() < contentLength;
    }

    /**
     * For 304 Not modified responses, adds a "Last-Modified" header with the
     * value of the "If-Modified-Since" header passed in the request. This
     * header is required to be able to reuse match the cache entry for
     * subsequent requests but as defined in http specifications it is not
     * included in 304 responses by backend servers. This header will not be
     * included in the resulting response.
     */
    private void storeRequestIfModifiedSinceFor304Response(
            final HttpRequest request, final HttpResponse backendResponse) {
        if (backendResponse.getCode() == HttpStatus.SC_NOT_MODIFIED) {
            final Header h = request.getFirstHeader("If-Modified-Since");
            if (h != null) {
                backendResponse.addHeader("Last-Modified", h.getValue());
            }
        }
    }

    private boolean alreadyHaveNewerCacheEntry(
            final HttpHost target, final HttpRequest request, final HttpResponse backendResponse) {
        HttpCacheEntry existing = null;
        try {
            existing = responseCache.getCacheEntry(target, request);
        } catch (final IOException ioe) {
            // nop
        }
        if (existing == null) {
            return false;
        }
        final Header entryDateHeader = existing.getFirstHeader(HttpHeaders.DATE);
        if (entryDateHeader == null) {
            return false;
        }
        final Header responseDateHeader = backendResponse.getFirstHeader(HttpHeaders.DATE);
        if (responseDateHeader == null) {
            return false;
        }
        final Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
        final Date responseDate = DateUtils.parseDate(responseDateHeader.getValue());
        if (entryDate == null || responseDate == null) {
            return false;
        }
        return responseDate.before(entryDate);
    }

}

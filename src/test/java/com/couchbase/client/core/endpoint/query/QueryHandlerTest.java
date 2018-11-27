/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.core.endpoint.query;

import com.couchbase.client.core.CoreContext;
import com.couchbase.client.core.RequestCancelledException;
import com.couchbase.client.core.ResponseEvent;
import com.couchbase.client.core.endpoint.AbstractEndpoint;
import com.couchbase.client.core.endpoint.AbstractGenericHandler;
import com.couchbase.client.core.endpoint.DecodingState;
import com.couchbase.client.core.env.CoreEnvironment;
import com.couchbase.client.core.env.DefaultCoreEnvironment;
import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.core.message.CouchbaseMessage;
import com.couchbase.client.core.message.CouchbaseRequest;
import com.couchbase.client.core.message.CouchbaseResponse;
import com.couchbase.client.core.message.ResponseStatus;
import com.couchbase.client.core.message.query.GenericQueryRequest;
import com.couchbase.client.core.message.query.GenericQueryResponse;
import com.couchbase.client.core.message.query.QueryRequest;
import com.couchbase.client.core.message.query.RawQueryRequest;
import com.couchbase.client.core.message.query.RawQueryResponse;
import com.couchbase.client.core.retry.FailFastRetryStrategy;
import com.couchbase.client.core.util.Resources;
import com.couchbase.client.core.utils.DefaultObjectMapper;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.assertj.core.api.SoftAssertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import rx.Subscriber;
import rx.functions.Action1;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;
import rx.subjects.AsyncSubject;
import rx.subjects.Subject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the correct functionality of the {@link QueryHandler}.
 *
 * @author Michael Nitschinger
 * @since 1.0
 */
@Ignore("V2 Query Handler is the default")
public class QueryHandlerTest {

    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(QueryHandlerTest.class);

    protected static final String FAKE_REQUESTID = "1234test-7802-4fc2-acd6-dfcd1c05a288";
    protected static final String FAKE_CLIENTID = "1234567890123456789012345678901234567890123456789012345678901234";
    protected static final String FAKE_SIGNATURE = "{\"*\":\"*\"}";

    protected Queue<QueryRequest> queue;
    protected EmbeddedChannel channel;
    protected Disruptor<ResponseEvent> responseBuffer;
    protected RingBuffer<ResponseEvent> responseRingBuffer;
    protected List<CouchbaseMessage> firedEvents;
    protected CountDownLatch latch;
    protected AbstractEndpoint endpoint;
    protected AbstractGenericHandler handler;

    protected void commonSetup() {
        responseBuffer = new Disruptor<ResponseEvent>(new EventFactory<ResponseEvent>() {
            @Override
            public ResponseEvent newInstance() {
                return new ResponseEvent();
            }
        }, 1024, Executors.newCachedThreadPool());

        firedEvents = Collections.synchronizedList(new ArrayList<CouchbaseMessage>());
        latch = new CountDownLatch(1);
        responseBuffer.handleEventsWith(new EventHandler<ResponseEvent>() {
            @Override
            public void onEvent(ResponseEvent event, long sequence, boolean endOfBatch) throws Exception {
                firedEvents.add(event.getMessage());
                latch.countDown();
            }
        });
        responseRingBuffer = responseBuffer.start();

        CoreEnvironment environment = mock(CoreEnvironment.class);
        when(environment.scheduler()).thenReturn(Schedulers.computation());
        when(environment.maxRequestLifetime()).thenReturn(10000L);
        when(environment.autoreleaseAfter()).thenReturn(2000L);
        when(environment.retryStrategy()).thenReturn(FailFastRetryStrategy.INSTANCE);
        endpoint = mock(AbstractEndpoint.class);
        when(endpoint.environment()).thenReturn(environment);
        when(endpoint.context()).thenReturn(new CoreContext(environment, null, 1));
        when(environment.userAgent()).thenReturn("Couchbase Client Mock");

        queue = new ArrayDeque<QueryRequest>();
    }

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        commonSetup();
        handler = new QueryHandler(endpoint, responseRingBuffer, queue, false, false);
        channel = new EmbeddedChannel(handler);
    }

    @After
    public void clear() throws Exception {
        channel.close().awaitUninterruptibly();
        responseBuffer.shutdown();
    }

    private void assertGenericQueryRequest(GenericQueryRequest request, boolean jsonExpected) {
        channel.writeOutbound(request);
        HttpRequest outbound = (HttpRequest) channel.readOutbound();

        assertEquals(HttpMethod.POST, outbound.getMethod());
        assertEquals(HttpVersion.HTTP_1_1, outbound.getProtocolVersion());
        assertEquals("/query", outbound.getUri());
        assertTrue(outbound.headers().contains(HttpHeaders.Names.AUTHORIZATION));
        assertEquals("Couchbase Client Mock", outbound.headers().get(HttpHeaders.Names.USER_AGENT));
        assertTrue(outbound instanceof FullHttpRequest);
        assertEquals("query", ((FullHttpRequest) outbound).content().toString(CharsetUtil.UTF_8));
        if (jsonExpected) {
            assertEquals("application/json", outbound.headers().get(HttpHeaders.Names.CONTENT_TYPE));
        } else {
            assertNotEquals("application/json", outbound.headers().get(HttpHeaders.Names.CONTENT_TYPE));
        }
        assertTrue(outbound.headers().contains(HttpHeaders.Names.HOST));
    }

    @Test
    public void shouldEncodeSimpleStatementToGenericQueryRequest() {
        GenericQueryRequest request = GenericQueryRequest.simpleStatement("query", "bucket", "password");
        assertGenericQueryRequest(request, false);
    }

    @Test
    public void shouldEncodeJsonQueryToGenericQueryRequest() {
        GenericQueryRequest request = GenericQueryRequest.jsonQuery("query", "bucket", "password", "contextId");
        assertGenericQueryRequest(request, true);
    }

    /**
     *
     * @param inbound
     * @param expectedSuccess
     * @param expectedStatus
     * @param expectedRequestId
     * @param expectedClientId
     * @param expectedFinalStatus
     * @param expectedSignature
     * @param assertRows
     * @param assertErrors
     * @param metricsToCheck null to expect no metrics
     */
    protected void assertResponse(GenericQueryResponse inbound,
            boolean expectedSuccess, ResponseStatus expectedStatus,
            String expectedRequestId, String expectedClientId,
            String expectedFinalStatus, String expectedSignature,
            Action1<ByteBuf> assertRows,
            Action1<ByteBuf> assertErrors,
            Map<String, Object> metricsToCheck) {
        assertEquals(expectedSuccess, inbound.status().isSuccess());
        assertEquals(expectedStatus, inbound.status());
        assertEquals(expectedRequestId, inbound.requestId());
        assertEquals(expectedClientId, inbound.clientRequestId());
        assertNotNull(inbound.request());

        assertEquals(expectedFinalStatus, inbound.queryStatus().timeout(1, TimeUnit.SECONDS).toBlocking().single());

        inbound.rows().timeout(5, TimeUnit.SECONDS).toBlocking()
               .forEach(assertRows);

        List<ByteBuf> metricList = inbound.info().timeout(1, TimeUnit.SECONDS).toList().toBlocking().single();
        if (metricsToCheck == null) {
            assertEquals(0, metricList.size());
        } else {
            assertEquals(1, metricList.size());
            String metricsJson = metricList.get(0).toString(CharsetUtil.UTF_8);
            metricList.get(0).release();
            try {
                Map<String, Object> metrics = DefaultObjectMapper.readValueAsMap(metricsJson);
                assertEquals(7, metrics.size());

                for (Map.Entry<String, Object> entry : metricsToCheck.entrySet()) {
                    assertNotNull(metrics.get(entry.getKey()));
                    assertEquals(entry.getKey(), entry.getValue(), metrics.get(entry.getKey()));
                }
            } catch (IOException e) {
                fail(e.toString());
            }
        }

        inbound.errors().timeout(1, TimeUnit.SECONDS).toBlocking()
               .forEach(assertErrors);

        List<ByteBuf> signatureList = inbound.signature().timeout(1, TimeUnit.SECONDS).toList().toBlocking().single();
        if (expectedSignature != null) {
            assertEquals(1, signatureList.size());
            String signatureJson = signatureList.get(0).toString(CharsetUtil.UTF_8);
            assertNotNull(signatureJson);
            assertEquals(expectedSignature, signatureJson.replaceAll("\\s", ""));
            ReferenceCountUtil.releaseLater(signatureList.get(0));
        } else {
            assertEquals(0, signatureList.size());
        }
        assertEquals(0, inbound.profileInfo().timeout(1, TimeUnit.SECONDS).toList().toBlocking().single().size());
    }

    protected static Map<String, Object> expectedMetricsCounts(int expectedErrors, int expectedResults) {
        Map<String, Object> result = new HashMap<String, Object>(2);
        result.put("errorCount", expectedErrors);
        result.put("resultCount", expectedResults);
        return result;
    }

    @Test
    public void shouldDecodeErrorResponse() throws Exception {
        String response = Resources.read("parse_error.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        assertResponse(inbound, false, ResponseStatus.FAILURE, FAKE_REQUESTID, FAKE_CLIENTID, "fatal", null,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map<String, Object> error = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(5, error.size());
                            assertEquals(new Integer(4100), error.get("code"));
                            assertEquals(Boolean.FALSE, error.get("temp"));
                            assertEquals("Parse Error", error.get("msg"));
                        } catch (IOException e) {
                            fail();
                        }
                        ReferenceCountUtil.releaseLater(buf);
                    }
                },
                expectedMetricsCounts(1, 0)
        );
    }

    @Test
    public void shouldDecodeChunkedErrorResponse() throws Exception {
        String response = Resources.read("parse_error.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk1 = new DefaultHttpContent(Unpooled.copiedBuffer(response.substring(0, 223), CharsetUtil.UTF_8));
        HttpContent responseChunk2 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(223), CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk1, responseChunk2);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        assertResponse(inbound, false, ResponseStatus.FAILURE, FAKE_REQUESTID, FAKE_CLIENTID, "fatal", null,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        ReferenceCountUtil.releaseLater(byteBuf);
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String response = buf.toString(CharsetUtil.UTF_8);
                        ReferenceCountUtil.releaseLater(buf);
                        try {
                            Map<String, Object> error = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(5, error.size());
                            assertEquals(new Integer(4100), error.get("code"));
                            assertEquals(Boolean.FALSE, error.get("temp"));
                            assertEquals("Parse Error", error.get("msg"));
                        } catch (IOException e) {
                            fail();
                        }
                    }
                },
                expectedMetricsCounts(1, 0)
        );
    }

    @Test
    public void shouldDecodeEmptySuccessResponse() throws Exception {
        String response = Resources.read("success_0.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 0)
        );
    }

    @Test
    public void shouldDecodeOneRowResponse() throws Exception {
        String response = Resources.read("success_1.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        try {
                            Map<String, Object> found = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            fail("no result expected");
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String error = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        fail("no error expected, got " + error);
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeNRowResponse() throws Exception {
        String response = Resources.read("success_5.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger found = new AtomicInteger(0);
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf row) {
                        found.incrementAndGet();
                        String content = row.toString(CharsetUtil.UTF_8);
                        row.release();
                        assertNotNull(content);
                        assertTrue(!content.isEmpty());
                        try {
                            Map<String, Object> decoded = DefaultObjectMapper.readValueAsMap(content);
                            assertTrue(decoded.size() > 0);
                            assertTrue(decoded.containsKey("name"));
                        } catch(Exception e) {
                            assertTrue(false);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 5)
        );
        assertEquals(5, found.get());
    }

    @Test
    public void shouldDecodeNRowResponseChunked() throws Exception {
        String response = Resources.read("success_5.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk1 = new DefaultHttpContent(Unpooled.copiedBuffer(response.substring(0, 300),
            CharsetUtil.UTF_8));
        HttpContent responseChunk2 = new DefaultHttpContent(Unpooled.copiedBuffer(response.substring(300, 950),
            CharsetUtil.UTF_8));
        HttpContent responseChunk3 = new DefaultHttpContent(Unpooled.copiedBuffer(response.substring(950, 1345),
            CharsetUtil.UTF_8));
        HttpContent responseChunk4 = new DefaultHttpContent(Unpooled.copiedBuffer(response.substring(1345, 3000),
            CharsetUtil.UTF_8));
        HttpContent responseChunk5 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(3000),
            CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk1, responseChunk2, responseChunk3, responseChunk4,
            responseChunk5);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger found = new AtomicInteger(0);
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        found.incrementAndGet();
                        String content = byteBuf.toString(CharsetUtil.UTF_8);
                        byteBuf.release();
                        assertNotNull(content);
                        assertTrue(!content.isEmpty());
                        try {
                            Map<String, Object> decoded = DefaultObjectMapper.readValueAsMap(content);
                            assertTrue(decoded.size() > 0);
                            assertTrue(decoded.containsKey("name"));
                        } catch(Exception e) {
                            assertTrue(false);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 5)
        );
        assertEquals(5, found.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithQuotesInClientIdAndResults() throws Exception {
        String expectedClientIdWithQuotes = "ThisIsA\\\"Client\\\"Id";

        String response = Resources.read("with_escaped_quotes.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, expectedClientIdWithQuotes, "success",
                FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        ReferenceCountUtil.releaseLater(buf);
                        try {
                            Map<String, Object> found = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                            //TODO check the quote in the result
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithShortClientID() throws Exception {
        String response = Resources.read("short_client_id.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, "123456789", "success", FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        ReferenceCountUtil.releaseLater(buf);
                        try {
                            Map<String, Object> found = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithNoClientID() throws Exception {
        String response = Resources.read("no_client_id.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, "", "success", FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map<String, Object> found = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                        ReferenceCountUtil.releaseLater(buf);
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithoutPrettyPrint() throws Exception {
        String response = Resources.read("no_pretty.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        try {
                            Map<String, Object> found = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldGroupErrorsAndWarnings() throws InterruptedException {
        String response = Resources.read("errors_and_warnings.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        Map<String, Object> expectedMetrics = expectedMetricsCounts(1, 0);
        expectedMetrics.put("warningCount", 1);

        final AtomicInteger count = new AtomicInteger(0);
        assertResponse(inbound, false, ResponseStatus.FAILURE, FAKE_REQUESTID, FAKE_CLIENTID, "fatal", null,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        count.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        try {
                            Map<String, Object> error = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals(5, error.size());
                            if (count.get() == 1) {
                                assertEquals(new Integer(4100), error.get("code"));
                                assertEquals(Boolean.FALSE, error.get("temp"));
                                assertEquals("Parse Error", error.get("msg"));
                            } else if (count.get() == 2) {
                                assertEquals(3, error.get("sev"));
                                assertEquals(201, error.get("code"));
                                assertEquals(Boolean.TRUE, error.get("temp"));
                                assertEquals("Nothing to do", error.get("msg"));
                                assertEquals("nothingToDo", error.get("name"));
                            }
                        } catch (IOException e) {
                            fail();
                        }
                    }
                },
                expectedMetrics
        );
        assertEquals(2, count.get());
    }

    @Test
    @Ignore("QueryHandler v1 is not used")
    public void shouldFireKeepAlive() throws Exception {
        final AtomicInteger keepAliveEventCounter = new AtomicInteger();
        final AtomicReference<ChannelHandlerContext> ctxRef = new AtomicReference();

        QueryHandler testHandler = new QueryHandler(endpoint, responseRingBuffer, queue, false, false) {
            @Override
            public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                super.channelRegistered(ctx);
                ctxRef.compareAndSet(null, ctx);
            }

            @Override
            protected void onKeepAliveFired(ChannelHandlerContext ctx, CouchbaseRequest keepAliveRequest) {
                assertEquals(1, keepAliveEventCounter.incrementAndGet());
            }

            @Override
            protected void onKeepAliveResponse(ChannelHandlerContext ctx, CouchbaseResponse keepAliveResponse) {
                assertEquals(2, keepAliveEventCounter.incrementAndGet());
            }

            @Override
            protected CoreEnvironment env() {
                return DefaultCoreEnvironment.builder()
                        .continuousKeepAliveEnabled(false).build();
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(testHandler);

        //test idle event triggers a query keepAlive request and hook is called
        testHandler.userEventTriggered(ctxRef.get(), IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);

        assertEquals(1, keepAliveEventCounter.get());
        assertTrue(queue.peek() instanceof QueryHandler.KeepAliveRequest);
        QueryHandler.KeepAliveRequest keepAliveRequest = (QueryHandler.KeepAliveRequest) queue.peek();

        //test responding to the request with http response is interpreted into a KeepAliveResponse and hook is called
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        LastHttpContent responseEnd = new DefaultLastHttpContent();
        channel.writeInbound(response, responseEnd);
        QueryHandler.KeepAliveResponse keepAliveResponse = keepAliveRequest.observable()
                .cast(QueryHandler.KeepAliveResponse.class)
                .timeout(1, TimeUnit.SECONDS).toBlocking().single();

        channel.pipeline().remove(testHandler);
        assertEquals(2, keepAliveEventCounter.get());
        assertEquals(ResponseStatus.NOT_EXISTS, keepAliveResponse.status());
        assertEquals(0, responseEnd.refCnt());
    }

    @Test
    public void shouldDecodeNRowResponseSmallyChunked() throws Exception {
        String response = Resources.read("chunked.json", this.getClass());
        String[] chunks = new String[] {
                response.substring(0, 48),
                response.substring(48, 84),
                response.substring(84, 144),
                response.substring(144, 258),
                response.substring(258, 438),
                response.substring(438, 564),
                response.substring(564, 702),
                response.substring(702, 740),
                response.substring(740)
        };

        StringBuilder sb = new StringBuilder("Chunks:");
        for (String chunk : chunks) {
            sb.append("\n>").append(chunk);
        }
        LOGGER.info(sb.toString());

        shouldDecodeChunked(true, chunks);
    }

    @Test
    public void shouldDecodeChunkedResponseSplitAtEveryPosition() throws Throwable {
        String response = Resources.read("chunked.json", this.getClass());
        for (int i = 1; i < response.length() - 1; i++) {
            String chunk1 = response.substring(0, i);
            String chunk2 = response.substring(i);

            try {
                shouldDecodeChunked(true, chunk1, chunk2);
            } catch (Throwable t) {
                LOGGER.info("Test failed in decoding response with chunk at position " + i);
                throw t;
            }
        }
    }

    @Test
    public void shouldDecodeChunkedResponseSplitAtEveryPositionNoMetrics() throws Throwable {
        String response = Resources.read("chunkedNoMetrics.json", this.getClass());
        for (int i = 1; i < response.length() - 1; i++) {
            String chunk1 = response.substring(0, i);
            String chunk2 = response.substring(i);

            try {
                shouldDecodeChunked(false, chunk1, chunk2);
            } catch (Throwable t) {
                LOGGER.info("Test failed in decoding response with chunk and no metrics at position " + i);
                throw t;
            }
        }
    }

    private void shouldDecodeChunked(boolean metrics, String... chunks) throws Exception {
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        Object[] httpChunks = new Object[chunks.length + 1];
        httpChunks[0] = responseHeader;
        for (int i = 1; i <= chunks.length; i++) {
            String chunk = chunks[i - 1];
            if (i == chunks.length) {
                httpChunks[i] = new DefaultLastHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            } else {
                httpChunks[i] = new DefaultHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            }
        }

        Subject<CouchbaseResponse,CouchbaseResponse> obs = AsyncSubject.create();
        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        when(requestMock.observable()).thenReturn(obs);
        queue.add(requestMock);
        channel.writeInbound(httpChunks);
        GenericQueryResponse inbound = (GenericQueryResponse) obs.timeout(1, TimeUnit.SECONDS).toBlocking().last();
        Map<String, Object> expectedMetrics;
        if (metrics) {
            expectedMetrics = expectedMetricsCounts(5678, 1234); //these are the numbers parsed from metrics object, not real count
        } else {
            expectedMetrics = null;
        }

        final AtomicInteger found = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, "123456\\\"78901234567890", "success",
                "{\"horseName\":\"json\"}",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        found.incrementAndGet();
                        String content = byteBuf.toString(CharsetUtil.UTF_8);
                        byteBuf.release();
                        assertNotNull(content);
                        assertTrue(!content.isEmpty());
                        try {
                            Map<String, Object> decoded = DefaultObjectMapper.readValueAsMap(content);
                            assertTrue(decoded.size() > 0);
                            assertTrue(decoded.containsKey("horseName"));
                        } catch (Exception e) {
                            fail(e.toString());
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        errors.incrementAndGet();
                    }
                },
                expectedMetrics
        );
        assertEquals(5, found.get());
        assertEquals(4, errors.get());
    }

    @Test
    public void shouldDecodeRawJsonResults() throws Exception {
        String response = Resources.read("raw_success_8.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final List<String> items = Collections.synchronizedList(new ArrayList<String>(11));
        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", "\"json\"",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String item = buf.toString(CharsetUtil.UTF_8).trim();
                        invokeCounter1.incrementAndGet();
                        items.add(item);
                        buf.release();
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        fail("no error expected");
                    }
                },
                //no metrics in this json sample
                expectedMetricsCounts(0, 8)
        );
        List<String> expectedItems = Arrays.asList("\"usertable:userAA\"", "\"usertable:user1\"",
                "\"usertable:user,2]\"", "null", "\"usertable:user3\"","\"u,s,e,r,t,a,\\\"b,l,e:userBBB\\\\\"","123","\"usertable:user4\"");
        assertEquals(8, invokeCounter1.get());
        assertEquals(expectedItems, items);
    }

    @Test
    public void shouldDecodeChunkedResponseSplitAtEveryPositionWithRaw() throws Throwable {
        String response = Resources.read("raw_success_8.json", this.getClass());
        for (int i = 1; i < response.length() - 1; i++) {
            String chunk1 = response.substring(0, i);
            String chunk2 = response.substring(i);

            try {
                shouldDecodeChunkedWithRaw(8, 0, chunk1, chunk2);
            } catch (Throwable t) {
                LOGGER.info("Test failed in decoding response with raw, chunked at position " + i);
                throw t;
            }
        }
    }

    @Test
    public void shouldDecodeRawJsonWithOneResult() throws Exception {
        String response = Resources.read("raw_success_1.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final List<String> items = Collections.synchronizedList(new ArrayList<String>(11));
        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", "\"json\"",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String item = buf.toString(CharsetUtil.UTF_8).trim();
                        System.out.println("item #" + invokeCounter1.incrementAndGet() + " = " + item);
                        items.add(item);
                        buf.release();
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        List<String> expectedItems = Arrays.asList("\"u,s,e,r,t,a,\\\"b,l,e:userBBB\\\\\"");
        assertEquals(1, invokeCounter1.get());
        assertEquals(expectedItems, items);
    }

    @Test
    public void shouldDecodeSuccess1NoMetrics() throws Exception {
        String response = Resources.read("success_1_noMetrics.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final List<String> items = Collections.synchronizedList(new ArrayList<String>(11));
        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, "ff226b49-9d4c-415b-8428-263cb080e184", "", "success", "{\"*\":\"*\"}",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String item = buf.toString(CharsetUtil.UTF_8).trim();
                        System.out.println("item #" + invokeCounter1.incrementAndGet() + " = " + item);
                        items.add(item);
                        buf.release();
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        fail("no error expected");
                    }
                },
                null
        );
        assertEquals(1, invokeCounter1.get());
        assertEquals("{\"adHoc_N1qlQuery492841478131758\":{\"item\":\"value\"}}", items.get(0).replaceAll("\\s", ""));
    }

    private void shouldDecodeChunkedWithRaw(final int expectedResults, final int expectedErrors, String... chunks) throws Exception {
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        Object[] httpChunks = new Object[chunks.length + 1];
        httpChunks[0] = responseHeader;
        for (int i = 1; i <= chunks.length; i++) {
            String chunk = chunks[i - 1];
            if (i == chunks.length) {
                httpChunks[i] = new DefaultLastHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            } else {
                httpChunks[i] = new DefaultHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            }
        }

        Subject<CouchbaseResponse,CouchbaseResponse> obs = AsyncSubject.create();
        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        when(requestMock.observable()).thenReturn(obs);
        queue.add(requestMock);
        channel.writeInbound(httpChunks);
        GenericQueryResponse inbound = (GenericQueryResponse) obs.timeout(1, TimeUnit.SECONDS).toBlocking().last();

        final AtomicInteger found = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, "1234567890123456789012345678901234567890123456789012345678901234", "success",
                "\"json\"",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        found.incrementAndGet();
                        String content = byteBuf.toString(CharsetUtil.UTF_8);
                        byteBuf.release();
                        assertNotNull(content);
                        assertTrue(!content.isEmpty());
                        try {
                            Object object = DefaultObjectMapper.readValue(content, Object.class);
                            boolean expected = object instanceof Integer || object == null ||
                                    (object instanceof String && ((String) object).startsWith("usertable")) ||
                                    (object instanceof String && ((String) object).startsWith("u,s,e,r"));
                            assertTrue(expected);

                        } catch (Exception e) {
                            e.printStackTrace();
                            fail();
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        errors.incrementAndGet();
                    }
                },
                expectedMetricsCounts(expectedErrors, expectedResults) //these are the numbers parsed from metrics object, not real count
        );
        assertEquals(expectedResults, found.get());
        assertEquals(expectedErrors, errors.get());
    }

    @Test
    public void shouldDecodeSimpleStringAsSignature() throws Exception {
        String response = Resources.read("signature_simple_string.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", "\"json\"",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String item = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        fail("no result expected, got " + item);
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        fail("no error expected");
                    }
                },
                //no metrics in this json sample
                expectedMetricsCounts(0, 1)
        );
        assertEquals(0, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeNullAsSignature() throws Exception {
        String response = Resources.read("signature_null.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", "null",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String item = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        fail("no result expected, got " + item);
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        fail("no error expected");
                    }
                },
                //no metrics in this json sample
                expectedMetricsCounts(0, 1)
        );
        assertEquals(0, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeBooleanAsSignature() throws Exception {
        String response = Resources.read("signature_scalar.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success", "true",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String item = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        fail("no result expected, got " + item);
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        fail("no error expected");
                    }
                },
                //no metrics in this json sample
                expectedMetricsCounts(0, 1)
        );
        assertEquals(0, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeArrayAsSignature() throws Exception {
        String response = Resources.read("signature_array.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                "[\"json\",\"array\",[\"sub\",true]]",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String item = buf.toString(CharsetUtil.UTF_8);
                        buf.release();
                        fail("no result expected, got " + item);
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        fail("no error expected");
                    }
                },
                //no metrics in this json sample
                expectedMetricsCounts(0, 1)
        );
        assertEquals(0, invokeCounter1.get());
    }

    /**
     * See JVMCBC-239.
     */
    @Test
    public void testEarlyChunkInSignatureDoesntFail() throws Exception {
        String chunk1 = "{\n" +
                "    \"requestID\": \"7cde0ed9-1844-436d-85b2-a7b9cd12361c\",\n" +
                "    \"clientContextID\": \"sdkd-java\",\n" +
                "    \"signature\": {\n" +
                "  ";
        String chunk2 = "      \"*\": \"*\"\n" +
                "    },\n" +
                "    \"results\": [\n" +
                "        {\n" +
                "            \"default\": {\n" +
                "                \"id\": 375,\n" +
                "                \"tag\": \"n1ql\",\n" +
                "                \"type\": \"n1qldoc\"\n" +
                "            }\n" +
                "        }\n" +
                "    ],\n" +
                "    \"status\": \"success\",\n" +
                "    \"metrics\": {\n" +
                " ";
        String chunk3 = "       \"elapsedTime\": \"1m18.410321814s\",\n" +
                "        \"executionTime\": \"1m18.410092882s\",\n" +
                "        \"resultCount\": 1,\n" +
                "        \"resultSize\": 100,\n" +
                "        \"mutationCount\": 0,\n" +
                "        \"errorCount\": 0,\n" +
                "        \"warningCount\": 0\n" +
                "    }\n" +
                "}";

        String[] chunks = new String[] { chunk1, chunk2, chunk3 };
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        Object[] httpChunks = new Object[chunks.length + 1];
        httpChunks[0] = responseHeader;
        for (int i = 1; i <= chunks.length; i++) {
            String chunk = chunks[i - 1];
            if (i == chunks.length) {
                httpChunks[i] = new DefaultLastHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            } else {
                httpChunks[i] = new DefaultHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            }
        }

        Subject<CouchbaseResponse,CouchbaseResponse> obs = AsyncSubject.create();
        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        when(requestMock.observable()).thenReturn(obs);
        queue.add(requestMock);
        channel.writeInbound(httpChunks);
        GenericQueryResponse inbound = (GenericQueryResponse) obs.timeout(1, TimeUnit.SECONDS).toBlocking().last();

        final AtomicInteger found = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        assertResponse(inbound, true, ResponseStatus.SUCCESS, "7cde0ed9-1844-436d-85b2-a7b9cd12361c", "sdkd-java", "success",
                "{\"*\":\"*\"}",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        found.incrementAndGet();
                        String content = byteBuf.toString(CharsetUtil.UTF_8);
                        byteBuf.release();
                        assertNotNull(content);
                        assertTrue(!content.isEmpty());
                        try {
                            Map<String, Object> decoded = DefaultObjectMapper.readValueAsMap(content);
                            assertTrue(decoded.size() > 0);
                            assertTrue(decoded.containsKey("default"));
                        } catch (Exception e) {
                            assertTrue(false);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        buf.release();
                        errors.incrementAndGet();
                    }
                },
                expectedMetricsCounts(0, 1) //these are the numbers parsed from metrics object, not real count
        );
        assertEquals(1, found.get());
        assertEquals(0, errors.get());
    }

    @Test
    public void testBigChunkedResponseWithEscapedBackslashInRowObject() throws Exception {
        String response = Resources.read("chunkedResponseWithDoubleBackslashes.txt", this.getClass());
        String[] chunks = response.split("(?m)^[0-9a-f]+");

        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        responseHeader.headers().add("Transfer-Encoding", "chunked");
        responseHeader.headers().add("Content-Type", "application/json; version=1.0.0");
        Object[] httpChunks = new Object[chunks.length];
        httpChunks[0] = responseHeader;
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            if (i == chunks.length - 1) {
                httpChunks[i] = new DefaultLastHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            } else {
                httpChunks[i] = new DefaultHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            }
        }

        Subject<CouchbaseResponse,CouchbaseResponse> obs = AsyncSubject.create();
        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        when(requestMock.observable()).thenReturn(obs);
        queue.add(requestMock);
        channel.writeInbound(httpChunks);
        GenericQueryResponse inbound = (GenericQueryResponse) obs.timeout(1, TimeUnit.SECONDS).toBlocking().last();

        final AtomicInteger found = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        inbound.rows().timeout(5, TimeUnit.SECONDS).toBlocking().forEach(new Action1<ByteBuf>() {
            @Override
            public void call(ByteBuf byteBuf) {
                int rowNumber = found.incrementAndGet();
                String content = byteBuf.toString(CharsetUtil.UTF_8);
                byteBuf.release();
                assertNotNull(content);
                assertFalse(content.isEmpty());
            }
        });

        inbound.errors().timeout(5, TimeUnit.SECONDS).toBlocking().forEach(new Action1<ByteBuf>() {
            @Override
            public void call(ByteBuf buf) {
                buf.release();
                errors.incrementAndGet();
            }
        });

        //ignore signature
        ReferenceCountUtil.release(inbound.signature().timeout(5, TimeUnit.SECONDS).toBlocking().single());

        String status = inbound.queryStatus().timeout(5, TimeUnit.SECONDS).toBlocking().single();

        List<ByteBuf> metricList = inbound.info().timeout(1, TimeUnit.SECONDS).toList().toBlocking().single();
        assertEquals(1, metricList.size());
        ByteBuf metricsBuf = metricList.get(0);
        ReferenceCountUtil.releaseLater(metricsBuf);
        Map<String, Object> metrics = DefaultObjectMapper.readValueAsMap(metricsBuf.toString(CharsetUtil.UTF_8));

        assertEquals("success", status);

        assertEquals(5, found.get());
        assertEquals(0, errors.get());

        assertEquals(found.get(), metrics.get("resultCount"));
    }

    @Test
    public void shouldDecodeRawQueryResponseAsSingleJson() throws Exception {
        String response = Resources.read("chunked.json", this.getClass());
        String[] chunks = new String[] {
                response.substring(0, 48),
                response.substring(48, 84),
                response.substring(84, 144),
                response.substring(144, 258),
                response.substring(258, 438),
                response.substring(438, 564),
                response.substring(564, 702),
                response.substring(702, 740),
                response.substring(740)
        };

        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        Object[] httpChunks = new Object[chunks.length + 1];
        httpChunks[0] = responseHeader;
        for (int i = 1; i <= chunks.length; i++) {
            String chunk = chunks[i - 1];
            if (i == chunks.length) {
                httpChunks[i] = new DefaultLastHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            } else {
                httpChunks[i] = new DefaultHttpContent(Unpooled.copiedBuffer(chunk, CharsetUtil.UTF_8));
            }
        }

        Subject<CouchbaseResponse,CouchbaseResponse> obs = AsyncSubject.create();
        RawQueryRequest requestMock = mock(RawQueryRequest.class);
        when(requestMock.observable()).thenReturn(obs);
        queue.add(requestMock);
        channel.writeInbound(httpChunks);
        RawQueryResponse inbound = (RawQueryResponse) obs.timeout(1, TimeUnit.SECONDS).toBlocking().last();
        //convert the ByteBuf to String and release before asserting
        String jsonResponse = inbound.jsonResponse().toString(CharsetUtil.UTF_8);
        inbound.jsonResponse().release();

        assertNotNull(inbound);
        assertEquals(ResponseStatus.SUCCESS, inbound.status());
        assertEquals(200, inbound.httpStatusCode());
        assertEquals("OK", inbound.httpStatusMsg());

        assertEquals(response, jsonResponse);
    }

    @Test
    public void shouldDecodeRawQueryServerNotOk() throws Exception {
        int expectedCode = 400; //BAD REQUEST
        String expectedMsg = "Sorry Sir!";
        String body = "Something bad happened, and this is not JSON";

        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(expectedCode, expectedMsg));
        HttpContent responseBody = new DefaultLastHttpContent(Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));

        Subject<CouchbaseResponse,CouchbaseResponse> obs = AsyncSubject.create();
        RawQueryRequest requestMock = mock(RawQueryRequest.class);
        when(requestMock.observable()).thenReturn(obs);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseBody);
        RawQueryResponse inbound = (RawQueryResponse) obs.timeout(1, TimeUnit.SECONDS).toBlocking().last();

        //convert the ByteBuf to String and release before asserting
        String byteBufResponse = inbound.jsonResponse().toString(CharsetUtil.UTF_8);
        inbound.jsonResponse().release();

        assertNotNull(inbound);
        assertEquals(ResponseStatus.INVALID_ARGUMENTS, inbound.status());
        assertEquals(expectedCode, inbound.httpStatusCode());
        assertEquals(expectedMsg, inbound.httpStatusMsg());

        assertEquals(body, byteBufResponse); //the response still contains the body
    }


    @Test
    public void testSplitAtStatusWithEmptyResponse() {
        String chunk1 = "{\n" +
                "    \"requestID\": \"826e33cb-af29-4002-8a40-ef90915e05b1\",\n" +
                "    \"clientContextID\": \"$$637\",\n" +
                "    \"signature\": {\n" +
                "        \"doc\": \"json\"\n" +
                "    },\n" +
                "    \"results\": [\n" +
                "    ],\n" +
                "    \"sta";
        String chunk2 = "tus\": \"success\",\n" +
                "    \"metrics\": {\n" +
                "        \"elapsedTime\": \"4.69718ms\",\n" +
                "        \"executionTime\": \"4.582526ms\",\n" +
                "        \"resultCount\": 0,\n" +
                "        \"resultSize\": 0\n" +
                "    }\n" +
                "}";

        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        responseHeader.headers().add("Transfer-Encoding", "chunked");
        responseHeader.headers().add("Content-Type", "application/json; version=1.0.0");
        Object[] httpChunks = new Object[3];
        httpChunks[0] = responseHeader;
        httpChunks[1] = new DefaultHttpContent(Unpooled.copiedBuffer(chunk1, CharsetUtil.UTF_8));
        httpChunks[2] = new DefaultLastHttpContent(Unpooled.copiedBuffer(chunk2, CharsetUtil.UTF_8));

        Subject<CouchbaseResponse,CouchbaseResponse> obs = AsyncSubject.create();
        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        when(requestMock.observable()).thenReturn(obs);
        queue.add(requestMock);
        channel.writeInbound(httpChunks);
        Exception error = null;
        GenericQueryResponse inbound = null;
        try {
            inbound = (GenericQueryResponse) obs.timeout(1, TimeUnit.SECONDS).toBlocking().last();
            ReferenceCountUtil.release(inbound.info().timeout(1, TimeUnit.SECONDS).toBlocking().last());
        } catch (Exception e) {
            error = e;
        }

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(inbound).isNotNull();
        softly.assertThat(error).isNull();
        if (handler instanceof QueryHandler) {
            softly.assertThat(((QueryHandler) handler).getDecodingState()).isEqualTo(DecodingState.INITIAL);
        }
        softly.assertAll();
    }

    @Test
    public void shouldHavePipeliningDisabled() {
        Subject<CouchbaseResponse,CouchbaseResponse> obs1 = AsyncSubject.create();
        RawQueryRequest requestMock1 = mock(RawQueryRequest.class);
        when(requestMock1.query()).thenReturn("SELECT * FROM `foo`");
        when(requestMock1.bucket()).thenReturn("foo");
        when(requestMock1.username()).thenReturn("foo");
        when(requestMock1.password()).thenReturn("");
        when(requestMock1.observable()).thenReturn(obs1);
        when(requestMock1.isActive()).thenReturn(true);

        Subject<CouchbaseResponse,CouchbaseResponse> obs2 = AsyncSubject.create();
        RawQueryRequest requestMock2 = mock(RawQueryRequest.class);
        when(requestMock2.query()).thenReturn("SELECT * FROM `foo`");
        when(requestMock2.bucket()).thenReturn("foo");
        when(requestMock2.username()).thenReturn("foo");
        when(requestMock2.password()).thenReturn("");
        when(requestMock2.observable()).thenReturn(obs2);
        when(requestMock2.isActive()).thenReturn(true);

        TestSubscriber<CouchbaseResponse> t1 = TestSubscriber.create();
        TestSubscriber<CouchbaseResponse> t2 = TestSubscriber.create();

        obs1.subscribe(t1);
        obs2.subscribe(t2);

        channel.writeOutbound(requestMock1, requestMock2);

        t1.assertNotCompleted();
        t2.assertError(RequestCancelledException.class);
    }

    @Test
    public void shouldDecodeCorrectlyOnContinousEscapedCharacters() throws Exception {
        String response = Resources.read("with_multiple_escaped_quotes.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                FAKE_SIGNATURE,
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        ReferenceCountUtil.releaseLater(buf);
                        try {
                            Map<String, Object> found = DefaultObjectMapper.readValueAsMap(response);
                            assertEquals("Map expected count", 1, found.size());
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 0)
        );
        assertEquals(1, invokeCounter1.get());
    }

    /**
     * Make sure that if nothing is in the request queue that a keepalive should be sent.
     */
    @Test
    public void shouldSendKeepaliveIfEmpty() {
        assertTrue(handler.shouldSendKeepAlive());
    }

    /**
     * Make sure that if an operation is in-flight and active, since pipelining is
     * not available, a keepalive request should not be sent.
     */
    @Test
    public void shouldNotSendKeepaliveIfActiveInFlight() {
        Subscriber subscriber = new TestSubscriber();
        GenericQueryRequest request = GenericQueryRequest.simpleStatement("select 1=1", "bucket", "pw");
        request.subscriber(subscriber);
        queue.add(request);
        channel.writeOutbound();
        assertFalse(handler.shouldSendKeepAlive());
    }

    /**
     * Make sure that if an operation is in-flight and inactive, even if pipelining
     * is not available, a keepalive request is sent to proactively check the socket.
     */
    @Test
    public void shouldSendKeepaliveIfInactiveInFlight() {
        Subscriber subscriber = new TestSubscriber();
        GenericQueryRequest request = GenericQueryRequest.simpleStatement("select 1=1", "bucket", "pw");
        request.subscriber(subscriber);
        queue.add(request);
        channel.writeOutbound();
        assertFalse(handler.shouldSendKeepAlive());
        subscriber.unsubscribe();
        assertTrue(handler.shouldSendKeepAlive());
    }
}

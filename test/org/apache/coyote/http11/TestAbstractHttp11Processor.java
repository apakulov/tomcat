/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.CharBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.TesterServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.ByteChunk;

public class TestAbstractHttp11Processor extends TomcatBaseTest {

    @Test
    public void testStatusForcesClose() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        Context ctxt = tomcat.addContext("", System.getProperty("java.io.tmpdir"));

        // Add protected servlet
        Tomcat.addServlet(ctxt, "StatusForcesCloseServlet", new StatusForcesCloseServlet());
        ctxt.addServletMapping("/*", "StatusForcesCloseServlet");

        tomcat.start();

        ByteChunk bc = new ByteChunk();
        Map<String,List<String>> responseHeaders = new HashMap<>();
        getUrl("http://localhost:" + getPort() + "/anything", bc, responseHeaders);

        // Assumes header name uses standard case
        List<String> values = responseHeaders.get("Connection");
        Assert.assertEquals(1, values.size());
        Assert.assertEquals("close", values.get(0).toLowerCase(Locale.ENGLISH));
    }

    private static class StatusForcesCloseServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            // Set the Connection header
            resp.setHeader("Connection", "keep-alive");

            // Set a status code that should force the connection to close
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    @Test
    public void testWithTEVoid() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Use the normal Tomcat ROOT context
        File root = new File("test/webapp");
        tomcat.addWebapp("", root.getAbsolutePath());

        tomcat.start();

        String request =
            "POST /echo-params.jsp HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: any" + SimpleHttpClient.CRLF +
            "Transfer-encoding: void" + SimpleHttpClient.CRLF +
            "Content-Length: 9" + SimpleHttpClient.CRLF +
            "Content-Type: application/x-www-form-urlencoded" +
                    SimpleHttpClient.CRLF +
                    SimpleHttpClient.CRLF +
            "test=data";

        Client client = new Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] {request});

        client.connect();
        client.processRequest();
        assertTrue(client.isResponse501());
    }


    @Test
    public void testWithTEBuffered() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Use the normal Tomcat ROOT context
        File root = new File("test/webapp");
        tomcat.addWebapp("", root.getAbsolutePath());

        tomcat.start();

        String request =
            "POST /echo-params.jsp HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: any" + SimpleHttpClient.CRLF +
            "Transfer-encoding: buffered" + SimpleHttpClient.CRLF +
            "Content-Length: 9" + SimpleHttpClient.CRLF +
            "Content-Type: application/x-www-form-urlencoded" +
                    SimpleHttpClient.CRLF +
                    SimpleHttpClient.CRLF +
            "test=data";

        Client client = new Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] {request});

        client.connect();
        client.processRequest();
        assertTrue(client.isResponse501());
    }


    @Test
    public void testWithTEChunked() throws Exception {
        doTestWithTEChunked(false);
    }


    @Test
    public void testWithTEChunkedWithCL() throws Exception {
        // Should be ignored
        doTestWithTEChunked(true);
    }


    private void doTestWithTEChunked(boolean withCL)
            throws Exception {

        Tomcat tomcat = getTomcatInstance();

        // Use the normal Tomcat ROOT context
        File root = new File("test/webapp");
        tomcat.addWebapp("", root.getAbsolutePath());

        tomcat.start();

        String request =
            "POST /echo-params.jsp HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: any" + SimpleHttpClient.CRLF +
            (withCL ? "Content-length: 1" + SimpleHttpClient.CRLF : "") +
            "Transfer-encoding: chunked" + SimpleHttpClient.CRLF +
            "Content-Type: application/x-www-form-urlencoded" +
                    SimpleHttpClient.CRLF +
            "Connection: close" + SimpleHttpClient.CRLF +
            SimpleHttpClient.CRLF +
            "9" + SimpleHttpClient.CRLF +
            "test=data" + SimpleHttpClient.CRLF +
            "0" + SimpleHttpClient.CRLF +
            SimpleHttpClient.CRLF;

        Client client = new Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] {request});

        client.connect();
        client.processRequest();
        assertTrue(client.isResponse200());
        assertTrue(client.getResponseBody().contains("test - data"));
    }


    @Test
    public void testWithTEIdentity() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Use the normal Tomcat ROOT context
        File root = new File("test/webapp");
        tomcat.addWebapp("", root.getAbsolutePath());

        tomcat.start();

        String request =
            "POST /echo-params.jsp HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: any" + SimpleHttpClient.CRLF +
            "Transfer-encoding: identity" + SimpleHttpClient.CRLF +
            "Content-Length: 9" + SimpleHttpClient.CRLF +
            "Content-Type: application/x-www-form-urlencoded" +
                    SimpleHttpClient.CRLF +
            "Connection: close" + SimpleHttpClient.CRLF +
                SimpleHttpClient.CRLF +
            "test=data";

        Client client = new Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] {request});

        client.connect();
        client.processRequest();
        assertTrue(client.isResponse200());
        assertTrue(client.getResponseBody().contains("test - data"));
    }


    @Test
    public void testWithTESavedRequest() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Use the normal Tomcat ROOT context
        File root = new File("test/webapp");
        tomcat.addWebapp("", root.getAbsolutePath());

        tomcat.start();

        String request =
            "POST /echo-params.jsp HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: any" + SimpleHttpClient.CRLF +
            "Transfer-encoding: savedrequest" + SimpleHttpClient.CRLF +
            "Content-Length: 9" + SimpleHttpClient.CRLF +
            "Content-Type: application/x-www-form-urlencoded" +
                    SimpleHttpClient.CRLF +
                    SimpleHttpClient.CRLF +
            "test=data";

        Client client = new Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] {request});

        client.connect();
        client.processRequest();
        assertTrue(client.isResponse501());
    }


    @Test
    public void testWithTEUnsupported() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Use the normal Tomcat ROOT context
        File root = new File("test/webapp");
        tomcat.addWebapp("", root.getAbsolutePath());

        tomcat.start();

        String request =
            "POST /echo-params.jsp HTTP/1.1" + SimpleHttpClient.CRLF +
            "Host: any" + SimpleHttpClient.CRLF +
            "Transfer-encoding: unsupported" + SimpleHttpClient.CRLF +
            "Content-Length: 9" + SimpleHttpClient.CRLF +
            "Content-Type: application/x-www-form-urlencoded" +
                    SimpleHttpClient.CRLF +
                    SimpleHttpClient.CRLF +
            "test=data";

        Client client = new Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] {request});

        client.connect();
        client.processRequest();
        assertTrue(client.isResponse501());
    }


    @Test
    public void testPipelining() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        Context ctxt = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));

        // Add protected servlet
        Tomcat.addServlet(ctxt, "TesterServlet", new TesterServlet());
        ctxt.addServletMapping("/foo", "TesterServlet");

        tomcat.start();

        String requestPart1 =
            "GET /foo HTTP/1.1" + SimpleHttpClient.CRLF;
        String requestPart2 =
            "Host: any" + SimpleHttpClient.CRLF +
            SimpleHttpClient.CRLF;

        final Client client = new Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] {requestPart1, requestPart2});
        client.setRequestPause(1000);
        client.setUseContentLength(true);
        client.connect();

        Runnable send = new Runnable() {
            @Override
            public void run() {
                try {
                    client.sendRequest();
                    client.sendRequest();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Thread t = new Thread(send);
        t.start();

        // Sleep for 1500 ms which should mean the all of request 1 has been
        // sent and half of request 2
        Thread.sleep(1500);

        // Now read the first response
        client.readResponse(true);
        assertFalse(client.isResponse50x());
        assertTrue(client.isResponse200());
        assertEquals("OK", client.getResponseBody());

        // Read the second response. No need to sleep, read will block until
        // there is data to process
        client.readResponse(true);
        assertFalse(client.isResponse50x());
        assertTrue(client.isResponse200());
        assertEquals("OK", client.getResponseBody());
    }


    @Test
    public void testChunking11NoContentLength() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        Context ctxt = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));

        Tomcat.addServlet(ctxt, "NoContentLengthFlushingServlet",
                new NoContentLengthFlushingServlet());
        ctxt.addServletMapping("/test", "NoContentLengthFlushingServlet");

        tomcat.start();

        ByteChunk responseBody = new ByteChunk();
        Map<String,List<String>> responseHeaders = new HashMap<>();
        int rc = getUrl("http://localhost:" + getPort() + "/test", responseBody,
                responseHeaders);

        assertEquals(HttpServletResponse.SC_OK, rc);
        assertTrue(responseHeaders.containsKey("Transfer-Encoding"));
        List<String> encodings = responseHeaders.get("Transfer-Encoding");
        assertEquals(1, encodings.size());
        assertEquals("chunked", encodings.get(0));
    }

    @Test
    public void testNoChunking11NoContentLengthConnectionClose()
            throws Exception {

        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        Context ctxt = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));

        Tomcat.addServlet(ctxt, "NoContentLengthConnectionCloseFlushingServlet",
                new NoContentLengthConnectionCloseFlushingServlet());
        ctxt.addServletMapping("/test",
                "NoContentLengthConnectionCloseFlushingServlet");

        tomcat.start();

        ByteChunk responseBody = new ByteChunk();
        Map<String,List<String>> responseHeaders = new HashMap<>();
        int rc = getUrl("http://localhost:" + getPort() + "/test", responseBody,
                responseHeaders);

        assertEquals(HttpServletResponse.SC_OK, rc);

        assertTrue(responseHeaders.containsKey("Connection"));
        List<String> connections = responseHeaders.get("Connection");
        assertEquals(1, connections.size());
        assertEquals("close", connections.get(0));

        assertFalse(responseHeaders.containsKey("Transfer-Encoding"));

        assertEquals("OK", responseBody.toString());
    }

    @Test
    public void testBug53677a() throws Exception {
        doTestBug53677(false);
    }

    @Test
    public void testBug53677b() throws Exception {
        doTestBug53677(true);
    }

    private void doTestBug53677(boolean flush) throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        Context ctxt = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));

        Tomcat.addServlet(ctxt, "LargeHeaderServlet",
                new LargeHeaderServlet(flush));
        ctxt.addServletMapping("/test", "LargeHeaderServlet");

        tomcat.start();

        ByteChunk responseBody = new ByteChunk();
        Map<String,List<String>> responseHeaders = new HashMap<>();
        int rc = getUrl("http://localhost:" + getPort() + "/test", responseBody,
                responseHeaders);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, rc);
        if (responseBody.getLength() > 0) {
            // It will be >0 if the standard error page handling has been
            // triggered
            assertFalse(responseBody.toString().contains("FAIL"));
        }
    }


    private static CountDownLatch bug55772Latch1 = new CountDownLatch(1);
    private static CountDownLatch bug55772Latch2 = new CountDownLatch(1);
    private static CountDownLatch bug55772Latch3 = new CountDownLatch(1);
    private static boolean bug55772IsSecondRequest = false;
    private static boolean bug55772RequestStateLeaked = false;


    @Test
    public void testBug55772() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        tomcat.getConnector().setProperty("processorCache", "1");
        tomcat.getConnector().setProperty("maxThreads", "1");

        // Must have a real docBase - just use temp
        Context ctxt = tomcat.addContext("",
                System.getProperty("java.io.tmpdir"));

        Tomcat.addServlet(ctxt, "async", new Bug55772Servlet());
        ctxt.addServletMapping("/*", "async");

        tomcat.start();

        String request1 = "GET /async?1 HTTP/1.1\r\n" +
                "Host: localhost:" + getPort() + "\r\n" +
                "Connection: keep-alive\r\n" +
                "Cache-Control: max-age=0\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent: Request1\r\n" +
                "Accept-Encoding: gzip,deflate,sdch\r\n" +
                "Accept-Language: en-US,en;q=0.8,fr;q=0.6,es;q=0.4\r\n" +
                "Cookie: something.that.should.not.leak=true\r\n" +
                "\r\n";

        String request2 = "GET /async?2 HTTP/1.1\r\n" +
                "Host: localhost:" + getPort() + "\r\n" +
                "Connection: keep-alive\r\n" +
                "Cache-Control: max-age=0\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent: Request2\r\n" +
                "Accept-Encoding: gzip,deflate,sdch\r\n" +
                "Accept-Language: en-US,en;q=0.8,fr;q=0.6,es;q=0.4\r\n" +
                "\r\n";

        try (final Socket connection = new Socket("localhost", getPort())) {
            connection.setSoLinger(true, 0);
            Writer writer = new OutputStreamWriter(connection.getOutputStream(),
                    B2CConverter.getCharset("US-ASCII"));
            writer.write(request1);
            writer.flush();

            bug55772Latch1.await();
            connection.close();
        }

        bug55772Latch2.await();
        bug55772IsSecondRequest = true;

        try (final Socket connection = new Socket("localhost", getPort())) {
            connection.setSoLinger(true, 0);
            Writer writer = new OutputStreamWriter(connection.getOutputStream(),
                    B2CConverter.getCharset("US-ASCII"));
            writer.write(request2);
            writer.flush();
            connection.getInputStream().read();
        }

        bug55772Latch3.await();
        if (bug55772RequestStateLeaked) {
            Assert.fail("State leaked between requests!");
        }
    }


    private static class Bug55772Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            if (bug55772IsSecondRequest) {
                Cookie[] cookies = req.getCookies();
                if (cookies != null && cookies.length > 0) {
                    for (Cookie cookie : req.getCookies()) {
                        if (cookie.getName().equalsIgnoreCase("something.that.should.not.leak")) {
                            bug55772RequestStateLeaked = true;
                        }
                    }
                }
                bug55772Latch3.countDown();
            } else {
                req.getCookies(); // We have to do this so Tomcat will actually parse the cookies from the request
            }

            req.setAttribute("org.apache.catalina.ASYNC_SUPPORTED", Boolean.TRUE);
            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(5000);

            bug55772Latch1.countDown();

            PrintWriter writer = asyncContext.getResponse().getWriter();
            writer.print('\n');
            writer.flush();

            bug55772Latch2.countDown();
        }
    }


    private static final class LargeHeaderServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        boolean flush = false;

        public LargeHeaderServlet(boolean flush) {
            this.flush = flush;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            String largeValue =
                    CharBuffer.allocate(10000).toString().replace('\0', 'x');
            resp.setHeader("x-Test", largeValue);
            if (flush) {
                resp.flushBuffer();
            }
            resp.setContentType("text/plain");
            resp.getWriter().print("FAIL");
        }

    }

    // flushes with no content-length set
    // should result in chunking on HTTP 1.1
    private static final class NoContentLengthFlushingServlet
            extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/plain");
            resp.getWriter().write("OK");
            resp.flushBuffer();
        }
    }

    // flushes with no content-length set but sets Connection: close header
    // should no result in chunking on HTTP 1.1
    private static final class NoContentLengthConnectionCloseFlushingServlet
            extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/event-stream");
            resp.addHeader("Connection", "close");
            resp.flushBuffer();
            resp.getWriter().write("OK");
            resp.flushBuffer();
        }
    }

    private static final class Client extends SimpleHttpClient {

        public Client(int port) {
            setPort(port);
        }

        @Override
        public boolean isResponseBodyOK() {
            return getResponseBody().contains("test - data");
        }
    }
}

/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2015 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j.challenge;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.shredzone.acme4j.toolbox.AcmeUtils.parseTimestamp;
import static org.shredzone.acme4j.toolbox.TestUtils.*;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;

import org.jose4j.lang.JoseException;
import org.junit.Before;
import org.junit.Test;
import org.shredzone.acme4j.Problem;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.shredzone.acme4j.exception.AcmeRetryAfterException;
import org.shredzone.acme4j.provider.TestableConnectionProvider;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.toolbox.JSONBuilder;
import org.shredzone.acme4j.toolbox.TestUtils;

/**
 * Unit tests for {@link Challenge}.
 */
public class ChallengeTest {
    private Session session;
    private URL locationUrl = url("https://example.com/acme/some-location");

    @Before
    public void setup() throws IOException {
        session = TestUtils.session();
    }

    /**
     * Test that a challenge is properly restored.
     */
    @Test
    public void testChallenge() throws Exception {
        TestableConnectionProvider provider = new TestableConnectionProvider() {
            @Override
            public void sendRequest(URL url, Session session) {
                assertThat(url, is(locationUrl));
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("updateHttpChallengeResponse");
            }
        };

        Session session = provider.createSession();

        provider.putTestChallenge(Http01Challenge.TYPE, Http01Challenge::new);

        Http01Challenge challenge = Challenge.bind(session, locationUrl);

        assertThat(challenge.getType(), is(Http01Challenge.TYPE));
        assertThat(challenge.getStatus(), is(Status.VALID));
        assertThat(challenge.getLocation(), is(locationUrl));
        assertThat(challenge.getToken(), is("IlirfxKKXAsHtmzK29Pj8A"));

        provider.close();
    }

    /**
     * Test that after unmarshaling, the challenge properties are set correctly.
     */
    @Test
    public void testUnmarshal() throws URISyntaxException {
        Challenge challenge = new Challenge(session, getJSON("genericChallenge"));

        // Test unmarshalled values
        assertThat(challenge.getType(), is("generic-01"));
        assertThat(challenge.getStatus(), is(Status.INVALID));
        assertThat(challenge.getLocation(), is(url("http://example.com/challenge/123")));
        assertThat(challenge.getValidated(), is(parseTimestamp("2015-12-12T17:19:36.336785823Z")));
        assertThat(challenge.getJSON().get("type").asString(), is("generic-01"));
        assertThat(challenge.getJSON().get("url").asURL(), is(url("http://example.com/challenge/123")));
        assertThat(challenge.getJSON().get("notPresent").asString(), is(nullValue()));
        assertThat(challenge.getJSON().get("notPresentUrl").asURL(), is(nullValue()));

        Problem error = challenge.getError();
        assertThat(error, is(notNullValue()));
        assertThat(error.getType(), is(URI.create("urn:ietf:params:acme:error:incorrectResponse")));
        assertThat(error.getDetail(), is("bad token"));
        assertThat(error.getInstance(), is(URI.create("http://example.com/documents/faq.html")));
    }

    /**
     * Test that {@link Challenge#prepareResponse(JSONBuilder)} contains the type.
     */
    @Test
    public void testRespond() throws JoseException {
        Challenge challenge = new Challenge(session, getJSON("genericChallenge"));

        JSONBuilder response = new JSONBuilder();
        challenge.prepareResponse(response);

        assertThat(response.toString(), sameJSONAs("{}"));
    }

    /**
     * Test that an exception is thrown on challenge type mismatch.
     */
    @Test(expected = AcmeProtocolException.class)
    public void testNotAcceptable() throws URISyntaxException {
        new Http01Challenge(session, getJSON("dnsChallenge"));
    }

    /**
     * Test that a challenge can be triggered.
     */
    @Test
    public void testTrigger() throws Exception {
        TestableConnectionProvider provider = new TestableConnectionProvider() {
            @Override
            public int sendSignedRequest(URL url, JSONBuilder claims, Session session, int... httpStatus) {
                assertThat(url, is(locationUrl));
                assertThat(claims.toString(), sameJSONAs(getJSON("triggerHttpChallengeRequest").toString()));
                assertThat(session, is(notNullValue()));
                assertThat(httpStatus, isIntArrayContainingInAnyOrder());
                return HttpURLConnection.HTTP_OK;
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("triggerHttpChallengeResponse");
            }
        };

        Session session = provider.createSession();

        Http01Challenge challenge = new Http01Challenge(session, getJSON("triggerHttpChallenge"));

        challenge.trigger();

        assertThat(challenge.getStatus(), is(Status.PENDING));
        assertThat(challenge.getLocation(), is(locationUrl));

        provider.close();
    }

    /**
     * Test that a challenge is properly updated.
     */
    @Test
    public void testUpdate() throws Exception {
        TestableConnectionProvider provider = new TestableConnectionProvider() {
            @Override
            public void sendRequest(URL url, Session session) {
                assertThat(url, is(locationUrl));
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("updateHttpChallengeResponse");
            }

            @Override
            public void handleRetryAfter(String message) throws AcmeException {
                // Just do nothing
            }
        };

        Session session = provider.createSession();

        Challenge challenge = new Http01Challenge(session, getJSON("triggerHttpChallengeResponse"));

        challenge.update();

        assertThat(challenge.getStatus(), is(Status.VALID));
        assertThat(challenge.getLocation(), is(locationUrl));

        provider.close();
    }

    /**
     * Test that a challenge is properly updated, with Retry-After header.
     */
    @Test
    public void testUpdateRetryAfter() throws Exception {
        final Instant retryAfter = Instant.now().plus(Duration.ofSeconds(30));

        TestableConnectionProvider provider = new TestableConnectionProvider() {
            @Override
            public void sendRequest(URL url, Session session) {
                assertThat(url, is(locationUrl));
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("updateHttpChallengeResponse");
            }


            @Override
            public void handleRetryAfter(String message) throws AcmeException {
                throw new AcmeRetryAfterException(message, retryAfter);
            }
        };

        Session session = provider.createSession();

        Challenge challenge = new Http01Challenge(session, getJSON("triggerHttpChallengeResponse"));

        try {
            challenge.update();
            fail("Expected AcmeRetryAfterException");
        } catch (AcmeRetryAfterException ex) {
            assertThat(ex.getRetryAfter(), is(retryAfter));
        }

        assertThat(challenge.getStatus(), is(Status.VALID));
        assertThat(challenge.getLocation(), is(locationUrl));

        provider.close();
    }

    /**
     * Test that null is handled properly.
     */
    @Test
    public void testNullChallenge() throws Exception {
        try {
            Challenge.bind(session, null);
            fail("locationUri accepts null");
        } catch (NullPointerException ex) {
            // expected
        }

        try {
            Challenge.bind(null, locationUrl);
            fail("session accepts null");
        } catch (NullPointerException ex) {
            // expected
        }
    }

    /**
     * Test that an exception is thrown on a bad location URL.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testBadBind() throws Exception {
        TestableConnectionProvider provider = new TestableConnectionProvider() {
            @Override
            public void sendRequest(URL url, Session session) {
                assertThat(url, is(locationUrl));
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("updateAccountResponse");
            }
        };

        Session session = provider.createSession();
        Challenge.bind(session, locationUrl);

        provider.close();
    }

    /**
     * Test that unmarshalling something different like a challenge fails.
     */
    @Test(expected = AcmeProtocolException.class)
    public void testBadUnmarshall() {
        new Challenge(session, getJSON("updateAccountResponse"));
    }

}

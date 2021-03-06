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
package org.shredzone.acme4j;

import java.net.URI;
import java.net.URL;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.connector.Resource;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.shredzone.acme4j.provider.AcmeProvider;
import org.shredzone.acme4j.toolbox.JSON;

/**
 * A session stores the ACME server URI and the account's key pair. It also tracks
 * communication parameters.
 * <p>
 * Note that {@link Session} objects are not serializable, as they contain a keypair and
 * volatile data.
 */
public class Session {
    private final AtomicReference<Map<Resource, URL>> resourceMap = new AtomicReference<>();
    private final AtomicReference<Metadata> metadata = new AtomicReference<>();
    private final URI serverUri;
    private final AcmeProvider provider;

    private KeyPair keyPair;
    private String keyIdentifier;
    private byte[] nonce;
    private JSON directoryJson;
    private Locale locale = Locale.getDefault();
    protected Instant directoryCacheExpiry;

    /**
     * Creates a new {@link Session}.
     *
     * @param serverUri
     *            URI string of the ACME server
     * @param keyPair
     *            {@link KeyPair} of the ACME account
     */
    public Session(String serverUri, KeyPair keyPair) {
        this(URI.create(serverUri), keyPair);
    }

    /**
     * Creates a new {@link Session}.
     *
     * @param serverUri
     *            {@link URI} of the ACME server
     * @param keyPair
     *            {@link KeyPair} of the ACME account
     * @throws IllegalArgumentException
     *             if no ACME provider was found for the server URI.
     */
    public Session(URI serverUri, KeyPair keyPair) {
        this.serverUri = Objects.requireNonNull(serverUri, "serverUri");
        this.keyPair = Objects.requireNonNull(keyPair, "keyPair");

        final URI localServerUri = serverUri;

        Iterable<AcmeProvider> providers = ServiceLoader.load(AcmeProvider.class);
        provider = StreamSupport.stream(providers.spliterator(), false)
            .filter(p -> p.accepts(localServerUri))
            .reduce((a, b) -> {
                    throw new IllegalArgumentException("Both ACME providers "
                        + a.getClass().getSimpleName() + " and "
                        + b.getClass().getSimpleName() + " accept "
                        + localServerUri + ". Please check your classpath.");
                })
            .orElseThrow(() -> new IllegalArgumentException("No ACME provider found for " + localServerUri));
    }

    /**
     * Gets the ACME server {@link URI} of this session.
     */
    public URI getServerUri() {
        return serverUri;
    }

    /**
     * Gets the {@link KeyPair} of the ACME account.
     */
    public KeyPair getKeyPair() {
        return keyPair;
    }

    /**
     * Sets a different {@link KeyPair}.
     */
    public void setKeyPair(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /**
     * Gets the key identifier of the ACME account.
     */
    public String getKeyIdentifier() {
        return keyIdentifier;
    }

    /**
     * Sets the key identifier of the ACME account.
     */
    public void setKeyIdentifier(String keyIdentifier) {
        this.keyIdentifier = keyIdentifier;
    }

    /**
     * Gets the last nonce, or {@code null} if the session is new.
     */
    public byte[] getNonce() {
        return nonce;
    }

    /**
     * Sets the nonce received by the server.
     */
    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    /**
     * Gets the current locale of this session.
     */
    public Locale getLocale() {
        return locale;
    }

    /**
     * Sets the locale used in this session. The locale is passed to the server as
     * Accept-Language header. The server <em>may</em> respond with localized messages.
     */
    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    /**
     * Returns the {@link AcmeProvider} that is used for this session.
     *
     * @return {@link AcmeProvider}
     */
    public AcmeProvider provider() {
        return provider;
    }

    /**
     * Creates a {@link Challenge} instance for the given challenge data.
     *
     * @param data
     *            Challenge JSON data
     * @return {@link Challenge} instance
     */
    public Challenge createChallenge(JSON data) {
        Challenge challenge = provider().createChallenge(this, data);
        if (challenge == null) {
            throw new AcmeProtocolException("Could not create challenge for: " + data);
        }
        return challenge;
    }

    /**
     * Gets the {@link URL} of the given {@link Resource}. This may involve connecting to
     * the server and getting a directory. The result is cached.
     *
     * @param resource
     *            {@link Resource} to get the {@link URL} of
     * @return {@link URL}, or {@code null} if the server does not offer that resource
     */
    public URL resourceUrl(Resource resource) throws AcmeException {
        readDirectory();
        return resourceMap.get().get(Objects.requireNonNull(resource, "resource"));
    }

    /**
     * Gets the metadata of the provider's directory. This may involve connecting to the
     * server and getting a directory. The result is cached.
     *
     * @return {@link Metadata}. May contain no data, but is never {@code null}.
     */
    public Metadata getMetadata() throws AcmeException {
        readDirectory();
        return metadata.get();
    }

    /**
     * Reads the provider's directory, then rebuild the resource map. The response is
     * cached.
     */
    private void readDirectory() throws AcmeException {
        synchronized (this) {
            Instant now = Instant.now();
            if (directoryJson != null && directoryCacheExpiry.isAfter(now)) {
                return;
            }
            directoryJson = provider().directory(this, getServerUri());
            directoryCacheExpiry = now.plus(Duration.ofHours(1));
        }

        JSON meta = directoryJson.get("meta").asObject();
        if (meta != null) {
            metadata.set(new Metadata(meta));
        } else {
            metadata.set(new Metadata(JSON.empty()));
        }

        Map<Resource, URL> map = new EnumMap<>(Resource.class);
        for (Resource res : Resource.values()) {
            URL url = directoryJson.get(res.path()).asURL();
            if (url != null) {
                map.put(res, url);
            }
        }

        resourceMap.set(map);
    }

}

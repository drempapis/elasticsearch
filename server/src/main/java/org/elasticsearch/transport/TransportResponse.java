/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.transport;

import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public abstract class TransportResponse extends TransportMessage {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<TransportResponse, Releasable> AFTER_SEND_RELEASE_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(TransportResponse.class, Releasable.class, "afterSendRelease");

    private transient volatile Releasable afterSendRelease;

    /**
     * Constructs a new empty transport response
     */
    protected TransportResponse() {}

    /**
     * Sets a {@link Releasable} that will be released after the response has been fully written to the network.
     * This allows callers to defer resource cleanup (e.g. circuit breaker release) until the serialized bytes
     * have actually been sent, rather than releasing as soon as the send is queued.
     *
     * <p>If a releasable has already been set (and not yet consumed), the new releasable is composed with
     * the existing one so that both are released together when the transport layer consumes the after-send
     * releasable. This supports multiple wrapper layers each attaching their own cleanup.
     *
     * <p>Thread-safe: uses an {@link AtomicReferenceFieldUpdater} so that a set on one thread
     * is visible to a consume on another (e.g. search-service thread sets, transport thread consumes),
     * without allocating an {@code AtomicReference} per instance.
     */
    public void setAfterSendRelease(Releasable afterSendRelease) {
        while (true) {
            Releasable existing = AFTER_SEND_RELEASE_UPDATER.get(this);
            Releasable combined = existing == null ? afterSendRelease : Releasables.wrap(existing, afterSendRelease);
            if (AFTER_SEND_RELEASE_UPDATER.compareAndSet(this, existing, combined)) {
                return;
            }
        }
    }

    /**
     * Atomically returns and clears the after-send {@link Releasable}. Returns {@code null} if none was set.
     * Called by the transport layer to extract the releasable and compose it into the send-completion callback.
     */
    public Releasable consumeAfterSendRelease() {
        return AFTER_SEND_RELEASE_UPDATER.getAndSet(this, null);
    }
}

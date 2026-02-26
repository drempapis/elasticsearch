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
     * Attaches a {@link Releasable} to this response that the transport layer will release once the
     * response bytes have been fully written to the network. This is used to defer resource cleanup
     * (e.g. circuit breaker release) until after the write completes.
     *
     * @throws IllegalStateException if a releasable has already been set and not yet consumed
     */
    public void setAfterSendRelease(Releasable afterSendRelease) {
        if (AFTER_SEND_RELEASE_UPDATER.compareAndSet(this, null, afterSendRelease) == false) {
            throw new IllegalStateException("afterSendRelease already set");
        }
    }

    /**
     * Atomically returns and clears the after-send {@link Releasable}, or {@code null} if none was set.
     * The transport layer calls this to take ownership of the releasable and release it on write completion.
     */
    public Releasable consumeAfterSendRelease() {
        return AFTER_SEND_RELEASE_UPDATER.getAndSet(this, null);
    }
}

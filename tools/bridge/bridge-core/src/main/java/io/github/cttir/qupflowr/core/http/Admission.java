/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.util.Objects;

/** Decision of {@link HttpHandler#admit(RequestHead)}, taken before any body byte is read. */
public sealed interface Admission {

    /**
     * Rejects the request with a response; the body is never read and no {@code 100 Continue} sent.
     *
     * @param response rejection response
     */
    record Rejected(Response response) implements Admission {
        /**
         * Validates the rejection.
         *
         * @param response response
         */
        public Rejected {
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * Accepts the request.
     *
     * @param maxBodyBytes largest body this route accepts (the server also applies its absolute
     *     limit)
     * @param exchange code that produces the response
     */
    record Accepted(long maxBodyBytes, Exchange exchange) implements Admission {
        /**
         * Validates the acceptance.
         *
         * @param maxBodyBytes route body limit
         * @param exchange exchange
         */
        public Accepted {
            if (maxBodyBytes < 0) {
                throw new IllegalArgumentException("Negative body limit");
            }
            Objects.requireNonNull(exchange, "exchange");
        }
    }

    /** Produces the response of an accepted request. */
    @FunctionalInterface
    interface Exchange {
        /**
         * Handles the request.
         *
         * @param head request head
         * @param body request body stream
         * @return response
         * @throws Exception any failure; becomes {@code 500 INTERNAL_ERROR} unless it is a body
         *     timeout
         */
        Response handle(RequestHead head, RequestBody body) throws Exception;
    }

    /**
     * Creates a rejection.
     *
     * @param response response to send
     * @return admission
     */
    static Admission reject(Response response) {
        return new Rejected(response);
    }

    /**
     * Creates an acceptance.
     *
     * @param maxBodyBytes route body limit
     * @param exchange exchange
     * @return admission
     */
    static Admission accept(long maxBodyBytes, Exchange exchange) {
        return new Accepted(maxBodyBytes, exchange);
    }
}

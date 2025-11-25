/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core;

public class ProxyProtocolException extends Exception {
    private static final long serialVersionUID = 1L;

    public ProxyProtocolException(String message) {
        super(message);
    }
    public ProxyProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

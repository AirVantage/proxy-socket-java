/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core;

public final class ProxyProtocolParseException extends ProxyProtocolException {
    public ProxyProtocolParseException(String message) {
        super(message);
    }
    public ProxyProtocolParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

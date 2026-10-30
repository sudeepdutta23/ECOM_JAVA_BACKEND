package com.ecommerce.backend.common.mail;

/** The mail server rejected the message or could not be reached. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

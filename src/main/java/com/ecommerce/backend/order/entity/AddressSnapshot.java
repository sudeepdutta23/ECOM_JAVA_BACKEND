package com.ecommerce.backend.order.entity;

/**
 * An address as it was at checkout, stored as JSON in the order. Immutable: later edits to the
 * address book never reach an order.
 */
public record AddressSnapshot(String recipientName, String company, String line1, String line2, String city,
                              String region, String postalCode, String countryCode, String phoneNumber) {

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "AddressSnapshot[countryCode=" + countryCode + "]";
    }
}

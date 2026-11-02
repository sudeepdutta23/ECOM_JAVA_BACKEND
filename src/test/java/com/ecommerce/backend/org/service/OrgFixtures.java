package com.ecommerce.backend.org.service;

import java.time.Duration;

/** Shared settings for org unit tests: the defaults of application.yml. */
final class OrgFixtures {

    static final OrgProperties PROPERTIES = new OrgProperties(3, 50, Duration.ofDays(7), "http://localhost:3000");

    private OrgFixtures() {
    }
}

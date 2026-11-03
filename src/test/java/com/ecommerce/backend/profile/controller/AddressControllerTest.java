package com.ecommerce.backend.profile.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.security.SecurityConfig;
import com.ecommerce.backend.profile.dto.AddressRequest;
import com.ecommerce.backend.profile.dto.AddressResponse;
import com.ecommerce.backend.profile.service.AddressService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AddressController.class)
@Import(SecurityConfig.class)
class AddressControllerTest {

    private static final String ADDRESSES = "/api/v1/profile/addresses";
    private static final UUID CALLER = UUID.randomUUID();
    private static final String VALID = """
            {"recipientName": "Ada Lovelace", "line1": "Friedrichstraße 123", "city": "Berlin",
             "postalCode": "10117", "countryCode": "DE"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddressService addresses;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject(CALLER.toString())));
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return signedIn(post(ADDRESSES)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static AddressResponse response(UUID id) {
        return new AddressResponse(id, null, "Ada Lovelace", null, "Friedrichstraße 123", null, "Berlin", null,
                "SW1A 1AA", "GB", null, true, true, 0, Instant.now(), Instant.now());
    }

    @Test
    void createNormalizesAndAnswers201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(addresses.create(eq(CALLER), any())).thenReturn(response(id));

        mockMvc.perform(create("""
                        {"recipientName": " Ada Lovelace ", "line1": "Friedrichstraße 123", "line2": "  ",
                         "city": "London", "postalCode": " sw1a   1aa ", "countryCode": "gb",
                         "phoneNumber": "+44 20 7946 0958"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", ADDRESSES + "/" + id));

        ArgumentCaptor<AddressRequest> captor = ArgumentCaptor.forClass(AddressRequest.class);
        verify(addresses).create(eq(CALLER), captor.capture());
        AddressRequest request = captor.getValue();
        assertThat(request.recipientName()).isEqualTo("Ada Lovelace");
        assertThat(request.line2()).isNull();
        assertThat(request.postalCode()).isEqualTo("SW1A 1AA");
        assertThat(request.countryCode()).isEqualTo("GB");
        assertThat(request.phoneNumber()).isEqualTo("+442079460958");
        assertThat(request.defaultShipping()).as("left out means false").isFalse();
    }

    @Test
    void invalidAddressesAreValidationErrorsAndNeverReachTheService() throws Exception {
        for (String body : List.of(
                "{\"line1\": \"Street 1\", \"city\": \"Berlin\", \"countryCode\": \"DE\"}",           // no recipient
                "{\"recipientName\": \"Ada\", \"city\": \"Berlin\", \"countryCode\": \"DE\"}",         // no street
                "{\"recipientName\": \"Ada\", \"line1\": \"Street 1\", \"countryCode\": \"DE\"}",      // no city
                "{\"recipientName\": \"Ada\", \"line1\": \"Street 1\", \"city\": \"Berlin\"}",         // no country
                VALID.replace("\"DE\"", "\"DEU\""),
                VALID.replace("\"Berlin\"", "\"Berlin\\u202E\""),
                VALID.replace("\"Ada Lovelace\"", "\"" + "x".repeat(101) + "\""),
                VALID.replace("}", ", \"phoneNumber\": \"030 1234567\"}"))) {
            mockMvc.perform(create(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(addresses);
    }

    @Test
    void anOwnerCannotBeSmuggledIn() throws Exception {
        mockMvc.perform(create(VALID.replace("}", ", \"userId\": \"" + UUID.randomUUID() + "\"}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(addresses);
    }

    @Test
    void someoneElsesAddressIs404AndDeleteIs204() throws Exception {
        UUID theirs = UUID.randomUUID();
        when(addresses.get(CALLER, theirs)).thenThrow(new ResourceNotFoundException("Address"));

        mockMvc.perform(signedIn(get(ADDRESSES + "/" + theirs))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(signedIn(delete(ADDRESSES + "/" + UUID.randomUUID()))).andExpect(status().isNoContent());
    }

    @Test
    void aSignedOutCallerGets401() throws Exception {
        mockMvc.perform(get(ADDRESSES)).andExpect(status().isUnauthorized());
        verifyNoInteractions(addresses);
    }
}

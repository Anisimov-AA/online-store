package com.onlinestore.cart;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Calls the Credit Card Authorizer service over HTTP. */
@Component
public class CreditCardClient {

    private final RestClient http;

    public CreditCardClient(@Value("${cca.url}") String ccaUrl) {
        this.http = RestClient.create(ccaUrl);
    }

    /** Returns the CCA status code: 200 authorized, 402 declined, 400 bad card format. */
    public int authorize(String cardNumber) {
        return http.post()
                .uri("/credit-card-authorizer/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("credit_card_number", cardNumber))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}

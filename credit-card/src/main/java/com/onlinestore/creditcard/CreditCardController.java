package com.onlinestore.creditcard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Mock credit card authorizer: checks the card format, then approves 90% of charges at random. */
@RestController
public class CreditCardController {

    private static final Pattern CARD_FORMAT = Pattern.compile("^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{4}$");
    private static final double APPROVAL_RATE = 0.9;

    @PostMapping("/credit-card-authorizer/authorize")
    public ResponseEntity<Map<String, String>> authorize(@RequestBody Map<String, String> body) {
        String card = body.get("credit_card_number");

        if (card == null || !CARD_FORMAT.matcher(card).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "INVALID_CARD", "message", "Card must look like 1234-5678-9012-3456"));
        }

        if (ThreadLocalRandom.current().nextDouble() < APPROVAL_RATE) {
            return ResponseEntity.ok(Map.of("status", "Authorized"));
        }
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("error", "DECLINED", "message", "Declined"));
    }

    /** Load balancer health check. */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}

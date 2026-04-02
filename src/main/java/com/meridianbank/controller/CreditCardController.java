package com.meridianbank.controller;

import com.meridianbank.dto.CreditCardDTO;
import com.meridianbank.service.CreditCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Credit Card operations.
 *
 * <p>Thin HTTP adapter over CreditCardService. Oracle-specific constructs —
 * CONNECT BY PRIOR for reward tier hierarchies, LISTAGG for multi-card
 * summaries, CUME_DIST/NTILE analytic functions for spending percentiles,
 * ADD_MONTHS for expiry calculations, CREDIT_CARD_PKG stored procedure
 * calls — are all encapsulated in CreditCardOracleDAO and CreditCardService.
 *
 * <p>The /rewards endpoint is particularly notable: it calls
 * CreditCardService.getRewardTierPath() which issues a CONNECT BY PRIOR
 * hierarchical query to traverse the card reward tier table.
 */
@RestController
@RequestMapping("/api/credit-cards")
@CrossOrigin(origins = "*")
public class CreditCardController {

    private static final Logger log = LoggerFactory.getLogger(CreditCardController.class);

    private final CreditCardService creditCardService;

    @Autowired
    public CreditCardController(CreditCardService creditCardService) {
        this.creditCardService = creditCardService;
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/{id}
    // Returns a single credit card by primary key.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<CreditCardDTO> getCardById(@PathVariable Long id) {
        log.debug("GET /api/credit-cards/{}", id);
        CreditCardDTO card = creditCardService.getCardById(id);
        return ResponseEntity.ok(card);
    }

    // -------------------------------------------------------------------------
    // POST /api/credit-cards
    // Applies for a new credit card.
    // Body: { "customerId": Long, "cardType": String, "requestedLimit": BigDecimal }
    // CreditCardService.applyForCard() calls CREDIT_CARD_PKG.APPLY_FOR_CARD
    // and uses Oracle sequence for card ID generation.
    // Returns HTTP 201 Created.
    // -------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<CreditCardDTO> applyForCard(@RequestBody Map<String, Object> body) {
        Long customerId = Long.valueOf(body.get("customerId").toString());
        String cardType = (String) body.get("cardType");
        BigDecimal requestedLimit = new BigDecimal(body.get("requestedLimit").toString());

        log.debug("POST /api/credit-cards customerId={} cardType={} limit={}", customerId, cardType, requestedLimit);
        CreditCardDTO created = creditCardService.applyForCard(customerId, cardType, requestedLimit);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // -------------------------------------------------------------------------
    // POST /api/credit-cards/{id}/payment
    // Processes a payment against a credit card.
    // Body: { "amount": BigDecimal }
    // CreditCardService.processPayment() records a debit transaction and
    // updates current_balance via CREDIT_CARD_PKG.PROCESS_PAYMENT.
    // -------------------------------------------------------------------------
    @PostMapping("/{id}/payment")
    public ResponseEntity<CreditCardDTO> processPayment(
            @PathVariable Long id,
            @RequestBody Map<String, BigDecimal> body) {

        BigDecimal amount = body.get("amount");
        log.debug("POST /api/credit-cards/{}/payment amount={}", id, amount);
        CreditCardDTO updated = creditCardService.processPayment(id, amount);
        return ResponseEntity.ok(updated);
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/{id}/rewards
    // Returns the reward tier path for a card using Oracle CONNECT BY PRIOR
    // hierarchical query.  The response is a list of tier labels from root
    // to the card's current tier (e.g., ["STANDARD", "GOLD", "PLATINUM"]).
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/rewards")
    public ResponseEntity<Map<String, Object>> getRewardTierPath(@PathVariable Long id) {
        log.debug("GET /api/credit-cards/{}/rewards", id);
        List<String> tierPath = creditCardService.getRewardTierPath(id);
        Map<String, Object> response = new HashMap<>();
        response.put("cardId", id);
        response.put("rewardTierPath", tierPath);
        response.put("currentTier", tierPath.isEmpty() ? null : tierPath.get(tierPath.size() - 1));
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/customer/{customerId}
    // Returns all credit cards for a customer.
    // -------------------------------------------------------------------------
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<CreditCardDTO>> getCardsByCustomer(
            @PathVariable Long customerId) {

        log.debug("GET /api/credit-cards/customer/{}", customerId);
        List<CreditCardDTO> cards = creditCardService.getCardsByCustomer(customerId);
        return ResponseEntity.ok(cards);
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/expiring?months=3
    // Returns cards expiring within N months.
    // CreditCardService.findCardsExpiringSoon() uses Oracle ADD_MONTHS()
    // to compute the expiry window boundary.
    // -------------------------------------------------------------------------
    @GetMapping("/expiring")
    public ResponseEntity<List<CreditCardDTO>> findCardsExpiringSoon(
            @RequestParam(defaultValue = "3") int months) {

        log.debug("GET /api/credit-cards/expiring months={}", months);
        List<CreditCardDTO> expiring = creditCardService.findCardsExpiringSoon(months);
        return ResponseEntity.ok(expiring);
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/customer/{customerId}/ordered-by-tier
    // Returns a customer's cards ordered by reward tier using CONNECT BY.
    // -------------------------------------------------------------------------
    @GetMapping("/customer/{customerId}/ordered-by-tier")
    public ResponseEntity<List<CreditCardDTO>> getCardsByCustomerOrderedByTier(
            @PathVariable Long customerId) {

        log.debug("GET /api/credit-cards/customer/{}/ordered-by-tier", customerId);
        List<CreditCardDTO> cards = creditCardService.getCardsByCustomerOrderedByTier(customerId);
        return ResponseEntity.ok(cards);
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/customer/{customerId}/summary
    // Returns a comma-delimited card summary using Oracle LISTAGG().
    // -------------------------------------------------------------------------
    @GetMapping("/customer/{customerId}/summary")
    public ResponseEntity<Map<String, Object>> getCardSummary(
            @PathVariable Long customerId) {

        log.debug("GET /api/credit-cards/customer/{}/summary", customerId);
        String summary = creditCardService.getCardSummaryWithListagg(customerId);
        Map<String, Object> response = new HashMap<>();
        response.put("customerId", customerId);
        response.put("cardSummary", summary);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/spending-percentiles
    // Returns all cards with their CUME_DIST/NTILE spending percentile ranks.
    // -------------------------------------------------------------------------
    @GetMapping("/spending-percentiles")
    public ResponseEntity<List<CreditCardDTO>> getSpendingPercentiles() {
        log.debug("GET /api/credit-cards/spending-percentiles");
        List<CreditCardDTO> percentiles = creditCardService.getSpendingPercentiles();
        return ResponseEntity.ok(percentiles);
    }

    // -------------------------------------------------------------------------
    // GET /api/credit-cards/top-high-balance?topN=10
    // Returns the top N cards by current balance.
    // -------------------------------------------------------------------------
    @GetMapping("/top-high-balance")
    public ResponseEntity<List<CreditCardDTO>> getTopHighBalanceCards(
            @RequestParam(defaultValue = "10") int topN) {

        log.debug("GET /api/credit-cards/top-high-balance topN={}", topN);
        List<CreditCardDTO> cards = creditCardService.getTopHighBalanceCards(topN);
        return ResponseEntity.ok(cards);
    }
}

package com.meridianbank.controller;

import com.meridianbank.dto.AccountDTO;
import com.meridianbank.dto.CustomerDTO;
import com.meridianbank.service.AccountService;
import com.meridianbank.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Customer operations.
 *
 * <p>Thin HTTP adapter layer over CustomerService and AccountService.
 * Business logic and all Oracle-specific data access are delegated to
 * the service and DAO layers — this controller is largely Oracle-agnostic.
 *
 * <p>Exceptions thrown by the service layer (MeridianBankException,
 * AccountNotFoundException, DuplicateCustomerException, etc.) are caught
 * and translated to appropriate HTTP responses by GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/customers")
@CrossOrigin(origins = "*")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    private final CustomerService customerService;
    private final AccountService accountService;

    @Autowired
    public CustomerController(CustomerService customerService,
                               AccountService accountService) {
        this.customerService = customerService;
        this.accountService = accountService;
    }

    // -------------------------------------------------------------------------
    // GET /api/customers?page=0&size=20
    // Returns a paginated list of customers.
    // Delegates to CustomerService.getCustomersForPage() which uses Oracle
    // ROWNUM-based pagination internally.
    // -------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<List<CustomerDTO>> getCustomers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.debug("GET /api/customers page={} size={}", page, size);
        List<CustomerDTO> customers = customerService.getCustomersForPage(page, size);
        return ResponseEntity.ok(customers);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/{id}
    // Returns a single customer by primary key.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<CustomerDTO> getCustomerById(@PathVariable Long id) {
        log.debug("GET /api/customers/{}", id);
        CustomerDTO customer = customerService.getCustomerById(id);
        return ResponseEntity.ok(customer);
    }

    // -------------------------------------------------------------------------
    // POST /api/customers
    // Creates a new customer. Returns HTTP 201 Created with the saved DTO.
    // CustomerService.createCustomer() calls CustomerOracleDAO which uses
    // Oracle STRUCT for the address and DBMS_CRYPTO for SSN encryption.
    // -------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<CustomerDTO> createCustomer(@RequestBody CustomerDTO dto) {
        log.debug("POST /api/customers firstName={} lastName={}", dto.getFirstName(), dto.getLastName());
        CustomerDTO created = customerService.createCustomer(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // -------------------------------------------------------------------------
    // PUT /api/customers/{id}
    // Updates an existing customer's mutable fields.
    // -------------------------------------------------------------------------
    @PutMapping("/{id}")
    public ResponseEntity<CustomerDTO> updateCustomer(
            @PathVariable Long id,
            @RequestBody CustomerDTO dto) {

        log.debug("PUT /api/customers/{}", id);
        CustomerDTO updated = customerService.updateCustomer(id, dto);
        return ResponseEntity.ok(updated);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/{id}/accounts
    // Returns all accounts belonging to a customer, with ROWNUM pagination.
    // AccountService.getAccountsByCustomer() uses Spring Data JPA.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/accounts")
    public ResponseEntity<List<AccountDTO>> getCustomerAccounts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.debug("GET /api/customers/{}/accounts page={} size={}", id, page, size);
        List<AccountDTO> accounts = accountService.getAccountsByCustomer(id, page, size);
        return ResponseEntity.ok(accounts);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/search?q=query
    // Full-text customer search.  CustomerService.searchCustomers() uses
    // Oracle Text CONTAINS() for document body search and LIKE/INSTR for
    // name/email prefix matching.
    // -------------------------------------------------------------------------
    @GetMapping("/search")
    public ResponseEntity<List<CustomerDTO>> searchCustomers(
            @RequestParam("q") String query) {

        log.debug("GET /api/customers/search q={}", query);
        List<CustomerDTO> results = customerService.searchCustomers(query);
        return ResponseEntity.ok(results);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/{id}/summary
    // Returns a combined customer + account summary view.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/summary")
    public ResponseEntity<CustomerDTO> getCustomerSummary(@PathVariable Long id) {
        log.debug("GET /api/customers/{}/summary", id);
        CustomerDTO summary = customerService.getCustomerWithAccountsSummary(id);
        return ResponseEntity.ok(summary);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/contact-info
    // Returns all active customers with formatted contact info.
    // CustomerService.getCustomerContactInfo() uses Oracle NVL() for null-safe
    // phone/email values.
    // -------------------------------------------------------------------------
    @GetMapping("/contact-info")
    public ResponseEntity<List<CustomerDTO>> getCustomerContactInfo() {
        log.debug("GET /api/customers/contact-info");
        List<CustomerDTO> contacts = customerService.getCustomerContactInfo();
        return ResponseEntity.ok(contacts);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/top-by-balance
    // Returns the top customers ordered by total account balance.
    // ReportOracleDAO uses RATIO_TO_REPORT analytic function internally.
    // -------------------------------------------------------------------------
    @GetMapping("/top-by-balance")
    public ResponseEntity<List<CustomerDTO>> getTopCustomersByBalance() {
        log.debug("GET /api/customers/top-by-balance");
        List<CustomerDTO> top = customerService.getTopCustomersByBalance();
        return ResponseEntity.ok(top);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/recent?daysBack=30
    // Returns customers created within the last N days.
    // Uses Oracle SYSDATE - N day arithmetic internally.
    // -------------------------------------------------------------------------
    @GetMapping("/recent")
    public ResponseEntity<List<CustomerDTO>> getRecentCustomers(
            @RequestParam(defaultValue = "30") int daysBack) {

        log.debug("GET /api/customers/recent daysBack={}", daysBack);
        List<CustomerDTO> recent = customerService.getRecentCustomers(daysBack);
        return ResponseEntity.ok(recent);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/by-name?prefix=Smi
    // Name prefix lookup — uses Oracle INSTR/SUBSTR matching.
    // -------------------------------------------------------------------------
    @GetMapping("/by-name")
    public ResponseEntity<List<CustomerDTO>> getCustomersByNamePrefix(
            @RequestParam("prefix") String prefix) {

        log.debug("GET /api/customers/by-name prefix={}", prefix);
        List<CustomerDTO> results = customerService.findCustomersByNamePrefix(prefix);
        return ResponseEntity.ok(results);
    }

    // -------------------------------------------------------------------------
    // GET /api/customers/stats
    // Returns aggregate customer statistics.
    // -------------------------------------------------------------------------
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCustomerStats() {
        log.debug("GET /api/customers/stats");
        Map<String, Object> stats = new HashMap<>();
        stats.put("recentCustomers30Days", customerService.getRecentCustomers(30).size());
        stats.put("topCustomersByBalance", customerService.getTopCustomersByBalance().size());
        return ResponseEntity.ok(stats);
    }
}

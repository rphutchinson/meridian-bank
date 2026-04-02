package com.meridianbank.controller;

import com.meridianbank.dto.AccountDTO;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.dto.TransferRequestDTO;
import com.meridianbank.service.AccountService;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Account operations.
 *
 * <p>Thin HTTP adapter over AccountService. Oracle-specific operations —
 * ACCOUNT_MGMT_PKG calls, RETURNING INTO, OracleTypes.CURSOR, STRUCT/ARRAY
 * parameter binding, MERGE for balance reconciliation — are all encapsulated
 * in AccountOracleDAO and AccountService.
 *
 * <p>The /transfer endpoint exercises Oracle stored procedure package calls
 * (ACCOUNT_MGMT_PKG.TRANSFER_FUNDS) and Oracle sequence-based transaction
 * reference generation via AccountService.transferFunds().
 */
@RestController
@RequestMapping("/api/accounts")
@CrossOrigin(origins = "*")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    @Autowired
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // -------------------------------------------------------------------------
    // GET /api/accounts/{id}
    // Returns a single account by primary key.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<AccountDTO> getAccountById(@PathVariable Long id) {
        log.debug("GET /api/accounts/{}", id);
        AccountDTO account = accountService.getAccountById(id);
        return ResponseEntity.ok(account);
    }

    // -------------------------------------------------------------------------
    // GET /api/accounts/by-number/{accountNumber}
    // Returns an account by its human-readable account number.
    // -------------------------------------------------------------------------
    @GetMapping("/by-number/{accountNumber}")
    public ResponseEntity<AccountDTO> getAccountByNumber(
            @PathVariable String accountNumber) {

        log.debug("GET /api/accounts/by-number/{}", accountNumber);
        AccountDTO account = accountService.getAccountByNumber(accountNumber);
        return ResponseEntity.ok(account);
    }

    // -------------------------------------------------------------------------
    // POST /api/accounts
    // Opens a new account. Delegates to AccountService.openAccount() which
    // calls ACCOUNT_MGMT_PKG.CREATE_ACCOUNT via AccountOracleDAO (package
    // call, RETURNING INTO, OracleTypes.CURSOR OUT parameter).
    // Returns HTTP 201 Created.
    // -------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<AccountDTO> openAccount(@RequestBody AccountDTO dto) {
        log.debug("POST /api/accounts customerId={} type={}", dto.getCustomerId(), dto.getAccountType());
        AccountDTO created = accountService.openAccount(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // -------------------------------------------------------------------------
    // PUT /api/accounts/{id}/status
    // Updates the account status (ACTIVE, FROZEN, CLOSED).
    // Body: { "status": "FROZEN" }
    // -------------------------------------------------------------------------
    @PutMapping("/{id}/status")
    public ResponseEntity<AccountDTO> updateAccountStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String status = body.get("status");
        log.debug("PUT /api/accounts/{}/status status={}", id, status);
        AccountDTO updated = accountService.updateAccountStatus(id, status);
        return ResponseEntity.ok(updated);
    }

    // -------------------------------------------------------------------------
    // GET /api/accounts/{id}/balance
    // Returns the current balance for an account.
    // AccountService.getBalance() calls ACCOUNT_MGMT_PKG.GET_BALANCE via a
    // stored procedure (OracleTypes.NUMBER OUT parameter).
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable Long id) {
        log.debug("GET /api/accounts/{}/balance", id);
        BigDecimal balance = accountService.getBalance(id);
        Map<String, Object> response = new HashMap<>();
        response.put("accountId", id);
        response.put("balance", balance);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // POST /api/accounts/transfer
    // Transfers funds between two accounts.
    // Body: TransferRequestDTO { fromAccountId, toAccountId, amount, description }
    // AccountService.transferFunds() calls ACCOUNT_MGMT_PKG.TRANSFER_FUNDS
    // and generates a transaction reference using Oracle sequence.
    // -------------------------------------------------------------------------
    @PostMapping("/transfer")
    public ResponseEntity<TransferRequestDTO> transferFunds(
            @RequestBody TransferRequestDTO request) {

        log.debug("POST /api/accounts/transfer from={} to={} amount={}",
                request.getFromAccountId(), request.getToAccountId(), request.getAmount());
        TransferRequestDTO result = accountService.transferFunds(request);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // GET /api/accounts/{id}/transactions?daysBack=30
    // Returns transactions for an account up to daysBack days ago.
    // AccountService.getAccountTransactions() uses Oracle date arithmetic
    // (SYSDATE - daysBack) in the underlying query.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<TransactionDTO>> getAccountTransactions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int daysBack) {

        log.debug("GET /api/accounts/{}/transactions daysBack={}", id, daysBack);
        List<TransactionDTO> transactions = accountService.getAccountTransactions(id, daysBack);
        return ResponseEntity.ok(transactions);
    }

    // -------------------------------------------------------------------------
    // GET /api/accounts/{id}/effective-overdraft
    // Returns accounts with effective overdraft (uses Oracle NVL to default
    // null overdraft_limit to 0).
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/effective-overdraft")
    public ResponseEntity<List<AccountDTO>> getAccountsWithEffectiveOverdraft(
            @PathVariable Long id) {

        log.debug("GET /api/accounts/{}/effective-overdraft", id);
        List<AccountDTO> accounts = accountService.getAccountsWithEffectiveOverdraft(id);
        return ResponseEntity.ok(accounts);
    }

    // -------------------------------------------------------------------------
    // GET /api/accounts/ordered-by-type
    // Returns all accounts ordered by type using Oracle DECODE for custom sort.
    // -------------------------------------------------------------------------
    @GetMapping("/ordered-by-type")
    public ResponseEntity<List<AccountDTO>> getAccountsOrderedByType() {
        log.debug("GET /api/accounts/ordered-by-type");
        List<AccountDTO> accounts = accountService.getAccountsOrderedByType();
        return ResponseEntity.ok(accounts);
    }

    // -------------------------------------------------------------------------
    // GET /api/accounts/sequence/current
    // Returns the current Oracle sequence value (SELECT account_seq.CURRVAL FROM DUAL).
    // -------------------------------------------------------------------------
    @GetMapping("/sequence/current")
    public ResponseEntity<Map<String, Object>> getCurrentSequenceValue() {
        log.debug("GET /api/accounts/sequence/current");
        Long current = accountService.getCurrentSequenceValue();
        Map<String, Object> response = new HashMap<>();
        response.put("currentSequenceValue", current);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // POST /api/accounts/{id}/reconcile
    // Forces a balance reconciliation using Oracle MERGE INTO.
    // Body: { "balance": 12345.67 }
    // -------------------------------------------------------------------------
    @PostMapping("/{id}/reconcile")
    public ResponseEntity<Map<String, Object>> reconcileBalance(
            @PathVariable Long id,
            @RequestBody Map<String, BigDecimal> body) {

        BigDecimal balance = body.get("balance");
        log.debug("POST /api/accounts/{}/reconcile balance={}", id, balance);
        accountService.reconcileBalance(id, balance);
        Map<String, Object> response = new HashMap<>();
        response.put("accountId", id);
        response.put("reconciledBalance", balance);
        response.put("status", "RECONCILED");
        return ResponseEntity.ok(response);
    }
}

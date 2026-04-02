package com.meridianbank.service;

import com.meridianbank.dao.AccountOracleDAO;
import com.meridianbank.dao.TransactionOracleDAO;
import com.meridianbank.dto.AccountDTO;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.dto.TransferRequestDTO;
import com.meridianbank.entity.AccountEntity;
import com.meridianbank.exception.AccountNotFoundException;
import com.meridianbank.exception.InsufficientFundsException;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountService} using Mockito mocks.
 *
 * <p>All tests run without a database connection. They validate service logic
 * that orchestrates Oracle-specific DAO/repository calls:
 *
 * <ul>
 *   <li>ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]  AccountOracleDAO.createAccount() — ACCOUNT_MGMT_PKG</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES]   AccountOracleDAO.getBalance() — OracleTypes.NUMBER OUT</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]     AccountOracleDAO.bulkUpdateStatus() — oracle.sql.ARRAY</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]   AccountOracleDAO.reconcileBalance() — MERGE INTO DUAL</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]    TransactionOracleDAO.recordTransaction() — INSERT RETURNING</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]      Service mirrors NVL(overdraft_limit,0) for available balance</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService unit tests (Mockito — no DB required)")
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountOracleDAO accountOracleDAO;

    @Mock
    private TransactionOracleDAO transactionOracleDAO;

    @Mock
    private OracleErrorTranslator errorTranslator;

    @InjectMocks
    private AccountService accountService;

    // ------------------------------------------------------------------
    // openAccount
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("openAccount() tests")
    class OpenAccountTests {

        /**
         * Verifies that openAccount delegates to AccountOracleDAO.createAccount()
         * (which calls ACCOUNT_MGMT_PKG) and returns an AccountDTO with the generated ID.
         * The service returns the modified input DTO directly without re-fetching from DB.
         */
        @Test
        @DisplayName("openAccount success — delegates to AccountOracleDAO.createAccount()")
        void testOpenAccount_success() throws Exception {
            AccountDTO request = buildAccountDTO(1L, "SAVINGS", "500.00");
            when(accountOracleDAO.createAccount(any(AccountDTO.class))).thenReturn(101L);

            AccountDTO result = accountService.openAccount(request);

            assertNotNull(result, "openAccount must return a non-null AccountDTO");
            assertEquals(101L, result.getAccountId(),
                    "Returned DTO must carry the Oracle-sequence-generated account ID");
            assertEquals("ACTIVE", result.getStatus(),
                    "Newly opened account must have ACTIVE status");
            verify(accountOracleDAO, times(1)).createAccount(any(AccountDTO.class));
        }

        /**
         * Verifies that a duplicate account number (Oracle ORA-00001) propagates
         * as a runtime exception from the service layer.
         */
        @Test
        @DisplayName("openAccount duplicate account number — ORA-00001 translates to exception")
        void testOpenAccount_duplicateAccountNumber() throws Exception {
            AccountDTO request = buildAccountDTO(1L, "CHECKING", "1000.00");
            SQLException oraException = new SQLException(
                    "ORA-00001: unique constraint (MERIDIAN.ACCOUNTS_NUMBER_UK) violated",
                    "23000", 1);
            when(accountOracleDAO.createAccount(any(AccountDTO.class)))
                    .thenThrow(oraException);
            when(errorTranslator.translateSQLException(any(SQLException.class)))
                    .thenReturn(new MeridianBankException("DUPLICATE", "Duplicate", 409));

            assertThrows(MeridianBankException.class,
                    () -> accountService.openAccount(request),
                    "ORA-00001 from Oracle must be translated to MeridianBankException");
        }

        /**
         * Verifies that openAccount rejects an invalid account type before calling the DAO.
         */
        @Test
        @DisplayName("openAccount invalid account type — throws MeridianBankException before DAO call")
        void testOpenAccount_invalidAccountType() {
            AccountDTO request = buildAccountDTO(1L, "INVALID_TYPE", "500.00");

            assertThrows(MeridianBankException.class,
                    () -> accountService.openAccount(request),
                    "Invalid account type must throw MeridianBankException");
            verifyNoInteractions(accountOracleDAO);
        }
    }

    // ------------------------------------------------------------------
    // getBalance
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getBalance() tests")
    class GetBalanceTests {

        /**
         * Verifies getBalance calls AccountOracleDAO.getBalance() (OracleTypes.NUMBER OUT param)
         * and returns the correct balance.
         */
        @Test
        @DisplayName("getBalance success — returns balance via OracleTypes.NUMBER OUT param mock")
        void testGetBalance_success() throws Exception {
            when(accountOracleDAO.getBalance(1L)).thenReturn(new BigDecimal("2500.00"));

            BigDecimal balance = accountService.getBalance(1L);

            assertNotNull(balance, "getBalance must return a non-null BigDecimal");
            assertEquals(0, new BigDecimal("2500.00").compareTo(balance),
                    "Balance must match the value returned by OracleDAO mock");
            verify(accountOracleDAO, times(1)).getBalance(1L);
        }

        /**
         * Verifies getBalance propagates AccountNotFoundException for unknown account.
         */
        @Test
        @DisplayName("getBalance unknown account — throws AccountNotFoundException")
        void testGetBalance_unknownAccount() throws Exception {
            when(accountOracleDAO.getBalance(999L))
                    .thenThrow(new AccountNotFoundException(999L));

            assertThrows(AccountNotFoundException.class,
                    () -> accountService.getBalance(999L),
                    "Unknown account must throw AccountNotFoundException");
        }
    }

    // ------------------------------------------------------------------
    // transferFunds
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("transferFunds() tests")
    class TransferFundsTests {

        /**
         * Verifies that transferFunds throws InsufficientFundsException when
         * the source account entity balance is less than the transfer amount.
         *
         * The service reads balance from AccountEntity.getBalance() directly
         * after loading via accountRepository.findById(). It computes:
         * available = balance + NVL(overdraftLimit, 0)
         *
         * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]: Java mirror of NVL(overdraft_limit,0).
         */
        @Test
        @DisplayName("transferFunds insufficient balance — throws InsufficientFundsException")
        void testTransferFunds_insufficientBalance() {
            // Source account has only $10.00 with no overdraft
            AccountEntity fromEntity = buildAccountEntity(1L, 10L, "CHK-001", "CHECKING");
            fromEntity.setBalance(new BigDecimal("10.00"));
            fromEntity.setOverdraftLimit(BigDecimal.ZERO);

            AccountEntity toEntity = buildAccountEntity(2L, 20L, "SAV-001", "SAVINGS");
            toEntity.setBalance(new BigDecimal("500.00"));

            when(accountRepository.findById(1L)).thenReturn(Optional.of(fromEntity));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(toEntity));

            TransferRequestDTO request = buildTransferRequest(1L, 2L, "100.00");

            assertThrows(InsufficientFundsException.class,
                    () -> accountService.transferFunds(request),
                    "Transfer of $100 from account with $10 balance must throw InsufficientFundsException");
        }

        /**
         * Verifies that a successful transfer records two transactions (debit + credit)
         * via TransactionOracleDAO.recordTransaction() and saves both account entities.
         *
         * ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]: TransactionOracleDAO.recordTransaction
         * uses INSERT ... RETURNING transaction_id INTO ? to get the generated ID.
         */
        @Test
        @DisplayName("transferFunds success — records 2 transactions via TransactionOracleDAO RETURNING INTO")
        void testTransferFunds_success() throws Exception {
            AccountEntity fromEntity = buildAccountEntity(1L, 10L, "CHK-001", "CHECKING");
            fromEntity.setBalance(new BigDecimal("1000.00"));
            fromEntity.setOverdraftLimit(BigDecimal.ZERO);

            AccountEntity toEntity = buildAccountEntity(2L, 20L, "SAV-001", "SAVINGS");
            toEntity.setBalance(new BigDecimal("500.00"));
            toEntity.setOverdraftLimit(BigDecimal.ZERO);

            when(accountRepository.findById(1L)).thenReturn(Optional.of(fromEntity));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(toEntity));
            when(transactionOracleDAO.recordTransaction(any(TransactionDTO.class)))
                    .thenReturn(201L)
                    .thenReturn(202L);
            when(accountRepository.save(any(AccountEntity.class)))
                    .thenAnswer(i -> i.getArgument(0));

            TransferRequestDTO request = buildTransferRequest(1L, 2L, "250.00");
            TransferRequestDTO result = accountService.transferFunds(request);

            assertNotNull(result, "Successful transfer must return a non-null result");
            assertEquals("COMPLETED", result.getStatus());
            // Exactly two INSERT ... RETURNING INTO calls (debit + credit)
            verify(transactionOracleDAO, times(2))
                    .recordTransaction(any(TransactionDTO.class));
            // Both account entities must be saved with updated balances
            verify(accountRepository, times(2)).save(any(AccountEntity.class));
        }

        /**
         * Verifies that transferFunds uses overdraft limit in the available balance
         * calculation — mirroring Oracle NVL(overdraft_limit, 0).
         */
        @Test
        @DisplayName("transferFunds with overdraft allows transfer exceeding nominal balance")
        void testTransferFunds_withOverdraftAllowsTransfer() throws Exception {
            AccountEntity fromEntity = buildAccountEntity(1L, 10L, "CHK-001", "CHECKING");
            fromEntity.setBalance(new BigDecimal("50.00"));
            fromEntity.setOverdraftLimit(new BigDecimal("200.00")); // effective available = 250.00

            AccountEntity toEntity = buildAccountEntity(2L, 20L, "SAV-001", "SAVINGS");
            toEntity.setBalance(new BigDecimal("500.00"));
            toEntity.setOverdraftLimit(BigDecimal.ZERO);

            when(accountRepository.findById(1L)).thenReturn(Optional.of(fromEntity));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(toEntity));
            when(transactionOracleDAO.recordTransaction(any(TransactionDTO.class)))
                    .thenReturn(301L).thenReturn(302L);
            when(accountRepository.save(any(AccountEntity.class)))
                    .thenAnswer(i -> i.getArgument(0));

            // Transfer of 150 > balance(50) but <= balance+overdraft(250)
            TransferRequestDTO request = buildTransferRequest(1L, 2L, "150.00");

            assertDoesNotThrow(
                    () -> accountService.transferFunds(request),
                    "Transfer within overdraft limit must succeed (NVL overdraft handling)");
        }
    }

    // ------------------------------------------------------------------
    // updateAccountStatus
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("updateAccountStatus() tests")
    class UpdateAccountStatusTests {

        /**
         * Verifies that updateAccountStatus saves the entity with the new status
         * and returns an AccountDTO with the updated status.
         */
        @Test
        @DisplayName("updateAccountStatus success — saves entity with updated status via JPA")
        void testUpdateAccountStatus_success() {
            AccountEntity entity = buildAccountEntity(1L, 1L, "CHK-001", "CHECKING");
            entity.setStatus("ACTIVE");
            when(accountRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(accountRepository.save(any(AccountEntity.class)))
                    .thenAnswer(i -> i.getArgument(0));

            AccountDTO result = accountService.updateAccountStatus(1L, "FROZEN");

            assertNotNull(result);
            verify(accountRepository, times(1)).save(any(AccountEntity.class));

            ArgumentCaptor<AccountEntity> captor = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accountRepository).save(captor.capture());
            assertEquals("FROZEN", captor.getValue().getStatus(),
                    "Saved entity must have FROZEN status after updateAccountStatus call");
        }

        /**
         * Verifies that updating a non-existent account throws AccountNotFoundException.
         */
        @Test
        @DisplayName("updateAccountStatus unknown account — throws AccountNotFoundException")
        void testUpdateAccountStatus_notFound() {
            when(accountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(AccountNotFoundException.class,
                    () -> accountService.updateAccountStatus(999L, "FROZEN"),
                    "Updating status for unknown account must throw AccountNotFoundException");
        }
    }

    // ------------------------------------------------------------------
    // bulkUpdateAccountStatuses
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("bulkUpdateAccountStatuses() tests")
    class BulkUpdateTests {

        /**
         * Verifies that bulkUpdateAccountStatuses delegates to AccountOracleDAO
         * (which uses oracle.sql.ARRAY NUMBER_TABLE_TYPE for bulk binding).
         */
        @Test
        @DisplayName("bulkUpdateAccountStatuses success — delegates to Oracle ARRAY-based DAO")
        void testBulkUpdateStatuses_success() throws Exception {
            List<Long> ids = Arrays.asList(1L, 2L, 3L, 4L, 5L);
            when(accountOracleDAO.bulkUpdateStatus(ids, "ACTIVE")).thenReturn(5);

            int updated = accountService.bulkUpdateAccountStatuses(ids, "ACTIVE");

            assertEquals(5, updated,
                    "Bulk update must return count from Oracle ARRAY-based DAO call");
            verify(accountOracleDAO, times(1)).bulkUpdateStatus(ids, "ACTIVE");
        }

        /**
         * Verifies that passing an empty list returns 0 without calling the DAO.
         */
        @Test
        @DisplayName("bulkUpdateAccountStatuses empty list — returns 0 without calling DAO")
        void testBulkUpdateStatuses_emptyList() throws Exception {
            int updated = accountService.bulkUpdateAccountStatuses(List.of(), "ACTIVE");

            assertEquals(0, updated);
            verifyNoInteractions(accountOracleDAO);
        }
    }

    // ------------------------------------------------------------------
    // getAccountById
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getAccountById() tests")
    class GetAccountByIdTests {

        /**
         * Verifies that a known account ID returns a correctly mapped AccountDTO.
         */
        @Test
        @DisplayName("getAccountById found — returns AccountDTO with correct ID and account number")
        void testGetAccountById_found() {
            AccountEntity entity = buildAccountEntity(1L, 1L, "CHK-001", "CHECKING");
            when(accountRepository.findById(1L)).thenReturn(Optional.of(entity));

            AccountDTO result = accountService.getAccountById(1L);

            assertNotNull(result, "getAccountById must return non-null for known ID");
            assertEquals(1L, result.getAccountId(), "AccountDTO must carry the correct account ID");
            assertEquals("CHK-001", result.getAccountNumber(),
                    "AccountDTO must carry the correct account number");
        }

        /**
         * Verifies that an unknown account ID throws AccountNotFoundException.
         */
        @Test
        @DisplayName("getAccountById not found — throws AccountNotFoundException")
        void testGetAccountById_notFound() {
            when(accountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(AccountNotFoundException.class,
                    () -> accountService.getAccountById(999L),
                    "Unknown account ID must throw AccountNotFoundException");
        }
    }

    // ------------------------------------------------------------------
    // reconcileBalance
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("reconcileBalance() tests")
    class ReconcileBalanceTests {

        /**
         * Verifies that reconcileBalance delegates to AccountOracleDAO.reconcileBalance()
         * which executes the Oracle MERGE INTO ... USING (SELECT ... FROM DUAL) statement.
         */
        @Test
        @DisplayName("reconcileBalance success — delegates to Oracle MERGE DAO method")
        void testReconcileBalance_success() throws Exception {
            doNothing().when(accountOracleDAO)
                    .reconcileBalance(eq(1L), any(BigDecimal.class));

            assertDoesNotThrow(
                    () -> accountService.reconcileBalance(1L, new BigDecimal("1500.00")),
                    "MERGE-based reconcileBalance must not throw for valid inputs");

            verify(accountOracleDAO, times(1))
                    .reconcileBalance(eq(1L), eq(new BigDecimal("1500.00")));
        }

        /**
         * Verifies that a negative balance is rejected before calling the DAO.
         */
        @Test
        @DisplayName("reconcileBalance negative balance — throws MeridianBankException before DAO")
        void testReconcileBalance_negativeBalance() {
            assertThrows(MeridianBankException.class,
                    () -> accountService.reconcileBalance(1L, new BigDecimal("-100.00")),
                    "Negative reconciliation balance must throw MeridianBankException");
            verifyNoInteractions(accountOracleDAO);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AccountDTO buildAccountDTO(Long customerId, String type, String initialDeposit) {
        AccountDTO dto = new AccountDTO();
        dto.setCustomerId(customerId);
        dto.setAccountType(type);
        dto.setInitialDeposit(new BigDecimal(initialDeposit));
        dto.setOpenDate(LocalDate.now());
        dto.setBranchId(1L);
        return dto;
    }

    private AccountEntity buildAccountEntity(Long accountId, Long customerId,
                                              String number, String type) {
        AccountEntity e = new AccountEntity();
        e.setAccountId(accountId);
        e.setCustomerId(customerId);
        e.setAccountNumber(number);
        e.setAccountType(type);
        e.setStatus("ACTIVE");
        e.setBalance(new BigDecimal("1000.00"));
        e.setOverdraftLimit(BigDecimal.ZERO);
        e.setOpenDate(LocalDate.now());
        return e;
    }

    private TransferRequestDTO buildTransferRequest(Long fromId, Long toId, String amount) {
        TransferRequestDTO dto = new TransferRequestDTO();
        dto.setFromAccountId(fromId);
        dto.setToAccountId(toId);
        dto.setAmount(new BigDecimal(amount));
        dto.setDescription("Test transfer");
        return dto;
    }
}

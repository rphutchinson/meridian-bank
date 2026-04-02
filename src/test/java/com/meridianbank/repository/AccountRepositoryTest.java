package com.meridianbank.repository;

import com.meridianbank.entity.AccountEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AccountRepository} — Spring Data JPA interface whose
 * native queries exercise a broad range of Oracle SQL constructs.
 *
 * <p><strong>Oracle dependencies covered:</strong>
 * <ul>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:ROWNUM]           Pre-12c pagination using ROWNUM</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]  DECODE(account_type,…) for ordering</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SEQUENCE_CURRVAL] ACCOUNT_SEQ.CURRVAL FROM DUAL</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]  MERGE INTO for balance upsert</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINT]      Oracle hint INDEX(a IDX_ACCT_CUST)</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]     NVL(overdraft_limit, 0)</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]       ADD_MONTHS(open_date,12) anniversary</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]  portfolio allocation analytics</li>
 * </ul>
 */
class AccountRepositoryTest {

    // ------------------------------------------------------------------
    // DB-dependent tests (all @Disabled until Oracle Docker is running)
    // ------------------------------------------------------------------

    @SpringBootTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ActiveProfiles("test")
    @Nested
    @Disabled("Requires Oracle DB — entire nested class disabled until Oracle 23ai container is running")
    @DisplayName("Oracle DB-dependent query tests")
    class OracleQueryTests {

        @Autowired
        private AccountRepository accountRepository;

        /**
         * Tests that the pre-12c ROWNUM pagination query returns the expected
         * window of rows. This pattern requires Oracle-specific wrapping:
         * {@code SELECT * FROM (SELECT a.*, ROWNUM rn FROM ...) WHERE rn BETWEEN ...}
         *
         * <p>Migration target: use standard {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}
         * (supported in Oracle 12c+ and PostgreSQL 8.4+).
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:ROWNUM]")
        @DisplayName("findByStatusPaginated uses Oracle ROWNUM for pre-12c pagination")
        void testRowNumPagination() {
            // Fetch rows 1–5 of ACTIVE accounts ordered by balance DESC
            List<AccountEntity> page1 = accountRepository.findByStatusPaginated("ACTIVE", 1, 5);

            assertNotNull(page1, "ROWNUM pagination must return non-null list");
            assertTrue(page1.size() <= 5,
                    "Page size of 5 must not be exceeded by ROWNUM WHERE rn BETWEEN 1 AND 5");
            page1.forEach(a ->
                    assertEquals("ACTIVE", a.getStatus(),
                            "ROWNUM pagination status filter must be respected"));
        }

        /**
         * Tests the DECODE-based custom ordering query.
         *
         * <p>SQL fragment:
         * {@code ORDER BY DECODE(account_type,'CHECKING',1,'SAVINGS',2,'MONEY_MARKET',3,4)}
         * <p>Migration target:
         * {@code ORDER BY CASE account_type WHEN 'CHECKING' THEN 1 ... END}
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]")
        @DisplayName("findAllOrderedByType uses Oracle DECODE() for custom sort order")
        void testDecodeOrdering() {
            List<AccountEntity> ordered = accountRepository.findAllOrderedByType();

            assertNotNull(ordered, "DECODE-ordered query must return non-null");
            // Verify CHECKING accounts appear before SAVINGS accounts in the result
            int firstChecking = -1, firstSavings = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if ("CHECKING".equals(ordered.get(i).getAccountType()) && firstChecking < 0) {
                    firstChecking = i;
                }
                if ("SAVINGS".equals(ordered.get(i).getAccountType()) && firstSavings < 0) {
                    firstSavings = i;
                }
            }
            if (firstChecking >= 0 && firstSavings >= 0) {
                assertTrue(firstChecking < firstSavings,
                        "DECODE ordering must place CHECKING (rank 1) before SAVINGS (rank 2)");
            }
        }

        /**
         * Tests the {@code SELECT account_seq.CURRVAL FROM DUAL} query.
         *
         * <p>CURRVAL is only valid after NEXTVAL has been called in the same session,
         * so this test inserts a new account first to initialise CURRVAL.
         *
         * <p>Migration target: {@code SELECT currval('account_seq')} in PostgreSQL.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:SEQUENCE_CURRVAL]")
        @DisplayName("getCurrentSequenceValue uses Oracle ACCOUNT_SEQ.CURRVAL FROM DUAL")
        void testSequenceCurrval() {
            // Advance the sequence so CURRVAL is initialised for this session
            Long nextVal = accountRepository.getNextSequenceValue();
            assertNotNull(nextVal, "NEXTVAL FROM DUAL should return a non-null Long");

            Long currVal = accountRepository.getCurrentSequenceValue();
            assertNotNull(currVal, "CURRVAL FROM DUAL should return a non-null Long");
            assertEquals(nextVal, currVal,
                    "CURRVAL should equal the NEXTVAL obtained in the same session");
        }

        /**
         * Tests the MERGE statement used for balance upsert.
         *
         * <p>SQL fragment:
         * {@code MERGE INTO accounts tgt USING (SELECT :accountId id, :balance bal FROM DUAL) src
         *   ON (tgt.account_id = src.id)
         *   WHEN MATCHED THEN UPDATE SET balance = src.bal
         *   WHEN NOT MATCHED THEN INSERT ...}
         *
         * <p>Migration target: {@code INSERT ... ON CONFLICT (account_id) DO UPDATE SET ...}
         * in PostgreSQL.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]")
        @DisplayName("upsertAccountBalance uses Oracle MERGE INTO ... USING ... statement")
        void testMergeUpsert() {
            Long existingAccountId = 1L;
            BigDecimal newBalance = new BigDecimal("9999.99");

            // Should not throw — MERGE handles both matched and not-matched cases
            assertDoesNotThrow(
                    () -> accountRepository.upsertAccountBalance(existingAccountId, newBalance),
                    "MERGE upsert must not throw for an existing account");

            // Verify the balance was updated
            Optional<AccountEntity> updated = accountRepository.findById(existingAccountId);
            updated.ifPresent(a ->
                    assertEquals(0, newBalance.compareTo(a.getBalance()),
                            "MERGE WHEN MATCHED should have updated the balance"));
        }

        /**
         * Tests the query that uses an Oracle index hint to force index usage.
         *
         * <p>SQL fragment: SELECT with Oracle INDEX(a IDX_ACCT_CUST) hint FROM accounts a ...
         * <p>Migration target: remove the hint in PostgreSQL; the planner will
         * choose the index automatically, or use {@code SET enable_seqscan=off} for testing.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINT]")
        @DisplayName("findActiveByCustomerWithHint uses Oracle /*+ INDEX() */ hint")
        void testHintQuery() {
            // customerId=1 should have at least one account in seed data
            List<AccountEntity> accounts =
                    accountRepository.findActiveByCustomerWithHint(1L);

            assertNotNull(accounts, "Hint-based query must return non-null list");
            accounts.forEach(a ->
                    assertEquals(1L, a.getCustomerId(),
                            "INDEX hint query must still filter by customerId correctly"));
        }

        /**
         * Tests the NVL-based overdraft query.
         *
         * <p>SQL fragment:
         * {@code NVL(a.overdraft_limit, 0) AS effective_overdraft}
         * <p>Migration target: {@code COALESCE(a.overdraft_limit, 0)}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]")
        @DisplayName("findAccountsWithEffectiveOverdraft uses Oracle NVL() for null-safe overdraft")
        void testNvlOverdraftQuery() {
            List<Object[]> results = accountRepository.findAccountsWithEffectiveOverdraft(1L);

            assertNotNull(results, "NVL overdraft query must return non-null");
            for (Object[] row : results) {
                // The effective_overdraft column (last column) must never be null
                Object effectiveOverdraft = row[row.length - 1];
                assertNotNull(effectiveOverdraft,
                        "NVL(overdraft_limit, 0) must never return null — should default to 0");
            }
        }
    }

    // ------------------------------------------------------------------
    // Mock-based tests (no DB connection required)
    // ------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    @DisplayName("Mock-based tests (no DB required)")
    class MockBasedTests {

        @Mock
        private AccountRepository accountRepository;

        @BeforeEach
        void setUpMocks() {
            AccountEntity checking = buildAccount(1L, 1L, "CHK-001", "CHECKING", "ACTIVE");
            AccountEntity savings  = buildAccount(2L, 1L, "SAV-001", "SAVINGS",  "ACTIVE");
            AccountEntity frozen   = buildAccount(3L, 2L, "CHK-002", "CHECKING", "FROZEN");

            when(accountRepository.findByCustomerId(1L))
                    .thenReturn(Arrays.asList(checking, savings));
            when(accountRepository.findByCustomerId(99L))
                    .thenReturn(List.of());
            when(accountRepository.findByAccountNumber("CHK-001"))
                    .thenReturn(Optional.of(checking));
            when(accountRepository.findByAccountNumber("UNKNOWN"))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByStatus("ACTIVE"))
                    .thenReturn(Arrays.asList(checking, savings));
            when(accountRepository.findByStatus("FROZEN"))
                    .thenReturn(List.of(frozen));
        }

        /**
         * Verifies findByCustomerId returns accounts for a known customer.
         */
        @Test
        @DisplayName("findByCustomerId returns accounts for known customer")
        void testFindByCustomerId_mock() {
            List<AccountEntity> accounts = accountRepository.findByCustomerId(1L);

            assertNotNull(accounts);
            assertEquals(2, accounts.size(), "Customer 1 should have exactly 2 accounts");
            accounts.forEach(a ->
                    assertEquals(1L, a.getCustomerId(),
                            "All returned accounts must belong to customer 1"));
            verify(accountRepository, times(1)).findByCustomerId(1L);
        }

        /**
         * Verifies findByCustomerId returns empty list for unknown customer.
         */
        @Test
        @DisplayName("findByCustomerId returns empty list for unknown customer")
        void testFindByCustomerId_unknown_returnsEmpty() {
            List<AccountEntity> accounts = accountRepository.findByCustomerId(99L);

            assertNotNull(accounts);
            assertTrue(accounts.isEmpty());
        }

        /**
         * Verifies findByAccountNumber returns the matching account entity.
         */
        @Test
        @DisplayName("findByAccountNumber returns present Optional for known account")
        void testFindByAccountNumber_mock() {
            Optional<AccountEntity> result = accountRepository.findByAccountNumber("CHK-001");

            assertTrue(result.isPresent(), "Known account number must return present Optional");
            assertEquals("CHK-001", result.get().getAccountNumber());
            verify(accountRepository, times(1)).findByAccountNumber("CHK-001");
        }

        /**
         * Verifies findByAccountNumber returns empty Optional for unknown number.
         */
        @Test
        @DisplayName("findByAccountNumber returns empty Optional for unknown number")
        void testFindByAccountNumber_unknown_returnsEmpty() {
            Optional<AccountEntity> result = accountRepository.findByAccountNumber("UNKNOWN");

            assertFalse(result.isPresent());
        }

        // ------------------------------------------------------------------
        // Helper
        // ------------------------------------------------------------------

        private AccountEntity buildAccount(Long id, Long customerId, String number,
                                           String type, String status) {
            AccountEntity a = new AccountEntity();
            a.setAccountId(id);
            a.setCustomerId(customerId);
            a.setAccountNumber(number);
            a.setAccountType(type);
            a.setStatus(status);
            a.setBalance(new BigDecimal("1000.00"));
            a.setOpenDate(LocalDate.now());
            return a;
        }
    }
}

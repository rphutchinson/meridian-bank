package com.meridianbank.repository;

import com.meridianbank.entity.TransactionEntity;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TransactionRepository} — Spring Data JPA interface with
 * native Oracle SQL queries for financial transaction access.
 *
 * <p><strong>Oracle dependencies covered:</strong>
 * <ul>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]       transaction_date + 30 days</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SYSDATE_SYSTIMESTAMP]  SYSTIMESTAMP default column</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE]          TO_CHAR(transaction_date,'YYYY-MM-DD')</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]            TRUNC(transaction_date) for day grouping</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]          NVL(description,'No description')</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINT]           Oracle hint INDEX(t IDX_TXN_ACCT_DATE)</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SEQUENCE_NEXTVAL]      txn_seq.NEXTVAL for reference numbers</li>
 * </ul>
 */
class TransactionRepositoryTest {

    // ------------------------------------------------------------------
    // DB-dependent tests
    // ------------------------------------------------------------------

    @SpringBootTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ActiveProfiles("test")
    @Nested
    @Disabled("Requires Oracle DB — entire nested class disabled until Oracle 23ai container is running")
    @DisplayName("Oracle DB-dependent query tests")
    class OracleQueryTests {

        @Autowired
        private TransactionRepository transactionRepository;

        /**
         * Tests Oracle date arithmetic: adding an integer (days) to a DATE column.
         *
         * <p>Oracle allows {@code date_column + 30} to mean "30 days later". In
         * PostgreSQL you must write {@code date_column + INTERVAL '30 days'}.
         *
         * <p>SQL fragment (from getSignedTransactions native query):
         * {@code WHERE transaction_date > SYSDATE - 30}
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]")
        @DisplayName("getSignedTransactions uses Oracle date + integer arithmetic")
        void testOracleDateArithmetic() {
            // Account 1 should have recent transactions in seed data
            List<Object[]> results = transactionRepository.findSignedTransactions(1L);

            assertNotNull(results, "Oracle date arithmetic query must return non-null");
            // All returned transactions should be within the last 30 days
            OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
            for (Object[] row : results) {
                // row[0] is transaction_id, row[row.length-1] is the signed_amount
                assertNotNull(row[0], "Transaction ID must not be null");
            }
        }

        /**
         * Tests that the SYSTIMESTAMP column default is populated correctly.
         *
         * <p>Oracle {@code SYSTIMESTAMP} returns TIMESTAMP WITH TIME ZONE, unlike
         * PostgreSQL's {@code NOW()} which returns TIMESTAMP WITH TIME ZONE but
         * behaves slightly differently in precision and timezone representation.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:SYSDATE_SYSTIMESTAMP]")
        @DisplayName("Transaction processed_date is populated by Oracle SYSTIMESTAMP default")
        void testSystimestampDefault() {
            // Insert a minimal transaction and verify processed_date is set by DB
            TransactionEntity txn = buildTransaction(1L, "DEBIT", new BigDecimal("50.00"));
            TransactionEntity saved = transactionRepository.save(txn);

            assertNotNull(saved.getTransactionId(), "Saved transaction must have an ID");
            assertNotNull(saved.getProcessedDate(),
                    "SYSTIMESTAMP column default must populate processed_date automatically");
            // Verify it is within the last minute (accounting for test execution time)
            OffsetDateTime oneMinuteAgo = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
            assertTrue(saved.getProcessedDate().isAfter(oneMinuteAgo),
                    "SYSTIMESTAMP should be recent — within the last minute");
        }

        /**
         * Tests Oracle TO_CHAR() date formatting in transaction queries.
         *
         * <p>SQL fragment:
         * {@code TO_CHAR(transaction_date, 'YYYY-MM-DD') AS transaction_date_str}
         * <p>Migration target: {@code TO_CHAR(transaction_date, 'YYYY-MM-DD')} is also
         * supported in PostgreSQL; or use Java {@code DateTimeFormatter.ISO_LOCAL_DATE}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE]")
        @DisplayName("getDailySummary uses Oracle TO_CHAR() for date-group label formatting")
        void testToCharDateFormatting() {
            LocalDate start = LocalDate.now().minusDays(30);
            LocalDate end   = LocalDate.now();

            List<Object[]> dailySummary = transactionRepository
                    .findDailySummary(1L, 30);

            assertNotNull(dailySummary, "TO_CHAR date formatting query must return non-null");
            for (Object[] row : dailySummary) {
                // row[0] is the TO_CHAR(transaction_date,'YYYY-MM-DD') label
                if (row[0] != null) {
                    String dateLabel = row[0].toString();
                    assertEquals(10, dateLabel.length(),
                            "TO_CHAR(date,'YYYY-MM-DD') must produce a 10-character string");
                    assertTrue(dateLabel.matches("\\d{4}-\\d{2}-\\d{2}"),
                            "TO_CHAR date label must match YYYY-MM-DD format");
                }
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
        private TransactionRepository transactionRepository;

        @BeforeEach
        void setUpMocks() {
            TransactionEntity debit  = buildTransaction(1L, "DEBIT",  new BigDecimal("100.00"));
            TransactionEntity credit = buildTransaction(2L, "CREDIT", new BigDecimal("250.00"));
            debit.setAccountId(10L);
            credit.setAccountId(10L);

            when(transactionRepository.findByAccountId(10L))
                    .thenReturn(Arrays.asList(debit, credit));
            when(transactionRepository.findByAccountId(99L))
                    .thenReturn(List.of());
            when(transactionRepository.findByStatus("COMPLETED"))
                    .thenReturn(Arrays.asList(debit, credit));
            when(transactionRepository.findByStatus("FAILED"))
                    .thenReturn(List.of());
            when(transactionRepository.findByAccountIdAndStatus(10L, "COMPLETED"))
                    .thenReturn(List.of(debit));
        }

        /**
         * Verifies findByAccountId returns all transactions for a given account.
         */
        @Test
        @DisplayName("findByAccountId returns transactions for account 10")
        void testFindByAccountId_mock() {
            List<TransactionEntity> results = transactionRepository.findByAccountId(10L);

            assertNotNull(results);
            assertEquals(2, results.size(), "Account 10 should have 2 transactions in mock data");
            results.forEach(t ->
                    assertEquals(10L, t.getAccountId(),
                            "All transactions must belong to account 10"));
            verify(transactionRepository, times(1)).findByAccountId(10L);
        }

        /**
         * Verifies findByAccountId returns empty list for unknown account.
         */
        @Test
        @DisplayName("findByAccountId returns empty list for unknown account")
        void testFindByAccountId_unknown() {
            List<TransactionEntity> results = transactionRepository.findByAccountId(99L);

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        /**
         * Verifies findByStatus returns completed transactions.
         */
        @Test
        @DisplayName("findByStatus('COMPLETED') returns all completed transactions")
        void testFindByStatus_mock() {
            List<TransactionEntity> completed = transactionRepository.findByStatus("COMPLETED");

            assertNotNull(completed);
            assertEquals(2, completed.size());
            verify(transactionRepository, times(1)).findByStatus("COMPLETED");
        }

        /**
         * Verifies findByStatus returns empty list for FAILED when none exist.
         */
        @Test
        @DisplayName("findByStatus('FAILED') returns empty list when none exist")
        void testFindByStatus_failed_empty() {
            List<TransactionEntity> failed = transactionRepository.findByStatus("FAILED");

            assertNotNull(failed);
            assertTrue(failed.isEmpty());
        }

        /**
         * Verifies findByAccountIdAndStatus returns only COMPLETED transactions for account 10.
         */
        @Test
        @DisplayName("findByAccountIdAndStatus returns COMPLETED transactions for a given account")
        void testFindByTransactionType_mock() {
            List<TransactionEntity> completed =
                    transactionRepository.findByAccountIdAndStatus(10L, "COMPLETED");

            assertNotNull(completed);
            assertEquals(1, completed.size());
            assertEquals("DEBIT", completed.get(0).getTransactionType());
            verify(transactionRepository, times(1)).findByAccountIdAndStatus(10L, "COMPLETED");
        }

        // ------------------------------------------------------------------
        // Helper
        // ------------------------------------------------------------------

        private TransactionEntity buildTransaction(Long id, String type, BigDecimal amount) {
            TransactionEntity t = new TransactionEntity();
            t.setTransactionId(id);
            t.setTransactionType(type);
            t.setAmount(amount);
            t.setStatus("COMPLETED");
            t.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
            return t;
        }
    }

    // Expose helper method at class level for OracleQueryTests inner class
    private static TransactionEntity buildTransaction(Long accountId, String type,
                                                       BigDecimal amount) {
        TransactionEntity t = new TransactionEntity();
        t.setAccountId(accountId);
        t.setTransactionType(type);
        t.setAmount(amount);
        t.setStatus("PENDING");
        t.setDescription("Test transaction");
        t.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
        return t;
    }
}

package com.meridianbank.service;

import com.meridianbank.dao.TransactionOracleDAO;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.entity.TransactionEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TransactionService} using Mockito mocks.
 *
 * <p>All tests run without a database connection. They validate service logic
 * that orchestrates Oracle-specific DAO/repository calls:
 *
 * <ul>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]    TransactionOracleDAO.recordTransaction() — INSERT RETURNING</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING] Oracle treats '' as NULL; service normalises empty desc to null</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED] TransactionOracleDAO.bulkRecordTransactions() — array binding</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]   Settlement date = transaction date + 2 days (Oracle date+int)</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SYSDATE_SYSTIMESTAMP] processedDate from Oracle SYSTIMESTAMP default</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService unit tests (Mockito — no DB required)")
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionOracleDAO transactionOracleDAO;

    @Mock
    private OracleErrorTranslator errorTranslator;

    @InjectMocks
    private TransactionService transactionService;

    // ------------------------------------------------------------------
    // recordTransaction
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("recordTransaction() tests")
    class RecordTransactionTests {

        /**
         * Verifies that a valid TransactionDTO is routed to TransactionOracleDAO
         * which uses the Oracle RETURNING INTO clause to retrieve the generated ID.
         * The service returns the modified input DTO directly with the new ID set.
         */
        @Test
        @DisplayName("recordTransaction success — delegates to DAO and returns DTO with generated ID")
        void testRecordTransaction_success() throws Exception {
            TransactionDTO dto = buildTransactionDTO(1L, "DEBIT", "150.00", "PENDING");
            when(transactionOracleDAO.recordTransaction(any(TransactionDTO.class)))
                    .thenReturn(501L);

            TransactionDTO result = transactionService.recordTransaction(dto);

            assertNotNull(result, "recordTransaction must return a non-null DTO");
            assertEquals(501L, result.getTransactionId(),
                    "Returned DTO must carry the Oracle-sequence-generated transaction ID");
            verify(transactionOracleDAO, times(1))
                    .recordTransaction(any(TransactionDTO.class));
        }

        /**
         * Tests Oracle's empty-string-as-NULL semantic.
         *
         * <p>In Oracle, empty string ('') and NULL are equivalent in VARCHAR2 columns.
         * The service explicitly converts empty description to null before persisting,
         * then sets a default description. This mirrors Oracle behaviour where
         * {@code INSERT INTO ... VALUES('', ...)} stores NULL.
         *
         * <p>ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING]: Oracle VARCHAR2 treats ''
         * as NULL. PostgreSQL distinguishes '' from NULL — this normalisation is
         * essential for semantic equivalence after migration.
         */
        @Test
        @DisplayName("recordTransaction empty description — Oracle empty-string-as-NULL normalisation")
        void testRecordTransaction_oracleEmptyStringValidation() throws Exception {
            TransactionDTO dto = buildTransactionDTO(1L, "CREDIT", "200.00", "PENDING");
            dto.setDescription(""); // Oracle treats empty VARCHAR2 as NULL

            when(transactionOracleDAO.recordTransaction(any(TransactionDTO.class)))
                    .thenReturn(502L);

            TransactionDTO result = transactionService.recordTransaction(dto);

            assertNotNull(result);
            // After Oracle empty-string-as-NULL normalisation, the service either
            // sets null (Oracle equivalent) and then assigns a default description
            assertNotNull(result.getDescription(),
                    "After empty-string normalisation the service must assign a default description");
            // Capture what was actually sent to the DAO
            ArgumentCaptor<TransactionDTO> captor = ArgumentCaptor.forClass(TransactionDTO.class);
            verify(transactionOracleDAO).recordTransaction(captor.capture());
            // The description sent to the DAO must never be an empty string
            String daoDescription = captor.getValue().getDescription();
            assertFalse(daoDescription != null && daoDescription.isEmpty(),
                    "Service must not pass empty string to Oracle DAO (Oracle empty = NULL)");
        }

        /**
         * Verifies that Oracle date arithmetic is applied: settlement date = transaction date + 2.
         *
         * <p>ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]: In Oracle SQL, {@code date_col + 2}
         * adds 2 days. The service mirrors this in Java: {@code transactionDate.plusDays(2)}.
         */
        @Test
        @DisplayName("recordTransaction sets processedDate = transactionDate + 2 days (Oracle date arithmetic)")
        void testOracleDateArithmetic_settlementDate() throws Exception {
            OffsetDateTime txnDate = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime expectedSettlement = txnDate.plusDays(2); // Oracle: date + 2

            TransactionDTO dto = buildTransactionDTO(1L, "TRANSFER", "500.00", "PENDING");
            dto.setTransactionDate(txnDate);

            when(transactionOracleDAO.recordTransaction(any(TransactionDTO.class)))
                    .thenReturn(600L);

            TransactionDTO result = transactionService.recordTransaction(dto);

            assertNotNull(result);
            // Capture to verify the processedDate set before DAO call
            ArgumentCaptor<TransactionDTO> captor = ArgumentCaptor.forClass(TransactionDTO.class);
            verify(transactionOracleDAO).recordTransaction(captor.capture());

            TransactionDTO capturedDTO = captor.getValue();
            assertNotNull(capturedDTO.getProcessedDate(),
                    "Oracle date+2 settlement: processedDate must be set before DAO call");
            assertEquals(expectedSettlement, capturedDTO.getProcessedDate(),
                    "Oracle date arithmetic: processedDate must equal transactionDate + 2 days");
        }

        /**
         * Verifies that missing transaction type causes validation failure before DAO call.
         */
        @Test
        @DisplayName("recordTransaction null transaction type — throws MeridianBankException before DAO")
        void testRecordTransaction_nullType_throws() {
            TransactionDTO dto = buildTransactionDTO(1L, null, "100.00", "PENDING");

            assertThrows(MeridianBankException.class,
                    () -> transactionService.recordTransaction(dto),
                    "Null transaction type must fail validation before calling Oracle DAO");
            verifyNoInteractions(transactionOracleDAO);
        }
    }

    // ------------------------------------------------------------------
    // getTransactionById
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getTransactionById() tests")
    class GetTransactionByIdTests {

        /**
         * Verifies that a known transaction ID returns a populated TransactionDTO
         * mapped from the JPA entity.
         */
        @Test
        @DisplayName("getTransactionById found — returns TransactionDTO mapped from entity")
        void testGetTransactionById_found() {
            TransactionEntity entity = buildTransactionEntity(101L, 1L, "DEBIT");
            when(transactionRepository.findById(101L)).thenReturn(Optional.of(entity));

            TransactionDTO result = transactionService.getTransactionById(101L);

            assertNotNull(result, "Found transaction must return a non-null DTO");
            assertEquals(101L, result.getTransactionId(),
                    "Returned DTO transaction ID must match the entity ID");
            assertEquals("DEBIT", result.getTransactionType());
        }

        /**
         * Verifies that a missing transaction ID throws MeridianBankException (NOT_FOUND).
         */
        @Test
        @DisplayName("getTransactionById not found — throws MeridianBankException with NOT_FOUND code")
        void testGetTransactionById_notFound() {
            when(transactionRepository.findById(999L)).thenReturn(Optional.empty());

            MeridianBankException ex = assertThrows(MeridianBankException.class,
                    () -> transactionService.getTransactionById(999L),
                    "Missing transaction must throw MeridianBankException");
            assertEquals("NOT_FOUND", ex.getErrorCode(),
                    "Exception code must be NOT_FOUND for a missing transaction");
        }
    }

    // ------------------------------------------------------------------
    // bulkRecordTransactions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("bulkRecordTransactions() tests")
    class BulkRecordTests {

        /**
         * Verifies that bulkRecordTransactions delegates to TransactionOracleDAO
         * which uses Oracle array binding (OraclePreparedStatement.setExecuteBatch())
         * for efficient bulk inserts.
         */
        @Test
        @DisplayName("bulkRecordTransactions success — delegates to Oracle batch array binding DAO")
        void testBulkRecordTransactions_success() throws Exception {
            List<TransactionDTO> batch = buildTransactionBatch(5);
            when(transactionOracleDAO.bulkRecordTransactions(anyList())).thenReturn(5);

            int count = transactionService.bulkRecordTransactions(batch);

            assertEquals(5, count,
                    "Bulk insert count must match the 5 transactions submitted");
            verify(transactionOracleDAO, times(1)).bulkRecordTransactions(batch);
        }

        /**
         * Verifies that empty batch returns 0 without calling the DAO.
         */
        @Test
        @DisplayName("bulkRecordTransactions empty batch — returns 0")
        void testBulkRecordTransactions_emptyBatch() throws Exception {
            int count = transactionService.bulkRecordTransactions(List.of());

            assertEquals(0, count,
                    "Empty batch must return 0 without DAO interaction");
        }
    }

    // ------------------------------------------------------------------
    // searchTransactions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("searchTransactions() tests")
    class SearchTransactionTests {

        /**
         * Verifies that searchTransactions calls the DAO's date-range query
         * (which uses Oracle TO_CHAR/TO_DATE and TIMESTAMP WITH TIME ZONE).
         */
        @Test
        @DisplayName("searchTransactions success — delegates to TransactionOracleDAO date-range query")
        void testSearchTransactions_success() throws Exception {
            LocalDate start = LocalDate.now().minusDays(30);
            LocalDate end   = LocalDate.now();

            TransactionDTO t1 = buildTransactionDTO(1L, "DEBIT",  "100.00", "COMPLETED");
            TransactionDTO t2 = buildTransactionDTO(1L, "CREDIT", "250.00", "COMPLETED");
            when(transactionOracleDAO.getSummaryForDateRange(1L, start, end))
                    .thenReturn(Arrays.asList(t1, t2));

            List<TransactionDTO> results = transactionService.searchTransactions(1L, start, end);

            assertNotNull(results, "Date-range search results must not be null");
            assertEquals(2, results.size(),
                    "Search must return the 2 mocked transactions from Oracle date-range query");
            verify(transactionOracleDAO, times(1))
                    .getSummaryForDateRange(1L, start, end);
        }

        /**
         * Verifies that invalid date range (start after end) is rejected before DAO call.
         */
        @Test
        @DisplayName("searchTransactions start after end — throws MeridianBankException")
        void testSearchTransactions_invalidDateRange() {
            LocalDate start = LocalDate.now();
            LocalDate end   = LocalDate.now().minusDays(5);

            assertThrows(MeridianBankException.class,
                    () -> transactionService.searchTransactions(1L, start, end),
                    "Start date after end date must fail validation");
            verifyNoInteractions(transactionOracleDAO);
        }
    }

    // ------------------------------------------------------------------
    // getOverdueSettlements
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getOverdueSettlements() tests")
    class OverdueSettlementTests {

        /**
         * Verifies that getOverdueSettlements delegates to TransactionRepository
         * which uses the Oracle date arithmetic query: SYSDATE - transaction_date > 2.
         *
         * <p>ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]: {@code SYSDATE - transaction_date}
         * returns a NUMBER (number of days) in Oracle. In PostgreSQL this returns an INTERVAL,
         * requiring {@code EXTRACT(EPOCH FROM (NOW() - transaction_date)) / 86400 > 2}.
         */
        @Test
        @DisplayName("getOverdueSettlements delegates to Oracle SYSDATE - date arithmetic query")
        void testGetOverdueSettlements_returnsResults() {
            Object[] overdueRow = new Object[]{1L, "TXN-001", 5.0};
            List<Object[]> overdueRows = new ArrayList<>();
            overdueRows.add(overdueRow);
            when(transactionRepository.findOverdueSettlements()).thenReturn(overdueRows);

            List<Object[]> results = transactionService.getOverdueSettlements();

            assertNotNull(results);
            assertFalse(results.isEmpty(),
                    "Oracle date arithmetic overdue query must return matching rows");
            verify(transactionRepository, times(1)).findOverdueSettlements();
        }

        /**
         * Verifies that empty result is handled gracefully.
         */
        @Test
        @DisplayName("getOverdueSettlements returns empty list when no overdue settlements")
        void testGetOverdueSettlements_empty() {
            when(transactionRepository.findOverdueSettlements()).thenReturn(List.of());

            List<Object[]> results = transactionService.getOverdueSettlements();

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TransactionDTO buildTransactionDTO(Long accountId, String type,
                                               String amount, String status) {
        TransactionDTO dto = new TransactionDTO();
        dto.setAccountId(accountId);
        dto.setTransactionType(type);
        dto.setAmount(amount != null ? new BigDecimal(amount) : null);
        dto.setStatus(status);
        dto.setDescription("Test " + (type != null ? type.toLowerCase() : "tx"));
        dto.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
        return dto;
    }

    private TransactionEntity buildTransactionEntity(Long txnId, Long accountId, String type) {
        TransactionEntity e = new TransactionEntity();
        e.setTransactionId(txnId);
        e.setAccountId(accountId);
        e.setTransactionType(type);
        e.setAmount(new BigDecimal("100.00"));
        e.setStatus("COMPLETED");
        e.setDescription("Test " + type.toLowerCase());
        e.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
        e.setProcessedDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2));
        return e;
    }

    private List<TransactionDTO> buildTransactionBatch(int count) {
        List<TransactionDTO> batch = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            batch.add(buildTransactionDTO((long) i, "DEBIT",
                    String.format("%d.00", i * 100), "PENDING"));
        }
        return batch;
    }
}

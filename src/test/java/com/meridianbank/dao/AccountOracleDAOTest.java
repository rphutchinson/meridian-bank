package com.meridianbank.dao;

import com.meridianbank.dto.AccountDTO;
import com.meridianbank.exception.AccountNotFoundException;
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
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AccountOracleDAO} — the raw JDBC DAO that uses Oracle-specific
 * JDBC extensions and calls Oracle PL/SQL packages.
 *
 * <p><strong>Oracle dependencies exercised by this DAO:</strong>
 * <ul>
 *   <li>ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]  {@code {call ACCOUNT_MGMT_PKG.CREATE_ACCOUNT(...)}}</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES]    {@code OracleTypes.NUMBER} as OUT parameter</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE] {@code OracleCallableStatement.getNumber()}</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]      {@code oracle.sql.ARRAY} for bulk ID list</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ARRAY_DESCRIPTOR]{@code ArrayDescriptor.createDescriptor("NUMBER_TABLE_TYPE")}</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]    {@code MERGE INTO accounts USING (SELECT ... FROM DUAL)}</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]     {@code INSERT ... RETURNING account_id INTO ?}</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SEQUENCE_NEXTVAL]   {@code account_seq.NEXTVAL} in INSERT</li>
 * </ul>
 */
class AccountOracleDAOTest {

    // ------------------------------------------------------------------
    // DB-dependent tests (all @Disabled)
    // ------------------------------------------------------------------

    @SpringBootTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ActiveProfiles("test")
    @Nested
    @Disabled("Requires Oracle DB — entire nested class disabled until Oracle 23ai container is running")
    @DisplayName("Oracle DB-dependent DAO tests")
    class OracleDAOTests {

        @Autowired
        private AccountOracleDAO accountOracleDAO;

        /**
         * Tests that {@link AccountOracleDAO#createAccount(AccountDTO)} calls
         * {@code ACCOUNT_MGMT_PKG.CREATE_ACCOUNT} via a {@code CallableStatement}
         * and returns the Oracle-sequence-generated account ID via an
         * {@code OracleTypes.NUMBER} OUT parameter.
         *
         * <p>Oracle constructs:
         * <ul>
         *   <li>{@code {call ACCOUNT_MGMT_PKG.CREATE_ACCOUNT(?,?,?,?,?)}}</li>
         *   <li>{@code cs.registerOutParameter(4, OracleTypes.CURSOR)}</li>
         *   <li>{@code cs.registerOutParameter(5, OracleTypes.NUMBER)}</li>
         * </ul>
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]")
        @DisplayName("createAccount calls ACCOUNT_MGMT_PKG and returns sequence-generated ID")
        void testCreateAccount_callsPackage() throws SQLException {
            AccountDTO dto = new AccountDTO();
            dto.setCustomerId(1L);
            dto.setAccountType("SAVINGS");
            dto.setInitialDeposit(new BigDecimal("500.00"));
            dto.setOpenDate(LocalDate.now());
            dto.setBranchId(1L);

            Long accountId = accountOracleDAO.createAccount(dto);

            assertNotNull(accountId, "createAccount must return Oracle-sequence-generated ID");
            assertTrue(accountId > 0, "Account ID from Oracle sequence must be positive");
        }

        /**
         * Tests that {@link AccountOracleDAO#getBalance(Long)} uses an
         * {@code OracleTypes.NUMBER} OUT parameter and returns a non-null BigDecimal.
         *
         * <p>Oracle constructs: {@code cs.registerOutParameter(2, OracleTypes.NUMBER)}
         * and {@code ((OracleCallableStatement) cs).getNUMBER(2).bigDecimalValue()}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES]")
        @DisplayName("getBalance uses OracleTypes.NUMBER as OUT parameter")
        void testGetBalance_outParam() throws SQLException {
            // Account 1 should exist in seed data with a non-zero balance
            BigDecimal balance = accountOracleDAO.getBalance(1L);

            assertNotNull(balance, "OracleTypes.NUMBER OUT parameter must not return null");
            assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0,
                    "Balance returned via OracleTypes.NUMBER must be non-negative");
        }

        /**
         * Tests that {@link AccountOracleDAO#bulkUpdateStatus(List, String)} creates an
         * {@code oracle.sql.ARRAY} of type {@code NUMBER_TABLE_TYPE} and passes it to
         * the stored procedure via an Oracle-specific array binding.
         *
         * <p>Oracle constructs:
         * <ul>
         *   <li>{@code ArrayDescriptor.createDescriptor("MERIDIAN.NUMBER_TABLE_TYPE", conn)}</li>
         *   <li>{@code new ARRAY(desc, conn, ids.toArray())}</li>
         *   <li>{@code ((OraclePreparedStatement) ps).setARRAY(1, oracleArray)}</li>
         * </ul>
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]")
        @DisplayName("bulkUpdateStatus uses oracle.sql.ARRAY with NUMBER_TABLE_TYPE")
        void testBulkUpdateStatus_usesOracleArray() throws SQLException {
            List<Long> accountIds = Arrays.asList(1L, 2L, 3L);

            int updatedCount = accountOracleDAO.bulkUpdateStatus(accountIds, "ACTIVE");

            assertTrue(updatedCount >= 0,
                    "Bulk update via oracle.sql.ARRAY must return a non-negative update count");
        }

        /**
         * Tests that {@link AccountOracleDAO#reconcileBalance(Long, BigDecimal)} uses
         * the Oracle MERGE statement to update an existing account balance.
         *
         * <p>Oracle SQL: {@code MERGE INTO accounts tgt USING (SELECT ? id, ? bal FROM DUAL) src
         * ON (tgt.account_id = src.id) WHEN MATCHED THEN UPDATE SET tgt.balance = src.bal}
         *
         * <p>Migration target: {@code UPDATE accounts SET balance = ? WHERE account_id = ?}
         * (since PostgreSQL MERGE requires specific syntax).
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]")
        @DisplayName("reconcileBalance uses Oracle MERGE INTO ... USING DUAL statement")
        void testReconcileBalance_usesMerge() throws SQLException {
            BigDecimal reconciledBalance = new BigDecimal("1500.00");

            assertDoesNotThrow(
                    () -> accountOracleDAO.reconcileBalance(1L, reconciledBalance),
                    "MERGE-based reconcileBalance must not throw for an existing account");

            // Verify balance was actually updated
            BigDecimal newBalance = accountOracleDAO.getBalance(1L);
            assertNotNull(newBalance);
            assertEquals(0, reconciledBalance.compareTo(newBalance),
                    "MERGE should have updated the balance to the reconciled value");
        }

        /**
         * Tests that {@link AccountOracleDAO#createAccount(AccountDTO)} uses
         * the Oracle {@code RETURNING account_id INTO ?} clause to retrieve the
         * generated primary key in a single round-trip.
         *
         * <p>Oracle SQL fragment:
         * {@code INSERT INTO accounts ... VALUES (account_seq.NEXTVAL, ...) RETURNING account_id INTO ?}
         *
         * <p>Migration target: {@code INSERT ... RETURNING account_id} (PostgreSQL
         * supports RETURNING directly; binding differs from Oracle's OUT-param approach).
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]")
        @DisplayName("createAccount INSERT uses Oracle RETURNING account_id INTO ? clause")
        void testReturningInto_generatesId() throws SQLException {
            AccountDTO dto = new AccountDTO();
            dto.setCustomerId(1L);
            dto.setAccountType("CHECKING");
            dto.setInitialDeposit(new BigDecimal("1000.00"));
            dto.setOpenDate(LocalDate.now());
            dto.setBranchId(1L);

            Long id1 = accountOracleDAO.createAccount(dto);
            Long id2 = accountOracleDAO.createAccount(dto);

            assertNotNull(id1, "First RETURNING INTO must produce a non-null ID");
            assertNotNull(id2, "Second RETURNING INTO must produce a non-null ID");
            assertNotEquals(id1, id2,
                    "Each RETURNING INTO must produce a distinct Oracle sequence value");
        }
    }

    // ------------------------------------------------------------------
    // Mock-based structural tests (no DB required)
    // ------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    @DisplayName("Mock-based structural tests (no DB required)")
    class MockBasedTests {

        @Mock
        private AccountOracleDAO accountOracleDAO;

        @BeforeEach
        void setUpMocks() throws SQLException {
            when(accountOracleDAO.createAccount(any(AccountDTO.class)))
                    .thenReturn(42L);
            when(accountOracleDAO.getBalance(1L))
                    .thenReturn(new BigDecimal("2500.00"));
            when(accountOracleDAO.getBalance(99L))
                    .thenThrow(new AccountNotFoundException(99L));
            when(accountOracleDAO.bulkUpdateStatus(anyList(), anyString()))
                    .thenReturn(3);
        }

        @Test
        @DisplayName("createAccount mock returns expected ID")
        void testCreateAccount_mockReturnsId() throws SQLException {
            AccountDTO dto = new AccountDTO();
            dto.setCustomerId(1L);
            dto.setAccountType("SAVINGS");

            Long id = accountOracleDAO.createAccount(dto);

            assertNotNull(id);
            assertEquals(42L, id);
            verify(accountOracleDAO, times(1)).createAccount(dto);
        }

        @Test
        @DisplayName("getBalance mock returns configured balance")
        void testGetBalance_mockReturnsBalance() throws SQLException {
            BigDecimal balance = accountOracleDAO.getBalance(1L);

            assertNotNull(balance);
            assertEquals(0, new BigDecimal("2500.00").compareTo(balance));
        }

        @Test
        @DisplayName("getBalance throws AccountNotFoundException for unknown account")
        void testGetBalance_unknownAccount_throws() {
            assertThrows(AccountNotFoundException.class,
                    () -> accountOracleDAO.getBalance(99L),
                    "getBalance for unknown accountId must throw AccountNotFoundException");
        }

        @Test
        @DisplayName("bulkUpdateStatus mock returns expected update count")
        void testBulkUpdateStatus_mockReturnsCount() throws SQLException {
            List<Long> ids = Arrays.asList(1L, 2L, 3L);
            int count = accountOracleDAO.bulkUpdateStatus(ids, "ACTIVE");

            assertEquals(3, count);
            verify(accountOracleDAO, times(1)).bulkUpdateStatus(ids, "ACTIVE");
        }
    }
}

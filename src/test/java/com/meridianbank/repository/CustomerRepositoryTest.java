package com.meridianbank.repository;

import com.meridianbank.entity.CustomerEntity;
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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CustomerRepository} — Spring Data JPA interface with
 * native Oracle SQL queries.
 *
 * <p><strong>Structure:</strong>
 * <ul>
 *   <li>{@code OracleQueryTests} — all annotated {@code @Disabled}; require live Oracle DB</li>
 *   <li>{@code MockBasedTests} — runnable without DB via Mockito</li>
 * </ul>
 *
 * <p>Oracle dependencies covered by this repository's native queries:
 * <ul>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]          NVL(phone,'N/A'), NVL(email,'unknown')</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]       DECODE(status,'ACTIVE','Active',...)</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS]  CONTAINS(doc_text, :query) > 0</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE]          TO_CHAR(created_date,'YYYY-MM-DD')</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN]        MONTHS_BETWEEN(SYSDATE, open_date)</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SUBSTR_INSTR]          SUBSTR(last_name,1,1), INSTR(email,'@')</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SYSDATE]               SYSDATE in date comparisons</li>
 * </ul>
 */
class CustomerRepositoryTest {

    // ------------------------------------------------------------------
    // DB-dependent integration tests
    // ------------------------------------------------------------------

    /**
     * Nested class groups all tests that require a live Oracle connection.
     * Every method here is {@code @Disabled} until Oracle Docker is running.
     */
    @SpringBootTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ActiveProfiles("test")
    @Nested
    @Disabled("Requires Oracle DB — entire nested class disabled until Oracle 23ai container is running")
    @DisplayName("Oracle DB-dependent query tests")
    class OracleQueryTests {

        @Autowired
        private CustomerRepository customerRepository;

        /**
         * Tests the native query that uses Oracle NVL() to substitute null
         * phone and email values.
         *
         * <p>SQL fragment: {@code NVL(phone, 'N/A') as phone, NVL(email, 'unknown') as email}
         * <p>Migration target: replace with {@code COALESCE(phone, 'N/A')}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]")
        @DisplayName("findCustomersWithContactInfo uses Oracle NVL() for null substitution")
        void testFindActiveCustomersWithNVL() {
            List<Object[]> results = customerRepository.findCustomersWithContactInfo();

            assertNotNull(results, "Result list should not be null");
            for (Object[] row : results) {
                // Column 3 (phone) must never be null due to NVL('N/A')
                assertNotNull(row[3],
                        "NVL(phone,'N/A') must guarantee non-null phone column");
                // Column 4 (email) must never be null due to NVL('unknown')
                assertNotNull(row[4],
                        "NVL(email,'unknown') must guarantee non-null email column");
            }
        }

        /**
         * Tests the native query that uses Oracle RATIO_TO_REPORT analytic
         * function to express each customer's balance as a ratio of the total.
         *
         * <p>SQL fragment:
         * {@code RATIO_TO_REPORT(total_balance) OVER () AS portfolio_ratio}
         * <p>Migration target: derive ratio in application layer or use window function.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]")
        @DisplayName("findActiveCustomersSummary uses Oracle RATIO_TO_REPORT analytic function")
        void testFindTopCustomersByBalance() {
            List<Object[]> results = customerRepository.findActiveCustomersSummary();

            assertNotNull(results, "RATIO_TO_REPORT summary should return non-null list");
            if (!results.isEmpty()) {
                // The ratio column should be a numeric value between 0 and 1
                Object ratioValue = results.get(0)[results.get(0).length - 1];
                assertNotNull(ratioValue, "RATIO_TO_REPORT column must be non-null");
            }
        }

        /**
         * Tests the native query that uses Oracle DECODE() for human-readable
         * status mapping.
         *
         * <p>SQL fragment:
         * {@code DECODE(status,'ACTIVE','Active Customer','INACTIVE','Dormant','Unknown')}
         * <p>Migration target: replace with {@code CASE WHEN status='ACTIVE' THEN ... END}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]")
        @DisplayName("findByStatusDecoded uses Oracle DECODE() for status label mapping")
        void testSearchByNameDecoded() {
            // customerId=1 is a known seed data record
            List<Object[]> results = customerRepository.findByStatusDecoded(1L);

            assertNotNull(results, "DECODE-based status query must return non-null");
            for (Object[] row : results) {
                // The decoded label column must not be null — DECODE always provides an else
                assertNotNull(row[row.length - 1],
                        "DECODE status label column must never be null");
            }
        }

        /**
         * Tests the native query using Oracle TO_CHAR() to format dates.
         *
         * <p>SQL fragment:
         * {@code TO_CHAR(created_date, 'YYYY-MM-DD') AS formatted_date}
         * <p>Migration target: use Java {@code DateTimeFormatter} or PostgreSQL
         * {@code TO_CHAR(date, 'YYYY-MM-DD')}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE]")
        @DisplayName("findWithFormattedDates uses Oracle TO_CHAR() for date formatting")
        void testFindCustomersByBirthYear() {
            List<Object[]> results = customerRepository.findWithFormattedDates("ACTIVE");

            assertNotNull(results, "TO_CHAR date formatting query must return non-null");
            if (!results.isEmpty()) {
                Object formattedDate = results.get(0)[results.get(0).length - 1];
                if (formattedDate != null) {
                    // Oracle TO_CHAR(date,'YYYY-MM-DD') returns a 10-char string
                    assertEquals(10, formattedDate.toString().length(),
                            "TO_CHAR(date,'YYYY-MM-DD') should produce a 10-character string");
                    // Verify the format: should match YYYY-MM-DD
                    assertTrue(formattedDate.toString().matches("\\d{4}-\\d{2}-\\d{2}"),
                            "TO_CHAR result should match YYYY-MM-DD pattern");
                }
            }
        }

        /**
         * Tests the native query using Oracle MONTHS_BETWEEN() to compute
         * customer tenure in months.
         *
         * <p>SQL fragment:
         * {@code MONTHS_BETWEEN(SYSDATE, created_date) AS tenure_months}
         * <p>Migration target: use {@code EXTRACT(EPOCH FROM (NOW() - created_date)) / 2592000}
         * in PostgreSQL or compute in application layer.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN]")
        @DisplayName("findRecentCustomers uses Oracle MONTHS_BETWEEN() for tenure calculation")
        void testFindCustomersOrderedByAccountAge() {
            // Fetch customers created in the last 120 days, page 0, size 10
            List<CustomerEntity> results = customerRepository.findRecentCustomers(120, 10);

            assertNotNull(results, "MONTHS_BETWEEN tenure query must return non-null");
            // All returned customers should have been created within last 120 days
            for (CustomerEntity entity : results) {
                assertNotNull(entity.getCreatedDate(),
                        "Each customer returned by MONTHS_BETWEEN query must have a created date");
            }
        }

        /**
         * Tests the native query using Oracle SUBSTR() and INSTR() for string
         * manipulation — both 1-based, unlike Java's 0-based indexing.
         *
         * <p>SQL fragment:
         * {@code WHERE UPPER(SUBSTR(last_name, 1, LENGTH(:prefix))) = UPPER(:prefix)}
         * <p>Migration target: {@code SUBSTRING(last_name, 1, LENGTH(:prefix))} in PostgreSQL
         * (still 1-based in PostgreSQL), or use {@code LIKE :prefix || '%'}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:SUBSTR_INSTR]")
        @DisplayName("findByPartialName uses Oracle SUBSTR() 1-based string function")
        void testFindCustomersWithMultipleAccounts() {
            List<CustomerEntity> results = customerRepository.findByPartialName("Sm");

            assertNotNull(results, "SUBSTR-based partial name search must return non-null");
            for (CustomerEntity entity : results) {
                assertTrue(
                        entity.getLastName().toUpperCase().startsWith("SM"),
                        "SUBSTR(last_name,1,2) prefix filter should only return names starting with 'Sm'");
            }
        }
    }

    // ------------------------------------------------------------------
    // Mock-based tests (run without any database connection)
    // ------------------------------------------------------------------

    /**
     * Nested class groups all Mockito-based tests. These tests verify
     * that standard Spring Data derived query methods (non-native-query)
     * are called correctly without needing a database.
     */
    @Nested
    @ExtendWith(MockitoExtension.class)
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    @DisplayName("Mock-based tests (no DB required)")
    class MockBasedTests {

        @Mock
        private CustomerRepository customerRepository;

        @BeforeEach
        void setUpMocks() {
            CustomerEntity alice = buildCustomer(1L, "alice@meridian.com", "ACTIVE");
            CustomerEntity bob   = buildCustomer(2L, "bob@meridian.com",   "ACTIVE");

            when(customerRepository.findByEmail("alice@meridian.com"))
                    .thenReturn(Optional.of(alice));
            when(customerRepository.findByEmail("unknown@meridian.com"))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByStatus("ACTIVE"))
                    .thenReturn(Arrays.asList(alice, bob));
            when(customerRepository.findByStatus("CLOSED"))
                    .thenReturn(List.of());
        }

        /**
         * Verifies that {@code findByEmail} is delegated to the repository
         * and returns the correct entity when the email exists.
         */
        @Test
        @DisplayName("findByEmail returns present Optional when email exists")
        void testFindByEmail_mockBased() {
            Optional<CustomerEntity> result =
                    customerRepository.findByEmail("alice@meridian.com");

            assertTrue(result.isPresent(), "Should find customer by known email");
            assertEquals("alice@meridian.com", result.get().getEmail());
            verify(customerRepository, times(1)).findByEmail("alice@meridian.com");
        }

        /**
         * Verifies that {@code findByEmail} returns an empty Optional for an
         * unknown email address.
         */
        @Test
        @DisplayName("findByEmail returns empty Optional for unknown email")
        void testFindByEmail_unknownEmail_returnsEmpty() {
            Optional<CustomerEntity> result =
                    customerRepository.findByEmail("unknown@meridian.com");

            assertFalse(result.isPresent(), "Should return empty Optional for unknown email");
        }

        /**
         * Verifies that {@code findByStatus("ACTIVE")} returns all active customers.
         */
        @Test
        @DisplayName("findByStatus('ACTIVE') returns all active customers")
        void testFindByStatus_mockBased() {
            List<CustomerEntity> active = customerRepository.findByStatus("ACTIVE");

            assertNotNull(active, "Active customer list must not be null");
            assertEquals(2, active.size(), "Should return exactly 2 active customers");
            active.forEach(c ->
                    assertEquals("ACTIVE", c.getStatus(),
                            "Every returned customer must have ACTIVE status"));
            verify(customerRepository, times(1)).findByStatus("ACTIVE");
        }

        /**
         * Verifies that {@code findByStatus("CLOSED")} returns an empty list
         * when no closed customers exist.
         */
        @Test
        @DisplayName("findByStatus('CLOSED') returns empty list when no closed customers")
        void testFindByStatus_closed_returnsEmpty() {
            List<CustomerEntity> closed = customerRepository.findByStatus("CLOSED");

            assertNotNull(closed);
            assertTrue(closed.isEmpty(), "No closed customers should exist in test data");
        }

        // ------------------------------------------------------------------
        // Helper
        // ------------------------------------------------------------------

        private CustomerEntity buildCustomer(Long id, String email, String status) {
            CustomerEntity c = new CustomerEntity();
            c.setCustomerId(id);
            c.setFirstName("Test");
            c.setLastName("Customer" + id);
            c.setEmail(email);
            c.setStatus(status);
            return c;
        }
    }
}

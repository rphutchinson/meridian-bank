package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.CustomerOracleDAO;
import com.meridianbank.dao.SecurityOracleDAO;
import com.meridianbank.dto.CustomerDTO;
import com.meridianbank.entity.CustomerEntity;
import com.meridianbank.exception.AccountNotFoundException;
import com.meridianbank.exception.DuplicateCustomerException;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for Customer operations.
 *
 * Orchestrates Spring Data JPA repositories and Oracle-specific DAO classes.
 * Contains Oracle-specific business logic including SSN encryption via DBMS_CRYPTO,
 * Oracle empty-string/NULL semantics, ROWNUM-based pagination, and ORA-00001 handling.
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]        Delegates to CustomerOracleDAO for STRUCT ops
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES]       Handles ORA-00001 duplicate email/SSN
 * ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING]        Oracle treats '' as NULL — explicit validation
 */
@Service
@Transactional
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerOracleDAO customerOracleDAO;

    @Autowired
    private SecurityOracleDAO securityOracleDAO;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Customer creation
    // -----------------------------------------------------------------------

    /**
     * Creates a new customer, encrypting the SSN via DBMS_CRYPTO before persisting.
     *
     * Oracle-specific behaviours exercised:
     *  1. SSN encryption via SecurityOracleDAO (DBMS_CRYPTO stored procedure call)
     *  2. Empty string == NULL in Oracle — phone/email empty strings treated as NULL
     *  3. ORA-00001 unique constraint (CUSTOMERS_EMAIL_UK, CUSTOMERS_SSN_UK) translated
     *  4. RETURNING INTO used inside CustomerOracleDAO.createCustomer for the generated ID
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "SSN is encrypted using DBMS_CRYPTO via SecurityOracleDAO before INSERT",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_CRYPTO with application-tier AES-256 encryption (e.g. Bouncy Castle or JCE AES/GCM)"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NULL_EMPTY_STRING",
        description = "Oracle treats empty string '' as NULL — blank phone/email normalised to null before INSERT",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "PostgreSQL distinguishes '' from NULL; remove normalisation or convert to explicit NULLIF() calls"
    )
    public CustomerDTO createCustomer(CustomerDTO dto) {
        log.info("Creating customer: {} {}", dto.getFirstName(), dto.getLastName());

        // ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING] Oracle treats '' as NULL in VARCHAR2 columns;
        // normalise empty strings to null here so behaviour is consistent across Oracle versions
        String phone = emptyToNull(dto.getPhone());
        String email = emptyToNull(dto.getEmail());

        // Email is mandatory even though Oracle would accept empty string silently
        if (email == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Customer email address is required.", 400);
        }

        // Validate first and last name are not blank (Oracle CHAR padding makes blank checks subtle)
        if (emptyToNull(dto.getFirstName()) == null || emptyToNull(dto.getLastName()) == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Customer first name and last name are required.", 400);
        }

        // Check for duplicate email before calling the DAO — provides a cleaner error message
        // than relying solely on ORA-00001 from the constraint
        if (customerRepository.findByEmail(email).isPresent()) {
            throw new DuplicateCustomerException("email", email);
        }

        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] Encrypt SSN using SECURITY_PKG.ENCRYPT_SSN
        // which internally calls DBMS_CRYPTO.ENCRYPT with AES-256
        String encryptedSsn = null;
        if (dto.getSsnMasked() != null && !dto.getSsnMasked().isBlank()) {
            encryptedSsn = securityOracleDAO.encryptSSN(dto.getSsnMasked());
            log.debug("SSN encrypted for new customer");
        }

        // Build a DTO copy with normalised fields ready for Oracle INSERT
        CustomerDTO normalisedDto = new CustomerDTO();
        normalisedDto.setFirstName(dto.getFirstName().trim());
        normalisedDto.setLastName(dto.getLastName().trim());
        normalisedDto.setEmail(email);
        normalisedDto.setPhone(phone);
        normalisedDto.setDateOfBirth(dto.getDateOfBirth());
        normalisedDto.setSsnMasked(encryptedSsn);   // store hex cipher, not plaintext
        normalisedDto.setStatus("ACTIVE");
        normalisedDto.setStreet(dto.getStreet());
        normalisedDto.setCity(dto.getCity());
        normalisedDto.setState(dto.getState());
        normalisedDto.setZipCode(dto.getZipCode());
        normalisedDto.setCountry(dto.getCountry() != null ? dto.getCountry() : "US");

        // ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] CustomerOracleDAO.createCustomer uses
        // INSERT ... RETURNING customer_id INTO ? to obtain the generated PK in a single round-trip
        Long newId;
        try {
            newId = customerOracleDAO.createCustomer(normalisedDto);
        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] ORA-00001 mapped to DuplicateCustomerException
            throw errorTranslator.translateSQLException(e);
        }

        log.info("Customer created with ID {}", newId);

        // Reload and return the persisted entity as a DTO
        normalisedDto.setCustomerId(newId);
        normalisedDto.setCreatedDate(LocalDateTime.now());
        maskSensitiveFields(normalisedDto);
        return normalisedDto;
    }

    // -----------------------------------------------------------------------
    // Retrieval
    // -----------------------------------------------------------------------

    /**
     * Retrieves a customer by primary key.
     * Uses Spring Data JPA findById — no Oracle-specific dependency in this path.
     */
    @Transactional(readOnly = true)
    public CustomerDTO getCustomerById(Long customerId) {
        CustomerEntity entity = customerRepository.findById(customerId)
            .orElseThrow(() -> new AccountNotFoundException(customerId));
        return entityToDto(entity);
    }

    /**
     * Retrieves a customer by email.
     * Email comparison is case-insensitive (Oracle default collation is binary,
     * but we normalise email to lowercase on entry).
     */
    @Transactional(readOnly = true)
    public CustomerDTO getCustomerByEmail(String email) {
        String normalisedEmail = email != null ? email.toLowerCase().trim() : null;
        CustomerEntity entity = customerRepository.findByEmail(normalisedEmail)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "No customer found with email: " + email, 404));
        return entityToDto(entity);
    }

    /**
     * Retrieves a page of customers using Oracle ROWNUM-based pagination.
     *
     * Delegates to CustomerOracleDAO which runs the classic Oracle pre-12c pagination pattern:
     *   SELECT * FROM (SELECT t.*, ROWNUM rn FROM (...) t WHERE ROWNUM <= :end)
     *   WHERE rn >= :start
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM_PAGINATION",
        description = "Page-based customer list uses Oracle ROWNUM double-nest pagination via CustomerOracleDAO",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with LIMIT/OFFSET in PostgreSQL or Spring Data Pageable"
    )
    @Transactional(readOnly = true)
    public List<CustomerDTO> getCustomersForPage(int page, int size) {
        // ORACLE-DEP [EMBEDDED_SQL:ROWNUM_PAGINATION] Oracle ROWNUM used inside DAO for paging
        try {
            return customerOracleDAO.findCustomersPaginated(page, size);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Full-text customer search delegating to Oracle Text CONTAINS() query in the DAO.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT_CONTAINS",
        description = "Customer search uses Oracle Text CONTAINS() with a CONTEXT index on customer names/email",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostgreSQL full-text search using tsvector/tsquery or pg_trgm GIN index"
    )
    @Transactional(readOnly = true)
    public List<CustomerDTO> searchCustomers(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] Delegates to CustomerOracleDAO which uses
        // CONTAINS(full_text_col, :query) > 0 with a CONTEXT index
        try {
            return customerOracleDAO.searchCustomers(query.trim(), 50);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns a customer with a summary of their accounts.
     * Calls CustomerOracleDAO which executes a native query with NVL/DECODE aggregation
     * and an OracleTypes.CURSOR OUT parameter from a package call.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "REF_CURSOR",
        description = "Account summary fetched via SYS_REFCURSOR OUT param from CUSTOMER_MGMT_PKG.GET_SUMMARY",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace REF CURSOR with standard ResultSet from a direct SELECT or function returning TABLE type in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public CustomerDTO getCustomerWithAccountsSummary(Long customerId) {
        // ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] CustomerOracleDAO.getCustomerWithAccountsSummary
        // registers OracleTypes.CURSOR as OUT parameter
        try {
            Optional<CustomerDTO> result = customerOracleDAO.getCustomerWithAccountsSummary(customerId);
            return result.orElseThrow(() -> new AccountNotFoundException(customerId));
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Update operations
    // -----------------------------------------------------------------------

    /**
     * Updates customer profile fields.
     * Address updates are routed through CustomerOracleDAO to use Oracle ADDRESS_TYPE STRUCT binding.
     */
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "STRUCT_TYPE",
        description = "Address update passes an ADDRESS_TYPE STRUCT to the DAO for Oracle composite type binding",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle STRUCT binding with individual column parameters or a PostgreSQL composite type"
    )
    public CustomerDTO updateCustomer(Long customerId, CustomerDTO dto) {
        // Verify the customer exists first
        CustomerEntity existing = customerRepository.findById(customerId)
            .orElseThrow(() -> new AccountNotFoundException(customerId));

        // Update basic profile fields via JPA entity
        if (emptyToNull(dto.getFirstName()) != null) {
            existing.setFirstName(dto.getFirstName().trim());
        }
        if (emptyToNull(dto.getLastName()) != null) {
            existing.setLastName(dto.getLastName().trim());
        }
        // ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING] Normalise empty strings to null before Oracle save
        if (dto.getPhone() != null) {
            existing.setPhone(emptyToNull(dto.getPhone()));
        }
        if (dto.getEmail() != null) {
            String newEmail = emptyToNull(dto.getEmail());
            // Check uniqueness if email is changing
            if (newEmail != null && !newEmail.equals(existing.getEmail())) {
                if (customerRepository.findByEmail(newEmail).isPresent()) {
                    throw new DuplicateCustomerException("email", newEmail);
                }
                existing.setEmail(newEmail);
            }
        }
        if (dto.getStatus() != null) {
            existing.setStatus(dto.getStatus());
        }

        CustomerEntity saved = customerRepository.save(existing);

        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] If address fields provided, update via DAO
        // which passes an Oracle ADDRESS_TYPE STRUCT to the stored procedure
        boolean hasAddressUpdate = emptyToNull(dto.getStreet()) != null
            || emptyToNull(dto.getCity()) != null
            || emptyToNull(dto.getZipCode()) != null;

        if (hasAddressUpdate) {
            try {
                customerOracleDAO.updateAddress(customerId, dto);
            } catch (SQLException e) {
                throw errorTranslator.translateSQLException(e);
            }
        }

        return entityToDto(saved);
    }

    // -----------------------------------------------------------------------
    // Bulk operations
    // -----------------------------------------------------------------------

    /**
     * Bulk-updates customer status for a list of IDs.
     * Delegates to CustomerOracleDAO which uses a NUMBER_TABLE_TYPE Oracle ARRAY
     * to pass the ID list to a stored procedure in a single call.
     */
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ARRAY_TYPE",
        description = "Bulk status update passes customer IDs as NUMBER_TABLE_TYPE Oracle ARRAY to CustomerOracleDAO",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle ARRAY with unnest(:ids::bigint[]) in PostgreSQL or individual row updates in a loop"
    )
    public int bulkUpdateCustomerStatus(List<Long> customerIds, String newStatus) {
        if (customerIds == null || customerIds.isEmpty()) {
            return 0;
        }
        validateStatus(newStatus, new String[]{"ACTIVE", "INACTIVE", "SUSPENDED", "CLOSED"});

        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] CustomerOracleDAO.bulkUpdateStatus uses
        // NUMBER_TABLE_TYPE ARRAY binding via ArrayDescriptor + OraclePreparedStatement
        try {
            return customerOracleDAO.bulkUpdateStatus(customerIds, newStatus);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns the top customers ordered by total account balance.
     * The repository query uses Oracle analytic functions (RANK() OVER) in the native SQL.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ANALYTIC_FUNCTIONS",
        description = "Top customer query uses Oracle RANK() OVER PARTITION BY and NVL for null balance handling",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL supports equivalent RANK() OVER window functions natively"
    )
    @Transactional(readOnly = true)
    public List<CustomerDTO> getTopCustomersByBalance() {
        // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] Uses RANK() OVER and NVL in native query
        List<Object[]> rows = customerRepository.findActiveCustomersSummary();
        List<CustomerDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            CustomerDTO dto = new CustomerDTO();
            dto.setCustomerId(toLong(row[0]));
            dto.setFirstName(toString(row[1]));
            dto.setLastName(toString(row[2]));
            dto.setEmail(toString(row[3]));
            // row[4] = account_count from NVL(COUNT(...),0) aggregation
            if (row.length > 4 && row[4] != null) {
                dto.setAccountCount(toInt(row[4]));
            }
            results.add(dto);
        }
        return results;
    }

    /**
     * Returns customers whose names match the given prefix using Oracle SUBSTR() search.
     * The repository native query uses Oracle 1-based SUBSTR and UPPER for case-insensitive search.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SUBSTR_FUNCTION",
        description = "Customer name search uses Oracle UPPER(SUBSTR(last_name,1,LENGTH(:prefix))) pattern",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace Oracle SUBSTR(str,1,n) with standard SUBSTRING(str FROM 1 FOR n) or LIKE :prefix||'%'"
    )
    @Transactional(readOnly = true)
    public List<CustomerDTO> findCustomersByNamePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        // ORACLE-DEP [EMBEDDED_SQL:SUBSTR_FUNCTION] Repository uses Oracle SUBSTR (1-based index)
        List<CustomerEntity> entities = customerRepository.findByPartialName(prefix.toUpperCase().trim());
        List<CustomerDTO> results = new ArrayList<>();
        for (CustomerEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    /**
     * Returns customers formatted with TO_CHAR date display via native Oracle query.
     * Demonstrates Oracle TO_CHAR() for date formatting in SQL.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_FUNCTION",
        description = "Customer list uses TO_CHAR(created_date,'DD-MON-YYYY') for Oracle date display formatting",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(date,'DD-MON-YYYY') with TO_CHAR(date,'DD-Mon-YYYY') in PostgreSQL (same function, same args)"
    )
    @Transactional(readOnly = true)
    public List<CustomerDTO> getCustomersWithFormattedDates(String status) {
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_FUNCTION] Native query uses TO_CHAR for date formatting
        List<Object[]> rows = customerRepository.findWithFormattedDates(status);
        List<CustomerDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            CustomerDTO dto = new CustomerDTO();
            dto.setCustomerId(toLong(row[0]));
            dto.setFirstName(toString(row[1]));
            dto.setLastName(toString(row[2]));
            dto.setEmail(toString(row[3]));
            dto.setStatus(toString(row[4]));
            // row[5] = TO_CHAR(created_date,'DD-MON-YYYY') formatted string
            if (row.length > 5) {
                dto.setFullName(toString(row[5])); // reuse fullName to carry formatted date
            }
            results.add(dto);
        }
        return results;
    }

    /**
     * Returns customers who joined within the last N days using Oracle date arithmetic.
     * Demonstrates SYSDATE - :daysBack Oracle date subtraction returning a date.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "Recent customer query uses SYSDATE - :daysBack Oracle date arithmetic in WHERE clause",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with NOW() or CURRENT_DATE in PostgreSQL; date - integer still valid"
    )
    @Transactional(readOnly = true)
    public List<CustomerDTO> getRecentCustomers(int daysBack) {
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE used in native query date arithmetic
        List<CustomerEntity> entities = customerRepository.findRecentCustomers(daysBack, 100);
        List<CustomerDTO> results = new ArrayList<>();
        for (CustomerEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    /**
     * Returns customer contact information with Oracle NVL() applied to phone and email.
     * Uses the CustomerRepository native query that calls NVL(phone,'N/A'), NVL(email,'unknown').
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "Contact list query uses NVL(phone,'N/A') and NVL(email,'unknown') for null-safe display",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(col,default) with COALESCE(col,default) in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<CustomerDTO> getCustomerContactInfo() {
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Repository native query uses NVL() for phone/email
        List<Object[]> rows = customerRepository.findCustomersWithContactInfo();
        List<CustomerDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            CustomerDTO dto = new CustomerDTO();
            dto.setCustomerId(toLong(row[0]));
            dto.setFirstName(toString(row[1]));
            dto.setLastName(toString(row[2]));
            dto.setPhone(toString(row[3]));   // NVL result: 'N/A' if null
            dto.setEmail(toString(row[4]));   // NVL result: 'unknown' if null
            results.add(dto);
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Converts CustomerEntity to CustomerDTO with sensitive field masking.
     */
    private CustomerDTO entityToDto(CustomerEntity entity) {
        CustomerDTO dto = new CustomerDTO();
        dto.setCustomerId(entity.getCustomerId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setDateOfBirth(entity.getDateOfBirth());
        dto.setStatus(entity.getStatus());
        dto.setCreatedDate(entity.getCreatedDate() != null ? entity.getCreatedDate().atStartOfDay() : null);
        maskSensitiveFields(dto);
        return dto;
    }

    /**
     * Masks SSN — never return plaintext or encrypted SSN in API responses.
     */
    private void maskSensitiveFields(CustomerDTO dto) {
        // SSN is always masked; only the last 4 digits shown
        if (dto.getSsnMasked() != null) {
            String ssn = dto.getSsnMasked();
            dto.setSsnMasked("***-**-" + (ssn.length() >= 4
                ? ssn.substring(ssn.length() - 4)
                : "****"));
        }
    }

    /**
     * Oracle empty-string-to-null normalisation.
     * ORACLE-DEP: Oracle VARCHAR2 treats '' as NULL; we mirror this in the service layer
     * so behaviour is explicit and documented for the migration framework.
     */
    // ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING] Normalise '' to null matching Oracle VARCHAR2 semantics
    private String emptyToNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;    // Oracle VARCHAR2: empty string == NULL
        }
        return value;
    }

    private void validateStatus(String status, String[] allowed) {
        for (String a : allowed) {
            if (a.equalsIgnoreCase(status)) return;
        }
        throw new MeridianBankException("VALIDATION_ERROR",
            "Invalid status value: " + status, 400);
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private String toString(Object o) {
        return o == null ? null : o.toString();
    }
}

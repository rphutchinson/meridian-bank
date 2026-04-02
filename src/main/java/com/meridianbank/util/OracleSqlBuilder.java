package com.meridianbank.util;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Programmatic builder for Oracle-specific SQL fragments.
 *
 * Centralizes the construction of recurring Oracle SQL patterns so they are easy to
 * locate, test, and replace during migration. Each method generates a specific
 * Oracle SQL construct; the migration target for each is documented inline.
 *
 * ORACLE-DEP [EMBEDDED_SQL:ROWNUM]          Oracle ROWNUM double-wrap pagination
 * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY]      Oracle CONNECT BY hierarchical query
 * ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT]      Oracle optimizer hint injection
 * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]    Oracle NVL() null defaulting
 * ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Oracle DECODE() conditional expression
 * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Oracle MERGE INTO ... USING ... statement
 * ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] Oracle SYSDATE +/- integer arithmetic
 * ORACLE-DEP [EMBEDDED_SQL:SYSDATE]         Oracle SYSDATE current date/time
 * ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]    Oracle SYSTIMESTAMP with timezone
 */
@Component
// ORACLE-DEP [EMBEDDED_SQL:ROWNUM] SQL builder generating Oracle-specific fragments
@OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM",
        description = "SQL builder that generates Oracle-specific SQL fragments including ROWNUM, "
                + "CONNECT BY, hints, NVL, DECODE, MERGE, and Oracle date arithmetic",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace generated Oracle SQL fragments with target-DB equivalents per each method"
)
public class OracleSqlBuilder {

    private static final Logger log = LoggerFactory.getLogger(OracleSqlBuilder.class);

    // -----------------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------------

    /**
     * Wraps a query in Oracle's pre-12c double-ROWNUM pagination pattern.
     *
     * Produces:
     *   SELECT * FROM (
     *     SELECT inner_q.*, ROWNUM AS rn FROM (...) inner_q WHERE ROWNUM <= :maxRow
     *   ) WHERE rn >= :minRow
     *
     * ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Oracle ROWNUM double-wrap pagination pattern
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "ROWNUM",
            description = "Generates Oracle pre-12c ROWNUM double-wrap pagination SQL",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with LIMIT/OFFSET (PostgreSQL) or FETCH FIRST ROWS ONLY (SQL standard)"
    )
    public String wrapWithRownumPagination(String innerSql) {
        if (innerSql == null || innerSql.trim().isEmpty()) {
            throw new IllegalArgumentException("Inner SQL must not be null or empty");
        }
        // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Oracle double-ROWNUM pagination — outer select filters rn, inner adds ROWNUM
        return "SELECT * FROM (\n"
                + "  SELECT inner_q.*, ROWNUM AS rn FROM (\n"
                + "    " + innerSql.trim() + "\n"
                + "  ) inner_q WHERE ROWNUM <= :maxRow\n"
                + ") WHERE rn >= :minRow";
    }

    /**
     * Appends Oracle 12c FETCH FIRST n ROWS ONLY clause to a query.
     *
     * ORACLE-DEP [EMBEDDED_SQL:FETCH_FIRST] Oracle 12c FETCH FIRST ROWS ONLY syntax
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "FETCH_FIRST",
            description = "Appends Oracle 12c FETCH FIRST n ROWS ONLY for top-N queries",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Oracle FETCH FIRST is SQL standard — compatible with PostgreSQL without change"
    )
    public String wrapWithFetchFirst(String innerSql, int limit) {
        if (innerSql == null || innerSql.trim().isEmpty()) {
            throw new IllegalArgumentException("Inner SQL must not be null or empty");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive, got: " + limit);
        }
        // ORACLE-DEP [EMBEDDED_SQL:FETCH_FIRST] Oracle 12c+ FETCH FIRST n ROWS ONLY — SQL standard syntax
        return innerSql.trim() + "\nFETCH FIRST " + limit + " ROWS ONLY";
    }

    // -----------------------------------------------------------------------
    // Hierarchical query builder
    // -----------------------------------------------------------------------

    /**
     * Builds an Oracle hierarchical query using CONNECT BY PRIOR.
     *
     * Includes LEVEL, SYS_CONNECT_BY_PATH, CONNECT_BY_ISLEAF, CONNECT_BY_ROOT.
     *
     * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Oracle CONNECT BY PRIOR hierarchical traversal
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "CONNECT_BY",
            description = "Builds Oracle CONNECT BY PRIOR hierarchical query with LEVEL and SYS_CONNECT_BY_PATH",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with recursive CTE (WITH RECURSIVE) in PostgreSQL"
    )
    public String buildConnectByQuery(String tableName, String idCol,
                                      String parentIdCol, String displayCol) {
        if (tableName == null || idCol == null || parentIdCol == null || displayCol == null) {
            throw new IllegalArgumentException("All parameters must be non-null");
        }
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] LEVEL pseudocolumn — node depth starting at 1 for roots
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] SYS_CONNECT_BY_PATH() — full path from root to current node
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT_BY_ISLEAF — 1 if node has no children
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT_BY_ROOT — value at root of the current subtree
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT BY PRIOR — hierarchical traversal direction
        return "SELECT " + idCol + ", " + parentIdCol + ", " + displayCol + ",\n"
                + "       LEVEL AS hierarchy_level,\n"
                + "       SYS_CONNECT_BY_PATH(" + displayCol + ", '/') AS full_path,\n"
                + "       CONNECT_BY_ISLEAF AS is_leaf,\n"
                + "       CONNECT_BY_ROOT " + idCol + " AS root_id\n"
                + "FROM " + tableName + "\n"
                + "START WITH " + parentIdCol + " IS NULL\n"
                + "CONNECT BY PRIOR " + idCol + " = " + parentIdCol + "\n"
                + "ORDER SIBLINGS BY " + displayCol;
    }

    // -----------------------------------------------------------------------
    // Oracle optimizer hint injection
    // -----------------------------------------------------------------------

    /**
     * Injects an Oracle optimizer hint immediately after the SELECT keyword.
     *
     * Common hints: INDEX(alias name), PARALLEL(alias degree), FULL(alias),
     *               LEADING(t1 t2), USE_NL(alias), NO_MERGE.
     *
     * ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] Oracle optimizer hint injection after SELECT keyword
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "QUERY_HINT",
            description = "Injects Oracle optimizer hint after SELECT keyword",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Remove Oracle hints; replace with PostgreSQL-specific hints via pg_hint_plan if needed"
    )
    public String injectHint(String sql, String hint) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL must not be null or empty");
        }
        if (hint == null || hint.trim().isEmpty()) {
            return sql;
        }
        // ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] Oracle hint /*+ ... */ injected immediately after SELECT keyword
        String trimmedSql = sql.trim();
        String hintClause = "/*+ " + hint.trim() + " */ ";
        int selectIdx = trimmedSql.toUpperCase().indexOf("SELECT");
        if (selectIdx < 0) {
            log.warn("Cannot inject hint: no SELECT keyword found in SQL");
            return sql;
        }
        return trimmedSql.substring(0, selectIdx + 6)
                + " " + hintClause
                + trimmedSql.substring(selectIdx + 6).stripLeading();
    }

    // -----------------------------------------------------------------------
    // NVL and DECODE expression builders
    // -----------------------------------------------------------------------

    /**
     * Builds an Oracle NVL(column, default) expression.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Oracle NVL() null-defaulting expression
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "NVL_FUNCTION",
            description = "Builds Oracle NVL(column, default) expression fragment",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace NVL(col, default) with COALESCE(col, default) in PostgreSQL"
    )
    public String buildNvlExpression(String column, String defaultValue) {
        if (column == null || defaultValue == null) {
            throw new IllegalArgumentException("Column and defaultValue must not be null");
        }
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Oracle NVL() — use COALESCE() in PostgreSQL/standard SQL
        return "NVL(" + column + ", " + defaultValue + ")";
    }

    /**
     * Builds an Oracle DECODE(column, val1, result1, val2, result2, ..., default) expression.
     *
     * whenThenPairs: [val1, result1, val2, result2, ..., default]
     *
     * ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Oracle DECODE() conditional expression
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "DECODE_FUNCTION",
            description = "Builds Oracle DECODE(col, val1, result1, ..., default) conditional expression",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace DECODE(...) with CASE WHEN ... THEN ... ELSE ... END in PostgreSQL"
    )
    public String buildDecodeExpression(String column, String... whenThenPairs) {
        if (column == null || whenThenPairs == null || whenThenPairs.length == 0) {
            throw new IllegalArgumentException("Column and when/then pairs must not be null or empty");
        }
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Oracle DECODE() — equivalent to CASE WHEN in standard SQL
        StringBuilder sb = new StringBuilder("DECODE(").append(column);
        for (String part : whenThenPairs) {
            sb.append(", ").append(part);
        }
        return sb.append(")").toString();
    }

    // -----------------------------------------------------------------------
    // MERGE statement builder
    // -----------------------------------------------------------------------

    /**
     * Assembles a complete Oracle MERGE INTO ... USING (SELECT 1 FROM DUAL) ... statement.
     *
     * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Oracle MERGE INTO ... USING ... statement
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "MERGE_STATEMENT",
            description = "Builds Oracle MERGE INTO ... USING (SELECT FROM DUAL) upsert statement",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with INSERT ... ON CONFLICT (col) DO UPDATE SET ... in PostgreSQL"
    )
    public String buildMergeStatement(String targetTable, String sourceAlias,
                                      String joinCondition, String updateClause,
                                      String insertColumns, String insertValues) {
        if (targetTable == null || joinCondition == null
                || updateClause == null || insertColumns == null || insertValues == null) {
            throw new IllegalArgumentException("All MERGE parameters must be non-null");
        }
        String alias = (sourceAlias != null && !sourceAlias.trim().isEmpty()) ? sourceAlias : "src";
        // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Oracle MERGE INTO ... USING ... ON ... WHEN MATCHED/NOT MATCHED
        // ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE] SELECT 1 FROM DUAL used as single-row source in MERGE USING clause
        return "MERGE INTO " + targetTable.trim() + " tgt\n"
                + "USING (SELECT 1 FROM DUAL) " + alias + "\n"
                + "ON (" + joinCondition.trim() + ")\n"
                + "WHEN MATCHED THEN\n"
                + "  UPDATE SET " + updateClause.trim() + "\n"
                + "WHEN NOT MATCHED THEN\n"
                + "  INSERT (" + insertColumns.trim() + ")\n"
                + "  VALUES (" + insertValues.trim() + ")";
    }

    // -----------------------------------------------------------------------
    // Oracle date arithmetic filter
    // -----------------------------------------------------------------------

    /**
     * Builds an Oracle date arithmetic filter: column >= SYSDATE - n days.
     *
     * Oracle allows integer arithmetic directly on DATE values (date ± integer = date).
     * PostgreSQL requires: column >= NOW() - INTERVAL 'n days'
     *
     * ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] Oracle SYSDATE +/- integer day arithmetic
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "DATE_ARITHMETIC",
            description = "Builds Oracle SYSDATE - n date arithmetic filter fragment",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace SYSDATE - n with NOW() - INTERVAL 'n days' in PostgreSQL"
    )
    public String buildDateArithmeticFilter(String dateColumn, int daysBack) {
        if (dateColumn == null || dateColumn.trim().isEmpty()) {
            throw new IllegalArgumentException("Date column must not be null or empty");
        }
        if (daysBack < 0) {
            throw new IllegalArgumentException("daysBack must be non-negative, got: " + daysBack);
        }
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] Oracle date + integer arithmetic — SYSDATE - n subtracts days
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Oracle SYSDATE returns current date+time without timezone
        return dateColumn.trim() + " >= SYSDATE - " + daysBack;
    }

    // -----------------------------------------------------------------------
    // Oracle SYSDATE / SYSTIMESTAMP reference helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the Oracle SYSDATE expression for embedding in SQL strings.
     *
     * ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Oracle SYSDATE current date/time expression
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "SYSDATE",
            description = "Returns Oracle SYSDATE keyword for embedding in SQL strings",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace SYSDATE with NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    public String sysdate() {
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Oracle SYSDATE — use NOW() or CURRENT_TIMESTAMP in PostgreSQL
        return "SYSDATE";
    }

    /**
     * Returns the Oracle SYSTIMESTAMP expression for embedding in SQL strings.
     *
     * ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] Oracle SYSTIMESTAMP with timezone expression
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "SYSTIMESTAMP",
            description = "Returns Oracle SYSTIMESTAMP keyword for embedding in SQL strings",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace SYSTIMESTAMP with CURRENT_TIMESTAMP in PostgreSQL"
    )
    public String systimestamp() {
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] Oracle SYSTIMESTAMP — use CURRENT_TIMESTAMP in PostgreSQL
        return "SYSTIMESTAMP";
    }
}

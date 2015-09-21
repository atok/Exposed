package kotlin.sql.vendors

import java.util.*
import kotlin.sql.*

/**
 * User: Andrey.Tarashevskiy
 * Date: 08.05.2015
 */

internal object MysqlDialect : VendorDialect() {

    override @Synchronized fun tableColumns(): Map<String, List<Pair<String, Boolean>>> {

        val tables = HashMap<String, List<Pair<String, Boolean>>>()

        val rs = Session.get().connection.createStatement().executeQuery(
                "SELECT DISTINCT TABLE_NAME, COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '${getDatabase()}'")

        while (rs.next()) {
            val tableName = rs.getString("TABLE_NAME")!!
            val columnName = rs.getString("COLUMN_NAME")!!
            val nullable = rs.getBoolean("IS_NULLABLE")
            tables[tableName] = (tables[tableName]?.plus(listOf(columnName to nullable)) ?: listOf(columnName to nullable))
        }
        return tables
    }

    override @Synchronized fun columnConstraints(vararg tables: Table): Map<Pair<String, String>, List<ForeignKeyConstraint>> {

        val constraints = HashMap<Pair<String, String>, MutableList<ForeignKeyConstraint>>()

        val tableNames = tables.map { it.tableName }

        fun inTableList(): String {
            if (tables.isNotEmpty()) {
                return " AND ku.TABLE_NAME IN ${tableNames.join("','", prefix = "('", postfix = "')")}"
            }
            return ""
        }

        val rs = Session.get().connection.createStatement().executeQuery(
                "SELECT\n" +
                        "  rc.CONSTRAINT_NAME,\n" +
                        "  ku.TABLE_NAME,\n" +
                        "  ku.COLUMN_NAME,\n" +
                        "  ku.REFERENCED_TABLE_NAME,\n" +
                        "  ku.REFERENCED_COLUMN_NAME,\n" +
                        "  rc.DELETE_RULE\n" +
                        "FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc\n" +
                        "  INNER JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku\n" +
                        "    ON ku.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA AND rc.CONSTRAINT_NAME = ku.CONSTRAINT_NAME\n" +
                        "WHERE ku.TABLE_SCHEMA = '${getDatabase()}' ${inTableList()}")

        while (rs.next()) {
            val refereeTableName = rs.getString("TABLE_NAME")!!
            if (refereeTableName !in tableNames) continue
            val refereeColumnName = rs.getString("COLUMN_NAME")!!
            val constraintName = rs.getString("CONSTRAINT_NAME")!!
            val refTableName = rs.getString("REFERENCED_TABLE_NAME")!!
            val refColumnName = rs.getString("REFERENCED_COLUMN_NAME")!!
            val constraintDeleteRule = ReferenceOption.valueOf(rs.getString("DELETE_RULE")!!.replace(" ", "_"))
            constraints.getOrPut(Pair(refereeTableName, refereeColumnName), {arrayListOf()}).add(ForeignKeyConstraint(constraintName, refereeTableName, refereeColumnName, refTableName, refColumnName, constraintDeleteRule))
        }

        return constraints
    }

    override @Synchronized fun existingIndices(vararg tables: Table): Map<String, List<Index>> {

        val constraints = HashMap<String, MutableList<Index>>()

        val rs = Session.get().connection.createStatement().executeQuery(
                """SELECT ind.* from (
                        SELECT
                            TABLE_NAME, INDEX_NAME, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS `COLUMNS`, NON_UNIQUE
                            FROM INFORMATION_SCHEMA.STATISTICS s
                            WHERE table_schema = '${getDatabase()}' and INDEX_NAME <> 'PRIMARY'
                            GROUP BY 1, 2) ind
                LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                    on kcu.TABLE_NAME = ind.TABLE_NAME
                        and kcu.COLUMN_NAME = ind.columns
                        and TABLE_SCHEMA = '${getDatabase()}'
                        and kcu.REFERENCED_TABLE_NAME is not NULL
                WHERE kcu.COLUMN_NAME is NULL;
        """)

        while (rs.next()) {
            val tableName = rs.getString("TABLE_NAME")!!
            val indexName = rs.getString("INDEX_NAME")!!
            val columnsInIndex = rs.getString("COLUMNS")!!.split(',')
            val isUnique = rs.getInt("NON_UNIQUE") == 0
            constraints.getOrPut(tableName, { arrayListOf() }).add(Index(indexName, tableName, columnsInIndex, isUnique))
        }

        return constraints
    }

    override fun getDatabase(): String {
        return Session.get().connection.catalog
    }
}

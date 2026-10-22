package ru.campus.equipment.postgres

import org.flywaydb.core.Flyway
import ru.campus.equipment.common.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PgDatabase(private val dataSource: DataSource) : Transactions {
    private val current = ThreadLocal<Connection>()

    fun migrate() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .cleanDisabled(true).validateMigrationNaming(true).load().migrate()
    }

    fun healthy(): Boolean = try { dataSource.connection.use { it.isValid(2) } } catch (_: SQLException) { false }

    internal fun connection(): Connection = checkNotNull(current.get()) { "Repository access requires a transaction" }

    override fun <T> execute(block: () -> T): T {
        check(current.get() == null) { "Nested transactions are not supported" }
        try {
            return dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                current.set(connection)
                try {
                    connection.createStatement().use {
                        it.execute("SET LOCAL lock_timeout = '5s'")
                        it.execute("SET LOCAL statement_timeout = '10s'")
                    }
                    val value = block()
                    connection.commit()
                    value
                } catch (failure: Throwable) {
                    try { connection.rollback() } catch (rollbackFailure: SQLException) { failure.addSuppressed(rollbackFailure) }
                    throw failure
                } finally {
                    current.remove()
                }
            }
        } catch (failure: SQLException) {
            val translated = when (failure.sqlState) {
                "23P01" -> BusinessException(ErrorKind.CONFLICT, "BOOKING_CONFLICT", "Equipment is already reserved")
                "23505" -> BusinessException(ErrorKind.CONFLICT, "DUPLICATE_RESOURCE", "A unique value or active equipment loan already exists")
                "23503" -> BusinessException(ErrorKind.CONFLICT, "REFERENCE_CONFLICT", "Related resource is unavailable")
                else -> BusinessException(ErrorKind.UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database operation failed; retry later")
            }
            translated.initCause(failure)
            throw translated
        }
    }
}

internal fun PreparedStatement.bind(values: List<Any?>) {
    values.forEachIndexed { index, value ->
        when (value) {
            is Instant -> setTimestamp(index + 1, Timestamp.from(value))
            is Enum<*> -> setString(index + 1, value.name)
            else -> setObject(index + 1, value)
        }
    }
}

internal fun Connection.update(sql: String, vararg values: Any?): Int = prepareStatement(sql).use {
    it.bind(values.toList())
    it.executeUpdate()
}

internal fun <T> Connection.query(sql: String, values: List<Any?> = emptyList(), map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(values)
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

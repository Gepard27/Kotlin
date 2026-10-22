package ru.campus.equipment.postgres

import ru.campus.equipment.common.Page
import ru.campus.equipment.identity.*
import java.sql.ResultSet
import java.util.UUID

class PgUsers(private val db: PgDatabase) : UserRepository {
    override fun findByKeyHash(hash: String): User? = db.connection()
        .query("SELECT id, name, email, role FROM app_users WHERE api_key_hash = ?", listOf(hash), ::user).singleOrNull()

    override fun findByEmail(email: String): User? = db.connection()
        .query("SELECT id, name, email, role FROM app_users WHERE email = ?", listOf(email), ::user).singleOrNull()

    override fun insert(user: User, keyHash: String) {
        db.connection().update("INSERT INTO app_users(id, name, email, role, api_key_hash) VALUES (?, ?, ?, ?, ?)",
            user.id, user.name, user.email, user.role, keyHash)
    }

    override fun list(page: Page): List<User> = db.connection()
        .query("SELECT id, name, email, role FROM app_users ORDER BY email, id LIMIT ? OFFSET ?", listOf(page.limit, page.offset), ::user)

    override fun lockBootstrap() {
        db.connection().query("SELECT pg_advisory_xact_lock(17351001)") { Unit }
    }

    private fun user(rs: ResultSet) = User(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("email"), Role.valueOf(rs.getString("role")))
}

package ru.campus.equipment.identity

import ru.campus.equipment.common.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

enum class Role { USER, STAFF, ADMIN }

data class User(val id: UUID, val name: String, val email: String, val role: Role) {
    fun requireStaff() { if (role == Role.USER) forbidden() }
    fun requireAdmin() { if (role != Role.ADMIN) forbidden() }
}

interface UserRepository {
    fun findByKeyHash(hash: String): User?
    fun findByEmail(email: String): User?
    fun insert(user: User, keyHash: String)
    fun list(page: Page): List<User>
    fun lockBootstrap()
}

data class CreatedUser(val user: User, val apiKey: String)

class IdentityService(private val transactions: Transactions, private val users: UserRepository) {
    fun authenticate(apiKey: String?): User {
        if (apiKey.isNullOrBlank() || apiKey.length > 256) unauthorized()
        return transactions.execute { users.findByKeyHash(hash(apiKey)) } ?: unauthorized()
    }

    fun create(actor: User, name: String, email: String, role: Role): CreatedUser {
        actor.requireAdmin()
        val user = newUser(name, email, role)
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        transactions.execute {
            if (users.findByEmail(user.email) != null) conflict("EMAIL_EXISTS", "Email is already registered")
            users.insert(user, hash(key))
        }
        return CreatedUser(user, key)
    }

    fun list(actor: User, page: Page): List<User> {
        actor.requireAdmin()
        return transactions.execute { users.list(page) }
    }

    fun bootstrap(name: String, email: String, apiKey: String) {
        require(apiKey.length in 32..256 && apiKey.none(Char::isWhitespace)) {
            "BOOTSTRAP_ADMIN_KEY must contain 32..256 non-whitespace characters"
        }
        val user = newUser(name, email, Role.ADMIN)
        transactions.execute {
            users.lockBootstrap()
            val existing = users.findByEmail(user.email)
            if (existing == null) users.insert(user, hash(apiKey))
            else check(existing.role == Role.ADMIN && users.findByKeyHash(hash(apiKey))?.id == existing.id) {
                "Bootstrap administrator does not match persisted credentials; use the original bootstrap configuration"
            }
        }
    }

    private fun newUser(name: String, email: String, role: Role): User {
        val normalizedEmail = requiredText(email, "email", 254).lowercase(Locale.ROOT)
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalizedEmail)) invalid("Invalid email")
        return User(UUID.randomUUID(), requiredText(name, "name", 120), normalizedEmail, role)
    }

    companion object {
        fun hash(key: String): String = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun unauthorized(): Nothing = throw BusinessException(
            ErrorKind.UNAUTHENTICATED, "UNAUTHENTICATED", "A valid Bearer API key is required"
        )
    }
}

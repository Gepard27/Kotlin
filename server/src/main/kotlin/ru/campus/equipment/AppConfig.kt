package ru.campus.equipment

data class AppConfig(
    val databaseUrl: String, val databaseUser: String, val databasePassword: String,
    val adminName: String, val adminEmail: String, val adminKey: String,
    val host: String, val port: Int, val poolSize: Int, val httpThreads: Int, val logLevel: String,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(name: String): String = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Environment variable $name is required")
            fun number(name: String, default: Int, range: IntRange): Int {
                val value = env[name]?.let { it.toIntOrNull() ?: error("$name must be an integer") } ?: default
                require(value in range) { "$name must be in $range" }
                return value
            }
            val key = required("BOOTSTRAP_ADMIN_KEY")
            require(key.length in 32..256 && key.none(Char::isWhitespace)) { "BOOTSTRAP_ADMIN_KEY must be 32..256 non-whitespace characters" }
            val level = env["LOG_LEVEL"]?.lowercase() ?: "info"
            require(level in setOf("trace", "debug", "info", "warn", "error")) { "Invalid LOG_LEVEL" }
            val url = required("DATABASE_URL")
            require(url.startsWith("jdbc:postgresql://")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
            return AppConfig(url, required("DATABASE_USER"), required("DATABASE_PASSWORD"),
                env["BOOTSTRAP_ADMIN_NAME"] ?: "Administrator", required("BOOTSTRAP_ADMIN_EMAIL"), key,
                env["HTTP_HOST"] ?: "0.0.0.0", number("HTTP_PORT", 8080, 1..65535),
                number("DB_POOL_SIZE", 10, 1..64), number("HTTP_THREADS", 16, 1..128), level)
        }
    }
}

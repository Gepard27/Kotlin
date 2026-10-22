package ru.campus.equipment

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import ru.campus.equipment.booking.BookingService
import ru.campus.equipment.http.HttpApi
import ru.campus.equipment.identity.IdentityService
import ru.campus.equipment.inventory.InventoryService
import ru.campus.equipment.postgres.*
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.CountDownLatch

fun main() {
    val config = AppConfig.fromEnvironment()
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", config.logLevel)
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
    System.setProperty("sun.net.httpserver.maxReqTime", "15")
    System.setProperty("sun.net.httpserver.maxRspTime", "15")
    val logger = LoggerFactory.getLogger("Application")
    val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = config.poolSize
        connectionTimeout = 5_000
        validationTimeout = 2_000
        poolName = "campus-equipment"
    })
    try {
        val database = PgDatabase(pool)
        database.migrate()
        val identity = IdentityService(database, PgUsers(database))
        identity.bootstrap(config.adminName, config.adminEmail, config.adminKey)
        val inventoryRepository = PgEquipment(database)
        val clock = Clock.systemUTC()
        val api = HttpApi(InetSocketAddress(config.host, config.port), identity,
            InventoryService(database, inventoryRepository),
            BookingService(database, PgBookings(database), inventoryRepository, clock), database::healthy, clock, config.httpThreads)
        val stopped = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Stopping server")
            try { api.close() } finally { pool.close(); stopped.countDown() }
        })
        api.start()
        logger.info("Campus Equipment listening on {}:{}", config.host, api.port)
        stopped.await()
    } catch (failure: Throwable) {
        pool.close()
        throw failure
    }
}

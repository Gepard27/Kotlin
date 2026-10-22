package ru.campus.equipment.postgres

import ru.campus.equipment.common.Page
import ru.campus.equipment.inventory.*
import java.sql.ResultSet
import java.util.UUID

class PgEquipment(private val db: PgDatabase) : EquipmentRepository {
    override fun find(id: UUID, lock: Boolean): Equipment? = db.connection()
        .query("SELECT * FROM equipment WHERE id = ?" + if (lock) " FOR UPDATE" else "", listOf(id), ::equipment).singleOrNull()

    override fun list(page: Page, category: String?): List<Equipment> {
        val filter = if (category == null) "" else " WHERE category = ?"
        val args = buildList { if (category != null) add(category); add(page.limit); add(page.offset) }
        return db.connection().query("SELECT * FROM equipment$filter ORDER BY inventory_number, id LIMIT ? OFFSET ?", args, ::equipment)
    }

    override fun insert(equipment: Equipment) {
        db.connection().update("INSERT INTO equipment(id, inventory_number, name, category, location, description, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
            equipment.id, equipment.inventoryNumber, equipment.name, equipment.category, equipment.location, equipment.description, equipment.status)
    }

    override fun updateStatus(id: UUID, status: EquipmentStatus) {
        check(db.connection().update("UPDATE equipment SET status = ? WHERE id = ?", status, id) == 1)
    }

    private fun equipment(rs: ResultSet) = Equipment(
        rs.getObject("id", UUID::class.java), rs.getString("inventory_number"), rs.getString("name"),
        rs.getString("category"), rs.getString("location"), rs.getString("description"), EquipmentStatus.valueOf(rs.getString("status")),
    )
}

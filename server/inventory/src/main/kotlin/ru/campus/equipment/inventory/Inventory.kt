package ru.campus.equipment.inventory

import ru.campus.equipment.common.*
import ru.campus.equipment.identity.User
import java.util.UUID

enum class EquipmentStatus { ACTIVE, MAINTENANCE, RETIRED }

data class Equipment(
    val id: UUID,
    val inventoryNumber: String,
    val name: String,
    val category: String,
    val location: String,
    val description: String,
    val status: EquipmentStatus = EquipmentStatus.ACTIVE,
) {
    fun requireActive() {
        if (status != EquipmentStatus.ACTIVE) conflict("EQUIPMENT_UNAVAILABLE", "Equipment is not active")
    }
}

interface EquipmentRepository {
    fun find(id: UUID, lock: Boolean = false): Equipment?
    fun list(page: Page, category: String?): List<Equipment>
    fun insert(equipment: Equipment)
    fun updateStatus(id: UUID, status: EquipmentStatus)
}

class InventoryService(private val transactions: Transactions, private val equipment: EquipmentRepository) {
    fun create(actor: User, inventoryNumber: String, name: String, category: String, location: String, description: String): Equipment {
        actor.requireStaff()
        val result = Equipment(
            UUID.randomUUID(), requiredText(inventoryNumber, "inventoryNumber", 64),
            requiredText(name, "name", 160), requiredText(category, "category", 80),
            requiredText(location, "location", 160), description.trim().also {
                if (it.length > 2000) invalid("description must be at most 2000 characters")
            },
        )
        return transactions.execute { equipment.insert(result); result }
    }

    fun get(id: UUID): Equipment = transactions.execute { equipment.find(id) ?: missing("Equipment") }
    fun list(page: Page, category: String?): List<Equipment> = transactions.execute {
        equipment.list(page, category?.let { requiredText(it, "category", 80) })
    }
}

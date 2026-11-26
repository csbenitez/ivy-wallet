package com.ivy.sharedwallet.data

import androidx.room.withTransaction
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.BudgetEntity
import com.ivy.data.db.entity.CategoryEntity
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TagEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.sharedwallet.engine.LedgerChanges
import com.ivy.sharedwallet.engine.LocalLedgerStore
import com.ivy.sharedwallet.engine.RecordKey
import com.ivy.sharedwallet.engine.SyncRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import timber.log.Timber
import java.util.UUID

/**
 * Exposes the shareable data of one workspace database to the sync engine.
 *
 * Settings (base currency, name) and exchange rates stay per device on purpose.
 */
class RoomLocalLedgerStore(
    private val db: IvyRoomDatabase,
    private val json: Json,
) : LocalLedgerStore {

    private class EntityHandler<T>(
        val type: String,
        val serializer: KSerializer<T>,
        val findAll: suspend () -> List<T>,
        val idOf: (T) -> String,
        val saveAll: suspend (List<T>) -> Unit,
        val deleteById: suspend (String) -> Unit,
    ) {
        suspend fun snapshot(json: Json): List<Pair<RecordKey, JsonElement>> =
            findAll().map { RecordKey(type, idOf(it)) to json.encodeToJsonElement(serializer, it) }

        suspend fun upsert(json: Json, records: List<SyncRecord>) {
            val entities = records.mapNotNull { record ->
                try {
                    json.decodeFromJsonElement(serializer, record.data!!)
                } catch (e: Exception) {
                    Timber.e(e, "Skipping invalid shared $type ${record.id}")
                    null
                }
            }
            if (entities.isNotEmpty()) saveAll(entities)
        }

        fun normalize(json: Json, data: JsonElement): JsonElement =
            json.encodeToJsonElement(serializer, json.decodeFromJsonElement(serializer, data))
    }

    // Order matters: parents before children when inserting.
    private val handlers: List<EntityHandler<*>> = listOf(
        EntityHandler(
            type = "accounts",
            serializer = AccountEntity.serializer(),
            findAll = { db.accountDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writeAccountDao.saveMany(it) },
            deleteById = { db.writeAccountDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "categories",
            serializer = CategoryEntity.serializer(),
            findAll = { db.categoryDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writeCategoryDao.saveMany(it) },
            deleteById = { db.writeCategoryDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "tags",
            serializer = TagEntity.serializer(),
            findAll = { db.tagDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writeTagDao.save(it) },
            deleteById = { db.writeTagDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "loans",
            serializer = LoanEntity.serializer(),
            findAll = { db.loanDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writeLoanDao.saveMany(it) },
            deleteById = { db.writeLoanDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "loan_records",
            serializer = LoanRecordEntity.serializer(),
            findAll = { db.loanRecordDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writeLoanRecordDao.saveMany(it) },
            deleteById = { db.writeLoanRecordDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "planned_payment_rules",
            serializer = PlannedPaymentRuleEntity.serializer(),
            findAll = { db.plannedPaymentRuleDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writePlannedPaymentRuleDao.saveMany(it) },
            deleteById = { db.writePlannedPaymentRuleDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "budgets",
            serializer = BudgetEntity.serializer(),
            findAll = { db.budgetDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writeBudgetDao.saveMany(it) },
            deleteById = { db.writeBudgetDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "transactions",
            serializer = TransactionEntity.serializer(),
            findAll = { db.transactionDao.findAll() },
            idOf = { it.id.toString() },
            saveAll = { db.writeTransactionDao.saveMany(it) },
            deleteById = { db.writeTransactionDao.deleteById(UUID.fromString(it)) },
        ),
        EntityHandler(
            type = "tags_association",
            serializer = TagAssociationEntity.serializer(),
            findAll = { db.tagAssociationDao.findAll() },
            idOf = { "${it.tagId}$ID_SEPARATOR${it.associatedId}" },
            saveAll = { db.writeTagAssociationDao.save(it) },
            deleteById = { id ->
                val (tagId, associatedId) = id.split(ID_SEPARATOR)
                db.writeTagAssociationDao.deleteId(UUID.fromString(tagId), UUID.fromString(associatedId))
            },
        ),
    )
    private val handlersByType = handlers.associateBy { it.type }

    override val supportedTypes: Set<String> = handlersByType.keys

    override suspend fun snapshot(): Map<RecordKey, JsonElement> {
        val result = HashMap<RecordKey, JsonElement>()
        handlers.forEach { handler -> result.putAll(handler.snapshot(json)) }
        return result
    }

    override suspend fun apply(changes: LedgerChanges) {
        val upsertsByType = changes.upserts.groupBy { it.type }
        val deletesByType = changes.deletes.groupBy { it.type }
        db.withTransaction {
            handlers.asReversed().forEach { handler ->
                deletesByType[handler.type].orEmpty().forEach { handler.deleteById(it.id) }
            }
            handlers.forEach { handler ->
                upsertsByType[handler.type]?.let { handler.upsert(json, it) }
            }
        }
    }

    override fun normalize(record: SyncRecord): SyncRecord {
        val handler = handlersByType[record.type]
        return when {
            record.isTombstone -> record.copy(data = null)
            handler == null -> record
            else -> try {
                record.copy(data = handler.normalize(json, record.data!!))
            } catch (e: Exception) {
                Timber.e(e, "Can't read shared ${record.type} ${record.id}")
                record
            }
        }
    }

    companion object {
        private const val ID_SEPARATOR = "_"
    }
}

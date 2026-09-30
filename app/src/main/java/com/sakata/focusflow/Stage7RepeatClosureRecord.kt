package com.sakata.focusflow

/**
 * 阶段 7.4 · 重复规则恢复组的**独立**编解码。
 *
 * 本模块只提供内存模型与严格序列化能力：**不写入任何真实存储**，也**不接入或冒充**
 * 既有 `TrashJournal` 的组格式（后者由 7.3 拥有，成员种类与状态语义不同）。
 *
 * 严格性要求：
 * - 模板与实例的 `preState`/`postState` 保留**完整 `Item`**（含 null、`userNote`、`checklist`、
 *   关联字段、窗口、重复字段），`handlingReason` 真实往返；
 * - **不**用 `ItemsCodec.decode` 做快照解码：它会补默认值、归一化 ID、清理关联、迁移旧字段，
 *   会静默改变身份或字段。这里逐字段显式读写，缺失即拒绝；
 * - 整数无损且限定在 `Int`/`Long` 范围内；小数、指数、溢出、数字字符串、布尔字符串一律拒绝；
 * - 必填键缺失即拒绝；**可空字段也必须显式出现**（写 `null`）；
 * - 拒绝未知版本/种类、重复 groupId、成员身份冲突、非法期限、状态与恢复结果不一致；
 * - 未知或损坏负载返回 [RepeatClosureDecode.Invalid] 并**保留原始输入**；
 *   **不**解释为空组、**不**部分解码成功、**不**丢弃坏记录；
 * - 解码后只做**结构**校验：不读取当前任务/课程，动态恢复资格仍在 `RepeatClosureRecovery`。
 */

/** 编解码格式版本。格式变更必须提升它，旧版本随即被拒绝。 */
const val REPEAT_CLOSURE_FORMAT_VERSION: Int = 1

/** 组种类；本模块只处理重复规则闭包。 */
const val REPEAT_CLOSURE_KIND: String = "repeat_rule_closure"

/** 单个实例的恢复结果（落库用；与 `RecoveryInstanceStatus` 一一对应）。 */
enum class RepeatClosureInstanceStatus(val wire: String) {
    RESTORED("restored"),
    SKIPPED_PAST("skipped_past"),
    SKIPPED_COURSE("skipped_course");

    companion object {
        fun fromWire(value: String): RepeatClosureInstanceStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** 成功恢复后需要保存的逐实例结果与完成时间。 */
data class RepeatClosureRestoredInstance(
    val itemId: Long,
    val status: RepeatClosureInstanceStatus,
)

data class RepeatClosureRestoration(
    val restoredAt: Long,
    /** 必须与组内实例集合**完全相同**（同一批 ID，且不重复）。 */
    val instances: List<RepeatClosureRestoredInstance>,
)

data class RepeatClosureRecord(
    val groupId: String,
    val operationId: String,
    val template: RecoveryTemplateMember,
    val instances: List<RecoveryInstanceMember>,
    val deletedAt: Long,
    val expiresAt: Long,
    /** 只允许 `ACTIVE` 或 `RESTORED`。 */
    val status: RecoveryGroupStatus,
    /** `RESTORED` 时必须存在且结果一致；`ACTIVE` 时必须为空。 */
    val restoration: RepeatClosureRestoration? = null,
) {
    /** 转给既有策略做结构校验/恢复；不在这里重复一套判定。 */
    internal fun asClosure(): RecoveryClosure = RecoveryClosure(
        kind = REPEAT_CLOSURE_KIND,
        template = template,
        instances = instances,
        deletedAt = deletedAt,
        expiresAt = expiresAt,
        groupRestored = status == RecoveryGroupStatus.RESTORED,
    )
}

sealed interface RepeatClosureDecode {
    data class Ok(val record: RepeatClosureRecord) : RepeatClosureDecode

    /** 失败时保留调用方原始输入，便于调用点记录或原样回写，不做任何解释。 */
    data class Invalid(val reason: String, val raw: String) : RepeatClosureDecode
}

object RepeatClosureCodec {
    internal fun encodeItem(item: Item): String = StrictJson.write(itemJson(item))
    internal fun decodeItem(raw: String): Item {
        val value = (StrictJson.parse(raw) as? StrictJson.ParseResult.Ok)?.value
            ?: throw IllegalArgumentException("invalid item snapshot")
        return readItem(value, "item")
    }


    fun encode(record: RepeatClosureRecord): String = StrictJson.write(toJson(record))

    fun decode(raw: String): RepeatClosureDecode {
        val parsed = when (val result = StrictJson.parse(raw)) {
            is StrictJson.ParseResult.Invalid -> return RepeatClosureDecode.Invalid(result.reason, raw)
            is StrictJson.ParseResult.Ok -> result.value
        }
        return try {
            RepeatClosureDecode.Ok(readRecord(parsed))
        } catch (failure: Rejection) {
            RepeatClosureDecode.Invalid(failure.message ?: "invalid payload", raw)
        }
    }

    private class Rejection(message: String) : RuntimeException(message)

    private fun reject(message: String): Nothing = throw Rejection(message)

    // ---------- 编码 ----------

    private fun toJson(record: RepeatClosureRecord): StrictJson.Value = obj(
        "version" to StrictJson.Value.Num(REPEAT_CLOSURE_FORMAT_VERSION.toLong()),
        "kind" to StrictJson.Value.Str(REPEAT_CLOSURE_KIND),
        "groupId" to StrictJson.Value.Str(record.groupId),
        "operationId" to StrictJson.Value.Str(record.operationId),
        "deletedAt" to StrictJson.Value.Num(record.deletedAt),
        "expiresAt" to StrictJson.Value.Num(record.expiresAt),
        "status" to StrictJson.Value.Str(record.status.wire()),
        "template" to templateJson(record.template),
        "instances" to StrictJson.Value.Arr(record.instances.map(::instanceJson)),
        "restoration" to (record.restoration?.let(::restorationJson) ?: StrictJson.Value.Null),
    )

    private fun templateJson(template: RecoveryTemplateMember): StrictJson.Value = obj(
        "templateId" to StrictJson.Value.Num(template.templateId),
        "preState" to itemJson(template.preState),
        "postState" to itemJson(template.postState),
    )

    private fun instanceJson(member: RecoveryInstanceMember): StrictJson.Value = obj(
        "itemId" to StrictJson.Value.Num(member.itemId),
        "preState" to itemJson(member.preState),
        "postState" to itemJson(member.postState),
        "handlingReason" to StrictJson.Value.Str(member.handlingReason),
    )

    private fun restorationJson(restoration: RepeatClosureRestoration): StrictJson.Value = obj(
        "restoredAt" to StrictJson.Value.Num(restoration.restoredAt),
        "instances" to StrictJson.Value.Arr(restoration.instances.map { entry ->
            obj(
                "itemId" to StrictJson.Value.Num(entry.itemId),
                "status" to StrictJson.Value.Str(entry.status.wire),
            )
        }),
    )

    /** `Item` 的规范形式：**每个**字段都显式出现，可空字段写 `null`。 */
    private fun itemJson(item: Item): StrictJson.Value = obj(
        "id" to StrictJson.Value.Num(item.id),
        "title" to StrictJson.Value.Str(item.title),
        "detail" to StrictJson.Value.Str(item.detail),
        "kind" to StrictJson.Value.Str(item.kind),
        "done" to StrictJson.Value.Bool(item.done),
        "scheduledAt" to nullableNum(item.scheduledAt),
        "dayOnly" to StrictJson.Value.Bool(item.dayOnly),
        "goalId" to nullableNum(item.goalId),
        "completionLevel" to StrictJson.Value.Str(item.completionLevel),
        "completedAt" to nullableNum(item.completedAt),
        "durationMinutes" to StrictJson.Value.Num(item.durationMinutes.toLong()),
        "windowStartAt" to nullableNum(item.windowStartAt),
        "windowEndAt" to nullableNum(item.windowEndAt),
        "rescheduleCount" to StrictJson.Value.Num(item.rescheduleCount.toLong()),
        "lastRescheduledAt" to nullableNum(item.lastRescheduledAt),
        "recoverySourceScheduledAt" to nullableNum(item.recoverySourceScheduledAt),
        "priority" to StrictJson.Value.Str(item.priority),
        "captureRoute" to StrictJson.Value.Str(item.captureRoute),
        "sourceDetail" to StrictJson.Value.Str(item.sourceDetail),
        "userNote" to (item.userNote?.let { StrictJson.Value.Str(it) } ?: StrictJson.Value.Null),
        "nextAction" to StrictJson.Value.Str(item.nextAction),
        "parentCaptureId" to nullableNum(item.parentCaptureId),
        "dueAt" to nullableNum(item.dueAt),
        "checklist" to StrictJson.Value.Arr(item.checklist.map { entry ->
            obj(
                "id" to StrictJson.Value.Num(entry.id),
                "title" to StrictJson.Value.Str(entry.title),
                "done" to StrictJson.Value.Bool(entry.done),
            )
        }),
        "planBucket" to StrictJson.Value.Str(item.planBucket),
        "planFocus" to StrictJson.Value.Bool(item.planFocus),
        "repeatFrequency" to StrictJson.Value.Str(item.repeatFrequency),
        "repeatStartDay" to nullableNum(item.repeatStartDay),
        "repeatMinute" to StrictJson.Value.Num(item.repeatMinute.toLong()),
        "repeatTemplateId" to nullableNum(item.repeatTemplateId),
        "repeatOccurrenceDay" to nullableNum(item.repeatOccurrenceDay),
        "repeatPaused" to StrictJson.Value.Bool(item.repeatPaused),
        "trashedAt" to nullableNum(item.trashedAt),
        "trashSnapshot" to (item.trashSnapshot?.let { StrictJson.Value.Str(it) } ?: StrictJson.Value.Null),
    )

    private fun nullableNum(value: Long?): StrictJson.Value =
        value?.let { StrictJson.Value.Num(it) } ?: StrictJson.Value.Null

    private fun obj(vararg entries: Pair<String, StrictJson.Value>): StrictJson.Value.Obj =
        StrictJson.Value.Obj(entries.toList())

    private fun RecoveryGroupStatus.wire(): String = when (this) {
        RecoveryGroupStatus.ACTIVE -> "active"
        RecoveryGroupStatus.RESTORED -> "restored"
    }

    // ---------- 解码 ----------

    private fun readRecord(value: StrictJson.Value): RepeatClosureRecord {
        val root = objectKeys(value, "root", ROOT_KEYS)
        val version = long(root, "version", "root")
        if (version != REPEAT_CLOSURE_FORMAT_VERSION.toLong()) reject("unsupported version $version")
        val kind = string(root, "kind", "root")
        if (kind != REPEAT_CLOSURE_KIND) reject("unsupported kind \"$kind\"")
        val groupId = nonBlankString(root, "groupId", "root")
        val operationId = nonBlankString(root, "operationId", "root")
        val deletedAt = long(root, "deletedAt", "root")
        val expiresAt = long(root, "expiresAt", "root")
        val status = when (val wire = string(root, "status", "root")) {
            "active" -> RecoveryGroupStatus.ACTIVE
            "restored" -> RecoveryGroupStatus.RESTORED
            else -> reject("unsupported status \"$wire\"")
        }
        val template = readTemplate(root.entries.first { it.first == "template" }.second)
        val instances = array(root, "instances", "root").mapIndexed { index, element ->
            readInstance(element, "instances[$index]")
        }
        val restoration = when (val element = root.entries.first { it.first == "restoration" }.second) {
            StrictJson.Value.Null -> null
            else -> readRestoration(element)
        }
        val record = RepeatClosureRecord(
            groupId = groupId,
            operationId = operationId,
            template = template,
            instances = instances,
            deletedAt = deletedAt,
            expiresAt = expiresAt,
            status = status,
            restoration = restoration,
        )
        recordRejection(record)?.let { reject(it) }
        return record
    }

    private fun readTemplate(value: StrictJson.Value): RecoveryTemplateMember {
        val obj = objectKeys(value, "template", TEMPLATE_KEYS)
        val templateId = long(obj, "templateId", "template")
        val preState = readItem(obj.entries.first { it.first == "preState" }.second, "template.preState")
        val postState = readItem(obj.entries.first { it.first == "postState" }.second, "template.postState")
        return RecoveryTemplateMember(templateId, postState, preState)
    }

    private fun readInstance(value: StrictJson.Value, where: String): RecoveryInstanceMember {
        val obj = objectKeys(value, where, INSTANCE_KEYS)
        val itemId = long(obj, "itemId", where)
        val preState = readItem(obj.entries.first { it.first == "preState" }.second, "$where.preState")
        val postState = readItem(obj.entries.first { it.first == "postState" }.second, "$where.postState")
        val handlingReason = string(obj, "handlingReason", where)
        return RecoveryInstanceMember(itemId, preState, postState, handlingReason)
    }

    private fun readRestoration(value: StrictJson.Value): RepeatClosureRestoration {
        val obj = objectKeys(value, "restoration", RESTORATION_KEYS)
        val restoredAt = long(obj, "restoredAt", "restoration")
        val instances = array(obj, "instances", "restoration").mapIndexed { index, element ->
            val where = "restoration.instances[$index]"
            val entry = objectKeys(element, where, RESTORED_INSTANCE_KEYS)
            val itemId = long(entry, "itemId", where)
            val wire = string(entry, "status", where)
            val status = RepeatClosureInstanceStatus.fromWire(wire) ?: reject("unsupported instance status \"$wire\"")
            RepeatClosureRestoredInstance(itemId, status)
        }
        return RepeatClosureRestoration(restoredAt, instances)
    }

    private fun readItem(value: StrictJson.Value, where: String): Item {
        val obj = objectKeys(value, where, ITEM_KEYS)
        val checklist = array(obj, "checklist", where).mapIndexed { index, element ->
            val entryWhere = "$where.checklist[$index]"
            val entry = objectKeys(element, entryWhere, CHECKLIST_KEYS)
            ChecklistEntry(
                id = long(entry, "id", entryWhere),
                title = string(entry, "title", entryWhere),
                done = bool(entry, "done", entryWhere),
            )
        }
        return Item(
            // 身份字段不做任何归一化：解码结果必须与编码前逐字段相同。
            id = long(obj, "id", where),
            title = string(obj, "title", where),
            detail = string(obj, "detail", where),
            kind = string(obj, "kind", where),
            done = bool(obj, "done", where),
            scheduledAt = nullableLong(obj, "scheduledAt", where),
            dayOnly = bool(obj, "dayOnly", where),
            goalId = nullableLong(obj, "goalId", where),
            completionLevel = string(obj, "completionLevel", where),
            completedAt = nullableLong(obj, "completedAt", where),
            durationMinutes = intInRange(obj, "durationMinutes", where, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            windowStartAt = nullableLong(obj, "windowStartAt", where),
            windowEndAt = nullableLong(obj, "windowEndAt", where),
            rescheduleCount = intInRange(obj, "rescheduleCount", where, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            lastRescheduledAt = nullableLong(obj, "lastRescheduledAt", where),
            recoverySourceScheduledAt = nullableLong(obj, "recoverySourceScheduledAt", where),
            priority = string(obj, "priority", where),
            captureRoute = string(obj, "captureRoute", where),
            sourceDetail = string(obj, "sourceDetail", where),
            userNote = nullableString(obj, "userNote", where),
            nextAction = string(obj, "nextAction", where),
            parentCaptureId = nullableLong(obj, "parentCaptureId", where),
            dueAt = nullableLong(obj, "dueAt", where),
            checklist = checklist,
            planBucket = string(obj, "planBucket", where),
            planFocus = bool(obj, "planFocus", where),
            repeatFrequency = string(obj, "repeatFrequency", where),
            repeatStartDay = nullableLong(obj, "repeatStartDay", where),
            repeatMinute = intInRange(obj, "repeatMinute", where, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            repeatTemplateId = nullableLong(obj, "repeatTemplateId", where),
            repeatOccurrenceDay = nullableLong(obj, "repeatOccurrenceDay", where),
            repeatPaused = bool(obj, "repeatPaused", where),
            trashedAt = nullableLong(obj, "trashedAt", where),
            trashSnapshot = nullableString(obj, "trashSnapshot", where),
        )
    }

    // ---------- 结构守卫（不读当前任务/课程） ----------

    /** 结构校验的统一入口：codec、捕获、操作计划都复用这一份，不各写一套。 */
    fun recordRejection(record: RepeatClosureRecord): String? {
        if (record.groupId.isBlank()) return "groupId must not be blank"
        if (record.operationId.isBlank()) return "operationId must not be blank"
        if (record.deletedAt <= 0L) return "deletedAt must be positive"
        when (val expiry = RepeatClosureRecovery.expiresAtFor(record.deletedAt)) {
            is RecoveryExpiry.Ok -> if (record.expiresAt != expiry.expiresAt) {
                return "expiresAt disagrees with deletedAt"
            }
            RecoveryExpiry.InvalidDeletedAt, RecoveryExpiry.Overflow -> return "invalid deletedAt"
        }
        if (record.template.templateId <= 0L) return "templateId must be positive"
        if (record.template.preState.id != record.template.templateId ||
            record.template.postState.id != record.template.templateId
        ) {
            return "template identity conflict"
        }
        if (record.template.postState.trashedAt != record.deletedAt) {
            return "template trashedAt disagrees with deletedAt"
        }
        RepeatClosureRecovery.structureRejection(record.asClosure())?.let {
            return "closure structure is invalid: $it"
        }
        when (record.status) {
            RecoveryGroupStatus.ACTIVE -> if (record.restoration != null) {
                return "ACTIVE group must not carry restoration results"
            }
            RecoveryGroupStatus.RESTORED -> {
                val restoration = record.restoration ?: return "RESTORED group must carry restoration results"
                if (restoration.restoredAt <= 0L) return "restoration.restoredAt must be positive"
                val memberIds = record.instances.map { it.itemId }
                val resultIds = restoration.instances.map { it.itemId }
                if (resultIds.size != resultIds.distinct().size) return "duplicate itemId in restoration results"
                if (resultIds.toSet() != memberIds.toSet()) {
                    return "restoration results must cover every member exactly once"
                }
            }
        }
        return null
    }

    /** 供调用方在计划计算前断言「这个记录能合法往返」，不靠先编解码一次来掩盖数据变化。 */
    fun assertRoundTrips(record: RepeatClosureRecord): String? {
        recordRejection(record)?.let { return it }
        return when (val decoded = decode(encode(record))) {
            is RepeatClosureDecode.Invalid -> decoded.reason
            is RepeatClosureDecode.Ok -> if (decoded.record == record) null else "round trip changed the record"
        }
    }

    // ---------- 取值原语（缺失/类型错/越界即拒绝） ----------

    private val ROOT_KEYS = setOf(
        "version", "kind", "groupId", "operationId", "deletedAt", "expiresAt",
        "status", "template", "instances", "restoration",
    )
    private val TEMPLATE_KEYS = setOf("templateId", "preState", "postState")
    private val INSTANCE_KEYS = setOf("itemId", "preState", "postState", "handlingReason")
    private val RESTORATION_KEYS = setOf("restoredAt", "instances")
    private val RESTORED_INSTANCE_KEYS = setOf("itemId", "status")
    private val CHECKLIST_KEYS = setOf("id", "title", "done")
    private val ITEM_KEYS = setOf(
        "id", "title", "detail", "kind", "done", "scheduledAt", "dayOnly", "goalId",
        "completionLevel", "completedAt", "durationMinutes", "windowStartAt", "windowEndAt",
        "rescheduleCount", "lastRescheduledAt", "recoverySourceScheduledAt", "priority",
        "captureRoute", "sourceDetail", "userNote", "nextAction", "parentCaptureId", "dueAt",
        "checklist", "planBucket", "planFocus", "repeatFrequency", "repeatStartDay",
        "repeatMinute", "repeatTemplateId", "repeatOccurrenceDay", "repeatPaused",
        "trashedAt", "trashSnapshot",
    )

    private fun objectKeys(
        value: StrictJson.Value,
        where: String,
        expected: Set<String>,
    ): StrictJson.Value.Obj {
        val obj = value as? StrictJson.Value.Obj ?: reject("$where must be an object")
        val keys = obj.entries.map { it.first }
        if (keys.size != keys.distinct().size) reject("$where has duplicate keys")
        val unknown = keys.filterNot { it in expected }
        if (unknown.isNotEmpty()) reject("$where has unknown keys: ${unknown.sorted()}")
        val missing = expected.filterNot { it in keys }
        if (missing.isNotEmpty()) reject("$where is missing keys: ${missing.sorted()}")
        return obj
    }

    private fun value(obj: StrictJson.Value.Obj, key: String, where: String): StrictJson.Value =
        obj.entries.first { it.first == key }.second

    private fun long(obj: StrictJson.Value.Obj, key: String, where: String): Long =
        (value(obj, key, where) as? StrictJson.Value.Num)?.value ?: reject("$where.$key must be an integer")

    private fun nullableLong(obj: StrictJson.Value.Obj, key: String, where: String): Long? =
        when (val element = value(obj, key, where)) {
            StrictJson.Value.Null -> null
            is StrictJson.Value.Num -> element.value
            else -> reject("$where.$key must be an integer or null")
        }

    private fun intInRange(obj: StrictJson.Value.Obj, key: String, where: String, min: Long, max: Long): Long {
        val number = long(obj, key, where)
        if (number < min || number > max) reject("$where.$key is out of range: $number")
        return number
    }

    private fun string(obj: StrictJson.Value.Obj, key: String, where: String): String =
        (value(obj, key, where) as? StrictJson.Value.Str)?.value ?: reject("$where.$key must be a string")

    private fun nonBlankString(obj: StrictJson.Value.Obj, key: String, where: String): String =
        string(obj, key, where).also { if (it.isBlank()) reject("$where.$key must not be blank") }

    private fun nullableString(obj: StrictJson.Value.Obj, key: String, where: String): String? =
        when (val element = value(obj, key, where)) {
            StrictJson.Value.Null -> null
            is StrictJson.Value.Str -> element.value
            else -> reject("$where.$key must be a string or null")
        }

    private fun bool(obj: StrictJson.Value.Obj, key: String, where: String): Boolean =
        (value(obj, key, where) as? StrictJson.Value.Bool)?.value ?: reject("$where.$key must be a boolean")

    private fun array(obj: StrictJson.Value.Obj, key: String, where: String): List<StrictJson.Value> =
        (value(obj, key, where) as? StrictJson.Value.Arr)?.items ?: reject("$where.$key must be an array")
}

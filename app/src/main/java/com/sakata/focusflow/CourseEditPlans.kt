package com.sakata.focusflow

/**
 * 阶段 6 组②：手动合并／拆分与编辑范围的**纯计划**（不接线、不写存储）。
 *
 * 授权边界（见 `docs/9.0-stage6-manual-merge-design.md`）：
 * - 只回答"会改成什么样"，不落库、不写任何存储、不排闹钟、不碰 Room schema；
 * - **不自动判断**两条记录是否同一门课——只把**用户选中的**记录拟成一份计划；
 * - `Course.id` 只允许"减少记录"，不允许重新编号。
 *
 * 三条已定稿的取舍（2026-09-29 用户确认）：
 * 1. 合并后存活 `Course.id` = 输入中**最小**者（与操作顺序无关，确定性）；
 * 2. 同一天两侧都有临时地点时，**用户正在操作的那条优先**，其余按 id 升序并入；
 * 3. 合并**不支持撤销**，但确认文案必须写明"将删除 N 条记录"。
 *
 * 实现方默认（非用户定稿，接线前需再确认，已在设计件登记）：
 * - 可编辑字段取**用户正在操作的那条**（标题为空时取 id 最小的非空标题）；
 * - 生效期取所有记录的**并集**，且**空值代表"无边界"而不是"没有值"**——
 *   任一记录该端为空 ⇒ 合并结果该端为空。这条是修掉一轮阻断级缺陷后定下的：
 *   原实现用 `mapNotNull{}.min()/max()` 丢弃 null，会把 `(null, null)` 这类默认课程
 *   往返成 `(150, 149)` 这种**倒置区间**，而 `CourseActivationPolicy.isActiveOn`
 *   要求 `from <= 当天 <= until` ⇒ 整门课从此不再生效（从课表消失），且合并不可撤销。
 * - `enabled` / `needsConfirmation` 取**任一为真**（保守）：不因合并让课程静默停用或跳过确认。
 */
internal object CourseEditPlans {

    // ---------------------------------------------------------------- 编辑范围

    /** 一次编辑的作用域。 */
    internal enum class CourseEditScope {
        /** 只改某一天的这一次课（走既有的按天临时覆盖，不新增记录）。 */
        SINGLE_OCCURRENCE,

        /** 从某天起改，之前不动（= 拆分：原记录在该天之前结束，另起一条）。 */
        FOLLOWING_SESSIONS,

        /** 改这门课本身的定义。 */
        WHOLE_COURSE
    }

    /** 某个作用域会带来什么后果——**直接给确认界面用**，不要让它自己再解释一遍。 */
    internal data class CourseEditScopeConsequence(
        val scope: CourseEditScope,
        val affectsOneOccurrenceOnly: Boolean,
        val createsNewRecord: Boolean,
        val keepsOriginalId: Boolean,
        val description: String
    )

    internal fun describeCourseEditScope(scope: CourseEditScope): CourseEditScopeConsequence = when (scope) {
        CourseEditScope.SINGLE_OCCURRENCE -> CourseEditScopeConsequence(
            scope = scope,
            affectsOneOccurrenceOnly = true,
            createsNewRecord = false,
            keepsOriginalId = true,
            description = "只影响选中的这一次课，其他课次不变；不会新增记录。"
        )

        CourseEditScope.FOLLOWING_SESSIONS -> CourseEditScopeConsequence(
            scope = scope,
            affectsOneOccurrenceOnly = false,
            createsNewRecord = true,
            keepsOriginalId = true,
            description = "从这一天起改用新值；原记录会在这一天之前结束，并新增一条记录。"
        )

        CourseEditScope.WHOLE_COURSE -> CourseEditScopeConsequence(
            scope = scope,
            affectsOneOccurrenceOnly = false,
            createsNewRecord = false,
            keepsOriginalId = true,
            description = "影响这门课的全部课次；不会新增记录。"
        )
    }

    // ---------------------------------------------------------------- 拆分

    internal enum class SplitRejectReason {
        /** 分界点不晚于生效起点：前面没有可保留的课次。 */
        BOUNDARY_NOT_AFTER_START,

        /** 分界点晚于生效终点：分界点之后本来就没有课次。 */
        BOUNDARY_AFTER_END
    }

    internal sealed interface CourseSplitPlan {

        val reason: String

        /** 原记录收在 [boundaryEpochDay] 之前，[successor] 从该天起生效。 */
        data class Applied(
            val boundaryEpochDay: Long,
            val original: Course,
            val successor: Course,
            override val reason: String
        ) : CourseSplitPlan

        data class Rejected(
            val rejectReason: SplitRejectReason,
            override val reason: String
        ) : CourseSplitPlan
    }

    /**
     * 把 [course] 在 [boundaryEpochDay] 这天切成两段：原记录 `effectiveUntil = 分界-1`，
     * 新记录 `effectiveFrom = 分界`。新记录的 id 显式传入以便测试确定（默认新生成）。
     *
     * 注意：新 id 由 `newItemId()` 随机生成，**不保证大于原 id**；因此"拆分后再合并"时
     * 存活 id 是两者中较小者，未必还是原来那个 id。这是规则 1 的直接后果，非缺陷。
     */
    internal fun planCourseSplit(
        course: Course,
        boundaryEpochDay: Long,
        successorId: Long = newItemId()
    ): CourseSplitPlan {
        val start = course.effectiveFromEpochDay
        if (start != null && boundaryEpochDay <= start) {
            return CourseSplitPlan.Rejected(
                SplitRejectReason.BOUNDARY_NOT_AFTER_START,
                "分界点（$boundaryEpochDay）不晚于生效起点（$start），这一天之前没有课次可保留。"
            )
        }
        val end = course.effectiveUntilEpochDay
        if (end != null && boundaryEpochDay > end) {
            return CourseSplitPlan.Rejected(
                SplitRejectReason.BOUNDARY_AFTER_END,
                "分界点（$boundaryEpochDay）晚于生效终点（$end），之后本来就没有课次。"
            )
        }

        val original = course.copy(effectiveUntilEpochDay = boundaryEpochDay - 1)
        val successor = course.copy(
            id = successorId,
            effectiveFromEpochDay = boundaryEpochDay,
            effectiveUntilEpochDay = end
        )
        return CourseSplitPlan.Applied(
            boundaryEpochDay = boundaryEpochDay,
            original = original,
            successor = successor,
            reason = "原记录改为截至 ${boundaryEpochDay - 1}；新记录从 $boundaryEpochDay 起生效。"
        )
    }

    // ---------------------------------------------------------------- 合并

    /**
     * 一条记录当前挂在 `course.id` 上的覆盖值（由调用方从存储读出，本件不读存储）。
     *
     * [temporaryLocations] **只应包含非空地点**：真实存储里"某天没有临时地点"用**键缺席**表示
     * （`CourseLocationOverrides` 对 blank 直接 remove，读取时也按非空过滤），不存在空串值。
     * 本件对空白值一律按"没有值"处理，不参与计数。
     */
    internal data class CourseOverrideSnapshot(
        val courseId: Long,
        /** epochDay -> 当天临时地点（非空）。 */
        val temporaryLocations: Map<Long, String> = emptyMap(),
        /** 按课次的提醒覆盖；null = 没设过。 */
        val reminderEnabled: Boolean? = null
    )

    internal enum class MergeRejectReason {
        NEEDS_TWO_RECORDS,
        DUPLICATE_IDS,

        /** 所有记录标题都为空：空标题不等于"标题一致"，交回人工。 */
        MISSING_TITLE,

        /** **全部**记录的生效期都倒置时，并集端点才会倒置（只要有一条合法，守卫就不会触发）。 */
        INVALID_EFFECTIVE_RANGE,

        /** 同一门课给了多条覆盖快照：无从判断哪条为准，交回调用方。 */
        DUPLICATE_OVERRIDES
    }

    internal sealed interface CourseMergePlan {

        val reason: String

        data class Applied(
            /** 存活下来的 id = 输入中的最小 id（定稿规则 1）。 */
            val survivingId: Long,
            val survivingCourse: Course,
            /** 将被删除的 id（升序），不含存活者。 */
            val deletedCourseIds: List<Long>,
            /** 合并后的临时地点（epochDay -> 地点）。 */
            val mergedTemporaryLocations: Map<Long, String>,
            val mergedReminderEnabled: Boolean?,
            /** 结果里来自被删除记录、且存活记录本来**没有**的那几天（真正的"带过来"）。 */
            val migratedTemporaryLocationCount: Int,
            /** 存活记录本来有值、且另有记录也有值的天数：必有值被丢弃。与上一项**互斥**。 */
            val supersededTemporaryLocationCount: Int,
            /** 存活记录自己有值、但最终被别的记录覆盖掉的天数（必须让用户看见）。 */
            val overwrittenSurvivorDayCount: Int,
            val preferredCourseId: Long,
            /** 直接给确认框用；必含"将删除 N 条记录"与"不支持撤销"。 */
            val confirmationText: String,
            val reasons: List<String>,
            override val reason: String
        ) : CourseMergePlan

        data class Rejected(
            val rejectReason: MergeRejectReason,
            override val reason: String
        ) : CourseMergePlan
    }

    /**
     * 把用户选中的多条记录拟成一条。
     *
     * @param preferredCourseId 用户正在操作的那条；缺省或不在 [courses] 里时取存活记录。
     */
    internal fun planCourseMerge(
        courses: List<Course>,
        overrides: List<CourseOverrideSnapshot> = emptyList(),
        preferredCourseId: Long? = null
    ): CourseMergePlan {
        if (courses.size < 2) {
            return CourseMergePlan.Rejected(
                MergeRejectReason.NEEDS_TWO_RECORDS,
                "至少需要两条记录才能合并（当前 ${courses.size} 条）。"
            )
        }
        val ids = courses.map { it.id }
        if (ids.toSet().size != ids.size) {
            return CourseMergePlan.Rejected(
                MergeRejectReason.DUPLICATE_IDS,
                "输入里出现了重复的课程 id，无法确定唯一的存活记录。"
            )
        }
        val overrideIds = overrides.map { it.courseId }
        if (overrideIds.toSet().size != overrideIds.size) {
            // 同 id 两条快照时"取哪条"没有合理默认；稳定排序也只是把入参顺序换个样子，仍然依赖入参。
            return CourseMergePlan.Rejected(
                MergeRejectReason.DUPLICATE_OVERRIDES,
                "同一门课给了多条覆盖快照，无法确定以哪条为准。"
            )
        }
        // 标题：只要有任意一条非空即可合并；全空交回人工（与组①预览同一口径）。
        val byId = courses.sortedBy { it.id }
        val titleSource = byId.firstOrNull { it.title.isNotBlank() }
        if (titleSource == null) {
            return CourseMergePlan.Rejected(
                MergeRejectReason.MISSING_TITLE,
                "所有待合并记录的标题都为空，无法确认它们是同一门课。"
            )
        }

        // 生效期：空值 = **无边界**。任一端为空 ⇒ 结果该端为空；两端都非空才取 min/max。
        // 生效期：空值 = **无边界**。任一端为空 ⇒ 结果该端为空；两端都非空才取 min/max。
        val mergedFrom = if (byId.any { it.effectiveFromEpochDay == null }) {
            null
        } else {
            byId.minOf { requireNotNull(it.effectiveFromEpochDay) }
        }
        val mergedUntil = if (byId.any { it.effectiveUntilEpochDay == null }) {
            null
        } else {
            byId.maxOf { requireNotNull(it.effectiveUntilEpochDay) }
        }
        if (mergedFrom != null && mergedUntil != null && mergedUntil < mergedFrom) {
            // 需要**每一条**记录都严格倒置才会走到这里（只要有一条 from<=until，min(from)<=max(until)）：
            // 宁可不合并，也不产出"任何一天都不生效"的课程。
            return CourseMergePlan.Rejected(
                MergeRejectReason.INVALID_EFFECTIVE_RANGE,
                "合并后的生效期会变成 $mergedFrom ~ $mergedUntil（终点早于起点），已停止合并。"
            )
        }

        val survivingId = byId.first().id
        val preferredId = preferredCourseId?.takeIf { id -> ids.contains(id) } ?: survivingId
        val preferred = byId.first { it.id == preferredId }

        val survivingCourse = preferred.copy(
            id = survivingId,
            title = if (preferred.title.isNotBlank()) preferred.title else titleSource.title,
            effectiveFromEpochDay = mergedFrom,
            effectiveUntilEpochDay = mergedUntil,
            // 保守：任一条为真即保留，避免合并没有任何提示地停用课程或跳过确认。
            enabled = byId.any { it.enabled },
            needsConfirmation = byId.any { it.needsConfirmation }
        )

        // 覆盖值：先把非优先记录按 id 升序并入，**最后**写优先记录 ⇒ 冲突时优先记录胜出（定稿规则 2）。
        // 注意方向：`compareBy`（false 在前、true 在后）= 优先记录排最后；写成 descending 会让它先写、随后被覆盖。
        // 同一门课给多条快照的情形已在上面直接拒绝，故这里每个 id 至多一条；`byId` 已是 id 升序。
        val snapshotsById = overrides.associateBy { it.courseId }
        val orderedSnapshots = byId.mapNotNull { snapshotsById[it.id] }
            .sortedWith(compareBy<CourseOverrideSnapshot> { it.courseId == preferredId }.thenBy { it.courseId })

        val mergedLocations = linkedMapOf<Long, String>()
        for (snapshot in orderedSnapshots) {
            for ((day, place) in snapshot.temporaryLocations.toSortedMap()) {
                // 空白地点按"没有值"处理：**既不写入、也不删除**。
                // （真实存储里清空 = 键缺席，不存在空串值；若这里改成"空白=删除"，
                // 一条违约的空白会让存活记录自己的地点被静默删掉，而三个计数与文案全都不动。）
                if (place.isNotBlank()) mergedLocations[day] = place
            }
        }

        // 计数口径（逐天判定；写清楚，避免"看起来迁移了其实是覆盖"）：
        // - 胜负：优先记录有该天则优先记录胜；否则 id 最大者胜（写入顺序决定）。
        // - migrated ⊥ superseded：以"存活记录本来有没有这一天"划分，两者**互斥**。
        // - overwrittenSurvivor 是**叠加的警示口径**，与 superseded 可以同一天同时成立
        //   （存活记录本来有该天、另有记录也有 ⇒ superseded；而胜者又不是存活记录 ⇒ overwritten）。
        // - 只有存活记录持有某天时，三个数都不动（没有值被丢弃）。
        val locationsByCourse = orderedSnapshots.associate { snapshot ->
            snapshot.courseId to snapshot.temporaryLocations.filterValues { it.isNotBlank() }
        }
        val survivingLocations = locationsByCourse[survivingId].orEmpty()
        var migrated = 0
        var superseded = 0
        var overwrittenSurvivor = 0
        for (day in mergedLocations.keys) {
            val holders = locationsByCourse.filterValues { it.containsKey(day) }.keys
            // 胜者必须按**写入顺序**判定：优先记录有该天则它胜；否则 id 最大者胜。
            // 不能简化成"存活记录有该天就它胜"——复核给过的反例正是：存活记录 10 有该天、
            // 被删除记录 20 也有、优先记录 30 没有快照 ⇒ 胜者是 20，存活记录自己的值被换掉了。
            val winnerId = if (locationsByCourse[preferredId]?.containsKey(day) == true) {
                preferredId
            } else {
                holders.maxOrNull()
            }
            val winnerIsSurvivor = winnerId == survivingId
            when {
                // 存活记录本来没有这一天 ⇒ 这一天是纯"带过来的"。
                !survivingLocations.containsKey(day) && !winnerIsSurvivor -> migrated++
                // 存活记录本来有、且另有记录也有 ⇒ 必有值被丢弃。
                survivingLocations.containsKey(day) && holders.size > 1 -> superseded++
            }
            if (survivingLocations.containsKey(day) && !winnerIsSurvivor) overwrittenSurvivor++
        }

        val mergedReminder = orderedSnapshots.lastOrNull { it.reminderEnabled != null }?.reminderEnabled

        val deletedIds = ids.filter { it != survivingId }.sorted()
        val reasons = buildList {
            add("存活记录：id $survivingId（输入中最小者）")
            add("字段取自用户正在操作的记录：id $preferredId")
            add("生效期取并集：" + (mergedFrom ?: "不限") + " ~ " + (mergedUntil ?: "不限"))
            if (migrated > 0) add("从被删除记录迁移 $migrated 天本次地点")
            if (superseded > 0) add("另有 $superseded 天与其他记录冲突，只保留了胜出的一条")
            if (overwrittenSurvivor > 0) add("其中 $overwrittenSurvivor 天覆盖了保留记录原有的本次地点")
        }

        val confirmationText = buildString {
            append("将删除 ${deletedIds.size} 条记录（保留 1 条）。")
            if (migrated > 0) append("已从被删除的记录迁移 $migrated 天本次地点。")
            if (overwrittenSurvivor > 0) {
                append("注意：有 $overwrittenSurvivor 天覆盖了保留记录原有的本次地点。")
            }
            append("合并不支持撤销。")
        }

        return CourseMergePlan.Applied(
            survivingId = survivingId,
            survivingCourse = survivingCourse,
            deletedCourseIds = deletedIds,
            mergedTemporaryLocations = mergedLocations.toMap(),
            mergedReminderEnabled = mergedReminder,
            migratedTemporaryLocationCount = migrated,
            supersededTemporaryLocationCount = superseded,
            overwrittenSurvivorDayCount = overwrittenSurvivor,
            preferredCourseId = preferredId,
            confirmationText = confirmationText,
            reasons = reasons,
            reason = "合并 ${courses.size} 条记录为 id $survivingId。"
        )
    }
}

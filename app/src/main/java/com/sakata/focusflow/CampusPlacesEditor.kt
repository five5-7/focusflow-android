package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 自填教学楼自动进入地点库的计算：返回需要新增/更新的自定义地点列表；null 表示无需变动（名空白、「地点待确认」或已存在）。 */
fun ensurePlaceForCourse(course: Course, campusPlaces: List<CampusPlace>, customPlaces: List<CampusPlace>): List<CampusPlace>? {
    val name = course.building.trim()
    if (name.isBlank() || name == "地点待确认") return null
    val known = campusPlaces.any { CourseScreenshotParser.normalize(it.name) == CourseScreenshotParser.normalize(name) }
    if (known) return null
    val zone = CourseScreenshotParser.zoneByPrefix(name)
    return customPlaces.filterNot { it.name.lowercase() == name.lowercase() } + CampusPlace(name = name, zone = zone, kind = "教学楼")
}

/**
 * 校园地点子页面（3.9 校园地图流程的本地退化版 + 可选 API 优化）。
 * 内容区滚动由外层 PlanSubpageFrame 负责（同 RoadmapSubpageContent 模式）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampusPlacesEditorContent(
    allPlaces: List<CampusPlace>,
    customPlaces: List<CampusPlace>,
    hasPackage: Boolean,
    amapKey: String,
    campusCenter: CampusCenter,
    currentCampusPlace: String?,
    hiddenPlaces: Set<String>,
    onToggleHiddenPlace: (String) -> Unit,
    onAmapKeyChange: (String) -> Unit,
    onSavePlace: (originalName: String?, place: CampusPlace) -> Unit,
    onDeletePlace: (name: String) -> Unit
) {
    val builtInNames = ZijingangTravel.places.map { it.name.lowercase() }.toSet()
    val customNames = customPlaces.map { it.name.lowercase() }.toSet()
    val existingNames = allPlaces.map { it.name.lowercase() }.toSet()
    var addOpen by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf<String?>(null) }
    var addPoi by remember { mutableStateOf<AmapPoi?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var extractCandidates by remember { mutableStateOf<List<Pair<AmapPoi, Boolean>>?>(null) }
    var extractError by remember { mutableStateOf<String?>(null) }
    var extractMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("校园地点", fontWeight = FontWeight.Bold)
                Text(
                    "内置 ${ZijingangTravel.places.size} 个 · 地点包 ${if (hasPackage) (allPlaces.count { it.name.lowercase() !in builtInNames && it.name.lowercase() !in customNames }) else 0} 个 · 自定义 ${customPlaces.size} 个",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("地点用于课程地点选择、手动当前位置与通勤起终点。地图点选需要地图 SDK，留待后续版本。", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("高德 API 优化（可选）", fontWeight = FontWeight.SemiBold)
                Text("填写高德 Web 服务 key 后，可搜索校园 POI 并一键加入地点列表。key 只保存在本机，只发往 restapi.amap.com。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = amapKey,
                    onValueChange = onAmapKeyChange,
                    label = { Text("高德 Web 服务 key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (amapKey.isNotBlank()) {
                    var keyword by remember { mutableStateOf("") }
                    var results by remember { mutableStateOf<List<AmapPoi>?>(null) }
                    var searching by remember { mutableStateOf(false) }
                    var error by remember { mutableStateOf<String?>(null) }
                    var usedFallback by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = { Text("搜索关键词，如“教学楼”") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            enabled = keyword.isNotBlank() && !searching,
                            onClick = {
                                searching = true
                                error = null
                                results = null
                                usedFallback = false
                                scope.launch {
                                    runCatching {
                                        // 校区范围优先（设置页填的中心，默认紫金港 3 公里，按距离排序）；空结果再降级全城文本搜索。
                                        val around = AmapWebApi.searchAroundPois(
                                            amapKey, keyword.trim(),
                                            campusCenter.lat, campusCenter.lng
                                        )
                                        if (around.isNotEmpty()) around else {
                                            usedFallback = true
                                            AmapWebApi.searchPois(amapKey, keyword.trim(), city = campusCenter.city)
                                        }
                                    }
                                        .onSuccess { results = it; if (it.isEmpty()) error = "没有找到地点，换个关键词试试" }
                                        .onFailure { error = it.message ?: "搜索失败" }
                                    searching = false
                                }
                            }
                        ) { Text(if (searching) "搜索中…" else "搜索") }
                    }
                    if (usedFallback) {
                        Text("校园 3 公里内没有匹配结果，已按全城搜索。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (error != null) Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    results?.let { pois ->
                        if (pois.isEmpty()) Text("没有找到地点", style = MaterialTheme.typography.bodySmall)
                        else pois.take(10).forEach { poi ->
                            val exists = poi.name.lowercase() in existingNames
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(poi.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        buildString {
                                            if (poi.distance >= 0) append("距校园中心约 ${poi.distance} 米")
                                            else append("全城范围")
                                            if (poi.address.isNotBlank()) append(" · ${poi.address}")
                                        },
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (exists) Text("已存在", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else OutlinedButton(onClick = { addPoi = poi }) { Text("加入") }
                            }
                        }
                    }
                } else {
                    Text("未填 key 时可用内置目录、地点包与手动自定义；POI 搜索与逆地理编码需要 key。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (amapKey.isNotBlank()) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("从地图批量提取（初次配置）", fontWeight = FontWeight.SemiBold)
                            Text("按教学楼／图书馆／体育／食堂／宿舍／实验等关键词在校园范围内提取候选地点，勾选保留后一键加入地点库；已存在的地点自动跳过。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(enabled = !extracting, onClick = {
                            extracting = true
                            extractError = null
                            extractMessage = null
                            extractCandidates = null
                            scope.launch {
                                val keywords = listOf("教学楼", "图书馆", "体育馆", "操场", "食堂", "宿舍", "实验室", "运动场")
                                val merged = mutableListOf<AmapPoi>()
                                runCatching {
                                    keywords.forEach { kw -> merged += AmapWebApi.searchAroundPois(amapKey, kw, campusCenter.lat, campusCenter.lng) }
                                }.onFailure { extractError = it.message ?: "提取失败" }
                                val seen = mutableSetOf<String>()
                                val deduped = merged.filter { seen.add(it.name.lowercase()) }
                                extractCandidates = deduped.filter { it.name.lowercase() !in existingNames }.take(40).map { it to true }
                                extracting = false
                            }
                        }) { Text(if (extracting) "提取中…" else "提取") }
                    }
                    extractError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    extractMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    extractCandidates?.let { candidates ->
                        if (candidates.isEmpty()) {
                            Text("校园范围内没有找到新的候选地点（可能已全部存在或关键词无结果）。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("候选 ${candidates.size} 个（勾选保留，默认全保留）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { extractCandidates = candidates.map { it.first to true } }) { Text("全部保留") }
                                TextButton(onClick = { extractCandidates = candidates.map { it.first to false } }) { Text("全部忽略") }
                            }
                            candidates.forEach { (poi, keep) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Checkbox(checked = keep, onCheckedChange = { checked ->
                                        extractCandidates = candidates.map { if (it.first.name == poi.name) it.first to checked else it }
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text(poi.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            buildString {
                                                if (poi.distance >= 0) append("距校园中心约 ${poi.distance} 米")
                                                else append("校园范围")
                                                if (poi.address.isNotBlank()) append(" · ${poi.address}")
                                                append(" · ${AmapWebApi.suggestKind(poi.type)}")
                                            },
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            val kept = candidates.filter { it.second }
                            Button(
                                enabled = kept.isNotEmpty(),
                                onClick = {
                                    val seenNames = mutableSetOf<String>()
                                    kept.forEach { (poi, _) ->
                                        val name = poi.name.trim()
                                        if (name.isNotBlank() && seenNames.add(name.lowercase()) && name.lowercase() !in existingNames) {
                                            onSavePlace(null, CampusPlace(name, CourseScreenshotParser.zoneByPrefix(name), AmapWebApi.suggestKind(poi.type), poi.lat, poi.lng))
                                        }
                                    }
                                    extractMessage = "已加入 ${seenNames.size} 个地点；可在上方列表继续编辑分区/用途。"
                                    extractCandidates = null
                                }
                            ) { Text("添加保留项（${kept.size}）") }
                        }
                    }
                }
            }
        }

        Text("地点列表（按用途分组）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val grouped = allPlaces.groupBy { it.kind }
        grouped.forEach { (kind, places) ->
            Text(kind, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            places.forEach { place ->
                val isCustom = place.name.lowercase() in customNames
                val source = when {
                    place.name.lowercase() in builtInNames -> "内置"
                    isCustom -> "自定义"
                    else -> "地点包"
                }
                Card {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                buildString {
                                    append("$source · ${place.zone.label}")
                                    if (place.lat != null && place.lng != null) append(" · 有坐标")
                                    if (place.name == currentCampusPlace) append(" · 当前位置")
                                },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isCustom) {
                            TextButton(onClick = { editingName = place.name }) { Text("编辑") }
                            TextButton(onClick = { onDeletePlace(place.name) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        } else {
                            TextButton(onClick = { onToggleHiddenPlace(place.name) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        if (allPlaces.isEmpty()) Text("还没有地点。", style = MaterialTheme.typography.bodySmall)

        val hiddenBuiltIn = ZijingangTravel.places.filter { it.name.lowercase() in hiddenPlaces }
        if (hiddenBuiltIn.isNotEmpty()) {
            Text("已隐藏的默认地点（可恢复）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            hiddenBuiltIn.forEach { place ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("默认 · ${place.zone.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onToggleHiddenPlace(place.name) }) { Text("恢复") }
                    }
                }
            }
        }

        Button(onClick = { addOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 自定义地点") }
    }

    if (addOpen || editingName != null) {
        val existing = editingName?.let { name -> customPlaces.firstOrNull { it.name == name } }
        CustomPlaceEditorDialog(
            existing = existing,
            allNames = existingNames - (existing?.name?.lowercase() ?: ""),
            amapKey = amapKey,
            onDismiss = { addOpen = false; editingName = null },
            onSave = { place ->
                onSavePlace(existing?.name, place)
                addOpen = false
                editingName = null
            }
        )
    }

    addPoi?.let { poi -> AddPoiDialog(poi = poi, onDismiss = { addPoi = null }, onAdd = { addPoi = null; onSavePlace(null, it) }) }
}

/** 自定义地点新增/编辑对话框：名称、分区、用途、可选经纬度（同填或同空）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPlaceEditorDialog(
    existing: CampusPlace?,
    allNames: Set<String>,
    amapKey: String,
    onDismiss: () -> Unit,
    onSave: (CampusPlace) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var zone by remember { mutableStateOf(existing?.zone ?: CampusZone.WEST_TEACHING) }
    var kind by remember { mutableStateOf(existing?.kind ?: "地点") }
    var latText by remember { mutableStateOf(existing?.lat?.toString() ?: "") }
    var lngText by remember { mutableStateOf(existing?.lng?.toString() ?: "") }
    var message by remember { mutableStateOf<String?>(null) }
    var geocoding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val kindOptions = listOf("教学楼", "实验", "学习", "运动", "地点")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增自定义地点" else "编辑地点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; message = null }, label = { Text("地点名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("分区", style = MaterialTheme.typography.labelMedium)
                FlowRow {
                    CampusZone.entries.forEach { option ->
                        FilterChip(
                            selected = zone == option,
                            onClick = { zone = option },
                            label = { Text(option.label) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Text("用途", style = MaterialTheme.typography.labelMedium)
                FlowRow {
                    kindOptions.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text("纬度（可选）") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = lngText, onValueChange = { lngText = it }, label = { Text("经度（可选）") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (amapKey.isNotBlank()) {
                    TextButton(
                        enabled = !geocoding && latText.isNotBlank() && lngText.isNotBlank(),
                        onClick = {
                            val lat = latText.toDoubleOrNull()
                            val lng = lngText.toDoubleOrNull()
                            if (lat == null || lng == null) { message = "经纬度需要是数字"; return@TextButton }
                            geocoding = true
                            scope.launch {
                                val suggested = AmapWebApi.reverseGeocode(amapKey, lat, lng)
                                if (suggested != null && name.isBlank()) name = suggested
                                else if (suggested != null) message = "该坐标位于：$suggested"
                                geocoding = false
                            }
                        }
                    ) { Text(if (geocoding) "查询中…" else "根据经纬度建议名称") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val trimmed = name.trim()
                if (trimmed.isBlank()) { message = "请填写地点名称"; return@Button }
                if (trimmed.lowercase() in allNames) { message = "已有同名地点"; return@Button }
                val lat = latText.toDoubleOrNull()
                val lng = lngText.toDoubleOrNull()
                if ((lat == null) != (lng == null)) { message = "经纬度需要一起填写或都留空"; return@Button }
                if (lat != null && (lat !in -90.0..90.0 || lng!! !in -180.0..180.0)) { message = "经纬度超出范围"; return@Button }
                onSave(CampusPlace(trimmed, zone, kind, lat, lng))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** POI 搜索结果的加入确认：分区与用途按推断预选，可改。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPoiDialog(poi: AmapPoi, onDismiss: () -> Unit, onAdd: (CampusPlace) -> Unit) {
    var zone by remember { mutableStateOf(CampusZone.WEST_TEACHING) }
    var kind by remember { mutableStateOf(AmapWebApi.suggestKind(poi.type)) }
    val kindOptions = listOf("教学楼", "实验", "学习", "运动", "地点")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入地点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(poi.name, fontWeight = FontWeight.Bold)
                Text("${poi.address} · ${poi.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("分区", style = MaterialTheme.typography.labelMedium)
                FlowRow {
                    CampusZone.entries.forEach { option ->
                        FilterChip(selected = zone == option, onClick = { zone = option }, label = { Text(option.label) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
                Text("用途（已按 POI 类型推断）", style = MaterialTheme.typography.labelMedium)
                FlowRow {
                    kindOptions.forEach { option ->
                        FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(option) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onAdd(CampusPlace(poi.name, zone, kind, poi.lat, poi.lng)) }) { Text("加入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

package com.sakata.focusflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * 课表识别的解析与工具。
 *
 * 4.0.1 起不再内置本地 OCR 引擎（ML Kit / Tesseract 效果差，已移除）：
 * 课表导入只走硅基流动视觉模型（CourseVisionRecognizer）。本文件保留
 * 视觉模型客户端复用的工具：图片解码、地点归一（校区前缀剥离、教室号/楼座
 * 归并到楼级、中文数字转阿拉伯）、楼名分区猜测。
 */
object CourseScreenshotParser {
    /** 解码图片（先降采样到最长边 ≤ 2048px，避免大图让请求过大）并按 EXIF 方向旋转。视觉模型识别复用。 */
    internal fun decodeRotated(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 2048)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法解码图片")
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0) ?: 0
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    internal fun calculateSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var size = 1
        while (maxOf(width, height) / (size * 2) >= maxLongEdge) size *= 2
        return size
    }

    internal fun normalize(value: String): String = value.replace(Regex("[\\s　]"), "").replace("１", "1").replace("２", "2")

    internal fun detectBuilding(text: String, places: List<CampusPlace>): Pair<String, CampusZone> {
        val compact = normalize(stripCampusPrefix(text))
        places.firstOrNull { compact.contains(normalize(it.name)) }?.let { return it.name to it.zone }
        val match = Regex("(?:西|东|北)[一二三四五六七八九十0-9]+(?:教学)?楼|化学实验中心").find(compact)?.value
        if (match != null) {
            val normalized = toArabicDigits(match)
            val zone = when {
                normalized.startsWith("东") -> CampusZone.EAST_TEACHING
                normalized.startsWith("北") -> CampusZone.NORTH_TEACHING
                normalized.contains("化学") -> CampusZone.CHEMISTRY_LABS
                else -> CampusZone.WEST_TEACHING
            }
            return normalized to zone
        }
        // 教室号/楼座归到楼级：如“东1A-302”→“东1教学楼”，找教室靠通勤缓冲时间。
        buildingFromRoom(compact)?.let { return it to zoneByPrefix(it) }
        return "地点待确认" to CampusZone.WEST_TEACHING
    }

    /**
     * 剥离教室号/楼座回退到楼级：如“东1A-302”“东1B-201”“东一B”→“东1教学楼”，
     * 无房间号的楼座同样归并到同一栋楼；容忍模型输出末尾省略号（“东1B-2...”）。不匹配返回 null。
     */
    internal fun buildingFromRoom(room: String): String? {
        val text = room.trim().trimEnd('.', '…')
        val withRoom = Regex("^([东西南北中][0-9一二三四五六七八九十]+)[A-Za-z]?[-—－~～]?[0-9]+$").matchEntire(text)
        if (withRoom != null) return toArabicDigits(withRoom.groupValues[1]) + "教学楼"
        val blockOnly = Regex("^([东西南北中][0-9一二三四五六七八九十]+)[A-Za-z]?(栋)?$").matchEntire(text)
        if (blockOnly != null) return toArabicDigits(blockOnly.groupValues[1]) + "教学楼"
        return null
    }

    /** 楼名里的中文数字转阿拉伯数字（“东一”→“东1”），与地点目录/地图命名保持一致。 */
    internal fun toArabicDigits(value: String): String {
        val digits = mapOf('一' to '1', '二' to '2', '三' to '3', '四' to '4', '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9')
        return value.map { digits[it] ?: it }.joinToString("")
    }

    /** 按名称前缀猜测分区：东→东区教学楼、北→北区教学楼、含化学→化学实验中心、其余→西区。 */
    internal fun zoneByPrefix(name: String): CampusZone = when {
        name.startsWith("东") -> CampusZone.EAST_TEACHING
        name.startsWith("北") -> CampusZone.NORTH_TEACHING
        name.contains("化学") -> CampusZone.CHEMISTRY_LABS
        else -> CampusZone.WEST_TEACHING
    }

    /** 去掉校区前缀（如“紫金港东1A-213”→“东1A-213”），便于楼级归并与地点匹配；通用“××校区”前缀同样处理。 */
    internal fun stripCampusPrefix(text: String): String {
        var value = text.trim()
        for (prefix in listOf("浙江大学紫金港校区", "浙大紫金港校区", "紫金港校区", "浙大紫金港", "紫金港")) {
            if (value.startsWith(prefix)) {
                value = value.removePrefix(prefix).trim()
                break
            }
        }
        // 通用规则：以“××校区”开头时整体剥掉校区名（适配其他校园的地点包命名）。
        if (value.contains("校区")) {
            val direction = value.indexOfAny(charArrayOf('东', '西', '南', '北', '中'))
            if (direction > 0) value = value.substring(direction)
        }
        return value
    }
}

package com.sakata.focusflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * 8.2.0 自导入背景图的**存取**（见 docs/8.2.0-appearance-plan.md）。
 *
 * 三条硬约束：
 * - 图片只存在应用私有目录 `filesDir/appearance/`，不写外部存储、不上传；
 * - 一律**降采样**解码（[load] 按目标尺寸算 `inSampleSize`），绝不整图解码——1200 万像素原图直接解码会爆内存；
 * - 解码失败一律返回 null，调用方退回主题底色，不抛错、不清用户文件。
 */
internal object AppearanceImages {

    private const val MAX_STORED_BYTES = 12L * 1024 * 1024

    fun directory(context: Context): File = File(context.filesDir, "appearance").apply { if (!exists()) mkdirs() }

    /** 把偏好里存的相对名解析成真实文件；只取文件名本身，挡住任何路径成分。 */
    fun fileFor(context: Context, name: String): File? {
        if (name.isBlank()) return null
        val safe = File(name).name
        if (safe.isBlank() || safe.startsWith(".")) return null
        return File(directory(context), safe)
    }

    fun exists(context: Context, name: String): Boolean = fileFor(context, name)?.isFile == true

    /** 生成一个不会覆盖既有文件的存储名（扩展名保留，便于排查）。 */
    fun newName(extension: String?): String {
        val ext = extension.orEmpty().lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "img"
        return "bg_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext"
    }

    /**
     * 把一张已打开的图片流写进私有目录。[maxWidth]x[maxHeight] 决定降采样目标，
     * 写入的是**已降采样**的结果，不是原图——省内存也省磁盘。
     */
    fun store(context: Context, name: String, input: InputStream, maxWidth: Int, maxHeight: Int): Boolean {
        val target = fileFor(context, name) ?: return false
        return runCatching {
            val bytes = input.use { it.readBytes() }
            if (bytes.size > MAX_STORED_BYTES) return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            target.outputStream().use { out ->
                decoded.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            decoded.recycle()
            true
        }.getOrDefault(false)
    }

    /** 解码为不超过目标尺寸的位图；失败返回 null。 */
    fun load(context: Context, name: String, maxWidth: Int, maxHeight: Int): ImageBitmap? {
        val file = fileFor(context, name) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
        }.getOrNull()
    }

    fun delete(context: Context, name: String) {
        runCatching { fileFor(context, name)?.takeIf { it.isFile }?.delete() }
    }

    /** 纯函数：够了就不再降采样（返回 1），否则每次折半。 */
    fun sampleSizeFor(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxWidth && h / 2 >= maxHeight) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return max(1, sample)
    }
}

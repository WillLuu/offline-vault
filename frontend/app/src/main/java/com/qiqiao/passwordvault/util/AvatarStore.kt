package com.qiqiao.passwordvault.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/**
 * 头像存取（纯离线）：图片按 EXIF 方向摆正 → 中心裁剪方形 → 缩放至 256×256 存 app 私有目录
 * （files/avatar.jpg），无需存储权限，卸载即清除。
 *
 * 2026-09-06 修复（审查 P2 ×2）：
 *  - 原实现注释承诺「EXIF 方向修正」但代码未实现——竖拍照片保存后会横躺 90°；
 *  - 原实现整张大图直接 decodeStream 不设 inSampleSize——相册高像素照片（数千万像素）
 *    整张解码进内存，选图易 OOM。改为先读 bounds 再按目标尺寸采样解码。
 */
object AvatarStore {

    private const val FILE_NAME = "avatar.jpg"
    private const val TARGET = 256

    fun avatarFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = avatarFile(context).exists()

    /** 从相册 Uri 读图 → 采样解码（防 OOM）→ EXIF 方向修正 → 中心裁剪方形 → 缩放到 256 → 存 JPG。成功返回 true。 */
    fun saveFromUri(context: Context, uri: android.net.Uri): Boolean {
        return runCatching {
            val cr = context.contentResolver

            // ① 只读尺寸，不解码像素
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            // ② 按 TARGET 采样：保证解码后短边仍 >= TARGET（中心裁剪需要），避免整张大图进内存。
            //    inSampleSize 取 2 的幂（解码器按此取整），如 4000px 短边 → sample=8 → 解码约 500px。
            var sample = 1
            val shortSide = minOf(bounds.outWidth, bounds.outHeight)
            while (shortSide / (sample * 2) >= TARGET) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false

            // ③ EXIF 方向修正（竖拍/翻转照片摆正后再裁剪，否则裁剪轴与视觉轴不一致）
            val oriented = applyExifOrientation(cr, uri, decoded)

            val square = centerCrop(oriented, TARGET)
            avatarFile(context).outputStream().use { out ->
                square.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            // 回收中间位图（createBitmap 在无需变换时可能返回原对象，判等防误回收）
            if (square !== oriented) oriented.recycle()
            if (oriented !== decoded) decoded.recycle()
            true
        }.getOrDefault(false)
    }

    /** 按 EXIF orientation 旋转/翻转；无需修正或读取失败时原样返回（不抛，走 runCatching 兜底）。 */
    private fun applyExifOrientation(cr: ContentResolver, uri: android.net.Uri, src: Bitmap): Bitmap {
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun load(context: Context): Bitmap? =
        BitmapFactory.decodeFile(avatarFile(context).absolutePath)

    fun clear(context: Context) {
        avatarFile(context).delete()
    }

    /** 中心裁剪为正方形并缩放到 target。 */
    private fun centerCrop(src: Bitmap, target: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, left, top, side, side)
        val scale = target.toFloat() / side
        return if (scale >= 1f) {
            square
        } else {
            val m = Matrix().apply { setScale(scale, scale) }
            val scaled = Bitmap.createBitmap(square, 0, 0, square.width, square.height, m, true)
            if (scaled !== square) square.recycle()
            scaled
        }
    }
}

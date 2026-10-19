package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 定制版（自建签名）的应用内更新。
 *
 * 数据源是本仓库根目录的 updates.json，不依赖 GitHub API（避免匿名限流），
 * 并按「raw → jsDelivr → fastly」多镜像依次尝试，任一成功即用；raw 排第一保证实时性。
 *
 * updates.json 字段：
 *   versionCode            必填，整数，比较依据
 *   versionName            展示用版本名
 *   url                    必填，APK 直链（releases/latest/download/<ASCII 名>.apk）
 *   backupUrl/mirrorUrl/alternateMirrorUrl  可选备用/镜像链接
 *   size/sha256/notes/pubDate               可选
 */
@Keep
@Suppress("unused")
object AppUpdateCustom : AppUpdate.AppUpdateInterface {

    private const val REPO = "byl669/legado-lyrics-overlay"
    private const val BRANCH = "master"

    private val manifestUrls = arrayOf(
        "https://raw.githubusercontent.com/$REPO/$BRANCH/updates.json",
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/updates.json",
        "https://fastly.jsdelivr.net/gh/$REPO@$BRANCH/updates.json",
    )

    @Keep
    data class Manifest(
        val versionCode: Int = 0,
        val versionName: String = "",
        val url: String = "",
        val backupUrl: String? = null,
        val mirrorUrl: String? = null,
        val alternateMirrorUrl: String? = null,
        val size: Long = 0L,
        val sha256: String? = null,
        val notes: String = "",
        val pubDate: String? = null,
    )

    private const val TAG = "AppUpdateCustom"

    private suspend fun fetchManifest(): Manifest {
        var lastError = "无可用镜像"
        for (mirror in manifestUrls) {
            try {
                val res = okHttpClient.newCallResponse {
                    url(mirror)
                }
                if (!res.isSuccessful) {
                    lastError = "更新服务器返回 ${res.code}"
                    AppLog.put("$TAG 镜像失败 $mirror -> HTTP ${res.code}")
                    res.close()
                    continue
                }
                val body = res.body.text()
                res.close()
                if (body.isBlank()) {
                    lastError = "更新清单为空"
                    AppLog.put("$TAG 镜像空响应 $mirror")
                    continue
                }
                val manifest = runCatching {
                    GSON.fromJson(body, Manifest::class.java)
                }.getOrNull()
                if (manifest != null && manifest.versionCode > 0 && manifest.url.isNotBlank()) {
                    AppLog.put("$TAG 命中镜像 $mirror -> vc=${manifest.versionCode} vn=${manifest.versionName}")
                    return manifest
                }
                lastError = "更新清单格式错误"
                AppLog.put("$TAG 清单格式错误 $mirror")
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: e.javaClass.simpleName
                AppLog.put("$TAG 镜像异常 $mirror -> $lastError")
            }
        }
        // 明确失败，绝不静默误报「已是最新」
        throw NoStackTraceException("检查更新失败：$lastError")
    }

    private fun parseTime(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    override fun check(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val manifest = fetchManifest()
            if (manifest.versionCode <= AppConst.appInfo.versionCode) {
                AppLog.put("$TAG 已是最新：远端 ${manifest.versionCode} <= 本地 ${AppConst.appInfo.versionCode}")
                throw NoStackTraceException("已是最新版本")
            }
            AppLog.put("$TAG 发现新版本 ${manifest.versionName}（${manifest.versionCode} > ${AppConst.appInfo.versionCode}）")
            AppUpdate.UpdateInfo(
                tagName = manifest.versionName.ifBlank { "v${manifest.versionCode}" },
                updateLog = manifest.notes.ifBlank { "更新内容见发布页" },
                downloadUrl = manifest.url,
                fileName = manifest.url.substringAfterLast('/').ifBlank { "legado-custom.apk" },
                backupDownloadUrl = manifest.backupUrl,
                mirrorDownloadUrl = manifest.mirrorUrl,
                alternateMirrorDownloadUrl = manifest.alternateMirrorUrl,
                size = manifest.size,
                createdAt = parseTime(manifest.pubDate),
            )
        }.timeout(20000)
    }
}

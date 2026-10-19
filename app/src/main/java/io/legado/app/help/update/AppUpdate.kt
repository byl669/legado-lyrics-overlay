package io.legado.app.help.update

import io.legado.app.constant.AppConst
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    /**
     * 自建签名打包（applicationId 带 .custom 后缀）时 appVariant 为 UNKNOWN。
     * 这类包不能去查上游 Release —— 包名与签名都不同，装上去等于换了个 App。
     */
    val isCustomBuild: Boolean
        get() = AppConst.appInfo.appVariant == AppVariant.UNKNOWN

    val gitHubUpdate: AppUpdateInterface by lazy {
        if (isCustomBuild) AppUpdateCustom else AppUpdateGitHub
    }

    fun checkBeta(scope: CoroutineScope): Coroutine<UpdateInfo> =
        if (isCustomBuild) {
            // 定制版没有独立测试通道，beta 入口直接查正式更新
            AppUpdateCustom.check(scope)
        } else {
            AppUpdateGitHub.checkBeta(scope)
        }

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val backupDownloadUrl: String? = null,
        val mirrorDownloadUrl: String? = null,
        val alternateMirrorDownloadUrl: String? = null,
        val size: Long = 0L,
        val createdAt: Long = 0L,
        val isBeta: Boolean = false
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

}

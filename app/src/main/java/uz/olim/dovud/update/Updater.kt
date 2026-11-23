package uz.olim.dovud.update

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uz.olim.dovud.BuildConfig
import uz.olim.dovud.Net
import uz.olim.dovud.settings.SecureStore
import java.io.File

/**
 * O'zini o'zi yaxshilash (2-yo'l):
 *  1) requestImprovement — GitHub'ga "nima kerak" yozuvini qoldiradi; GitHub'dagi AI kodni o'zgartirib yangi APK yig'adi.
 *  2) checkLatest/installLatest — yangi versiyani topadi, yuklaydi, o'rnatish oynasini ochadi (tugmani Olim aka bosadi).
 *  3) requestRollback — oldingi versiyaga qaytarish.
 */
object Updater {
    data class Release(val versionCode: Int, val tag: String, val apkUrl: String, val notes: String)

    fun repo(): String = SecureStore.get(SecureStore.GITHUB_REPO).ifBlank { BuildConfig.GITHUB_REPO }

    suspend fun checkLatest(): Release? = withContext(Dispatchers.IO) {
        val repo = repo(); if (repo.isBlank()) return@withContext null
        val req = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        runCatching {
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val j = JSONObject(r.body!!.string())
                val tag = j.getString("tag_name")
                val code = tag.removePrefix("v").toIntOrNull() ?: return@use null
                val assets = j.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                    ?: return@use null
                Release(code, tag, apk.getString("browser_download_url"), j.optString("body").take(500))
            }
        }.getOrNull()
    }

    suspend fun newer(): Release? = checkLatest()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    suspend fun installLatest(ctx: Context): String = withContext(Dispatchers.IO) {
        val rel = newer() ?: return@withContext "Yangi versiya yo'q. Hozirgi versiya: ${BuildConfig.VERSION_NAME}"
        if (!ctx.packageManager.canRequestPackageInstalls()) return@withContext "Avval 'Noma'lum ilovalarni o'rnatish' ruxsatini yoqing (Ruxsatlar oynasida)."
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "dovud.apk")
        Net.http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext "Yuklab bo'lmadi: ${r.code}"
            f.outputStream().use { r.body!!.byteStream().copyTo(it) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { (uz.olim.dovud.control.DovudAccessibilityService.instance ?: ctx).startActivity(i) }
        "Yangi versiya (${rel.tag}) yuklandi. Ekranda 'O'rnatish' tugmasini bosing."
    }

    private suspend fun createIssue(title: String, body: String, label: String): String = withContext(Dispatchers.IO) {
        val token = SecureStore.get(SecureStore.GITHUB_TOKEN)
        val repo = repo()
        if (token.isBlank() || repo.isBlank()) return@withContext "GitHub tokeni yoki repo nomi sozlanmagan."
        val j = JSONObject().put("title", title).put("body", body).put("labels", JSONArray().put(label))
        val req = Request.Builder().url("https://api.github.com/repos/$repo/issues")
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .post(j.toString().toRequestBody("application/json".toMediaType())).build()
        Net.http.newCall(req).execute().use { r ->
            if (r.isSuccessful) "So'rov yuborildi. Yangi versiya taxminan 10–20 daqiqada tayyor bo'ladi, tayyor bo'lganda aytaman."
            else "GitHub so'rovni qabul qilmadi: ${r.code}"
        }
    }

    suspend fun requestImprovement(what: String) =
        createIssue("Dovud: ${what.take(70)}", "Olim aka tasdiqlagan yaxshilanish so'rovi:\n\n$what\n\nJoriy versiya: ${BuildConfig.VERSION_NAME}", "dovud-improve")

    suspend fun requestRollback() =
        createIssue("Dovud: oldingi versiyaga qaytish", "Olim aka oxirgi o'zgarishni bekor qilishni so'radi. Joriy versiya: ${BuildConfig.VERSION_NAME}", "dovud-rollback")
}

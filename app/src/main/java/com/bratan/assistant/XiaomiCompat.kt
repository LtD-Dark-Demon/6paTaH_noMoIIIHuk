package com.bratan.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object XiaomiCompat {

    val isXiaomi: Boolean =
        Build.MANUFACTURER.equals("xiaomi", true) ||
        Build.BRAND.equals("redmi", true) ||
        Build.BRAND.equals("poco", true)

    /** Экран «Автозапуск» (securitycenter), fallback — сведения о приложении. */
    fun autostart(ctx: Context): Intent = firstResolvable(
        ctx,
        Intent().setComponent(ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
    ) ?: appDetails(ctx)

    /** Экран «Контроль активности» (PowerKeeper) для нашего пакета. */
    fun powerKeeper(ctx: Context): Intent = firstResolvable(
        ctx,
        Intent().setComponent(ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", ctx.packageName)
            .putExtra("package_label", ctx.getString(R.string.app_name))
    ) ?: appDetails(ctx)

    fun appDetails(ctx: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    private fun firstResolvable(ctx: Context, vararg intents: Intent): Intent? =
        intents.firstOrNull { ctx.packageManager.resolveActivity(it, 0) != null }
}

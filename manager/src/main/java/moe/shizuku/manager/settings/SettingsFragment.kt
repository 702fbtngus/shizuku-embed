package moe.shizuku.manager.settings

import android.content.ComponentName
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.recovery.RecoveryPolicy
import moe.shizuku.manager.recovery.RecoverySettings
import moe.shizuku.manager.recovery.RecoveryService
import moe.shizuku.manager.starter.StarterActivity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.*
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.recovery.BackendRecovery
import moe.shizuku.manager.recovery.ExperimentalWirelessSession
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.manager.ShizukuLocales
import rikka.widget.borderview.BorderRecyclerView
import java.util.*
import moe.shizuku.manager.ShizukuSettings.LANGUAGE as KEY_LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE as KEY_NIGHT_MODE

class SettingsFragment : PreferenceFragmentCompat() {

    private val hotspotPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            RecoverySettings.prefs.edit().putBoolean(RecoverySettings.EXPERIMENT_ENABLED, true).apply()
            RecoverySettings.resetExperiment()
            findPreference<TwoStatePreference>(RecoverySettings.EXPERIMENT_ENABLED)?.isChecked = true
            RecoveryService.refresh(requireContext())
        } else {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.recovery_experiment_permission_denied)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RecoverySettings.STATUS) updateRecoveryStatus()
    }

    private lateinit var languagePreference: ListPreference
    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var startupPreference: PreferenceCategory
    private lateinit var translationPreference: Preference
    private lateinit var translationContributorsPreference: Preference
    private lateinit var useSystemColorPreference: TwoStatePreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        languagePreference = findPreference(KEY_LANGUAGE)!!
        nightModePreference = findPreference(KEY_NIGHT_MODE)!!
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME)!!
        startOnBootPreference = findPreference(KEEP_START_ON_BOOT)!!
        startupPreference = findPreference("startup")!!
        translationPreference = findPreference("translation")!!
        translationContributorsPreference = findPreference("translation_contributors")!!
        useSystemColorPreference = findPreference(KEY_USE_SYSTEM_COLOR)!!

        val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)

        startOnBootPreference.isChecked = context.packageManager.isComponentEnabled(componentName)
        startOnBootPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is Boolean) {
                    context.packageManager.setComponentEnabled(componentName, newValue)
                    val accepted = context.packageManager.isComponentEnabled(componentName) == newValue
                    if (accepted) {
                        RecoverySettings.prefs.edit().putBoolean(KEEP_START_ON_BOOT, newValue).apply()
                        if (!newValue) RecoverySettings.prefs.edit().putBoolean(RecoverySettings.BOOT_PENDING, false).apply()
                        RecoveryService.refresh(context)
                    }
                    accepted
                } else false
            }
        languagePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is String) {
                    val locale: Locale = if ("SYSTEM" == newValue) {
                        LocaleDelegate.systemLocale
                    } else {
                        Locale.forLanguageTag(newValue)
                    }
                    LocaleDelegate.defaultLocale = locale
                    activity?.recreate()
                }
                true
            }

        setupRecoveryPreferences()
        setupLocalePreference()

        nightModePreference.value = ShizukuSettings.getNightMode()
        nightModePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        activity?.recreate()
                    }
                }
                true
            }
        if (ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
            blackNightThemePreference.isChecked = ThemeHelper.isBlackNightTheme(context)
            blackNightThemePreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, _: Any? ->
                    if (ResourceUtils.isNightMode(context.resources.configuration)) {
                        activity?.recreate()
                    }
                    true
                }
        } else {
            blackNightThemePreference.isVisible = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            useSystemColorPreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                    if (value is Boolean) {
                        if (ThemeHelper.isUsingSystemColor() != value) {
                            activity?.recreate()
                        }
                    }
                    true
                }
        } else {
            useSystemColorPreference.isVisible = false
        }

        translationPreference.summary =
            context.getString(R.string.settings_translation_summary, context.getString(R.string.app_name))
        translationPreference.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
            true
        }

        val contributors = context.getString(R.string.translation_contributors).toHtml().toString()
        if (contributors.isNotBlank()) {
            translationContributorsPreference.summary = contributors
        } else {
            translationContributorsPreference.isVisible = false
        }
    }

    private fun setupRecoveryPreferences() {
        val context = requireContext()
        val experiment = findPreference<TwoStatePreference>(RecoverySettings.EXPERIMENT_ENABLED)!!
        experiment.isVisible = Build.VERSION.SDK_INT >= 30
        experiment.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            if (value == true) {
                confirmExperiment()
                false
            } else {
                RecoverySettings.prefs.edit().putBoolean(RecoverySettings.EXPERIMENT_ENABLED, false).apply()
                BackendRecovery.disableExperiment()
                true
            }
        }
        findPreference<Preference>(RecoverySettings.EXPERIMENT_LOG)!!.setOnPreferenceClickListener {
            showReport(R.string.recovery_experiment_log_title,
                RecoverySettings.experimentReport(getString(R.string.recovery_experiment_log_empty)))
            true
        }
        val port = findPreference<EditTextPreference>(RecoverySettings.TCP_PORT)!!
        port.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
        port.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            val valid = (value as? String)?.toIntOrNull()?.let(RecoveryPolicy::validPort) == true
            if (!valid) MaterialAlertDialogBuilder(context)
                .setMessage(R.string.recovery_port_invalid).setPositiveButton(android.R.string.ok, null).show()
            valid
        }
        findPreference<TwoStatePreference>(RecoverySettings.WATCHDOG)!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, value ->
                RecoverySettings.prefs.edit().putBoolean(RecoverySettings.WATCHDOG, value as Boolean).apply()
                RecoveryService.refresh(context)
                true
            }
        findPreference<Preference>("recovery_start")!!.setOnPreferenceClickListener {
            startActivity(Intent(context, StarterActivity::class.java)
                .putExtra(StarterActivity.EXTRA_RECOVERY, true))
            true
        }
        findPreference<Preference>(RecoverySettings.STATUS)!!.setOnPreferenceClickListener {
            updateRecoveryStatus()
            showReport(R.string.recovery_status_title,
                RecoverySettings.statusReport(getString(R.string.recovery_waiting)))
            true
        }
    }

    private fun showReport(title: Int, report: String) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context).setTitle(title)
            .setMessage(report).setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(android.R.string.copy) { _, _ ->
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(getString(title), report))
            }.show()
    }

    private fun confirmExperiment() {
        val context = requireContext()
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            MaterialAlertDialogBuilder(context).setMessage(getString(R.string.recovery_experiment_wss, context.packageName))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        MaterialAlertDialogBuilder(context).setTitle(R.string.recovery_experiment_title)
            .setMessage(R.string.recovery_experiment_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recovery_experiment_enable) { _, _ ->
                val permission = ExperimentalWirelessSession.hotspotPermission()
                if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    RecoverySettings.prefs.edit().putBoolean(RecoverySettings.EXPERIMENT_ENABLED, true).apply()
                    RecoverySettings.resetExperiment()
                    findPreference<TwoStatePreference>(RecoverySettings.EXPERIMENT_ENABLED)!!.isChecked = true
                    RecoveryService.refresh(context)
                } else {
                    hotspotPermissionRequest.launch(permission)
                }
            }.show()
    }

    private fun updateRecoveryStatus() {
        findPreference<Preference>(RecoverySettings.STATUS)?.summary =
            RecoverySettings.statusSummary(getString(R.string.recovery_waiting))
    }

    override fun onResume() {
        super.onResume()
        RecoverySettings.prefs.registerOnSharedPreferenceChangeListener(statusListener)
        updateRecoveryStatus()
    }

    override fun onPause() {
        RecoverySettings.prefs.unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onPause()
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState) as BorderRecyclerView
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        val lp = recyclerView.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.rightMargin = recyclerView.context.resources.getDimension(R.dimen.rd_activity_horizontal_margin).toInt()
            lp.leftMargin = lp.rightMargin
        }

        return recyclerView
    }

    private fun setupLocalePreference() {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

        languagePreference.entries = displayLocaleTags
        languagePreference.entryValues = localeTags

        val currentLocaleTag = languagePreference.value
        val currentLocaleIndex = localeTags.indexOf(currentLocaleTag)
        val currentLocale = ShizukuSettings.getLocale()
        val localizedLocales = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                localizedLocales.add(getString(R.string.follow_system))
                continue
            }

            val locale = Locale.forLanguageTag(displayLocale.toString())
            val localeName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(locale)
            else
                locale.getDisplayName(locale)

            val localizedLocaleName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(currentLocale)
            else
                locale.getDisplayName(currentLocale)

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName<small>".toHtml()
                } else {
                    localizedLocaleName
                }
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.summary = when {
            TextUtils.isEmpty(currentLocaleTag) || "SYSTEM" == currentLocaleTag -> {
                getString(R.string.follow_system)
            }
            currentLocaleIndex != -1 -> {
                val localizedLocale = localizedLocales[currentLocaleIndex]
                val newLineIndex = localizedLocale.indexOf('\n')
                if (newLineIndex == -1) {
                    localizedLocale.toString()
                } else {
                    localizedLocale.subSequence(0, newLineIndex).toString()
                }
            }
            else -> {
                ""
            }
        }
    }
}

/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.preferences

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthEndpoint
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthSyncWorker
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthUploader
import java.time.LocalDate
import java.time.ZoneId

class SelfHostedHealthPreferencesActivity : AbstractSettingsActivityV2() {
    override fun newFragment(): PreferenceFragmentCompat = SelfHostedHealthPreferencesFragment()

    class SelfHostedHealthPreferencesFragment : AbstractPreferenceFragment() {

        override fun getPreferenceKeysWithSummary(): Set<String> =
            setOf(GBPrefs.SELF_HOSTED_HEALTH_URL)

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.selfhosted_health_preferences, rootKey)

            setupEnabledSwitch()
            setupUrl()
            setupToken()
            setupInterval()
            setupDeviceSelection()
            setupActions()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // The worker writes its outcome to preferences, so "Upload now" reports back on this
            // screen instead of only on the next visit.
            WorkManager.getInstance(requireContext())
                .getWorkInfosByTagLiveData(SelfHostedHealthSyncWorker.WORK_TAG)
                .observe(viewLifecycleOwner) { updateStatusSummary() }
        }

        override fun onResume() {
            super.onResume()
            updateStatusSummary()
            updateTokenSummary()
            updateUrlSummary()
        }

        /**
         * Freezes a start date the first time upload is switched on.
         *
         * Without it the first run would walk back as far as the sample database goes and replay a
         * previous device's history into the server's day files.
         */
        private fun setupEnabledSwitch() {
            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
                val enabling = newValue == true
                if (enabling) {
                    val prefs = GBApplication.getPrefs()
                    if (prefs.getLong(GBPrefs.SELF_HOSTED_HEALTH_INITIAL_SYNC_TS, 0L) <= 0L) {
                        val startOfToday = LocalDate.now()
                            .atStartOfDay(ZoneId.systemDefault())
                            .toEpochSecond()
                        prefs.preferences.edit()
                            .putLong(GBPrefs.SELF_HOSTED_HEALTH_INITIAL_SYNC_TS, startOfToday)
                            .apply()
                    }
                }
                // Turning the feature off must also stop the periodic upload; turning it on re-arms
                // it if an interval is set. The listener fires before the new value is persisted, so
                // the fresh state is passed in explicitly.
                SelfHostedHealthSyncWorker.reschedulePeriodic(requireContext(), enabled = enabling)
                true
            }
        }

        /**
         * The periodic upload lives in WorkManager, not the preference store, so a cadence change has
         * to be turned into a (re)schedule here. useSimpleSummaryProvider already shows the choice.
         */
        private fun setupInterval() {
            findPreference<ListPreference>(GBPrefs.SELF_HOSTED_HEALTH_SYNC_INTERVAL)?.setOnPreferenceChangeListener { _, newValue ->
                val minutes = (newValue as? String)?.toIntOrNull() ?: 0
                SelfHostedHealthSyncWorker.reschedulePeriodic(requireContext(), minutes = minutes)
                true
            }
        }

        /**
         * Typing just the domain is the obvious thing to do, so the bare origin is completed to the
         * ingest path rather than rejected. The summary then shows the completed address, which is
         * what makes the field explain itself instead of failing later with a 404.
         */
        private fun setupUrl() {
            val urlPref = findPreference<EditTextPreference>(GBPrefs.SELF_HOSTED_HEALTH_URL) ?: return
            urlPref.setOnPreferenceChangeListener { _, newValue ->
                val normalized = SelfHostedHealthEndpoint.normalize(newValue as? String)
                if (normalized == null) {
                    updateUrlSummary()
                    return@setOnPreferenceChangeListener true
                }
                urlPref.text = normalized
                urlPref.summary = normalized
                // The value was rewritten here; returning false stops the framework from also
                // persisting the raw text over it.
                false
            }
            updateUrlSummary()
        }

        /** The token is never shown, only whether one exists: this screen is reachable without any
         *  device unlock and a bearer token is the server's only write lock. */
        private fun setupToken() {
            val tokenPref = findPreference<EditTextPreference>(GBPrefs.SELF_HOSTED_HEALTH_TOKEN) ?: return
            tokenPref.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                editText.setSelection(editText.text.length)
            }
            tokenPref.setOnPreferenceChangeListener { _, newValue ->
                // A token pasted from a wrapped copy arrives with a newline in the middle, which is a
                // control char no HTTP header value can hold; store it cleaned so the upload does not
                // fail with nothing to show for it. Mirrors the URL field: rewrite, then return false
                // so the framework does not persist the raw text over the sanitized value.
                val sanitized = SelfHostedHealthUploader.sanitizeToken(newValue as? String)
                tokenPref.text = sanitized
                tokenPref.summary = tokenSummary(sanitized)
                false
            }
            updateTokenSummary()
        }

        private fun setupDeviceSelection() {
            val devicesPref = findPreference<MultiSelectListPreference>(GBPrefs.SELF_HOSTED_HEALTH_DEVICE_SELECTION)
                ?: return
            val devices = GBApplication.app().deviceManager.devices
            devicesPref.entryValues = devices.map { it.address }.toTypedArray()
            devicesPref.entries = devices.map { it.aliasOrName }.toTypedArray()
        }

        private fun setupActions() {
            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_SYNC_NOW)?.setOnPreferenceClickListener {
                val context = context ?: return@setOnPreferenceClickListener false
                val request = OneTimeWorkRequest.Builder(SelfHostedHealthSyncWorker::class.java)
                    .addTag(SelfHostedHealthSyncWorker.WORK_TAG)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_TIME_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                GB.toast(context, R.string.selfhosted_health_upload_started, Toast.LENGTH_SHORT, GB.INFO)
                true
            }

            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_RESET_CURSOR)?.setOnPreferenceClickListener {
                val context = context ?: return@setOnPreferenceClickListener false
                resetCursors()
                GB.toast(context, R.string.pref_selfhosted_health_reset_cursor_done, Toast.LENGTH_SHORT, GB.INFO)
                true
            }
        }

        /** Drops the per-device upload and sleep cursors so the next run re-sends from the start
         *  date. Safe because the server merges: steps take the larger value and heart rate dedups. */
        private fun resetCursors() {
            val preferences = GBApplication.getPrefs().preferences
            val editor = preferences.edit()
            for (address in GBApplication.app().deviceManager.devices.mapNotNull { it.address }) {
                editor.remove(SelfHostedHealthSyncWorker.cursorKey(address))
                editor.remove(SelfHostedHealthSyncWorker.sleepCursorKey(address))
            }
            editor.apply()
        }

        private fun updateStatusSummary() {
            val status = GBApplication.getPrefs().getString(GBPrefs.SELF_HOSTED_HEALTH_STATUS, "").orEmpty()
            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_STATUS)?.let { preference ->
                preference.summary = status
                preference.isVisible = status.isNotEmpty()
            }
        }

        private fun updateTokenSummary() {
            findPreference<EditTextPreference>(GBPrefs.SELF_HOSTED_HEALTH_TOKEN)?.let { preference ->
                preference.summary = tokenSummary(preference.text)
            }
        }

        /** Show the configured endpoint once there is one; the XML summary is only the hint for an
         *  empty field, and reopening the screen would otherwise show the hint again. */
        private fun updateUrlSummary() {
            findPreference<EditTextPreference>(GBPrefs.SELF_HOSTED_HEALTH_URL)?.let { preference ->
                // Normalized, not raw: an address stored before this completion existed should read
                // back as the endpoint that will actually be posted to.
                val url = SelfHostedHealthEndpoint.normalize(preference.text)
                preference.summary = url ?: getString(R.string.pref_selfhosted_health_url_summary)
            }
        }

        private fun tokenSummary(token: String?): String = getString(
            if (token.isNullOrBlank()) {
                R.string.pref_selfhosted_health_token_not_set
            } else {
                R.string.pref_selfhosted_health_token_set
            }
        )

        companion object {
            private const val ONE_TIME_WORK_NAME = "SelfHostedHealthSyncWorker_OneTime"
        }
    }
}

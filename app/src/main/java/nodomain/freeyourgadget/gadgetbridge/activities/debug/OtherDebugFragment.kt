package nodomain.freeyourgadget.gadgetbridge.activities.debug

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.DatePicker
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol
import nodomain.freeyourgadget.gadgetbridge.util.GB
import java.util.Calendar
import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.huaweiband10.HuaweiBand10Coordinator
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiHeartRateDebugProbeContract

class OtherDebugFragment : AbstractDebugFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setupPreferences()
    }

    private fun setupPreferences() {
        setPreferencesFromResource(R.xml.debug_preferences_other_actions, null)

        if (BuildConfig.DEBUG) {
            addDynamicPref(
                title = "Band 10 即时心率探针",
                summary = "测量 15 秒，发送停止命令后继续观察 30 秒；结果记入调试日志",
                icon = R.drawable.ic_heart
            ) {
                runOnDebugDevices("选择 Band 10", true) { device ->
                    if (device.deviceCoordinator !is HuaweiBand10Coordinator) {
                        GB.toast(requireContext(), "仅支持 Band 10", Toast.LENGTH_LONG, GB.ERROR)
                        return@runOnDebugDevices
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("即时心率协议探针")
                        .setMessage("请戴好手环并保持连接。探针会测量最多 15 秒，发送 type=2 停止命令，再观察至少 30 秒。停止效果仍待实测，结果只写入本机调试日志。")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton("开始") { _, _ ->
                            val options = Bundle().apply {
                                putString(HuaweiHeartRateDebugProbeContract.ACTION_KEY,
                                    HuaweiHeartRateDebugProbeContract.ACTION_START)
                            }
                            GBApplication.deviceService(device).onTestNewFunction(options)
                        }
                        .show()
                }
            }
            addDynamicPref(
                title = "停止 Band 10 心率探针",
                summary = "提前发送停止命令并观察 30 秒；观察期间再点将中断记录",
                icon = R.drawable.ic_stop
            ) {
                runOnDebugDevices("选择 Band 10", true) { device ->
                    if (device.deviceCoordinator !is HuaweiBand10Coordinator) {
                        return@runOnDebugDevices
                    }
                    val options = Bundle().apply {
                        putString(HuaweiHeartRateDebugProbeContract.ACTION_KEY,
                            HuaweiHeartRateDebugProbeContract.ACTION_CANCEL)
                    }
                    GBApplication.deviceService(device).onTestNewFunction(options)
                }
            }
            addDynamicPref(
                title = getString(R.string.huawei_phone_alarm_probe_title),
                summary = getString(R.string.huawei_phone_alarm_probe_summary),
                icon = R.drawable.ic_access_time
            ) {
                openHuaweiPhoneAlarmProbe()
            }
        }

        onClick(PREF_DEBUG_SET_TIME) {
            runOnDebugDevices("Set time") {
                GBApplication.deviceService(it).onSetTime()
            }
        }

        onClick(PREF_DEBUG_SET_ACTIVITY_FETCH_TIME) {
            val currentDate = Calendar.getInstance()
            runOnDebugDevices {
                DatePickerDialog(
                    requireActivity(),
                    { _: DatePicker?, year: Int, monthOfYear: Int, dayOfMonth: Int ->
                        val date = DateTimeUtils.dayStart(year, monthOfYear, dayOfMonth)

                        GB.toast("Fetch time: ${date.time}", Toast.LENGTH_LONG, GB.INFO)
                        GBApplication.getDeviceSpecificSharedPrefs(it.address).edit {
                            //FIXME: key reconstruction is BAD

                            remove("lastSyncTimeMillis")
                            remove("lastTemperatureTimeMillis")
                            remove("lastStressManualTimeMillis")
                            remove("lastStressAutoTimeMillis")
                            remove("lastDebugTimeMillis")
                            remove("lastStatisticsTimeMillis")
                            remove("lastSportsActivityTimeMillis")
                            remove("lastSpo2sleepTimeMillis")
                            remove("lastSpo2normalTimeMillis")
                            remove("lastSleepSessionTimeMillis")
                            remove("lastPaiTimeMillis")
                            remove("lastHeartRateManualTimeMillis")
                            remove("lastHeartRateRestingTimeMillis")
                            remove("lastHrvTimeMillis")
                            remove("lastHeartRateMaxTimeMillis")
                            remove("lastSleepRespiratoryRateTimeMillis")

                            putLong("lastSyncTimeMillis", date.time)
                            putLong("lastTemperatureTimeMillis", date.time)
                            putLong("lastStressManualTimeMillis", date.time)
                            putLong("lastStressAutoTimeMillis", date.time)
                            putLong("lastDebugTimeMillis", date.time)
                            putLong("lastStatisticsTimeMillis", date.time)
                            putLong("lastSportsActivityTimeMillis", date.time)
                            putLong("lastSpo2sleepTimeMillis", date.time)
                            putLong("lastSpo2normalTimeMillis", date.time)
                            putLong("lastSleepSessionTimeMillis", date.time)
                            putLong("lastPaiTimeMillis", date.time)
                            putLong("lastHeartRateManualTimeMillis", date.time)
                            putLong("lastHeartRateRestingTimeMillis", date.time)
                            putLong("lastHrvTimeMillis", date.time)
                            putLong("lastHeartRateMaxTimeMillis", date.time)
                            putLong("lastSleepRespiratoryRateTimeMillis", date.time)
                        }
                    },
                    currentDate.get(Calendar.YEAR),
                    currentDate.get(Calendar.MONTH),
                    currentDate.get(
                        Calendar.DATE
                    )
                ).show()
            }
        }

        onClick(PREF_DEBUG_FETCH_DEBUG_LOGS) {
            runOnDebugDevices("Fetch debug logs", true) {
                GBApplication.deviceService(it).onFetchRecordedData(RecordedDataTypes.TYPE_DEBUGLOGS)
            }
        }

        onClick(PREF_DEBUG_REBOOT) {
            runOnDebugDevices("Reboot", true) {
                GBApplication.deviceService(it).onReset(GBDeviceProtocol.RESET_FLAGS_REBOOT)
            }
        }

        onClick(PREF_DEBUG_FACTORY_RESET) {
            MaterialAlertDialogBuilder(requireActivity())
                .setCancelable(true)
                .setTitle(R.string.debugactivity_really_factoryreset_title)
                .setMessage(R.string.debugactivity_really_factoryreset)
                .setPositiveButton(R.string.ok) { _, _ ->
                    runOnDebugDevices(getString(R.string.factory_reset), true) {
                        GBApplication.deviceService(it).onReset(GBDeviceProtocol.RESET_FLAGS_FACTORY_RESET)
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()
        }
    }

    private fun openHuaweiPhoneAlarmProbe() {
        try {
            val fragmentClass = Class.forName(HUAWEI_PHONE_ALARM_PROBE_FRAGMENT)
                .asSubclass(AbstractDebugFragment::class.java)
            goTo(fragmentClass.getDeclaredConstructor().newInstance())
        } catch (e: ReflectiveOperationException) {
            GB.toast(
                requireContext(),
                getString(R.string.huawei_phone_alarm_probe_unavailable),
                Toast.LENGTH_LONG,
                GB.ERROR,
                e
            )
        }
    }

    companion object {
        private const val PREF_DEBUG_SET_TIME = "pref_debug_set_time"
        private const val ACTIVITY_LIST_DEBUG_EXTRA_TIME_RANGE = "activity_list_debug_extra_time_range"
        private const val PREF_DEBUG_HEADER_FETCH_RECORDED_DATA = "pref_debug_header_fetch_recorded_data"
        private const val PREF_DEBUG_SET_ACTIVITY_FETCH_TIME = "pref_debug_set_activity_fetch_time"
        private const val PREF_DEBUG_FETCH_DEBUG_LOGS = "pref_debug_fetch_debug_logs"
        private const val PREF_DEBUG_HEADER_RESET = "pref_debug_header_reset"
        private const val PREF_DEBUG_REBOOT = "pref_debug_reboot"
        private const val PREF_DEBUG_FACTORY_RESET = "pref_debug_factory_reset"
        private const val HUAWEI_PHONE_ALARM_PROBE_FRAGMENT =
            "nodomain.freeyourgadget.gadgetbridge.activities.debug.HuaweiPhoneAlarmDebugProbeFragment"
    }
}

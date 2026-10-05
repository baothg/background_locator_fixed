package yukams.app.background_locator_2.pluggables

import android.content.Context
import yukams.app.background_locator_2.IsolateHolderService
import yukams.app.background_locator_2.Keys
import yukams.app.background_locator_2.PreferencesManager

class InitPluggable : Pluggable {
    private var isInitCallbackCalled = false

    override fun setCallback(context: Context, callbackHandle: Long) {
        PreferencesManager.setCallbackHandle(context, Keys.INIT_CALLBACK_HANDLE_KEY, callbackHandle)

    }

    override fun onServiceStart(context: Context) {
        if (!isInitCallbackCalled) {
            (PreferencesManager.getCallbackHandle(context, Keys.INIT_CALLBACK_HANDLE_KEY))?.let { initCallback ->
                val initialDataMap =
                    PreferencesManager.getDataCallback(context, Keys.INIT_DATA_CALLBACK_KEY)
                IsolateHolderService.invokeBackgroundMethod(
                    context,
                    Keys.BCM_INIT,
                    hashMapOf(
                        Keys.ARG_INIT_CALLBACK to initCallback,
                        Keys.ARG_INIT_DATA_CALLBACK to initialDataMap
                    )
                )
            }
            isInitCallbackCalled = true
        }
    }

    override fun onServiceDispose(context: Context) {
        isInitCallbackCalled = false
    }

    fun setInitData(context: Context, data: Map<*, *>) {
        PreferencesManager.setDataCallback(context, Keys.INIT_DATA_CALLBACK_KEY, data)
    }
}
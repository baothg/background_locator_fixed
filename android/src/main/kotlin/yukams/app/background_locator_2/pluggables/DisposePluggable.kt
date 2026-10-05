package yukams.app.background_locator_2.pluggables

import android.content.Context
import yukams.app.background_locator_2.IsolateHolderService
import yukams.app.background_locator_2.Keys
import yukams.app.background_locator_2.PreferencesManager

class DisposePluggable : Pluggable {
    override fun setCallback(context: Context, callbackHandle: Long) {
        PreferencesManager.setCallbackHandle(context, Keys.DISPOSE_CALLBACK_HANDLE_KEY, callbackHandle)
    }

    override fun onServiceDispose(context: Context) {
        (PreferencesManager.getCallbackHandle(context, Keys.DISPOSE_CALLBACK_HANDLE_KEY))?.let { disposeCallback ->
            IsolateHolderService.invokeBackgroundMethod(
                context,
                Keys.BCM_DISPOSE,
                hashMapOf(Keys.ARG_DISPOSE_CALLBACK to disposeCallback)
            )
        }
    }
}
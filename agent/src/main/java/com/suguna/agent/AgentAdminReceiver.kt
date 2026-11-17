package com.suguna.agent

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/** Device-admin receiver so the agent can lock the screen and (best-effort) wipe. */
class AgentAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) =
            ComponentName(context.packageName, AgentAdminReceiver::class.java.name)
    }
}

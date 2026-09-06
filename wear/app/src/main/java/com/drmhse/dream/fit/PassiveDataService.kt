package com.drmhse.dream.fit

import android.content.Intent
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType

// Health Services daily step aggregate: survives reboots, predates our install.
class PassiveDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(points: DataPointContainer) {
        // DAILY points are cumulative snapshots of the same day, not disjoint
        // slices: summing them multiplies the truth by the point count.
        val total = points.getData(DataType.STEPS_DAILY).maxOfOrNull { it.value } ?: return
        startForegroundService(
            Intent(this, BridgeService::class.java)
                .setAction("dreamfit.DAILY")
                .putExtra("total", total.toInt()),
        )
    }
}

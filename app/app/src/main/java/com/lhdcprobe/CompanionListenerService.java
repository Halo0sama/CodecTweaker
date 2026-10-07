package com.lhdcprobe;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.util.Log;

/**
 * CDM 设备在场回调：耳机 ACL 连接时由系统（companiondevice 服务）主动绑定并拉起
 * 本进程——这是"无无障碍、无常驻通知"的唤醒路径。到达时间在 ACL 层，可能早于
 * A2DP 建流，真正的修复由 DirectorCore.scheduleAutoFix 内部等待 A2DP 就绪。
 */
public class CompanionListenerService extends CompanionDeviceService {

    private static final String TAG = "LHDCDirector";

    @Override
    public void onDeviceAppeared(AssociationInfo association) {
        String mac = null;
        try {
            mac = String.valueOf(association.getDeviceMacAddress());
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "CDM onDeviceAppeared id=" + association.getId() + " mac=" + mac);
        DirectorCore core = DirectorCore.get(this);
        core.log("CDM onDeviceAppeared mac=" + mac);
        if (mac != null) {
            core.scheduleAutoFix(mac, null);
        }
    }

    @Override
    public void onDeviceDisappeared(AssociationInfo association) {
        Log.i(TAG, "CDM onDeviceDisappeared id=" + association.getId());
        DirectorCore.get(this).log("CDM onDeviceDisappeared id=" + association.getId());
    }
}

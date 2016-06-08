package com.lukekorth.auto_fi.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.lukekorth.auto_fi.models.Settings;
import com.lukekorth.auto_fi.models.WifiNetwork;
import com.lukekorth.auto_fi.utilities.Logger;
import com.lukekorth.auto_fi.utilities.VpnHelper;
import com.lukekorth.auto_fi.utilities.WifiHelper;

import java.util.List;

import io.realm.Realm;

public class WifiScanReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Settings.isEnabled(context)) {
            return;
        }

        WifiHelper wifiHelper = new WifiHelper(context);

        if (VpnHelper.isVpnEnabled(context) && wifiHelper.getWifiManager().isWifiEnabled() &&
                !wifiHelper.isConnectedToWifi() && intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
            List<ScanResult> scanResults = wifiHelper.getWifiManager().getScanResults();
            if (!wifiHelper.isConnectedToWifi() && scanResults.size() > 0) {
                ScanResult selectedNetwork = null;
                for (ScanResult scanResult : wifiHelper.getWifiManager().getScanResults()) {
                    if (isNetworkUnsecured(scanResult)) {
                        if (selectedNetwork == null) {
                            selectedNetwork = scanResult;
                        } else if (WifiManager.compareSignalLevel(scanResult.level, selectedNetwork.level) > 0) {
                            selectedNetwork = scanResult;
                        }
                    }
                }

                if (selectedNetwork != null && !TextUtils.isEmpty(selectedNetwork.SSID.trim())) {
                    Logger.debug("Found network " + selectedNetwork.SSID + " nearby");

                    List<WifiConfiguration> wifiConfigurationList = wifiHelper.getWifiManager().getConfiguredNetworks();
                    if (wifiConfigurationList == null) {
                        return;
                    }

                    for (WifiConfiguration configuredNetwork : wifiConfigurationList) {
                        if (configuredNetwork.SSID.equals(selectedNetwork.SSID)) {
                            Logger.debug("Network " + selectedNetwork.SSID + " is already configured.");
                            return;
                        }
                    }

                    String ssid = "\"" + selectedNetwork.SSID + "\"";
                    if (WifiNetwork.isBlacklisted(ssid)) {
                        Logger.info(selectedNetwork.SSID + " is blacklisted");
                    } else {
                        Realm realm = Realm.getDefaultInstance();
                        WifiNetwork network = WifiNetwork.findOrCreate(realm, ssid);
                        realm.beginTransaction();
                        network.setAutoconnected(true);
                        realm.commitTransaction();

                        WifiConfiguration configuration = new WifiConfiguration();
                        configuration.SSID = ssid;
                        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                        int networkId = wifiHelper.getWifiManager().addNetwork(configuration);
                        wifiHelper.getWifiManager().enableNetwork(networkId, true);
                        wifiHelper.getWifiManager().saveConfiguration();
                        wifiHelper.getWifiManager().reconnect();

                        FirebaseAnalytics.getInstance(context).logEvent("wifi_auto_connected", null);
                    }
                }
            }
        }
    }

    private boolean isNetworkUnsecured(ScanResult result) {
        return !(result.capabilities.contains("WEP") || result.capabilities.contains("PSK") ||
                result.capabilities.contains("EAP"));
    }
}

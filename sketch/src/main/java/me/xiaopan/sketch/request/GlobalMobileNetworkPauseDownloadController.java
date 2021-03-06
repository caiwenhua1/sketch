package me.xiaopan.sketch.request;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.lang.ref.WeakReference;

import me.xiaopan.sketch.Configuration;

/**
 * 全局移动网络下暂停下载控制器
 */
public class GlobalMobileNetworkPauseDownloadController {
    private NetworkChangedBroadcastReceiver receiver;
    private boolean opened;
    private Configuration configuration;

    public GlobalMobileNetworkPauseDownloadController(Context context, Configuration configuration) {
        context = context.getApplicationContext();
        receiver = new NetworkChangedBroadcastReceiver(context, this);
        this.configuration = configuration;
    }

    /**
     * 已经开启了？
     */
    public boolean isOpened() {
        return opened;
    }

    /**
     * 开启功能
     *
     * @param opened 开启
     */
    public void setOpened(boolean opened) {
        if (this.opened == opened) {
            return;
        }
        this.opened = opened;

        if (this.opened) {
            updateStatus(receiver.context);
            receiver.register();
        } else {
            configuration.setGlobalPauseDownload(false);
            receiver.unregister();
        }
    }

    /**
     * 网络状态变化或初始化时更新全局暂停功能
     *
     * @param context {@link Context}
     */
    private void updateStatus(Context context) {
        NetworkInfo networkInfo = ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        boolean isPause = networkInfo != null && networkInfo.isAvailable() && networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
        configuration.setGlobalPauseDownload(isPause);
    }

    /**
     * 监听网络变化的广播
     */
    private static class NetworkChangedBroadcastReceiver extends BroadcastReceiver {
        private Context context;
        private WeakReference<GlobalMobileNetworkPauseDownloadController> weakReference;

        public NetworkChangedBroadcastReceiver(Context context, GlobalMobileNetworkPauseDownloadController download) {
            context = context.getApplicationContext();
            this.context = context;
            this.weakReference = new WeakReference<>(download);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                GlobalMobileNetworkPauseDownloadController pauseDownloadController = weakReference.get();
                if (pauseDownloadController != null) {
                    pauseDownloadController.updateStatus(context);
                }
            }
        }

        private void register() {
            try {
                context.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        private void unregister() {
            try {
                context.unregisterReceiver(this);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }
}

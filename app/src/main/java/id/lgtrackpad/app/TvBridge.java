package id.lgtrackpad.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TvBridge {
    private final Context context;
    private final WebView webView;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newCachedThreadPool();

    private MiniWebSocketClient tvWs = null;
    private MiniWebSocketClient ptrWs = null;
    private volatile boolean connecting = false;
    private final AtomicInteger reqN = new AtomicInteger(0);
    private Runnable retryRunnable = null;

    private static final String[] PERMS = new String[]{
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_DISPLAY",
        "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN", "CONTROL_TV_STANBY",
        "CONTROL_FAVORITE_GROUP", "CONTROL_USER_INFO", "CONTROL_BLUETOOTH", "CONTROL_TIMER_INFO",
        "CONTROL_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_APP_STATUS",
        "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
        "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO",
        "READ_SETTINGS", "CONTROL_TV_POWER", "READ_INSTALLED_APPS", "READ_LGE_SDX",
        "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT",
        "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME"
    };

    public TvBridge(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.prefs = context.getSharedPreferences("lg_trackpad_prefs", Context.MODE_PRIVATE);
    }

    public void initConnection() {
        String ip = prefs.getString("tvIp", "");
        if (ip != null && !ip.trim().isEmpty()) {
            connectTV(ip.trim());
        } else {
            notifyStatus(false, "Ketuk ikon TV/WiFi di kanan atas untuk cari TV LG");
            emitToJs("t:'need_ip'");
        }
    }

    private void emitToJs(String rawJsonFields) {
        mainHandler.post(() -> {
            webView.evaluateJavascript("window.onNativeMsg && window.onNativeMsg({" + rawJsonFields + "});", null);
        });
    }

    private void notifyStatus(boolean ok, String msg) {
        String safeMsg = JSONObject.quote(msg);
        emitToJs("t:'status',ok:" + ok + ",msg:" + safeMsg);
    }

    private void notifyScanResult(List<String> ips) {
        JSONArray arr = new JSONArray(ips);
        emitToJs("t:'scan_result',ips:" + arr.toString());
    }

    @JavascriptInterface
    public String getSavedIp() {
        return prefs.getString("tvIp", "");
    }

    @JavascriptInterface
    public void setTvIp(String ip) {
        if (ip == null) return;
        String clean = ip.trim();
        if (clean.isEmpty()) return;
        String old = prefs.getString("tvIp", "");
        SharedPreferences.Editor ed = prefs.edit().putString("tvIp", clean);
        if (!clean.equals(old)) {
            ed.remove("clientKey_" + clean);
        }
        ed.apply();
        connectTV(clean);
    }

    @JavascriptInterface
    public void scanTv() {
        bgPool.execute(() -> {
            notifyStatus(false, "Memindai jaringan WiFi mencari TV LG...");
            Set<String> found = Collections.synchronizedSet(new LinkedHashSet<>());

            // 1. SSDP Discovery
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = null;
            if (wm != null) {
                lock = wm.createMulticastLock("lg_ssdp_lock");
                lock.setReferenceCounted(true);
                try { lock.acquire(); } catch (Exception ignored) {}
            }
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(700);
                String[] targets = new String[]{
                    "urn:lge-com:service:webos-second-screen:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "ssdp:all"
                };
                InetAddress group = InetAddress.getByName("239.255.255.250");
                for (String st : targets) {
                    String msg = "M-SEARCH * HTTP/1.1\r\n" +
                                 "HOST: 239.255.255.250:1900\r\n" +
                                 "MAN: \"ssdp:discover\"\r\n" +
                                 "MX: 2\r\n" +
                                 "ST: " + st + "\r\n\r\n";
                    byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket pkt = new DatagramPacket(data, data.length, group, 1900);
                    sock.send(pkt);
                }
                long end = System.currentTimeMillis() + 1800;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket recv = new DatagramPacket(buf, buf.length);
                        sock.receive(recv);
                        String resp = new String(recv.getData(), 0, recv.getLength(), StandardCharsets.UTF_8).toLowerCase();
                        String senderIp = recv.getAddress().getHostAddress();
                        if (resp.contains("webos") || resp.contains("lge") || resp.contains("lg ")) {
                            found.add(senderIp);
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (lock != null && lock.isHeld()) {
                    try { lock.release(); } catch (Exception ignored) {}
                }
            }

            // 2. Parallel TCP Subnet Scan on ports 3001 / 3000
            String myIp = getLocalIpv4();
            if (myIp != null && myIp.contains(".")) {
                String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);
                ExecutorService scanExec = Executors.newFixedThreadPool(48);
                for (int i = 1; i <= 254; i++) {
                    final String candidate = prefix + i;
                    if (candidate.equals(myIp)) continue;
                    scanExec.execute(() -> {
                        if (isPortOpen(candidate, 3001, 280) || isPortOpen(candidate, 3000, 280)) {
                            found.add(candidate);
                        }
                    });
                }
                scanExec.shutdown();
                try { scanExec.awaitTermination(3500, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
            }

            List<String> list = new ArrayList<>(found);
            notifyScanResult(list);
            String saved = prefs.getString("tvIp", "");
            if (!list.isEmpty() && (saved == null || saved.isEmpty())) {
                notifyStatus(false, "Ditemukan " + list.size() + " TV LG. Pilih untuk menyambung.");
            } else if (list.isEmpty()) {
                notifyStatus(false, "TV tidak ditemukan otomatis. Silakan ketik IP manual.");
            }
        });
    }

    private boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getLocalIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @JavascriptInterface
    public void sendCmd(String jsonStr) {
        bgPool.execute(() -> handlePhoneCommand(jsonStr));
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms > 0 ? ms : 8);
        } catch (Exception ignored) {}
    }

    private void scheduleRetry(String ip, long delayMs) {
        if (retryRunnable != null) mainHandler.removeCallbacks(retryRunnable);
        retryRunnable = () -> connectTV(ip);
        mainHandler.postDelayed(retryRunnable, delayMs);
    }

    public synchronized void resetLinks() {
        if (ptrWs != null) { try { ptrWs.close(); } catch (Exception ignored) {} ptrWs = null; }
        if (tvWs != null) { try { tvWs.close(); } catch (Exception ignored) {} tvWs = null; }
    }

    public void connectTV(String ip) {
        if (ip == null || ip.isEmpty()) return;
        if (connecting) return;
        connecting = true;
        if (retryRunnable != null) mainHandler.removeCallbacks(retryRunnable);

        bgPool.execute(() -> {
            resetLinks();
            notifyStatus(false, "Menghubungkan ke TV " + ip + " ...");

            String[] urls = new String[]{"wss://" + ip + ":3001", "ws://" + ip + ":3000"};
            MiniWebSocketClient connectedSock = null;

            for (String url : urls) {
                try {
                    final Object waitLock = new Object();
                    final boolean[] opened = new boolean[]{false};
                    MiniWebSocketClient candidate = new MiniWebSocketClient(url, new MiniWebSocketClient.Listener() {
                        @Override
                        public void onOpen() {
                            synchronized (waitLock) {
                                opened[0] = true;
                                waitLock.notifyAll();
                            }
                        }

                        @Override
                        public void onMessage(String text) {
                            onTvMessage(ip, text);
                        }

                        @Override
                        public void onClose() {
                            onTvClose(ip);
                        }

                        @Override
                        public void onError(Exception e) {
                        }
                    });
                    candidate.connect();
                    if (opened[0]) {
                        connectedSock = candidate;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }

            connecting = false;
            if (connectedSock == null) {
                notifyStatus(false, "TV (" + ip + ") tidak terjangkau. Cek TV nyala & WiFi sama.");
                scheduleRetry(ip, 5000);
                return;
            }

            tvWs = connectedSock;
            sendRegisterPayload(ip);
        });
    }

    private void sendRegisterPayload(String ip) {
        try {
            JSONArray permsArr = new JSONArray();
            for (String p : PERMS) permsArr.put(p);

            JSONObject signed = new JSONObject();
            signed.put("created", "20140509");
            signed.put("appId", "com.lge.test");
            signed.put("vendorId", "com.lge");
            signed.put("localizedAppNames", new JSONObject().put("", "HP Trackpad"));
            signed.put("localizedVendorNames", new JSONObject().put("", "LG Electronics"));
            signed.put("permissions", permsArr);
            signed.put("serial", "2f930e2d2cfe083771f68e4fe7bb07");

            JSONObject manifest = new JSONObject();
            manifest.put("manifestVersion", 1);
            manifest.put("appVersion", "1.1");
            manifest.put("signed", signed);
            manifest.put("permissions", permsArr);

            JSONObject payload = new JSONObject();
            payload.put("forcePairing", false);
            payload.put("pairingType", "PROMPT");
            payload.put("manifest", manifest);

            String clientKey = prefs.getString("clientKey_" + ip, null);
            if (clientKey != null && !clientKey.isEmpty()) {
                payload.put("client-key", clientKey);
            } else {
                notifyStatus(false, "Lihat layar TV, pilih YA / OK untuk mengizinkan");
            }

            JSONObject reg = new JSONObject();
            reg.put("type", "register");
            reg.put("id", "register_0");
            reg.put("payload", payload);

            tvSend(reg);
        } catch (Exception ignored) {}
    }

    private void onTvMessage(String ip, String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            if ("registered".equals(type)) {
                JSONObject payload = m.optJSONObject("payload");
                if (payload != null) {
                    String key = payload.optString("client-key", "");
                    if (!key.isEmpty()) {
                        prefs.edit().putString("clientKey_" + ip, key).apply();
                    }
                }
                JSONObject req = new JSONObject();
                req.put("type", "request");
                req.put("id", "ptr");
                req.put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket");
                tvSend(req);
            }
            JSONObject payload = m.optJSONObject("payload");
            if (payload != null && payload.has("socketPath")) {
                String socketPath = payload.optString("socketPath");
                if (!socketPath.isEmpty()) {
                    openPointer(ip, socketPath);
                }
            }
            if ("error".equals(type)) {
                if ("register_0".equals(m.optString("id"))) {
                    prefs.edit().remove("clientKey_" + ip).apply();
                    notifyStatus(false, "Izin ditolak / kunci lama tidak valid. Mencoba lagi...");
                    scheduleRetry(ip, 3000);
                }
            }
        } catch (Exception ignored) {}
    }

    private void onTvClose(String ip) {
        tvWs = null;
        if (ptrWs != null) {
            try { ptrWs.close(); } catch (Exception ignored) {}
            ptrWs = null;
        }
        notifyStatus(false, "Terputus dari TV (" + ip + "). Menyambung ulang...");
        scheduleRetry(ip, 3500);
    }

    private void openPointer(String ip, String socketUrl) {
        bgPool.execute(() -> {
            try {
                // Ensure host matches TV IP in case TV returns internal hostname
                URI u = new URI(socketUrl);
                String fixedUrl = u.getScheme() + "://" + ip + ":" + u.getPort() + u.getRawPath();
                if (u.getRawQuery() != null) fixedUrl += "?" + u.getRawQuery();

                MiniWebSocketClient p = new MiniWebSocketClient(fixedUrl, new MiniWebSocketClient.Listener() {
                    @Override
                    public void onOpen() {
                        notifyStatus(true, "Terhubung ke TV (" + ip + ")");
                    }
                    @Override
                    public void onMessage(String text) {}
                    @Override
                    public void onClose() {
                        ptrWs = null;
                    }
                    @Override
                    public void onError(Exception e) {}
                });
                p.connect();
                ptrWs = p;
            } catch (Exception e) {
                notifyStatus(false, "Gagal buka kontrol kursor: " + e.getMessage());
                scheduleRetry(ip, 4000);
            }
        });
    }

    private void tvSend(JSONObject o) {
        if (tvWs != null) tvWs.send(o.toString());
    }

    private void tvRequest(String uri, JSONObject payload) {
        try {
            JSONObject req = new JSONObject();
            req.put("type", "request");
            req.put("id", "r" + reqN.incrementAndGet());
            req.put("uri", uri);
            req.put("payload", payload != null ? payload : new JSONObject());
            tvSend(req);
        } catch (Exception ignored) {}
    }

    private void ptrSend(String... lines) {
        if (ptrWs != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            sb.append("\n\n");
            ptrWs.send(sb.toString());
        }
    }

    private void handlePhoneCommand(String jsonStr) {
        try {
            JSONObject m = new JSONObject(jsonStr);
            String t = m.optString("t");
            switch (t) {
                case "move": {
                    int dx = (int) Math.round(m.optDouble("dx", 0));
                    int dy = (int) Math.round(m.optDouble("dy", 0));
                    int down = m.optInt("down", 0) != 0 ? 1 : 0;
                    ptrSend("type:move", "dx:" + dx, "dy:" + dy, "down:" + down);
                    break;
                }
                case "tap":
                    ptrSend("type:click");
                    break;
                case "scroll": {
                    int dx = (int) Math.round(m.optDouble("dx", 0));
                    int dy = (int) Math.round(m.optDouble("dy", 0));
                    ptrSend("type:scroll", "dx:" + dx, "dy:" + dy);
                    break;
                }
                case "key": {
                    String name = m.optString("name", "").replaceAll("[^A-Z0-9_]", "");
                    ptrSend("type:button", "name:" + name);
                    break;
                }
                case "text": {
                    JSONObject p = new JSONObject();
                    p.put("text", m.optString("text", ""));
                    p.put("replace", 0);
                    tvRequest("ssap://com.webos.service.ime/insertText", p);
                    break;
                }
                case "del": {
                    int count = (int) Math.round(m.optDouble("count", 1));
                    if (count < 1) count = 1;
                    JSONObject p = new JSONObject();
                    p.put("count", count);
                    tvRequest("ssap://com.webos.service.ime/deleteCharacters", p);
                    break;
                }
                case "enter":
                    tvRequest("ssap://com.webos.service.ime/sendEnterKey", new JSONObject());
                    break;
            }
        } catch (Exception ignored) {}
    }
}

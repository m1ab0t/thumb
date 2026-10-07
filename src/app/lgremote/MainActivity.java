package app.lgremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Wi-Fi remote for LG webOS TVs (2014+), using the TV's own SSAP WebSocket API. */
public class MainActivity extends Activity {
    // Same pairing manifest Home Assistant (aiowebostv) uses; no signature needed with PROMPT pairing.
    static final String[] PERMISSIONS = {"APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY",
        "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING",
        "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER",
        "CONTROL_TV_SCREEN", "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO",
        "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX",
        "READ_LGE_TV_INPUT_EVENTS", "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE",
        "READ_RUNNING_APPS", "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME",
        "READ_UPDATE_INFO", "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE",
        "UPDATE_FROM_REMOTE_APP", "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS"};

    interface Task { void run() throws Exception; }

    final ExecutorService net = Executors.newSingleThreadExecutor();
    SharedPreferences prefs;
    EditText ipField;
    TextView status;
    volatile String ip = "";
    Ws main, pointer;
    int nextId = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        ip = prefs.getString("ip", "");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 24, 24, 24);

        ipField = new EditText(this);
        ipField.setHint("TV IP address");
        try { setTitle("LG Remote v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName); } catch (Exception ignored) {}
        ipField.setText(ip);
        ipField.setSingleLine();
        status = new TextView(this);
        status.setPadding(8, 8, 8, 24);
        status.setText(prefs.contains("key") ? "Paired. Tap any button." : "Tap Find (or type the TV's IP), then Connect.");

        col.addView(row(ipField, btn("Find", this::find), btn("Connect", () -> { request("ssap://system.notifications/createToast", new JSONObject().put("message", "Remote connected")); say("Connected"); })));
        col.addView(status);
        col.addView(row(btn("Power off", () -> request("ssap://system/turnOff", null)), btn("Power on", this::wake), key("Mute", "MUTE")));
        col.addView(row(btn("Vol −", () -> request("ssap://audio/volumeDown", null)), btn("Vol +", () -> request("ssap://audio/volumeUp", null)),
                        key("Ch −", "CHANNELDOWN"), key("Ch +", "CHANNELUP")));
        col.addView(row(space(), key("▲", "UP"), space()));
        col.addView(row(key("◀", "LEFT"), key("OK", "ENTER"), key("▶", "RIGHT")));
        col.addView(row(space(), key("▼", "DOWN"), space()));
        col.addView(row(key("Back", "BACK"), key("Home", "HOME"), key("Settings", "MENU"), key("Exit", "EXIT")));
        col.addView(row(key("⏪", "REWIND"), key("Play", "PLAY"), key("Pause", "PAUSE"), key("⏩", "FASTFORWARD")));
        col.addView(row(app("Netflix", "netflix"), app("YouTube", "youtube.leanback.v4"), app("Prime", "amazon")));
        col.addView(row(key("1", "1"), key("2", "2"), key("3", "3")));
        col.addView(row(key("4", "4"), key("5", "5"), key("6", "6")));
        col.addView(row(key("7", "7"), key("8", "8"), key("9", "9")));
        col.addView(row(key("Info", "INFO"), key("0", "0"), key("Guide", "GUIDE")));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    // Phone volume rockers control the TV once paired.
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (prefs.contains("key") && code == KeyEvent.KEYCODE_VOLUME_UP) { act(() -> request("ssap://audio/volumeUp", null)); return true; }
        if (prefs.contains("key") && code == KeyEvent.KEYCODE_VOLUME_DOWN) { act(() -> request("ssap://audio/volumeDown", null)); return true; }
        return super.onKeyDown(code, e);
    }

    // ---- UI helpers ----

    LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, 150, v instanceof EditText ? 2 : 1));
        return r;
    }

    View space() { return new View(this); }

    Button btn(String label, Task t) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> act(t));
        return b;
    }

    Button key(String label, String name) { return btn(label, () -> button(name)); }

    Button app(String label, String id) { return btn(label, () -> request("ssap://system.launcher/launch", new JSONObject().put("id", id))); }

    void say(String s) { runOnUiThread(() -> status.setText(s)); }

    /** Runs t on the network thread; on failure reconnects and retries once. */
    void act(Task t) {
        String now = ipField.getText().toString().trim();
        if (!now.equals(ip)) { ip = now; prefs.edit().putString("ip", ip).apply(); net.execute(this::drop); }
        net.execute(() -> {
            try { t.run(); } catch (Exception first) {
                drop();
                try { t.run(); } catch (Exception e) { drop(); say("Error: " + e.getMessage()); }
            }
        });
    }

    void drop() {
        if (main != null) main.close();
        if (pointer != null) pointer.close();
        main = pointer = null;
    }

    // ---- TV protocol ----

    void connect() throws Exception {
        if (main != null) return;
        if (ip.isEmpty()) throw new IOException("enter the TV's IP or tap Find");
        say("Connecting to " + ip + "…");
        Ws w;
        try { w = new Ws(ip, 3001, true, "/"); }            // newer firmware: TLS only
        catch (IOException e) { w = new Ws(ip, 3000, false, "/"); }
        JSONObject payload = new JSONObject().put("forcePairing", false).put("pairingType", "PROMPT")
            .put("manifest", new JSONObject().put("manifestVersion", 1).put("appVersion", "1.1").put("permissions", new JSONArray(PERMISSIONS)));
        String key = prefs.getString("key:" + ip, null);
        if (key != null) payload.put("client-key", key);
        w.send(new JSONObject().put("type", "register").put("id", "reg").put("payload", payload).toString());
        w.timeout(60000);
        while (true) {
            JSONObject m = new JSONObject(w.read());
            String type = m.optString("type");
            if (type.equals("registered")) {
                prefs.edit().putString("key:" + ip, m.getJSONObject("payload").getString("client-key")).putString("key", "1").apply();
                break;
            }
            if (type.equals("error")) { w.close(); throw new IOException(m.optString("error")); }
            say("Accept the prompt on your TV…");
        }
        w.timeout(5000);
        main = w;
        say("Connected to " + ip);
        // Remember the TV's MAC so "Power on" can Wake-on-LAN it later.
        try {
            JSONObject info = request("ssap://com.webos.service.connectionmanager/getinfo", null);
            StringBuilder macs = new StringBuilder();
            for (String k : new String[]{"wiredInfo", "wifiInfo"}) {
                JSONObject i = info.optJSONObject(k);
                if (i != null && i.has("macAddress")) macs.append(i.getString("macAddress")).append(' ');
            }
            prefs.edit().putString("mac:" + ip, macs.toString().trim()).apply();
        } catch (Exception ignored) {}
        if (prefs.getString("mac:" + ip, "").isEmpty()) say("Connected to " + ip + " (couldn't read its MAC, so Power on may not work)");
    }

    JSONObject request(String uri, JSONObject payload) throws Exception {
        connect();
        String id = "r" + nextId++;
        JSONObject msg = new JSONObject().put("type", "request").put("id", id).put("uri", uri);
        if (payload != null) msg.put("payload", payload);
        main.send(msg.toString());
        while (true) {
            JSONObject m = new JSONObject(main.read());
            if (!id.equals(m.optString("id"))) continue;
            if (m.optString("type").equals("error")) throw new IOException(m.optString("error"));
            return m.optJSONObject("payload");
        }
    }

    /** Remote-control keys go over a second "pointer input" socket. */
    void button(String name) throws Exception {
        if (pointer == null) {
            URI u = new URI(request("ssap://com.webos.service.networkinput/getPointerInputSocket", null).getString("socketPath"));
            pointer = new Ws(u.getHost(), u.getPort(), "wss".equals(u.getScheme()), u.getRawPath());
        }
        // ponytail: a silently-closed pointer socket can swallow one press before the retry reconnects.
        pointer.send("type:button\nname:" + name + "\n\n");
    }

    void find() throws Exception {
        say("Searching…");
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(3000);
            byte[] q = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                + "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n").getBytes("UTF-8");
            ds.send(new DatagramPacket(q, q.length, InetAddress.getByName("239.255.255.250"), 1900));
            DatagramPacket r = new DatagramPacket(new byte[2048], 2048);
            try { ds.receive(r); } catch (IOException e) { say("No LG TV found. Same Wi-Fi? Type the IP instead."); return; }
            String found = r.getAddress().getHostAddress();
            runOnUiThread(() -> ipField.setText(found));
            say("Found TV at " + found + ". Tap Connect.");
        }
    }

    /**
     * Power on two ways: Wake-on-LAN (needs TV setting "Turn on via Wi-Fi", General > Devices),
     * then ssap turnOn, which works while the TV is in quick-start standby with its network up.
     */
    void wake() throws Exception {
        String macs = prefs.getString("mac:" + ip, "");
        List<InetAddress> targets = new ArrayList<>();
        if (!macs.isEmpty()) {
            // Many Android Wi-Fi stacks drop 255.255.255.255, so also hit each subnet broadcast and the TV directly.
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (ni.isUp() && !ni.isLoopback())
                    for (InterfaceAddress a : ni.getInterfaceAddresses())
                        if (a.getBroadcast() != null) targets.add(a.getBroadcast());
            if (!ip.isEmpty()) targets.add(InetAddress.getByName(ip));
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                for (int round = 0; round < 3; round++)
                    for (String mac : macs.split(" ")) {
                        byte[] p = magicPacket(mac);
                        for (InetAddress t : targets)
                            for (int port : new int[]{9, 7})
                                try { ds.send(new DatagramPacket(p, p.length, t, port)); } catch (IOException ignored) {}
                    }
            }
        }
        say("Power-on sent…");
        try {
            request("ssap://system/turnOn", null);
            say("TV on");
        } catch (Exception e) {
            drop();
            if (macs.isEmpty()) throw new IOException("connect once while the TV is on so I can learn its MAC");
            say("Wake sent to " + macs + " via " + targets + ". Not on? Enable \"Turn on via Wi-Fi\" on the TV.");
        }
    }

    static byte[] magicPacket(String mac) {
        byte[] p = new byte[102];
        String[] hex = mac.split("[:-]");
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xff;
        for (int i = 6; i < 102; i++) p[i] = (byte) Integer.parseInt(hex[(i - 6) % 6], 16);
        return p;
    }

    /** Minimal client-side WebSocket (text frames). LG TVs use self-signed certs, so TLS trusts all. */
    static class Ws {
        final Socket s;
        final DataInputStream in;
        final OutputStream out;
        final SecureRandom rnd = new SecureRandom();

        @SuppressWarnings("resource")
        Ws(String host, int port, boolean tls, String path) throws IOException {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 3000);
            Socket sock = raw;
            if (tls) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }}, null);
                    sock = ctx.getSocketFactory().createSocket(raw, host, port, true);
                    ((SSLSocket) sock).startHandshake();
                } catch (IOException e) { raw.close(); throw e; }
                catch (Exception e) { raw.close(); throw new IOException(e); }
            }
            s = sock;
            s.setSoTimeout(5000);
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = s.getOutputStream();
            byte[] k = new byte[16];
            rnd.nextBytes(k);
            out.write(("GET " + (path.isEmpty() ? "/" : path) + " HTTP/1.1\r\nHost: " + host + ":" + port
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + Base64.encodeToString(k, Base64.NO_WRAP)
                + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes("UTF-8"));
            out.flush();
            StringBuilder h = new StringBuilder();
            while (h.length() < 4 || !h.substring(h.length() - 4).equals("\r\n\r\n")) h.append((char) in.readUnsignedByte());
            if (!h.toString().startsWith("HTTP/1.1 101")) { close(); throw new IOException("TV refused: " + h.toString().split("\r\n")[0]); }
        }

        void timeout(int ms) throws IOException { s.setSoTimeout(ms); }

        void send(String text) throws IOException { frame(0x81, text.getBytes("UTF-8")); }

        synchronized void frame(int b0, byte[] p) throws IOException {
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(b0);
            if (p.length < 126) f.write(0x80 | p.length);
            else { f.write(0x80 | 126); f.write(p.length >> 8); f.write(p.length & 0xff); }  // our messages are < 64KB
            byte[] m = new byte[4];
            rnd.nextBytes(m);
            f.write(m, 0, 4);
            for (int i = 0; i < p.length; i++) f.write(p[i] ^ m[i & 3]);
            out.write(f.toByteArray());
            out.flush();
        }

        String read() throws IOException {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                long len = b1 & 0x7f;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
                byte[] p = new byte[(int) len];
                in.readFully(p);
                if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                int op = b0 & 0x0f;
                if (op == 8) throw new EOFException("TV closed the connection");
                if (op == 9) { frame(0x8A, p); continue; }  // ping -> pong
                if (op == 10) continue;
                msg.write(p, 0, p.length);
                if ((b0 & 0x80) != 0) return msg.toString("UTF-8");
            }
        }

        void close() { try { s.close(); } catch (IOException ignored) {} }
    }
}

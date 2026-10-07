package app.lgremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
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

    static final int BG = 0xFF0E1013, SURFACE = 0xFF1B1E24, RAISED = 0xFF2A2E37, TEXT = 0xFFECEEF2, MUTED = 0xFF8B919C,
        ACCENT = 0xFFD6004B, RED = 0xFFE5484D, GREEN = 0xFF2FA36B;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        ip = prefs.getString("ip", "");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(24));

        String ver = "";
        try { ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        TextView title = text("LG Remote", 22, TEXT), version = text(ver, 13, MUTED);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        version.setPadding(dp(8), 0, 0, dp(3));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.BOTTOM);
        header.addView(title);
        header.addView(version);
        add(col, header, 48);

        ipField = new EditText(this);
        ipField.setHint("TV IP address");
        ipField.setText(ip);
        ipField.setSingleLine();
        ipField.setTextColor(TEXT);
        ipField.setHintTextColor(MUTED);
        ipField.setBackground(fill(SURFACE, dp(14)));
        ipField.setPadding(dp(14), 0, dp(14), 0);
        add(col, line(false, ipField, btn("Find", this::find),
            paint(btn("Connect", () -> { request("ssap://system.notifications/createToast", new JSONObject().put("message", "Remote connected")); say("Connected"); }), ACCENT, 14)), 48);
        status = text(prefs.contains("key") ? "Paired. Tap any button." : "Tap Find (or type the TV's IP), then Connect.", 13, MUTED);
        status.setPadding(dp(6), 0, dp(6), 0);
        col.addView(status);

        add(col, line(false, paint(btn("Power off", () -> request("ssap://system/turnOff", null)), RED, 999),
            paint(btn("Power on", this::wake), GREEN, 999), paint(key("Mute", "MUTE"), SURFACE, 999)), 48);

        LinearLayout pad = line(true,
            line(false, space(), arrow("▲", "UP"), space()),
            line(false, arrow("◀", "LEFT"), paint(key("OK", "ENTER"), ACCENT, 999), arrow("▶", "RIGHT")),
            line(false, space(), arrow("▼", "DOWN"), space()));
        pad.setBackground(fill(SURFACE, dp(999)));
        pad.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(dp(248), dp(248));
        padLp.gravity = Gravity.CENTER_HORIZONTAL;
        padLp.topMargin = dp(20);
        col.addView(pad, padLp);

        LinearLayout nav = line(true, line(false, key("Back", "BACK"), key("Home", "HOME")),
                                      line(false, key("Menu", "MENU"), key("Exit", "EXIT")));
        nav.setTag("wide");
        add(col, line(false,
            rocker("VOL", () -> request("ssap://audio/volumeUp", null), () -> request("ssap://audio/volumeDown", null)),
            nav,
            rocker("CH", () -> button("CHANNELUP"), () -> button("CHANNELDOWN"))), 150);

        add(col, line(false, key("◀◀", "REWIND"), key("▶", "PLAY"), key("▮▮", "PAUSE"), key("▶▶", "FASTFORWARD")), 52);
        add(col, line(false, app("Netflix", "netflix", 0xFFE50914), app("YouTube", "youtube.leanback.v4", 0xFFFF4E45),
            app("Prime", "amazon", 0xFF1FA2E1)), 52);

        LinearLayout nums = line(true,
            line(false, key("1", "1"), key("2", "2"), key("3", "3")),
            line(false, key("4", "4"), key("5", "5"), key("6", "6")),
            line(false, key("7", "7"), key("8", "8"), key("9", "9")),
            line(false, key("Info", "INFO"), key("0", "0"), key("Guide", "GUIDE")));
        nums.setVisibility(View.GONE);
        TextView more = paint(text("Number pad  ▾", 14, MUTED), 0, 14);
        more.setOnClickListener(v -> {
            boolean show = nums.getVisibility() != View.VISIBLE;
            nums.setVisibility(show ? View.VISIBLE : View.GONE);
            more.setText(show ? "Number pad  ▴" : "Number pad  ▾");
        });
        add(col, more, 44);
        add(col, nums, 224);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
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

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    void add(LinearLayout col, View v, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(heightDp));
        lp.topMargin = dp(12);
        col.addView(v, lp);
    }

    /** Children share the space equally (EditText and "wide" views get double). */
    LinearLayout line(boolean vertical, View... views) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (View v : views) {
            float w = v instanceof EditText || "wide".equals(v.getTag()) ? 2 : 1;
            LinearLayout.LayoutParams lp = vertical ? new LinearLayout.LayoutParams(-1, 0, w) : new LinearLayout.LayoutParams(0, -1, w);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            l.addView(v, lp);
        }
        return l;
    }

    View space() { return new View(this); }

    static GradientDrawable fill(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);  // larger than half the view = pill / circle
        return g;
    }

    <T extends View> T paint(T v, int color, int radiusDp) {
        float r = dp(radiusDp);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill(color, r), fill(0xFFFFFFFF, r)));
        return v;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    TextView btn(String label, Task t) {
        TextView b = paint(text(label, 15, TEXT), RAISED, 14);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); act(t); });
        return b;
    }

    TextView key(String label, String name) { return btn(label, () -> button(name)); }

    TextView arrow(String label, String name) { return paint(key(label, name), 0, 999); }

    TextView app(String label, String id, int brand) {
        TextView b = paint(btn(label, () -> request("ssap://system.launcher/launch", new JSONObject().put("id", id))), SURFACE, 14);
        b.setTextColor(brand);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    /** Vertical + / label / − pill, like the volume and channel rockers on the real remote. */
    LinearLayout rocker(String label, Task up, Task down) {
        TextView plus = paint(btn("+", up), 0, 999), minus = paint(btn("−", down), 0, 999), l = text(label, 11, MUTED);
        plus.setTextSize(22);
        minus.setTextSize(22);
        l.setGravity(Gravity.CENTER);
        LinearLayout r = line(true, plus, l, minus);
        r.setBackground(fill(SURFACE, dp(999)));
        return r;
    }

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

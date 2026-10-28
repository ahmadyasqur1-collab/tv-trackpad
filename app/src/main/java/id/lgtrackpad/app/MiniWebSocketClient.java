package id.lgtrackpad.app;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MiniWebSocketClient {
    public interface Listener {
        void onOpen();
        void onMessage(String text);
        void onClose();
        void onError(Exception e);
    }

    private final URI uri;
    private final Listener listener;
    private Socket socket;
    private OutputStream out;
    private volatile boolean closed = false;
    private final ExecutorService writePool = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    public MiniWebSocketClient(String urlStr, Listener listener) throws Exception {
        this.uri = new URI(urlStr);
        this.listener = listener;
    }

    public void connect() throws Exception {
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) port = secure ? 443 : 80;

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 5000);
        raw.setSoTimeout(8000);

        if (secure) {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            SSLSocketFactory factory = sc.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(raw, host, port, true);
            sslSocket.startHandshake();
            this.socket = sslSocket;
        } else {
            this.socket = raw;
        }

        InputStream in = socket.getInputStream();
        out = socket.getOutputStream();

        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        String req = "GET " + path + " HTTP/1.1\r\n" +
                     "Host: " + host + ":" + port + "\r\n" +
                     "Upgrade: websocket\r\n" +
                     "Connection: Upgrade\r\n" +
                     "Sec-WebSocket-Key: " + key + "\r\n" +
                     "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read HTTP response header
        ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
        int b;
        int state = 0;
        while ((b = in.read()) != -1) {
            headBuf.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = 0;
            if (state == 4) break;
        }
        String headerStr = new String(headBuf.toByteArray(), StandardCharsets.UTF_8);
        String firstLine = headerStr.split("\r\n")[0];
        if (!firstLine.contains(" 101 ")) {
            close();
            throw new Exception("Handshake ditolak: " + firstLine);
        }

        socket.setSoTimeout(0);
        if (listener != null) listener.onOpen();

        // Start reader thread
        new Thread(() -> readLoop(in), "WS-Reader").start();
    }

    private void readLoop(InputStream in) {
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        try {
            while (!closed) {
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;

                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;

                if (len == 126) {
                    len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
                }

                byte[] maskKey = null;
                if (masked) {
                    maskKey = new byte[4];
                    readFully(in, maskKey);
                }

                byte[] payload = new byte[(int) len];
                readFully(in, payload);
                if (masked && maskKey != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];
                }

                if (op == 8) { // Close
                    break;
                } else if (op == 9) { // Ping -> Pong
                    sendFrame(10, payload);
                } else if (op == 10) { // Pong
                    continue;
                } else {
                    if (op == 1 || op == 2) frag.reset();
                    frag.write(payload);
                    if (fin && listener != null) {
                        String text = new String(frag.toByteArray(), StandardCharsets.UTF_8);
                        frag.reset();
                        listener.onMessage(text);
                    }
                }
            }
        } catch (Exception e) {
            if (!closed && listener != null) listener.onError(e);
        } finally {
            boolean wasClosed = closed;
            close();
            if (!wasClosed && listener != null) listener.onClose();
        }
    }

    private void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r == -1) throw new Exception("EOF");
            off += r;
        }
    }

    public void send(String text) {
        if (closed) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        sendFrame(1, payload);
    }

    private void sendFrame(int opcode, byte[] payload) {
        writePool.execute(() -> {
            if (closed || out == null) return;
            try {
                int len = payload.length;
                ByteArrayOutputStream frame = new ByteArrayOutputStream(len + 14);
                frame.write(0x80 | opcode);
                if (len < 126) {
                    frame.write(0x80 | len);
                } else if (len < 65536) {
                    frame.write(0x80 | 126);
                    frame.write((len >> 8) & 0xff);
                    frame.write(len & 0xff);
                } else {
                    frame.write(0x80 | 127);
                    for (int i = 7; i >= 0; i--) frame.write((len >> (8 * i)) & 0xff);
                }
                byte[] mask = new byte[4];
                random.nextBytes(mask);
                frame.write(mask);
                byte[] maskedPayload = new byte[len];
                for (int i = 0; i < len; i++) maskedPayload[i] = (byte) (payload[i] ^ mask[i % 4]);
                frame.write(maskedPayload);
                synchronized (this) {
                    out.write(frame.toByteArray());
                    out.flush();
                }
            } catch (Exception e) {
                close();
            }
        });
    }

    public void close() {
        closed = true;
        writePool.shutdownNow();
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}

package com.neonimposter;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LAN transport. The host runs a TCP server plus a UDP beacon; clients listen for beacons
 * and connect over TCP. Messages are '|'-separated text lines; free text is base64.
 * Network threads only parse and enqueue — all game logic runs on the game thread.
 */
final class Net {
    static final int TCP_PORT = 47787;
    static final int UDP_PORT = 47788;
    static final int MAX_PLAYERS = 10;

    static final class Msg {
        final int from;
        final String[] f;

        Msg(int from, String... f) {
            this.from = from;
            this.f = f;
        }
    }

    static final class Found {
        String ip, name, diff;
        int players;
        boolean open;
        long seen;
    }

    private static final class Conn {
        final Socket s;
        final PrintWriter out;

        Conn(Socket s) throws Exception {
            this.s = s;
            out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void send(String line) {
            out.print(line);
            out.print('\n');
            out.flush();
        }

        void close() {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    final boolean host;
    final ConcurrentLinkedQueue<Msg> inbox = new ConcurrentLinkedQueue<>();
    final ConcurrentHashMap<String, Found> found = new ConcurrentHashMap<>();
    volatile boolean alive = true;
    volatile boolean accepting = true;
    volatile String beacon = "";

    private final ConcurrentHashMap<Integer, Conn> conns = new ConcurrentHashMap<>();
    private volatile Conn toHost;
    private ServerSocket server;
    private DatagramSocket udp;
    private WifiManager.MulticastLock lock;

    private Net(boolean host, Context ctx) {
        this.host = host;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("neonimposter");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    static Net host(Context ctx) {
        Net n = new Net(true, ctx);
        n.thread("tcp-accept", n::acceptLoop);
        n.thread("udp-beacon", n::beaconLoop);
        return n;
    }

    static Net client(Context ctx) {
        Net n = new Net(false, ctx);
        n.thread("udp-discover", n::discoverLoop);
        return n;
    }

    private void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    // ── host ──────────────────────────────────────────────────────────────

    private void acceptLoop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(TCP_PORT));
        } catch (Exception e) {
            inbox.add(new Msg(-1, "ERR", "Can't open port " + TCP_PORT + ": " + e.getMessage()));
            return;
        }
        while (alive) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                final Conn c = new Conn(s);
                int id = -1;
                for (int i = 1; i < MAX_PLAYERS && accepting; i++) {
                    if (!conns.containsKey(i)) {
                        id = i;
                        break;
                    }
                }
                if (id < 0) {
                    c.send("FULL");
                    c.close();
                    continue;
                }
                conns.put(id, c);
                final int fid = id;
                thread("tcp-peer-" + id, () -> readLoop(c, fid));
            } catch (Exception e) {
                if (!alive) return;
            }
        }
    }

    private void beaconLoop() {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            while (alive) {
                byte[] data = beacon.getBytes(StandardCharsets.UTF_8);
                if (data.length > 0) {
                    for (InetAddress a : broadcastAddrs()) {
                        try {
                            ds.send(new DatagramPacket(data, data.length, a, UDP_PORT));
                        } catch (Exception ignored) {
                        }
                    }
                }
                Thread.sleep(900);
            }
        } catch (Exception ignored) {
        }
    }

    void sendTo(int id, String line) {
        Conn c = conns.get(id);
        if (c == null) return;
        try {
            c.send(line);
        } catch (Exception e) {
            c.close();
        }
    }

    /** Host: send to every client except {@code except} (-1 for all). */
    void broadcast(String line, int except) {
        for (Integer id : conns.keySet()) if (id != except) sendTo(id, line);
    }

    void kick(int id) {
        Conn c = conns.remove(id);
        if (c != null) c.close();
    }

    // ── client ────────────────────────────────────────────────────────────

    private void discoverLoop() {
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.setBroadcast(true);
            udp.bind(new InetSocketAddress(UDP_PORT));
            udp.setSoTimeout(1000);
        } catch (Exception e) {
            inbox.add(new Msg(-1, "ERR", "Discovery unavailable: " + e.getMessage()));
            return;
        }
        byte[] buf = new byte[512];
        while (alive) {
            try {
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                udp.receive(pk);
                String[] f = new String(pk.getData(), 0, pk.getLength(), StandardCharsets.UTF_8).split("\\|", -1);
                if (f.length < 6 || !f[0].equals("NEONIMP")) continue;
                String ip = pk.getAddress().getHostAddress();
                Found fd = new Found();
                fd.ip = ip;
                fd.name = dec(f[2]);
                fd.players = parse(f[3]);
                fd.diff = f[4];
                fd.open = f[5].equals("1");
                fd.seen = System.currentTimeMillis();
                found.put(ip, fd);
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (!alive) return;
            }
        }
    }

    void connect(final String ip) {
        thread("tcp-connect", () -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, TCP_PORT), 4000);
                s.setTcpNoDelay(true);
                Conn c = new Conn(s);
                toHost = c;
                inbox.add(new Msg(0, "CONNECTED"));
                readLoop(c, 0);
            } catch (Exception e) {
                inbox.add(new Msg(0, "CONNFAIL", String.valueOf(e.getMessage())));
            }
        });
    }

    boolean connected() {
        return toHost != null;
    }

    void disconnect() {
        Conn c = toHost;
        toHost = null;
        if (c != null) c.close();
    }

    /** Client: send a line to the host. */
    void send(String line) {
        Conn c = toHost;
        if (c == null) return;
        try {
            c.send(line);
        } catch (Exception e) {
            c.close();
        }
    }

    // ── shared ────────────────────────────────────────────────────────────

    private void readLoop(Conn c, int id) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (alive && (line = in.readLine()) != null) {
                if (!line.isEmpty()) inbox.add(new Msg(id, line.split("\\|", -1)));
            }
        } catch (Exception ignored) {
        }
        c.close();
        if (host) {
            if (conns.remove(id, c)) inbox.add(new Msg(id, "LEFT"));
        } else if (toHost == c) {
            toHost = null;
            inbox.add(new Msg(0, "HOSTGONE"));
        }
    }

    void close() {
        alive = false;
        for (Conn c : conns.values()) c.close();
        conns.clear();
        disconnect();
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (udp != null) udp.close();
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }

    static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<InetAddress> broadcastAddrs() {
        LinkedHashSet<InetAddress> out = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out);
    }

    static String enc(String s) {
        return Wire.enc(s);
    }

    static String dec(String s) {
        return Wire.dec(s);
    }

    static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }
}

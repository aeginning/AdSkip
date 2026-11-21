package com.aeginning.adskip.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.ServiceInfo;

import com.aeginning.adskip.MainActivity;
import com.aeginning.adskip.db.RuleDatabase;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** DNS-only VPN: routes only the synthetic DNS resolver address, not all device traffic. */
public final class DnsFilterVpnService extends VpnService {
    public static final String ACTION_START = "com.aeginning.adskip.DNS_START";
    public static final String ACTION_STOP = "com.aeginning.adskip.DNS_STOP";
    private static final String CHANNEL_ID = "adskip_dns";
    private static final int NOTIFICATION_ID = 1701;
    private static final String LOCAL_DNS_V4 = "10.111.0.2";
    private static final String LOCAL_DNS_V6 = "fd00:ad5:1::2";
    private static final String[] UPSTREAM_DNS = {
            "1.1.1.1", "2606:4700:4700::1111", "8.8.8.8", "2001:4860:4860::8888"
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private static volatile boolean sRunning;
    private ParcelFileDescriptor tunnel;

    public static boolean isRunning() { return sRunning; }
    private RuleDatabase database;
    private final DnsResponseCache responseCache = new DnsResponseCache();

    @Override public void onCreate() {
        super.onCreate();
        database = new RuleDatabase(this);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopFiltering();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startFiltering();
        return START_NOT_STICKY;
    }

    private void startFiltering() {
        try {
            Builder builder = new Builder()
                    .setSession("AdSkip DNS Filter")
                    .setMtu(1500)
                    .addAddress("10.111.0.1", 32)
                    .addDnsServer(LOCAL_DNS_V4)
                    .addRoute(LOCAL_DNS_V4, 32)
                    .addAddress("fd00:ad5:1::1", 128)
                    .addDnsServer(LOCAL_DNS_V6)
                    .addRoute(LOCAL_DNS_V6, 128)
                    .addDisallowedApplication(getPackageName());
            tunnel = builder.establish();
            if (tunnel == null) {
                database.log("VPN", "", "ERROR", "VpnService.Builder.establish returned null");
                stopForeground(true);
                stopSelf();
                return;
            }
            // Establish the VPN interface before declaring the systemExempted FGS type;
            // Android requires this service to be an authorized/active VPN app.
            startForegroundCompat();
            running = true;
            sRunning = true;
            getSharedPreferences("adskip_settings", MODE_PRIVATE).edit().putBoolean("dns_active", true).apply();
            database.log("VPN", "", "START", "DNS-only IPv4/IPv6 UDP/53 filtering; upstream TCP fallback for truncated UDP responses");
            worker.execute(this::readPackets);
        } catch (Exception e) {
            database.log("VPN", "", "ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            stopFiltering();
            stopForeground(true);
            stopSelf();
        }
    }

    private void readPackets() {
        byte[] buffer = new byte[32767];
        try (FileInputStream input = new FileInputStream(tunnel.getFileDescriptor());
             FileOutputStream output = new FileOutputStream(tunnel.getFileDescriptor())) {
            while (running && !Thread.currentThread().isInterrupted()) {
                int length = input.read(buffer);
                if (length < 0) throw new java.io.IOException("TUN interface closed unexpectedly");
                if (length == 0) continue;
                DnsPacketCodec.Query query = DnsPacketCodec.parseQuery(buffer, length);
                if (query == null) continue;
                byte[] dnsResponse;
                if (database.isDomainBlocked(query.domain)) {
                    dnsResponse = DnsPacketCodec.blockedNxDomain(query.dnsPayload);
                    database.log("DNS", query.domain, "BLOCK", "Matched local domain rule");
                } else {
                    dnsResponse = responseCache.get(query.dnsPayload, SystemClock.elapsedRealtime());
                    if (dnsResponse == null) {
                        dnsResponse = forwardDns(query.dnsPayload);
                        if (dnsResponse == null) {
                            database.log("DNS", query.domain, "ERROR", "All configured upstream DNS transports failed; returned SERVFAIL");
                            dnsResponse = DnsPacketCodec.servFail(query.dnsPayload);
                            if (dnsResponse == null) continue;
                        } else {
                            responseCache.put(query.dnsPayload, dnsResponse, SystemClock.elapsedRealtime());
                        }
                    }
                }
                byte[] responsePacket = DnsPacketCodec.buildResponse(query, dnsResponse);
                if (responsePacket != null) output.write(responsePacket);
            }
        } catch (Exception e) {
            if (running) {
                database.log("VPN", "", "ERROR", "Packet loop: " + e.getClass().getSimpleName());
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopFiltering();
                    stopSelf();
                });
            }
        }
    }

    private byte[] forwardDns(byte[] query) {
        for (String server : UPSTREAM_DNS) {
            try {
                InetAddress address = InetAddress.getByName(server);
                byte[] response = forwardDnsUdp(query, address);
                if (response == null || !DnsPacketCodec.isValidResponse(query, response)) continue;
                if (DnsPacketCodec.isTruncatedResponse(response) || response.length > 1200) {
                    byte[] tcpResponse = forwardDnsTcp(query, address);
                    if (tcpResponse != null && DnsPacketCodec.isValidResponse(query, tcpResponse)
                            && !DnsPacketCodec.isTruncatedResponse(tcpResponse)) return tcpResponse;
                    continue;
                }
                return response;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private byte[] forwardDnsUdp(byte[] query, InetAddress address) {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (!protect(socket)) return null;
            socket.setSoTimeout(1800);
            socket.connect(address, 53); // Restrict replies to the selected upstream endpoint.
            DatagramPacket request = new DatagramPacket(query, query.length);
            socket.send(request);
            byte[] response = new byte[4096];
            DatagramPacket reply = new DatagramPacket(response, response.length);
            socket.receive(reply);
            byte[] exact = new byte[reply.getLength()];
            System.arraycopy(reply.getData(), reply.getOffset(), exact, 0, reply.getLength());
            return exact;
        } catch (Exception ignored) { return null; }
    }

    /** RFC 7766 length-prefixed TCP retry for an upstream UDP response with TC=1. */
    private byte[] forwardDnsTcp(byte[] query, InetAddress address) {
        try (Socket socket = new Socket()) {
            if (!protect(socket)) return null;
            socket.connect(new InetSocketAddress(address, 53), 1800);
            socket.setSoTimeout(2200);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.writeShort(query.length);
            out.write(query);
            out.flush();
            int responseLength = in.readUnsignedShort();
            if (responseLength < 12 || responseLength > 65535) return null;
            byte[] response = new byte[responseLength];
            in.readFully(response);
            return response;
        } catch (Exception ignored) { return null; }
    }

    private void stopFiltering() {
        boolean wasActive = running || tunnel != null;
        running = false;
        sRunning = false;
        getSharedPreferences("adskip_settings", MODE_PRIVATE).edit().putBoolean("dns_active", false).apply();
        if (tunnel != null) {
            try { tunnel.close(); } catch (Exception ignored) { }
            tunnel = null;
        }
        if (wasActive && database != null) database.log("VPN", "", "STOP", "DNS filter stopped");
        stopForeground(true);
    }

    @Override public void onRevoke() {
        stopFiltering();
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopFiltering();
        worker.shutdownNow();
        if (database != null) database.close();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "AdSkip DNS 过滤", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示 DNS 广告域名过滤运行状态");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        Notification notification = builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("AdSkip DNS 过滤已启动")
                .setContentText("过滤本地 IPv4 / IPv6 DNS UDP 查询")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}

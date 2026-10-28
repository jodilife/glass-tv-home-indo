package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WallpaperUploadServer {
    private static final String TAG = "TvHome.UploadServer";

    public interface UploadListener {
        void onUploaded(File imageFile);
        default void onDeleted(File file) {}
        default void onSelected(File file) {}
        default void onSlideshowMode() {}
        default void onCleared() {}
    }

    public interface OnWallpaperUploadedListener extends UploadListener {}

    private final Context context;
    private final int port;
    private final UploadListener listener;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private Thread serverThread;

    public WallpaperUploadServer(Context context, int port, UploadListener listener) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.listener = listener;
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            Collections.sort(interfaces, (a, b) -> {
                String an = a.getName().toLowerCase(Locale.ROOT);
                String bn = b.getName().toLowerCase(Locale.ROOT);
                if (an.startsWith("wlan") || an.startsWith("eth")) return -1;
                if (bn.startsWith("wlan") || bn.startsWith("eth")) return 1;
                return an.compareTo(bn);
            });
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                List<InetAddress> addrs = Collections.list(nif.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving IP: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            running = true;

            serverThread = new Thread(() -> {
                Log.i(TAG, "Server started on port " + port);
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client), "upload-client").start();
                    } catch (Exception e) {
                        if (!running) break;
                        Log.w(TAG, "Accept error: " + e.getMessage());
                    }
                }
            }, "wallpaper-http-server");
            serverThread.setDaemon(true);
            serverThread.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server on port " + port + ": " + e.getMessage());
            running = false;
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        Log.i(TAG, "Server stopped");
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30000); // 30 seconds timeout
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev3 = 0, prev2 = 0, prev1 = 0, b;
            while ((b = in.read()) != -1) {
                headerBuf.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }

            String headerText = headerBuf.toString("UTF-8");
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0 || lines[0].isEmpty()) {
                client.close();
                return;
            }

            String requestLine = lines[0];
            Log.d(TAG, "Request: " + requestLine);

            if (requestLine.startsWith("OPTIONS")) {
                String response = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(response.getBytes("UTF-8"));
                out.flush();
            } else if (requestLine.startsWith("GET /thumb")) {
                String fileName = getQueryParam(requestLine, "file");
                serveThumbnail(fileName, out);
            } else if (requestLine.startsWith("GET /api/list")) {
                serveApiList(out);
            } else if (requestLine.startsWith("POST /api/select")) {
                String fileName = getQueryParam(requestLine, "file");
                handleApiSelect(fileName, out);
            } else if (requestLine.startsWith("POST /api/delete")) {
                String fileName = getQueryParam(requestLine, "file");
                handleApiDelete(fileName, out);
            } else if (requestLine.startsWith("POST /api/slideshow")) {
                handleApiSlideshow(out);
            } else if (requestLine.startsWith("POST /api/clear")) {
                handleApiClear(out);
            } else if (requestLine.startsWith("GET")) {
                byte[] pageBytes = getHtmlPage().getBytes("UTF-8");
                String response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=UTF-8\r\n" +
                        "Content-Length: " + pageBytes.length + "\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(response.getBytes("UTF-8"));
                out.write(pageBytes);
                out.flush();
            } else if (requestLine.startsWith("POST /upload")) {
                long contentLength = 0;
                for (String line : lines) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        try {
                            contentLength = Long.parseLong(line.substring(15).trim());
                        } catch (Exception ignored) {}
                        break;
                    }
                }

                if (contentLength > 0 && contentLength < 60 * 1024 * 1024) { // Max 60 MB
                    File customDir = new File(context.getFilesDir(), "custom_wallpapers");
                    if (!customDir.exists()) customDir.mkdirs();

                    File targetFile = new File(customDir, "custom_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(targetFile);

                    byte[] buffer = new byte[8192];
                    long remaining = contentLength;
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buffer.length, remaining);
                        int r = in.read(buffer, 0, toRead);
                        if (r == -1) break;
                        fos.write(buffer, 0, r);
                        remaining -= r;
                    }
                    fos.flush();
                    fos.close();

                    String json = "{\"status\":\"ok\",\"file\":\"" + targetFile.getName() + "\"}";
                    byte[] jsonBytes = json.getBytes("UTF-8");
                    String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json; charset=UTF-8\r\n" +
                            "Content-Length: " + jsonBytes.length + "\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(response.getBytes("UTF-8"));
                    out.write(jsonBytes);
                    out.flush();

                    if (listener != null && targetFile.exists() && targetFile.length() > 0) {
                        listener.onUploaded(targetFile);
                    }
                } else {
                    String error = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nInvalid file size";
                    out.write(error.getBytes("UTF-8"));
                    out.flush();
                }
            } else {
                String notFound = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\nNot Found";
                out.write(notFound.getBytes("UTF-8"));
                out.flush();
            }

            out.close();
            in.close();
            client.close();
        } catch (Exception e) {
            Log.w(TAG, "Client handle exception: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private String getQueryParam(String requestLine, String param) {
        try {
            int qIdx = requestLine.indexOf('?');
            if (qIdx == -1) return "";
            int spIdx = requestLine.indexOf(' ', qIdx);
            String query = spIdx > qIdx ? requestLine.substring(qIdx + 1, spIdx) : requestLine.substring(qIdx + 1);
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(param)) {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void serveThumbnail(String fileName, OutputStream out) {
        try {
            File customDir = new File(context.getFilesDir(), "custom_wallpapers");
            String safeName = fileName.replaceAll("[^a-zA-Z0-9._-]", "");
            File target = new File(customDir, safeName);
            if (!target.exists() || !target.isFile()) {
                String notFound = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n";
                out.write(notFound.getBytes("UTF-8"));
                out.flush();
                return;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(target.getAbsolutePath(), opts);

            int sample = 1;
            while (opts.outWidth / (sample * 2) >= 200 && opts.outHeight / (sample * 2) >= 150) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bmp = BitmapFactory.decodeFile(target.getAbsolutePath(), opts);
            if (bmp == null) {
                String err = "HTTP/1.1 500 Error\r\nConnection: close\r\n\r\n";
                out.write(err.getBytes("UTF-8"));
                out.flush();
                return;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            bmp.recycle();
            byte[] bytes = baos.toByteArray();

            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Cache-Control: max-age=3600\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(response.getBytes("UTF-8"));
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "Thumbnail error: " + e.getMessage());
        }
    }

    private void serveApiList(OutputStream out) {
        try {
            File customDir = new File(context.getFilesDir(), "custom_wallpapers");
            File[] files = customDir.listFiles((dir, name) -> name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg"));
            JSONArray arr = new JSONArray();
            String activeSingle = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                    .getString("custom_single_file", "");
            boolean isSingleMode = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                    .getBoolean("custom_single_mode", false);

            if (files != null && files.length > 0) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                SimpleDateFormat sdf = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
                for (File f : files) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", f.getName());
                    double kb = f.length() / 1024.0;
                    if (kb > 1024) {
                        obj.put("size", String.format(Locale.ROOT, "%.1f MB", kb / 1024.0));
                    } else {
                        obj.put("size", String.format(Locale.ROOT, "%.0f KB", kb));
                    }
                    obj.put("date", sdf.format(new Date(f.lastModified())));
                    obj.put("active", isSingleMode && f.getName().equals(activeSingle));
                    arr.put(obj);
                }
            }

            byte[] jsonBytes = arr.toString().getBytes("UTF-8");
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n" +
                    "Content-Length: " + jsonBytes.length + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(response.getBytes("UTF-8"));
            out.write(jsonBytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "List API error: " + e.getMessage());
        }
    }

    private void handleApiSelect(String fileName, OutputStream out) {
        try {
            File customDir = new File(context.getFilesDir(), "custom_wallpapers");
            String safeName = fileName.replaceAll("[^a-zA-Z0-9._-]", "");
            File target = new File(customDir, safeName);
            if (target.exists() && listener != null) {
                listener.onSelected(target);
            }
            sendSimpleJsonResponse(out, "{\"status\":\"ok\"}");
        } catch (Exception ignored) {}
    }

    private void handleApiDelete(String fileName, OutputStream out) {
        try {
            File customDir = new File(context.getFilesDir(), "custom_wallpapers");
            String safeName = fileName.replaceAll("[^a-zA-Z0-9._-]", "");
            File target = new File(customDir, safeName);
            if (target.exists()) {
                target.delete();
                if (listener != null) listener.onDeleted(target);
            }
            sendSimpleJsonResponse(out, "{\"status\":\"ok\"}");
        } catch (Exception ignored) {}
    }

    private void handleApiSlideshow(OutputStream out) {
        try {
            if (listener != null) listener.onSlideshowMode();
            sendSimpleJsonResponse(out, "{\"status\":\"ok\"}");
        } catch (Exception ignored) {}
    }

    private void handleApiClear(OutputStream out) {
        try {
            File customDir = new File(context.getFilesDir(), "custom_wallpapers");
            File[] files = customDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            if (listener != null) listener.onCleared();
            sendSimpleJsonResponse(out, "{\"status\":\"ok\"}");
        } catch (Exception ignored) {}
    }

    private void sendSimpleJsonResponse(OutputStream out, String json) throws Exception {
        byte[] bytes = json.getBytes("UTF-8");
        String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        out.write(response.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private String getHtmlPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"id\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "  <title>Glass TV • Unggah Wallpaper</title>\n" +
                "  <style>\n" +
                "    * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
                "    body {\n" +
                "      background: #0b0f19;\n" +
                "      color: #f1f5f9;\n" +
                "      font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif;\n" +
                "      min-height: 100vh;\n" +
                "      display: flex;\n" +
                "      flex-direction: column;\n" +
                "      align-items: center;\n" +
                "      padding: 24px 16px 40px;\n" +
                "    }\n" +
                "    .card {\n" +
                "      width: 100%;\n" +
                "      max-width: 440px;\n" +
                "      background: rgba(255, 255, 255, 0.05);\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.12);\n" +
                "      border-radius: 28px;\n" +
                "      padding: 26px 20px;\n" +
                "      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.2);\n" +
                "      backdrop-filter: blur(24px);\n" +
                "      -webkit-backdrop-filter: blur(24px);\n" +
                "      text-align: center;\n" +
                "    }\n" +
                "    .badge {\n" +
                "      display: inline-block;\n" +
                "      padding: 6px 14px;\n" +
                "      background: rgba(56, 189, 248, 0.15);\n" +
                "      color: #38bdf8;\n" +
                "      border: 1px solid rgba(56, 189, 248, 0.3);\n" +
                "      border-radius: 9999px;\n" +
                "      font-size: 12px;\n" +
                "      font-weight: 600;\n" +
                "      letter-spacing: 0.5px;\n" +
                "      margin-bottom: 14px;\n" +
                "      text-transform: uppercase;\n" +
                "    }\n" +
                "    .watermark {\n" +
                "      margin-top: 18px;\n" +
                "      font-size: 13px;\n" +
                "      font-weight: 500;\n" +
                "      color: #94a3b8;\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      justify-content: center;\n" +
                "      gap: 6px;\n" +
                "      letter-spacing: 0.3px;\n" +
                "    }\n" +
                "    .watermark strong {\n" +
                "      color: #38bdf8;\n" +
                "      font-weight: 700;\n" +
                "    }\n" +
                "    h1 {\n" +
                "      font-size: 23px;\n" +
                "      font-weight: 700;\n" +
                "      margin-bottom: 8px;\n" +
                "      color: #ffffff;\n" +
                "    }\n" +
                "    p.subtitle {\n" +
                "      font-size: 13.5px;\n" +
                "      color: #94a3b8;\n" +
                "      margin-bottom: 20px;\n" +
                "      line-height: 1.5;\n" +
                "    }\n" +
                "    .dropzone {\n" +
                "      border: 2px dashed rgba(255, 255, 255, 0.2);\n" +
                "      border-radius: 20px;\n" +
                "      padding: 24px 16px;\n" +
                "      cursor: pointer;\n" +
                "      transition: all 0.2s ease;\n" +
                "      background: rgba(0, 0, 0, 0.2);\n" +
                "      margin-bottom: 18px;\n" +
                "    }\n" +
                "    .dropzone:active {\n" +
                "      border-color: #38bdf8;\n" +
                "      background: rgba(56, 189, 248, 0.08);\n" +
                "    }\n" +
                "    .dropzone-icon {\n" +
                "      font-size: 38px;\n" +
                "      margin-bottom: 8px;\n" +
                "    }\n" +
                "    .dropzone-text {\n" +
                "      font-size: 15px;\n" +
                "      font-weight: 600;\n" +
                "      color: #e2e8f0;\n" +
                "      margin-bottom: 4px;\n" +
                "    }\n" +
                "    .dropzone-hint {\n" +
                "      font-size: 12px;\n" +
                "      color: #64748b;\n" +
                "    }\n" +
                "    #preview-container {\n" +
                "      display: none;\n" +
                "      margin-bottom: 18px;\n" +
                "      position: relative;\n" +
                "      border-radius: 18px;\n" +
                "      overflow: hidden;\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.15);\n" +
                "      background: #000;\n" +
                "      max-height: 220px;\n" +
                "    }\n" +
                "    #preview {\n" +
                "      width: 100%;\n" +
                "      height: 220px;\n" +
                "      object-fit: cover;\n" +
                "      display: block;\n" +
                "    }\n" +
                "    .change-btn {\n" +
                "      position: absolute;\n" +
                "      bottom: 10px;\n" +
                "      right: 10px;\n" +
                "      background: rgba(0, 0, 0, 0.7);\n" +
                "      backdrop-filter: blur(8px);\n" +
                "      color: #fff;\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.3);\n" +
                "      padding: 6px 12px;\n" +
                "      border-radius: 12px;\n" +
                "      font-size: 12px;\n" +
                "      cursor: pointer;\n" +
                "    }\n" +
                "    input[type=\"file\"] { display: none; }\n" +
                "    .btn-submit {\n" +
                "      width: 100%;\n" +
                "      background: linear-gradient(135deg, #0ea5e9 0%, #2563eb 100%);\n" +
                "      color: #ffffff;\n" +
                "      border: none;\n" +
                "      border-radius: 16px;\n" +
                "      padding: 15px;\n" +
                "      font-size: 15px;\n" +
                "      font-weight: 600;\n" +
                "      cursor: pointer;\n" +
                "      box-shadow: 0 8px 20px rgba(14, 165, 233, 0.35);\n" +
                "      transition: all 0.2s ease;\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      justify-content: center;\n" +
                "      gap: 8px;\n" +
                "    }\n" +
                "    .btn-submit:disabled {\n" +
                "      opacity: 0.5;\n" +
                "      cursor: not-allowed;\n" +
                "      box-shadow: none;\n" +
                "    }\n" +
                "    .btn-submit:active:not(:disabled) {\n" +
                "      transform: scale(0.98);\n" +
                "    }\n" +
                "    .progress-wrap {\n" +
                "      display: none;\n" +
                "      width: 100%;\n" +
                "      height: 8px;\n" +
                "      background: rgba(255, 255, 255, 0.1);\n" +
                "      border-radius: 9999px;\n" +
                "      margin-top: 16px;\n" +
                "      overflow: hidden;\n" +
                "    }\n" +
                "    .progress-bar {\n" +
                "      width: 0%;\n" +
                "      height: 100%;\n" +
                "      background: linear-gradient(90deg, #38bdf8, #4ade80);\n" +
                "      border-radius: 9999px;\n" +
                "      transition: width 0.2s ease;\n" +
                "    }\n" +
                "    .status-msg {\n" +
                "      margin-top: 14px;\n" +
                "      font-size: 13.5px;\n" +
                "      font-weight: 500;\n" +
                "      display: none;\n" +
                "    }\n" +
                "    .status-msg.success { color: #4ade80; }\n" +
                "    .status-msg.error { color: #f87171; }\n" +
                "    \n" +
                "    /* Manager Gallery Section */\n" +
                "    .gallery-card {\n" +
                "      width: 100%;\n" +
                "      max-width: 440px;\n" +
                "      margin-top: 22px;\n" +
                "      background: rgba(255, 255, 255, 0.05);\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.12);\n" +
                "      border-radius: 28px;\n" +
                "      padding: 22px 18px;\n" +
                "      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.6);\n" +
                "      backdrop-filter: blur(24px);\n" +
                "      -webkit-backdrop-filter: blur(24px);\n" +
                "    }\n" +
                "    .gallery-header {\n" +
                "      display: flex;\n" +
                "      justify-content: space-between;\n" +
                "      align-items: center;\n" +
                "      margin-bottom: 8px;\n" +
                "    }\n" +
                "    .gallery-title {\n" +
                "      font-size: 17px;\n" +
                "      font-weight: 700;\n" +
                "      color: #ffffff;\n" +
                "    }\n" +
                "    .gallery-desc {\n" +
                "      font-size: 12.5px;\n" +
                "      color: #94a3b8;\n" +
                "      text-align: left;\n" +
                "      margin-bottom: 14px;\n" +
                "      line-height: 1.4;\n" +
                "    }\n" +
                "    .btn-slideshow {\n" +
                "      background: rgba(56, 189, 248, 0.18);\n" +
                "      border: 1px solid rgba(56, 189, 248, 0.35);\n" +
                "      color: #38bdf8;\n" +
                "      padding: 6px 12px;\n" +
                "      border-radius: 12px;\n" +
                "      font-size: 12px;\n" +
                "      font-weight: 600;\n" +
                "      cursor: pointer;\n" +
                "      transition: all 0.2s ease;\n" +
                "    }\n" +
                "    .btn-slideshow:active { transform: scale(0.96); }\n" +
                "    .gallery-list {\n" +
                "      display: flex;\n" +
                "      flex-direction: column;\n" +
                "      gap: 10px;\n" +
                "    }\n" +
                "    .photo-row {\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      gap: 10px;\n" +
                "      background: rgba(0, 0, 0, 0.35);\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.08);\n" +
                "      border-radius: 16px;\n" +
                "      padding: 8px 10px;\n" +
                "      transition: all 0.2s ease;\n" +
                "    }\n" +
                "    .photo-row.active {\n" +
                "      border-color: rgba(56, 189, 248, 0.4);\n" +
                "      background: rgba(56, 189, 248, 0.08);\n" +
                "    }\n" +
                "    .photo-thumb {\n" +
                "      width: 60px;\n" +
                "      height: 44px;\n" +
                "      border-radius: 10px;\n" +
                "      object-fit: cover;\n" +
                "      background: #1e293b;\n" +
                "      flex-shrink: 0;\n" +
                "    }\n" +
                "    .photo-meta {\n" +
                "      flex: 1;\n" +
                "      text-align: left;\n" +
                "      min-width: 0;\n" +
                "    }\n" +
                "    .photo-name {\n" +
                "      font-size: 13px;\n" +
                "      font-weight: 600;\n" +
                "      color: #f1f5f9;\n" +
                "      white-space: nowrap;\n" +
                "      overflow: hidden;\n" +
                "      text-overflow: ellipsis;\n" +
                "    }\n" +
                "    .photo-sub {\n" +
                "      font-size: 11px;\n" +
                "      color: #94a3b8;\n" +
                "      margin-top: 2px;\n" +
                "    }\n" +
                "    .photo-btns {\n" +
                "      display: flex;\n" +
                "      gap: 6px;\n" +
                "      flex-shrink: 0;\n" +
                "    }\n" +
                "    .btn-act {\n" +
                "      background: rgba(255, 255, 255, 0.08);\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.15);\n" +
                "      color: #cbd5e1;\n" +
                "      padding: 6px 9px;\n" +
                "      border-radius: 10px;\n" +
                "      font-size: 11.5px;\n" +
                "      font-weight: 500;\n" +
                "      cursor: pointer;\n" +
                "    }\n" +
                "    .btn-act:active { transform: scale(0.95); }\n" +
                "    .btn-act.active {\n" +
                "      background: #0284c7;\n" +
                "      border-color: #38bdf8;\n" +
                "      color: #ffffff;\n" +
                "      font-weight: 700;\n" +
                "    }\n" +
                "    .btn-act.del {\n" +
                "      color: #f87171;\n" +
                "      border-color: rgba(248, 113, 113, 0.3);\n" +
                "      background: rgba(248, 113, 113, 0.1);\n" +
                "    }\n" +
                "    footer {\n" +
                "      margin-top: 24px;\n" +
                "      font-size: 12px;\n" +
                "      color: #475569;\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"card\">\n" +
                "    <div class=\"badge\">Glass TV OS • Versi Indonesia</div>\n" +
                "    <h1>Unggah Wallpaper</h1>\n" +
                "    <p class=\"subtitle\">Pilih foto apa saja dari galeri ponsel Anda untuk langsung ditampilkan di layar Smart TV.</p>\n" +
                "    <div class=\"dropzone\" onclick=\"document.getElementById('fileInput').click()\">\n" +
                "      <div class=\"dropzone-icon\">🖼️</div>\n" +
                "      <div class=\"dropzone-text\">Ketuk untuk Pilih Foto</div>\n" +
                "      <div class=\"dropzone-hint\">Mendukung format JPG, PNG</div>\n" +
                "    </div>\n" +
                "    <input type=\"file\" id=\"fileInput\" accept=\"image/*\" onchange=\"handleFileSelect(event)\">\n" +
                "    <div id=\"preview-container\">\n" +
                "      <img id=\"preview\" alt=\"Pratinjau\">\n" +
                "      <button class=\"change-btn\" onclick=\"document.getElementById('fileInput').click()\">Ganti</button>\n" +
                "    </div>\n" +
                "    <button id=\"uploadBtn\" class=\"btn-submit\" onclick=\"uploadFile()\" disabled>\n" +
                "      <span>Kirim ke TV</span>\n" +
                "    </button>\n" +
                "    <div class=\"progress-wrap\">\n" +
                "      <div id=\"progressBar\" class=\"progress-bar\"></div>\n" +
                "    </div>\n" +
                "    <div id=\"statusMsg\" class=\"status-msg\"></div>\n" +
                "  </div>\n" +
                "  <div class=\"gallery-card\">\n" +
                "    <div class=\"gallery-header\">\n" +
                "      <div class=\"gallery-title\">Kelola Wallpaper</div>\n" +
                "      <button class=\"btn-slideshow\" onclick=\"toggleSlideshow()\">Slideshow</button>\n" +
                "    </div>\n" +
                "    <div class=\"gallery-desc\">Pilih foto aktif untuk ditampilkan atau hapus foto yang tidak diinginkan.</div>\n" +
                "    <div id=\"galleryList\" class=\"gallery-list\">\n" +
                "      <div style=\"color: #64748b; font-size: 13px; padding: 10px;\">Memuat galeri...</div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <footer>Glass TV Home Screen • Indonesia Edition</footer>\n" +
                "  <script>\n" +
                "    let selectedFile = null;\n" +
                "    function handleFileSelect(e) {\n" +
                "      const file = e.target.files[0];\n" +
                "      if (!file) return;\n" +
                "      selectedFile = file;\n" +
                "      const reader = new FileReader();\n" +
                "      reader.onload = function(evt) {\n" +
                "        document.getElementById('preview').src = evt.target.result;\n" +
                "        document.getElementById('preview-container').style.display = 'block';\n" +
                "        document.getElementById('uploadBtn').disabled = false;\n" +
                "      };\n" +
                "      reader.readAsDataURL(file);\n" +
                "    }\n" +
                "    function uploadFile() {\n" +
                "      if (!selectedFile) return;\n" +
                "      const btn = document.getElementById('uploadBtn');\n" +
                "      const progressWrap = document.querySelector('.progress-wrap');\n" +
                "      const progressBar = document.getElementById('progressBar');\n" +
                "      const statusMsg = document.getElementById('statusMsg');\n" +
                "      btn.disabled = true;\n" +
                "      progressWrap.style.display = 'block';\n" +
                "      progressBar.style.width = '10%';\n" +
                "      statusMsg.style.display = 'none';\n" +
                "      const xhr = new XMLHttpRequest();\n" +
                "      xhr.open('POST', '/upload', true);\n" +
                "      xhr.upload.onprogress = function(e) {\n" +
                "        if (e.lengthComputable) {\n" +
                "          const percent = (e.loaded / e.total) * 90;\n" +
                "          progressBar.style.width = percent + '%';\n" +
                "        }\n" +
                "      };\n" +
                "      xhr.onload = function() {\n" +
                "        progressBar.style.width = '100%';\n" +
                "        statusMsg.style.display = 'block';\n" +
                "        if (xhr.status === 200) {\n" +
                "          statusMsg.className = 'status-msg success';\n" +
                "          statusMsg.innerText = 'Berhasil! Wallpaper dikirim ke TV.';\n" +
                "          setTimeout(() => {\n" +
                "            progressWrap.style.display = 'none';\n" +
                "            progressBar.style.width = '0%';\n" +
                "            document.getElementById('preview-container').style.display = 'none';\n" +
                "            selectedFile = null;\n" +
                "            document.getElementById('fileInput').value = '';\n" +
                "            loadGallery();\n" +
                "          }, 1500);\n" +
                "        } else {\n" +
                "          statusMsg.className = 'status-msg error';\n" +
                "          statusMsg.innerText = 'Gagal mengunggah foto. Silakan coba lagi.';\n" +
                "          btn.disabled = false;\n" +
                "        }\n" +
                "      };\n" +
                "      xhr.onerror = function() {\n" +
                "        progressWrap.style.display = 'none';\n" +
                "        statusMsg.style.display = 'block';\n" +
                "        statusMsg.className = 'status-msg error';\n" +
                "        statusMsg.innerText = 'Kesalahan jaringan.';\n" +
                "        btn.disabled = false;\n" +
                "      };\n" +
                "      xhr.send(selectedFile);\n" +
                "    }\n" +
                "    function loadGallery() {\n" +
                "      fetch('/api/list')\n" +
                "        .then(res => res.json())\n" +
                "        .then(data => {\n" +
                "          const list = document.getElementById('galleryList');\n" +
                "          if (!data || data.length === 0) {\n" +
                "            list.innerHTML = '<div style=\"color: #64748b; font-size: 13px; padding: 10px; text-align: center;\">Belum ada foto tersimpan.</div>';\n" +
                "            return;\n" +
                "          }\n" +
                "          let html = '';\n" +
                "          data.forEach(item => {\n" +
                "            html += `<div class=\"photo-row ${item.active ? 'active' : ''}\">\n" +
                "              <img class=\"photo-thumb\" src=\"/thumb?file=${encodeURIComponent(item.name)}\" alt=\"\"> \n" +
                "              <div class=\"photo-meta\">\n" +
                "                <div class=\"photo-name\">${item.name}</div>\n" +
                "                <div class=\"photo-sub\">${item.size} • ${item.date}</div>\n" +
                "              </div>\n" +
                "              <div class=\"photo-btns\">\n" +
                "                <button class=\"btn-act ${item.active ? 'active' : ''}\" onclick=\"selectPhoto('${item.name}')\">${item.active ? 'Aktif' : 'Pilih'}</button>\n" +
                "                <button class=\"btn-act del\" onclick=\"deletePhoto('${item.name}')\">Hapus</button>\n" +
                "              </div>\n" +
                "            </div>`;\n" +
                "          });\n" +
                "          list.innerHTML = html;\n" +
                "        })\n" +
                "        .catch(err => {\n" +
                "          document.getElementById('galleryList').innerHTML = '<div style=\"color: #f87171; font-size: 13px; padding: 10px;\">Gagal memuat galeri.</div>';\n" +
                "        });\n" +
                "    }\n" +
                "    function selectPhoto(name) {\n" +
                "      fetch('/api/select?file=' + encodeURIComponent(name), { method: 'POST' })\n" +
                "        .then(() => loadGallery());\n" +
                "    }\n" +
                "    function deletePhoto(name) {\n" +
                "      if (confirm('Hapus foto ini?')) {\n" +
                "        fetch('/api/delete?file=' + encodeURIComponent(name), { method: 'POST' })\n" +
                "          .then(() => loadGallery());\n" +
                "      }\n" +
                "    }\n" +
                "    function toggleSlideshow() {\n" +
                "      fetch('/api/slideshow', { method: 'POST' })\n" +
                "        .then(() => alert('Mode slideshow diaktifkan di TV!'));\n" +
                "    }\n" +
                "    loadGallery();\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>";
    }
}

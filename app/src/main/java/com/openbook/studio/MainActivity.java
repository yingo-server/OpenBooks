// 路径: app/src/main/java/com/openbook/studio/MainActivity.java
package com.openbook.studio;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Runnable {

    // ======================== 常量 ========================
    private static final String TAG = "OpenBook";
    private static final String CONFIG_URL =
            "https://gitee.com/yingo-server/openbook/raw/master/users/private/0/config.ob";
    private static final String API_BASE = "http://v3.rain.ink/fanqie/";  // 使用 HTTP
    private static final String BASE_DIR = Environment.getExternalStorageDirectory() + "/openbook";
    private static final String CONFIG_DIR = BASE_DIR + "/config/user";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.ob";
    private static final String PROGRESS_FILE = CONFIG_DIR + "/progress.ob";
    private static final String BOOKS_DIR = BASE_DIR + "/books";
    private static final String LOG_DIR = BASE_DIR + "/logs";
    private static final int MAX_CACHED_CHAPTERS = 3;
    private static final int FONT_SIZE = 40;
    private static final int COLS = 6;
    private static final int ROWS = 6;
    private static final int STATUS_HEIGHT = 28;
    private static final int BOTTOM_HEIGHT = 42;
    private static final int CONTENT_TOP = STATUS_HEIGHT;
    private static final int CONTENT_BOTTOM = 240 - BOTTOM_HEIGHT;
    private static final int LIST_ITEM_HEIGHT = 48;
    private static final long LONG_PRESS_DURATION = 1500;
    private static final int TOUCH_SLOP = 10;

    // ======================== UI 组件 ========================
    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private boolean isSurfaceReady = false;
    private boolean isRunning = false;
    private Thread renderThread;
    private final Object lock = new Object();

    // ======================== 状态 ========================
    private static final int STATE_BOOK_LIST = 0;
    private static final int STATE_READING = 1;
    private int currentState = STATE_BOOK_LIST;

    private List<String> bookNames = new ArrayList<>();
    private List<String> bookIds = new ArrayList<>();
    private int bookListScrollOffset = 0;
    private int maxVisibleItems = 0;

    private String currentBookId = null;
    private int currentChapter = 1;
    private int currentPage = 0;
    private int totalPages = 0;
    private char[][] grid = new char[ROWS][COLS];
    private String chapterContent = "";
    private boolean isLoadingChapter = false;
    private String statusMessage = "";

    // ======================== 管理器 ========================
    private ConfigManager configManager;
    private BookManager bookManager;
    private ChapterCache chapterCache;
    private ApiClient apiClient;
    private Logger logger;

    // ======================== 线程 ========================
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ======================== Activity 生命周期 ========================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        surfaceView = new SurfaceView(this);
        holder = surfaceView.getHolder();
        holder.addCallback(this);
        setContentView(surfaceView);

        logger = new Logger();
        logger.log(Logger.INFO, "应用启动");
        configManager = new ConfigManager();
        bookManager = new BookManager();
        chapterCache = new ChapterCache();
        apiClient = new ApiClient();

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                loadConfig();
            }
        }, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (renderThread != null) {
            try {
                renderThread.join(500);
            } catch (InterruptedException ignored) {}
        }
        worker.shutdownNow();
        logger.log(Logger.INFO, "应用退出");
    }

    // ======================== SurfaceHolder.Callback ========================

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isSurfaceReady = true;
        synchronized (lock) {
            lock.notify();
        }
        if (!isRunning) {
            isRunning = true;
            renderThread = new Thread(this);
            renderThread.start();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isSurfaceReady = false;
        isRunning = false;
        synchronized (lock) {
            lock.notify();
        }
    }

    // ======================== 渲染循环 ========================

    @Override
    public void run() {
        while (isRunning) {
            while (!isSurfaceReady && isRunning) {
                synchronized (lock) {
                    try {
                        lock.wait(100);
                    } catch (InterruptedException ignored) {}
                }
            }
            if (!isRunning) break;

            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    drawUI(canvas);
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }
    }

    // ======================== UI 绘制 ========================

    private Paint bgPaint = new Paint();
    private Paint textPaint = new Paint();
    private Paint statusPaint = new Paint();
    private Paint bottomPaint = new Paint();

    private void drawUI(Canvas canvas) {
        bgPaint.setColor(Color.BLACK);
        canvas.drawRect(0, 0, 240, 240, bgPaint);

        statusPaint.setColor(Color.DKGRAY);
        canvas.drawRect(0, 0, 240, STATUS_HEIGHT, statusPaint);
        statusPaint.setColor(Color.WHITE);
        statusPaint.setTextSize(20);
        statusPaint.setAntiAlias(true);
        String left = "OpenBook";
        String right = statusMessage.isEmpty() ? (currentState == STATE_BOOK_LIST ? "选择书籍" : "阅读中") : statusMessage;
        canvas.drawText(left, 4, STATUS_HEIGHT - 6, statusPaint);
        float rightWidth = statusPaint.measureText(right);
        canvas.drawText(right, 240 - rightWidth - 4, STATUS_HEIGHT - 6, statusPaint);

        bottomPaint.setColor(Color.DKGRAY);
        canvas.drawRect(0, 240 - BOTTOM_HEIGHT, 240, 240, bottomPaint);
        bottomPaint.setColor(Color.WHITE);
        bottomPaint.setTextSize(18);
        bottomPaint.setAntiAlias(true);
        String bottomText = "";
        if (currentState == STATE_BOOK_LIST) {
            bottomText = "滑动列表 点击选择";
        } else {
            bottomText = "左半屏上一面  右半屏下一面  长按退出";
        }
        float bottomWidth = bottomPaint.measureText(bottomText);
        canvas.drawText(bottomText, (240 - bottomWidth) / 2, 240 - 10, bottomPaint);

        if (currentState == STATE_BOOK_LIST) {
            drawBookList(canvas);
        } else {
            drawReading(canvas);
        }
    }

    private void drawBookList(Canvas canvas) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28);
        textPaint.setAntiAlias(true);

        int visible = Math.min(bookNames.size() - bookListScrollOffset,
                (CONTENT_BOTTOM - CONTENT_TOP) / LIST_ITEM_HEIGHT);
        if (visible < 0) visible = 0;
        maxVisibleItems = visible;

        for (int i = 0; i < visible; i++) {
            int idx = bookListScrollOffset + i;
            if (idx >= bookNames.size()) break;
            String name = bookNames.get(idx);
            int y = CONTENT_TOP + i * LIST_ITEM_HEIGHT + LIST_ITEM_HEIGHT - 8;
            canvas.drawText(name, 8, y, textPaint);
            canvas.drawLine(0, CONTENT_TOP + i * LIST_ITEM_HEIGHT + LIST_ITEM_HEIGHT,
                    240, CONTENT_TOP + i * LIST_ITEM_HEIGHT + LIST_ITEM_HEIGHT, textPaint);
        }
    }

    private void drawReading(Canvas canvas) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(FONT_SIZE);
        textPaint.setAntiAlias(true);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                char ch = grid[r][c];
                if (ch == 0) ch = ' ';
                float x = c * FONT_SIZE + 4;
                float y = CONTENT_TOP + r * FONT_SIZE + FONT_SIZE - 4;
                canvas.drawText(String.valueOf(ch), x, y, textPaint);
            }
        }
        bottomPaint.setColor(Color.GRAY);
        bottomPaint.setTextSize(16);
        String pageInfo = currentChapter + "  " + (currentPage + 1) + "/" + totalPages;
        float w = bottomPaint.measureText(pageInfo);
        canvas.drawText(pageInfo, 240 - w - 4, CONTENT_BOTTOM - 4, bottomPaint);
    }

    // ======================== 触摸事件 ========================

    private float downX, downY;
    private long downTime;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            downTime = System.currentTimeMillis();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            float upX = event.getX();
            float upY = event.getY();
            long upTime = System.currentTimeMillis();
            float dx = upX - downX;
            float dy = upY - downY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            long elapsed = upTime - downTime;

            if (downY < STATUS_HEIGHT || downY > CONTENT_BOTTOM) return false;

            if (elapsed >= LONG_PRESS_DURATION) {
                if (currentState == STATE_READING) {
                    currentState = STATE_BOOK_LIST;
                    statusMessage = "";
                    logger.log(Logger.INFO, "长按退出阅读");
                }
                return true;
            }

            if (dist < TOUCH_SLOP) {
                if (currentState == STATE_BOOK_LIST) {
                    int relY = (int) (downY - CONTENT_TOP);
                    int index = bookListScrollOffset + relY / LIST_ITEM_HEIGHT;
                    if (index >= 0 && index < bookNames.size()) {
                        selectBook(index);
                    }
                } else {
                    if (upX < 120) {
                        turnPrevious();
                    } else {
                        turnNext();
                    }
                }
                return true;
            } else {
                if (currentState == STATE_BOOK_LIST) {
                    if (Math.abs(dy) > Math.abs(dx)) {
                        int delta = (int) (-dy / LIST_ITEM_HEIGHT);
                        bookListScrollOffset += delta;
                        int max = Math.max(0, bookNames.size() - maxVisibleItems);
                        if (bookListScrollOffset < 0) bookListScrollOffset = 0;
                        if (bookListScrollOffset > max) bookListScrollOffset = max;
                    }
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    // ======================== 核心逻辑 ========================

    private void loadConfig() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                statusMessage = "加载配置...";
            }
        });
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Config config = configManager.fetchAndParse();
                    if (config == null || config.bookIds.isEmpty()) {
                        logger.log(Logger.ERROR, "配置加载失败或无书籍");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                statusMessage = "配置加载失败";
                            }
                        });
                        return;
                    }
                    bookNames = config.bookNames;
                    bookIds = config.bookIds;
                    apiClient.setApiKeys(config.apiKeys);
                    logger.log(Logger.INFO, "配置加载成功，书籍数: " + bookNames.size());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusMessage = "";
                            currentState = STATE_BOOK_LIST;
                        }
                    });
                } catch (Exception e) {
                    logger.log(Logger.ERROR, "配置加载异常: " + e.toString());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusMessage = "配置加载异常";
                        }
                    });
                }
            }
        });
    }

    private void selectBook(final int index) {
        if (index < 0 || index >= bookIds.size()) return;
        currentBookId = bookIds.get(index);
        String progress = bookManager.getProgress(currentBookId);
        if (progress != null) {
            String[] parts = progress.split(",");
            try {
                currentChapter = Integer.parseInt(parts[0]);
                currentPage = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                currentChapter = 1;
                currentPage = 0;
            }
        } else {
            currentChapter = 1;
            currentPage = 0;
        }
        currentState = STATE_READING;
        statusMessage = "加载中...";
        logger.log(Logger.INFO, "选择书籍: " + bookNames.get(index) + " (ID: " + currentBookId + ")");
        loadChapter(currentChapter, currentPage);
    }

    private void loadChapter(final int chapter, final int page) {
        if (isLoadingChapter) return;
        isLoadingChapter = true;
        statusMessage = "加载第" + chapter + "章...";
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String content = chapterCache.loadChapter(currentBookId, chapter);
                if (content != null) {
                    logger.log(Logger.INFO, "从缓存加载第" + chapter + "章");
                    onChapterLoaded(content, chapter, page);
                    return;
                }

                logger.log(Logger.INFO, "从网络加载第" + chapter + "章");
                List<ChapterInfo> catalog = apiClient.fetchCatalog(currentBookId);
                if (catalog == null || catalog.isEmpty()) {
                    logger.log(Logger.ERROR, "获取目录失败");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusMessage = "目录加载失败";
                            isLoadingChapter = false;
                        }
                    });
                    return;
                }

                String itemId = null;
                if (chapter - 1 < catalog.size()) {
                    itemId = catalog.get(chapter - 1).itemId;
                }
                if (itemId == null) {
                    logger.log(Logger.ERROR, "章节索引超出目录范围");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusMessage = "章节不存在";
                            isLoadingChapter = false;
                        }
                    });
                    return;
                }

                String chapterContent = apiClient.fetchChapterContent(itemId);
                if (chapterContent == null) {
                    logger.log(Logger.ERROR, "获取章节内容失败");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusMessage = "内容加载失败";
                            isLoadingChapter = false;
                        }
                    });
                    return;
                }

                chapterCache.saveChapter(currentBookId, chapter, chapterContent);
                chapterCache.cleanOldChapters(currentBookId, chapter);
                onChapterLoaded(chapterContent, chapter, page);
            }
        });
    }

    private void onChapterLoaded(String content, int chapter, int page) {
        this.chapterContent = content;
        this.currentChapter = chapter;
        computeTotalPages();
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;
        this.currentPage = page;
        renderPage(page);
        bookManager.updateProgress(currentBookId, currentChapter, currentPage);
        isLoadingChapter = false;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                statusMessage = "第" + currentChapter + "章 " + (currentPage + 1) + "/" + totalPages;
            }
        });
        logger.log(Logger.INFO, "章节加载完成: " + currentChapter + ", 页: " + currentPage + "/" + totalPages);
    }

    private void computeTotalPages() {
        totalPages = 0;
        int row = 0, col = 0;
        for (int i = 0; i < chapterContent.length(); i++) {
            char ch = chapterContent.charAt(i);
            if (ch == '\n') {
                if (col > 0) {
                    col = COLS;
                }
                row++;
                col = 0;
                if (row >= ROWS) {
                    totalPages++;
                    row = 0;
                    col = 0;
                }
            } else {
                col++;
                if (col >= COLS) {
                    col = 0;
                    row++;
                    if (row >= ROWS) {
                        totalPages++;
                        row = 0;
                        col = 0;
                    }
                }
            }
        }
        if (row > 0 || col > 0) {
            totalPages++;
        }
        if (totalPages == 0) totalPages = 1;
    }

    private void renderPage(int page) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                grid[r][c] = ' ';
            }
        }
        if (chapterContent == null || chapterContent.isEmpty()) return;

        int target = page;
        int current = 0;
        int row = 0, col = 0;
        for (int i = 0; i < chapterContent.length(); i++) {
            char ch = chapterContent.charAt(i);
            if (current == target) {
                if (ch == '\n') {
                    while (col < COLS) {
                        grid[row][col] = ' ';
                        col++;
                    }
                    row++;
                    col = 0;
                    if (row >= ROWS) break;
                } else {
                    grid[row][col] = ch;
                    col++;
                    if (col >= COLS) {
                        col = 0;
                        row++;
                        if (row >= ROWS) break;
                    }
                }
            } else {
                if (ch == '\n') {
                    row++;
                    col = 0;
                    if (row >= ROWS) {
                        current++;
                        row = 0;
                        col = 0;
                        if (current > target) break;
                    }
                } else {
                    col++;
                    if (col >= COLS) {
                        col = 0;
                        row++;
                        if (row >= ROWS) {
                            current++;
                            row = 0;
                            col = 0;
                            if (current > target) break;
                        }
                    }
                }
            }
        }
    }

    private void turnNext() {
        if (isLoadingChapter) return;
        if (currentPage < totalPages - 1) {
            currentPage++;
            renderPage(currentPage);
            bookManager.updateProgress(currentBookId, currentChapter, currentPage);
            statusMessage = "第" + currentChapter + "章 " + (currentPage + 1) + "/" + totalPages;
            return;
        }
        int nextChapter = currentChapter + 1;
        if (chapterCache.hasChapter(currentBookId, nextChapter)) {
            String content = chapterCache.loadChapter(currentBookId, nextChapter);
            if (content != null) {
                onChapterLoaded(content, nextChapter, 0);
                return;
            }
        }
        loadChapter(nextChapter, 0);
    }

    private void turnPrevious() {
        if (isLoadingChapter) return;
        if (currentPage > 0) {
            currentPage--;
            renderPage(currentPage);
            bookManager.updateProgress(currentBookId, currentChapter, currentPage);
            statusMessage = "第" + currentChapter + "章 " + (currentPage + 1) + "/" + totalPages;
            return;
        }
        int prevChapter = currentChapter - 1;
        if (prevChapter >= 1) {
            if (chapterCache.hasChapter(currentBookId, prevChapter)) {
                String content = chapterCache.loadChapter(currentBookId, prevChapter);
                if (content != null) {
                    onChapterLoaded(content, prevChapter, Integer.MAX_VALUE);
                    return;
                }
            }
            statusMessage = "无法回退";
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    statusMessage = "第" + currentChapter + "章 " + (currentPage + 1) + "/" + totalPages;
                }
            }, 1500);
        } else {
            statusMessage = "已是第一章";
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    statusMessage = "第" + currentChapter + "章 " + (currentPage + 1) + "/" + totalPages;
                }
            }, 1500);
        }
    }

    // ======================== 内部类 ========================

    private static class Config {
        List<String> bookNames;
        List<String> bookIds;
        List<String> apiKeys;
        int version;
    }

    private class ConfigManager {
        Config fetchAndParse() {
            String content = downloadConfig();
            if (content == null) {
                content = readLocalConfig();
            }
            if (content == null) {
                logger.log(Logger.ERROR, "配置获取失败");
                return null;
            }
            return parseConfig(content);
        }

        private String downloadConfig() {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(CONFIG_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36")
                    .build();
            try {
                logger.log(Logger.INFO, "开始下载配置: " + CONFIG_URL);
                Response response = client.newCall(request).execute();
                int code = response.code();
                logger.log(Logger.INFO, "配置下载响应码: " + code);
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    logger.log(Logger.INFO, "配置下载成功，内容长度: " + body.length());
                    saveLocalConfig(body);
                    return body;
                } else {
                    logger.log(Logger.ERROR, "配置下载失败，响应码: " + code);
                }
            } catch (Exception e) {
                logger.log(Logger.ERROR, "下载配置异常: " + e.toString());
            }
            return null;
        }

        private void saveLocalConfig(String content) {
            try {
                File dir = new File(CONFIG_DIR);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(CONFIG_FILE);
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                writer.write(content);
                writer.close();
            } catch (IOException e) {
                logger.log(Logger.ERROR, "保存配置失败: " + e.toString());
            }
        }

        private String readLocalConfig() {
            File file = new File(CONFIG_FILE);
            if (!file.exists()) return null;
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                return sb.toString();
            } catch (IOException e) {
                logger.log(Logger.ERROR, "读取本地配置失败: " + e.toString());
                return null;
            }
        }

        private Config parseConfig(String content) {
            logger.log(Logger.INFO, "开始解析配置，内容长度: " + content.length());
            Config config = new Config();
            config.bookNames = new ArrayList<>();
            config.bookIds = new ArrayList<>();
            config.apiKeys = new ArrayList<>();
            config.version = 0;

            String[] lines = content.split("\n");
            logger.log(Logger.INFO, "配置行数: " + lines.length);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equals("!!!!!")) break;
                int idx = line.indexOf('@');
                if (idx == -1) continue;
                String key = line.substring(0, idx);
                String value = line.substring(idx + 1);
                if (value.endsWith("!")) {
                    value = value.substring(0, value.length() - 1);
                }
                if (key.equals("Ver")) {
                    try { config.version = Integer.parseInt(value);
                    logger.log(Logger.INFO, "配置版本: " + config.version);} catch (Exception ignored) {}
                } else if (key.equals("UserApiKey")) {
                    String[] keys = value.split(",");
                    for (String k : keys) {
                        if (!k.trim().isEmpty()) config.apiKeys.add(k.trim());
                    }
                    logger.log(Logger.INFO, "解析到 API Key 数量: " + config.apiKeys.size());
                } else if (key.equals("BookList")) {
                    String[] names = value.split(",");
                    for (String n : names) {
                        if (!n.trim().isEmpty()) config.bookNames.add(n.trim());
                    }
                    logger.log(Logger.INFO, "解析到书籍名称数量: " + config.bookNames.size());
                } else if (key.equals("BookId")) {
                    String[] ids = value.split(",");
                    for (String id : ids) {
                        if (!id.trim().isEmpty()) config.bookIds.add(id.trim());
                    }
                    logger.log(Logger.INFO, "解析到书籍ID数量: " + config.bookIds.size());
                }
            }

            int min = Math.min(config.bookNames.size(), config.bookIds.size());
            if (config.bookNames.size() > min) {
                config.bookNames = config.bookNames.subList(0, min);
            }
            if (config.bookIds.size() > min) {
                config.bookIds = config.bookIds.subList(0, min);
            }

            if (config.apiKeys.isEmpty()) {
                logger.log(Logger.ERROR, "配置中无API Key");
                return null;
            }
            logger.log(Logger.INFO, "配置解析完成，有效书籍数: " + config.bookNames.size());
            return config;
        }
    }

    private class BookManager {
        private final Map<String, String> progressMap = new HashMap<>();
        private boolean loaded = false;

        private void load() {
            if (loaded) return;
            File file = new File(PROGRESS_FILE);
            if (file.exists()) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int at = line.indexOf('@');
                        if (at != -1) {
                            String id = line.substring(0, at);
                            String val = line.substring(at + 1);
                            progressMap.put(id, val);
                        }
                    }
                    reader.close();
                } catch (IOException e) {
                    logger.log(Logger.ERROR, "读取进度文件失败: " + e.toString());
                }
            }
            loaded = true;
        }

        private void saveAll() {
            File dir = new File(CONFIG_DIR);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(PROGRESS_FILE);
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                for (Map.Entry<String, String> entry : progressMap.entrySet()) {
                    writer.write(entry.getKey() + "@" + entry.getValue() + "\n");
                }
                writer.close();
            } catch (IOException e) {
                logger.log(Logger.ERROR, "保存进度失败: " + e.toString());
            }
        }

        public synchronized String getProgress(String bookId) {
            load();
            return progressMap.get(bookId);
        }

        public synchronized void updateProgress(String bookId, int chapter, int page) {
            load();
            progressMap.put(bookId, chapter + "," + page);
            saveAll();
        }
    }

    private class ChapterCache {
        private String getBookDir(String bookId) {
            return BOOKS_DIR + "/" + bookId;
        }

        private String getChapterPath(String bookId, int chapter) {
            return getBookDir(bookId) + "/data" + String.format(Locale.US, "%04d", chapter) + ".ob";
        }

        public boolean hasChapter(String bookId, int chapter) {
            return new File(getChapterPath(bookId, chapter)).exists();
        }

        public String loadChapter(String bookId, int chapter) {
            File file = new File(getChapterPath(bookId, chapter));
            if (!file.exists()) return null;
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                return sb.toString();
            } catch (IOException e) {
                logger.log(Logger.ERROR, "读取章节缓存失败: " + e.toString());
                return null;
            }
        }

        public void saveChapter(String bookId, int chapter, String content) {
            File dir = new File(getBookDir(bookId));
            if (!dir.exists()) dir.mkdirs();
            String path = getChapterPath(bookId, chapter);
            File tmp = new File(path + ".tmp");
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(tmp));
                writer.write(content);
                writer.close();
                File dest = new File(path);
                if (dest.exists()) dest.delete();
                if (!tmp.renameTo(dest)) {
                    tmp.delete();
                    logger.log(Logger.ERROR, "重命名章节缓存失败");
                }
            } catch (IOException e) {
                logger.log(Logger.ERROR, "保存章节缓存失败: " + e.toString());
            }
        }

        public void cleanOldChapters(String bookId, int currentChapter) {
            File dir = new File(getBookDir(bookId));
            if (!dir.exists()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            List<Integer> numbers = new ArrayList<>();
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("data") && name.endsWith(".ob")) {
                    try {
                        int num = Integer.parseInt(name.substring(4, 8));
                        numbers.add(num);
                    } catch (Exception ignored) {}
                }
            }
            if (numbers.size() <= MAX_CACHED_CHAPTERS) return;
            Collections.sort(numbers);
            int toRemove = numbers.size() - MAX_CACHED_CHAPTERS;
            for (int i = 0; i < toRemove; i++) {
                int num = numbers.get(i);
                File f = new File(dir, "data" + String.format(Locale.US, "%04d", num) + ".ob");
                if (f.exists()) f.delete();
            }
            logger.log(Logger.INFO, "清理缓存，保留最近" + MAX_CACHED_CHAPTERS + "章");
        }
    }

    private class ApiClient {
        private List<String> apiKeys = new ArrayList<>();
        private int keyIndex = 0;
        private OkHttpClient client;

        public ApiClient() {
            // 纯 HTTP，无 TLS 限制，超时 60 秒
            client = new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }

        public void setApiKeys(List<String> keys) {
            this.apiKeys = keys;
            this.keyIndex = 0;
        }

        private String getNextKey() {
            if (apiKeys.isEmpty()) return null;
            String key = apiKeys.get(keyIndex);
            keyIndex = (keyIndex + 1) % apiKeys.size();
            return key;
        }

        private String executeGet(String url) {
            int attempts = apiKeys.size();
            if (attempts == 0) return null;
            for (int i = 0; i < attempts; i++) {
                String key = getNextKey();
                if (key == null) continue;
                String fullUrl = url + "&apikey=" + key;
                logger.log(Logger.DEBUG, "请求URL: " + fullUrl);
                Request request = new Request.Builder()
                        .url(fullUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36")
                        .header("Connection", "close")  // 避免 keep-alive 问题
                        .build();
                try {
                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        String body = response.body().string();
                        return body;
                    } else {
                        logger.log(Logger.WARN, "请求失败，状态码: " + response.code() + ", Key: " + key.substring(0, 4) + "****");
                    }
                } catch (Exception e) {
                    logger.log(Logger.WARN, "请求异常: " + e.toString() + ", Key: " + key.substring(0, 4) + "****");
                }
            }
            return null;
        }

        public List<ChapterInfo> fetchCatalog(String bookId) {
            String url = API_BASE + "?type=3&bookid=" + bookId;
            String json = executeGet(url);
            if (json == null) return null;
            try {
                List<ChapterInfo> list = new ArrayList<>();
                int start = json.indexOf("\"item_data_list\"");
                if (start == -1) return null;
                int arrStart = json.indexOf("[", start);
                if (arrStart == -1) return null;
                int arrEnd = findMatchingBracket(json, arrStart);
                if (arrEnd == -1) return null;
                String arr = json.substring(arrStart + 1, arrEnd);
                int idx = 0;
                while (true) {
                    int objStart = arr.indexOf("{", idx);
                    if (objStart == -1) break;
                    int objEnd = findMatchingBracket(arr, objStart);
                    if (objEnd == -1) break;
                    String obj = arr.substring(objStart + 1, objEnd);
                    String itemId = extractJsonString(obj, "item_id");
                    String title = extractJsonString(obj, "title");
                    if (itemId != null && title != null) {
                        ChapterInfo info = new ChapterInfo();
                        info.itemId = itemId;
                        info.title = title;
                        list.add(info);
                    }
                    idx = objEnd + 1;
                }
                return list;
            } catch (Exception e) {
                logger.log(Logger.ERROR, "解析目录JSON失败: " + e.toString());
                return null;
            }
        }

        public String fetchChapterContent(String itemId) {
            String url = API_BASE + "?type=4&itemid=" + itemId;
            String json = executeGet(url);
            if (json == null) return null;
            try {
                String content = extractJsonString(json, "content");
                if (content == null) return null;
                return cleanContent(content);
            } catch (Exception e) {
                logger.log(Logger.ERROR, "解析章节内容失败: " + e.toString());
                return null;
            }
        }

        private String cleanContent(String raw) {
            String warning = "为保证服务质量，免费用户请不要下书！或前往网站赞助后刷新隐藏该提示(赞助用户一天可下载一万章)";
            raw = raw.replace(warning, "");
            raw = raw.replace("</p>", "\n");
            raw = raw.replace("<br>", "\n").replace("<br/>", "\n");
            raw = raw.replaceAll("<[^>]*>", "");
            raw = raw.replaceAll("\n{3,}", "\n\n");
            raw = raw.trim();
            raw = raw.replace("\r\n", "\n").replace("\r", "\n");
            return raw;
        }

        private String extractJsonString(String json, String key) {
            String target = "\"" + key + "\":";
            int pos = json.indexOf(target);
            if (pos == -1) return null;
            int start = json.indexOf("\"", pos + target.length());
            if (start == -1) return null;
            int end = json.indexOf("\"", start + 1);
            if (end == -1) return null;
            return json.substring(start + 1, end);
        }

        private int findMatchingBracket(String s, int open) {
            char c = s.charAt(open);
            char close = (c == '[') ? ']' : '}';
            int count = 1;
            for (int i = open + 1; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == c) count++;
                else if (ch == close) {
                    count--;
                    if (count == 0) return i;
                }
            }
            return -1;
        }
    }

    private static class ChapterInfo {
        String itemId;
        String title;
    }

    private class Logger {
        public static final String INFO = "INFO";
        public static final String WARN = "WARN";
        public static final String ERROR = "ERROR";
        public static final String DEBUG = "DEBUG";

        private String logDir;
        private String logFile;

        public Logger() {
            String timeStamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
            logDir = LOG_DIR + "/" + timeStamp;
            logFile = logDir + "/logs.ob";
            File dir = new File(logDir);
            if (!dir.exists()) dir.mkdirs();
            log(INFO, "日志系统初始化");
        }

        public void log(String level, String msg) {
            String time = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss", Locale.US).format(new Date());
            String line = "[" + time + "][" + level + "]: " + msg + "\n";
            try {
                File file = new File(logFile);
                if (!file.exists()) file.createNewFile();
                FileWriter fw = new FileWriter(file, true);
                fw.write(line);
                fw.close();
            } catch (IOException e) {
                Log.e(TAG, "写入日志失败: " + e.toString());
            }
            switch (level) {
                case INFO: Log.i(TAG, msg); break;
                case WARN: Log.w(TAG, msg); break;
                case ERROR: Log.e(TAG, msg); break;
                case DEBUG: Log.d(TAG, msg); break;
                default: Log.v(TAG, msg);
            }
        }
    }
}

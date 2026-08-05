// 路径: app/src/main/java/com/openbook/studio/MainActivity.java
package com.openbook.studio;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import org.conscrypt.Conscrypt;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TlsVersion;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Runnable {

    // ======================== 静态初始化 Conscrypt ========================
    static {
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable t) {
            Log.e("OpenBook", "Conscrypt 注册失败: " + t.toString());
        }
    }

    // ======================== 常量 ========================
    private static final String TAG = "OpenBook";
    private static final String CONFIG_URL =
            "https://gitee.com/yingo-server/openbook/raw/master/users/private/0/config.ob";
    private static final String API_BASE = "https://v3.rain.ink/fanqie/";
    private static final String BASE_DIR = Environment.getExternalStorageDirectory() + "/openbook";
    private static final String CONFIG_DIR = BASE_DIR + "/config/user";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.ob";
    private static final String PROGRESS_FILE = CONFIG_DIR + "/progress.ob";
    private static final String BOOKS_DIR = BASE_DIR + "/books";
    public static final String LOG_DIR = BASE_DIR + "/logs";
    private static final int MAX_CACHED_CHAPTERS = 3;
    // 11x11 网格
    private static final int COLS = 11;
    private static final int ROWS = 11;
    private static final int FONT_SIZE = 20;
    private static final long LONG_PRESS_DURATION = 1500;
    private static final int TOUCH_SLOP = 10;

    // ======================== UI 组件 ========================
    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private boolean isSurfaceReady = false;
    private boolean isRunning = false;
    private Thread renderThread;
    private final Object lock = new Object();

    // ======================== 分辨率适配(240x240 逻辑 -> 物理等比缩放) ========================
    private int canvasW = 240;
    private int canvasH = 240;
    private float viewScale = 1f;
    private float viewOffsetX = 0f;
    private float viewOffsetY = 0f;

    // ======================== 状态 ========================
    private static final int STATE_BOOK_LIST = 0;
    private static final int STATE_READING = 1;
    private static final int STATE_SELECT_CHAPTER = 2;
    private int currentState = STATE_BOOK_LIST;

    private List<String> bookNames = new ArrayList<>();
    private List<String> bookIds = new ArrayList<>();
    private int bookListScrollOffset = 0;
    private int maxVisibleItems = 0;

    // 章节选择列表
    private List<ChapterInfo> chapterList = new ArrayList<>();
    private int chapterScrollOffset = 0;
    private int maxVisibleChapters = 0;

    private String currentBookId = null;
    private int currentChapter = 1;
    private int currentPage = 0;
    private int totalPages = 0;
    private char[][] grid = new char[ROWS][COLS];
    private String chapterContent = "";
    private volatile boolean isLoadingChapter = false;
    private volatile boolean isLoadingCatalog = false;
    private String statusMessage = "";

    // ======================== 管理器 ========================
    private ConfigManager configManager;
    private BookManager bookManager;
    private ChapterCache chapterCache;
    private ApiClient apiClient;
    private Logger logger;

    // ======================== 共享 OkHttp 客户端 ========================
    private OkHttpClient sharedClient;

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

        sharedClient = buildTls12Client();
        if (sharedClient == null) {
            logger.log(Logger.WARN, "TLS 1.2 客户端构建失败，使用默认客户端（可能失败）");
            sharedClient = new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }

        configManager = new ConfigManager(sharedClient);
        bookManager = new BookManager();
        chapterCache = new ChapterCache();
        apiClient = new ApiClient(sharedClient);

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
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 最大内接正方形:scale = size/240,居中偏移,触摸坐标按逆变换还原
        canvasW = width;
        canvasH = height;
        int size = Math.min(width, height);
        viewScale = size / 240f;
        viewOffsetX = (width - size) / 2f;
        viewOffsetY = (height - size) / 2f;
    }

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
                    updateInertia();
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
    private Paint listTextPaint = new Paint();
    private Paint chapterListPaint = new Paint();
    private Paint highlightPaint = new Paint();
    private Paint progressBarPaint = new Paint();

    private void drawUI(Canvas canvas) {
        bgPaint.setColor(Color.BLACK);
        canvas.drawRect(0, 0, canvasW, canvasH, bgPaint);

        canvas.save();
        canvas.translate(viewOffsetX, viewOffsetY);
        canvas.scale(viewScale, viewScale);

        if (currentState == STATE_BOOK_LIST) {
            drawBookList(canvas);
        } else if (currentState == STATE_SELECT_CHAPTER) {
            drawChapterList(canvas);
        } else {
            drawReading(canvas);
        }

        drawProgressBars(canvas);

        canvas.restore();
    }

    // 底部双进度条:青色(下)=本节页进度/列表当前位置,白色(上)=整书总进度
    // 白色条处理章节更新:目录缺失或章数落后时由 onChapterLoaded 补拉最新目录作分母
    private void drawProgressBars(Canvas canvas) {
        int barHeight = 3;
        int gap = 1;
        int whiteY1 = 240 - 1;
        int whiteY0 = whiteY1 - barHeight + 1;
        int cyanY1 = whiteY0 - gap;
        int cyanY0 = cyanY1 - barHeight + 1;

        float whiteFrac = 0f;
        float cyanFrac = 0f;
        if (currentState == STATE_READING) {
            cyanFrac = totalPages > 0 ? (currentPage + 1f) / totalPages : 0f;
            int totalChapters = chapterList.size();
            if (totalChapters > 0) {
                whiteFrac = (currentChapter - 1 + cyanFrac) / totalChapters;
            }
        } else if (currentState == STATE_BOOK_LIST) {
            whiteFrac = bookNames.isEmpty() ? 0f
                    : (bookListScrollOffset + maxVisibleItems) / (float) bookNames.size();
        } else if (currentState == STATE_SELECT_CHAPTER) {
            whiteFrac = chapterList.isEmpty() ? 0f
                    : (chapterScrollOffset + maxVisibleChapters) / (float) chapterList.size();
        }
        if (whiteFrac < 0f) whiteFrac = 0f;
        if (whiteFrac > 1f) whiteFrac = 1f;
        if (cyanFrac < 0f) cyanFrac = 0f;
        if (cyanFrac > 1f) cyanFrac = 1f;

        progressBarPaint.setColor(Color.WHITE);
        canvas.drawRect(0, whiteY0, 240 * whiteFrac, whiteY1, progressBarPaint);
        progressBarPaint.setColor(Color.CYAN);
        canvas.drawRect(0, cyanY0, 240 * cyanFrac, cyanY1, progressBarPaint);
    }

    private void drawBookList(Canvas canvas) {
        listTextPaint.setColor(Color.WHITE);
        listTextPaint.setTextSize(28);
        listTextPaint.setAntiAlias(true);

        int visible = Math.min(bookNames.size() - bookListScrollOffset,
                240 / 48);
        if (visible < 0) visible = 0;
        maxVisibleItems = visible;

        for (int i = 0; i < visible; i++) {
            int idx = bookListScrollOffset + i;
            if (idx >= bookNames.size()) break;
            String name = bookNames.get(idx);
            int y = i * 48 + 48 - 8;
            canvas.drawText(name, 8, y, listTextPaint);
            canvas.drawLine(0, i * 48 + 48, 240, i * 48 + 48, listTextPaint);
        }
    }

    private void drawChapterList(Canvas canvas) {
        chapterListPaint.setColor(Color.WHITE);
        chapterListPaint.setTextSize(14);
        chapterListPaint.setAntiAlias(true);
        int itemHeight = 18;

        int visible = Math.min(chapterList.size() - chapterScrollOffset,
                240 / itemHeight);
        if (visible < 0) visible = 0;
        maxVisibleChapters = visible;

        // 高亮当前章节
        int currentIdx = currentChapter - 1;
        int relY = (currentIdx - chapterScrollOffset) * itemHeight;
        if (relY >= 0 && relY < 240) {
            highlightPaint.setColor(Color.argb(80, 255, 255, 255));
            canvas.drawRect(0, relY, 240, relY + itemHeight, highlightPaint);
        }

        for (int i = 0; i < visible; i++) {
            int idx = chapterScrollOffset + i;
            if (idx >= chapterList.size()) break;
            String title = chapterList.get(idx).title;
            int y = i * itemHeight + itemHeight - 4;
            canvas.drawText(title, 4, y, chapterListPaint);
            canvas.drawLine(0, i * itemHeight + itemHeight, 240, i * itemHeight + itemHeight, chapterListPaint);
        }

        chapterListPaint.setColor(Color.argb(128, 255, 255, 255));
        chapterListPaint.setTextSize(12);
        String hint = "点击章节切换  长按退出";
        float w = chapterListPaint.measureText(hint);
        canvas.drawText(hint, (240 - w) / 2, 240 - 4, chapterListPaint);
    }

    private void drawReading(Canvas canvas) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(FONT_SIZE);
        textPaint.setAntiAlias(true);
        textPaint.setSubpixelText(true);
        textPaint.setTypeface(Typeface.DEFAULT);

        int totalWidth = COLS * FONT_SIZE;
        int startX = (240 - totalWidth) / 2;
        int startY = (240 - ROWS * FONT_SIZE) / 2;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                char ch = grid[r][c];
                if (ch == 0) ch = ' ';
                float x = startX + c * FONT_SIZE;
                float y = startY + r * FONT_SIZE + FONT_SIZE - 4;
                canvas.drawText(String.valueOf(ch), x, y, textPaint);
            }
        }
    }

    // ======================== 触摸事件 ========================

    private float downX, downY;
    private long downTime;
    private float lastMoveY = 0;
    private long lastMoveTime = 0;
    private volatile float scrollVelocity = 0;
    private static final float FLING_THRESHOLD = 3.0f;
    private static final float FLING_DECAY = 0.90f;
    private static final float FLING_MIN = 1.0f;
    private static final float MAX_FLING = 60f;

    // 物理像素 -> 240x240 逻辑坐标(分辨率适配的逆变换)
    private float toLogicX(float x) { return (x - viewOffsetX) / viewScale; }
    private float toLogicY(float y) { return (y - viewOffsetY) / viewScale; }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = toLogicX(event.getX());
            downY = toLogicY(event.getY());
            downTime = System.currentTimeMillis();
            lastMoveY = downY;
            lastMoveTime = downTime;
            scrollVelocity = 0;
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            // 拖动中实时滚动(书列表/章节列表)
            if (currentState == STATE_BOOK_LIST || currentState == STATE_SELECT_CHAPTER) {
                float moveY = toLogicY(event.getY());
                float dyPx = lastMoveY - moveY;
                long now = System.currentTimeMillis();
                long dt = now - lastMoveTime;
                // 瞬时速度限幅后做 EMA 平滑,避免 dt 过小导致的速度爆大
                if (dt > 0) {
                    float newV = dyPx * 50f / dt;
                    if (newV > MAX_FLING) newV = MAX_FLING;
                    else if (newV < -MAX_FLING) newV = -MAX_FLING;
                    scrollVelocity = scrollVelocity * 0.6f + newV * 0.4f;
                }
                lastMoveY = moveY;
                lastMoveTime = now;
                applyScroll((int) dyPx);
                return true;
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            float upX = toLogicX(event.getX());
            float upY = toLogicY(event.getY());
            long upTime = System.currentTimeMillis();
            float dx = upX - downX;
            float dy = upY - downY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            long elapsed = upTime - downTime;

            // 长按处理
            if (elapsed >= LONG_PRESS_DURATION) {
                if (currentState == STATE_READING) {
                    // 判断上下半屏
                    if (downY < 120) {
                        // 上半屏长按 → 进入章节选择
                        enterChapterSelect();
                    } else {
                        // 下半屏长按 → 退出阅读
                        currentState = STATE_BOOK_LIST;
                        statusMessage = "";
                        logger.log(Logger.INFO, "长按退出阅读");
                    }
                    return true;
                } else if (currentState == STATE_SELECT_CHAPTER) {
                    // 章节选择界面长按直接退出到书籍列表
                    currentState = STATE_BOOK_LIST;
                    statusMessage = "";
                    logger.log(Logger.INFO, "章节选择长按退出");
                    return true;
                }
                return true;
            }

            // 短按（点击或滑动）
            if (dist < TOUCH_SLOP) {
                // 点击
                if (currentState == STATE_BOOK_LIST) {
                    int index = bookListScrollOffset + (int) (downY / 48);
                    if (index >= 0 && index < bookNames.size()) {
                        selectBook(index);
                    }
                } else if (currentState == STATE_SELECT_CHAPTER) {
                    int itemHeight = 18;
                    if (downY >= 240 - itemHeight) return true;
                    int index = chapterScrollOffset + (int) (downY / itemHeight);
                    if (index >= 0 && index < chapterList.size()) {
                        // 切换到该章节
                        switchToChapter(index + 1);
                    }
                } else {
                    // 阅读模式：左右翻页
                    if (upX < 120) {
                        turnPrevious();
                    } else {
                        turnNext();
                    }
                }
                return true;
            } else {
                // 滑动:滚动已在 ACTION_MOVE 实时更新,这里只决定是否保留惯性(限幅后减半,降低惯性)
                if (Math.abs(scrollVelocity) < FLING_THRESHOLD) {
                    scrollVelocity = 0;
                } else {
                    if (scrollVelocity > MAX_FLING) scrollVelocity = MAX_FLING;
                    else if (scrollVelocity < -MAX_FLING) scrollVelocity = -MAX_FLING;
                    scrollVelocity *= 0.5f;
                }
                return true;
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            downX = -1;
            downY = -1;
            downTime = 0;
            scrollVelocity = 0;
            return true;
        }
        return super.onTouchEvent(event);
    }

    // ======================== 滚动与惯性 ========================

    private void applyScroll(int delta) {
        if (currentState == STATE_BOOK_LIST) {
            bookListScrollOffset += delta;
            int max = Math.max(0, bookNames.size() - maxVisibleItems);
            if (bookListScrollOffset < 0) {
                bookListScrollOffset = 0;
                if (scrollVelocity < 0) scrollVelocity *= 0.3f;
            } else if (bookListScrollOffset > max) {
                bookListScrollOffset = max;
                if (scrollVelocity > 0) scrollVelocity *= 0.3f;
            }
        } else if (currentState == STATE_SELECT_CHAPTER) {
            chapterScrollOffset += delta;
            int max = Math.max(0, chapterList.size() - maxVisibleChapters);
            if (chapterScrollOffset < 0) {
                chapterScrollOffset = 0;
                if (scrollVelocity < 0) scrollVelocity *= 0.3f;
            } else if (chapterScrollOffset > max) {
                chapterScrollOffset = max;
                if (scrollVelocity > 0) scrollVelocity *= 0.3f;
            }
        }
    }

    // 渲染循环每帧调用:惯性滚动(速度逐帧衰减,单位 px/帧)
    private void updateInertia() {
        if (scrollVelocity == 0f) return;
        applyScroll((int) scrollVelocity);
        scrollVelocity *= FLING_DECAY;
        if (Math.abs(scrollVelocity) < FLING_MIN) {
            scrollVelocity = 0;
        }
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

    // 进入章节选择界面
    private void enterChapterSelect() {
        if (isLoadingCatalog) return;
        // 先尝试从缓存加载目录
        List<ChapterInfo> catalog = chapterCache.loadCatalog(currentBookId);
        if (catalog == null || catalog.isEmpty()) {
            // 如果没有，从网络获取
            logger.log(Logger.INFO, "章节选择：从网络加载目录");
            statusMessage = "加载目录...";
            isLoadingCatalog = true;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    final List<ChapterInfo> fetched = apiClient.fetchCatalog(currentBookId);
                    if (fetched != null && !fetched.isEmpty()) {
                        chapterCache.saveCatalog(currentBookId, fetched);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                isLoadingCatalog = false;
                                chapterList = fetched;
                                // 定位到当前章节
                                int targetIdx = currentChapter - 1;
                                int itemHeight = 18;
                                int visible = 240 / itemHeight;
                                if (targetIdx >= 0 && targetIdx < chapterList.size()) {
                                    int offset = targetIdx - visible / 2;
                                    if (offset < 0) offset = 0;
                                    int max = Math.max(0, chapterList.size() - visible);
                                    if (offset > max) offset = max;
                                    chapterScrollOffset = offset;
                                }
                                currentState = STATE_SELECT_CHAPTER;
                                statusMessage = "";
                                logger.log(Logger.INFO, "章节选择界面已加载，共 " + chapterList.size() + " 章");
                            }
                        });
                    } else {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                isLoadingCatalog = false;
                                statusMessage = "目录加载失败";
                                logger.log(Logger.ERROR, "章节选择：目录加载失败");
                            }
                        });
                    }
                }
            });
        } else {
            // 缓存存在，直接显示
            chapterList = catalog;
            int targetIdx = currentChapter - 1;
            int itemHeight = 18;
            int visible = 240 / itemHeight;
            if (targetIdx >= 0 && targetIdx < chapterList.size()) {
                int offset = targetIdx - visible / 2;
                if (offset < 0) offset = 0;
                int max = Math.max(0, chapterList.size() - visible);
                if (offset > max) offset = max;
                chapterScrollOffset = offset;
            }
            currentState = STATE_SELECT_CHAPTER;
            statusMessage = "";
            logger.log(Logger.INFO, "从缓存加载章节列表，共 " + chapterList.size() + " 章");
        }
    }

    // 切换到指定章节
    private void switchToChapter(int chapter) {
        if (chapter < 1 || chapter > chapterList.size()) return;
        if (chapter == currentChapter) {
            // 相同章节，回到阅读界面
            currentState = STATE_READING;
            statusMessage = "第" + currentChapter + "章 " + (currentPage + 1) + "/" + totalPages;
            return;
        }
        currentChapter = chapter;
        currentPage = 0;
        currentState = STATE_READING;
        statusMessage = "加载第" + currentChapter + "章...";
        logger.log(Logger.INFO, "切换到第 " + currentChapter + " 章");
        loadChapter(currentChapter, 0);
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
                // 优先从缓存获取目录
                List<ChapterInfo> catalog = chapterCache.loadCatalog(currentBookId);
                if (catalog == null || catalog.isEmpty()) {
                    catalog = apiClient.fetchCatalog(currentBookId);
                    if (catalog != null && !catalog.isEmpty()) {
                        chapterCache.saveCatalog(currentBookId, catalog);
                    }
                }
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

        // 确保目录已加载以计算整书总进度;缺失或章数落后时刷新(处理章节更新)
        if (chapterList.isEmpty() || chapterList.size() < currentChapter) {
            List<ChapterInfo> catalog = chapterCache.loadCatalog(currentBookId);
            if (catalog == null || catalog.isEmpty()) {
                catalog = apiClient.fetchCatalog(currentBookId);
                if (catalog != null && !catalog.isEmpty()) {
                    chapterCache.saveCatalog(currentBookId, catalog);
                }
            }
            if (catalog != null && !catalog.isEmpty()) {
                chapterList = catalog;
            }
        }

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
                if (col > 0) col = COLS;
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
        if (row > 0 || col > 0) totalPages++;
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
            loadChapter(prevChapter, Integer.MAX_VALUE);
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

    // ======================== 构建 TLS 1.2 OkHttp 客户端 ========================

    private OkHttpClient buildTls12Client() {
        try {
            final X509TrustManager trustAllManager = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2", "Conscrypt");
            sslContext.init(null, new TrustManager[]{trustAllManager}, new java.security.SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            SSLSocket testSocket = (SSLSocket) sslSocketFactory.createSocket();
            String[] protocols = testSocket.getSupportedProtocols();
            logger.log(Logger.DEBUG, "Supported protocols: " + Arrays.toString(protocols));
            if (protocols != null) {
                boolean hasTls12 = false;
                for (String p : protocols) {
                    if ("TLSv1.2".equals(p)) {
                        hasTls12 = true;
                        break;
                    }
                }
                logger.log(Logger.INFO, "TLSv1.2 支持: " + hasTls12);
            }

            ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build();

            HostnameVerifier allowAll = new HostnameVerifier() {
                @Override public boolean verify(String hostname, SSLSession session) { return true; }
            };

            return new OkHttpClient.Builder()
                    .connectionSpecs(Collections.singletonList(spec))
                    .sslSocketFactory(sslSocketFactory, trustAllManager)
                    .hostnameVerifier(allowAll)
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) {
            logger.log(Logger.ERROR, "构建 TLS 1.2 客户端失败: " + e.toString());
            return null;
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
        private OkHttpClient client;
        public ConfigManager(OkHttpClient client) { this.client = client; }

        Config fetchAndParse() {
            String content = downloadConfig();
            if (content == null) content = readLocalConfig();
            if (content == null) {
                logger.log(Logger.ERROR, "配置获取失败");
                return null;
            }
            return parseConfig(content);
        }

        private String downloadConfig() {
            try {
                Request request = new Request.Builder()
                        .url(CONFIG_URL)
                        .header("User-Agent", "Mozilla/5.0")
                        .build();
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    response.close();
                    logger.log(Logger.INFO, "配置下载成功，内容长度: " + body.length());
                    saveLocalConfig(body);
                    return body;
                } else {
                    logger.log(Logger.ERROR, "配置下载失败，响应码: " + response.code());
                    response.close();
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

        private String getCatalogPath(String bookId) {
            return getBookDir(bookId) + "/content.ob";
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

        // ========== 目录缓存 ==========
        public void saveCatalog(String bookId, List<ChapterInfo> catalog) {
            File dir = new File(getBookDir(bookId));
            if (!dir.exists()) dir.mkdirs();
            File file = new File(getCatalogPath(bookId));
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                for (ChapterInfo info : catalog) {
                    writer.write(info.itemId + "@" + info.title + "\n");
                }
                writer.close();
                logger.log(Logger.INFO, "目录缓存保存成功，共 " + catalog.size() + " 章");
            } catch (IOException e) {
                logger.log(Logger.ERROR, "保存目录缓存失败: " + e.toString());
            }
        }

        public List<ChapterInfo> loadCatalog(String bookId) {
            File file = new File(getCatalogPath(bookId));
            if (!file.exists()) return null;
            List<ChapterInfo> result = new ArrayList<>();
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    int at = line.indexOf('@');
                    if (at != -1) {
                        ChapterInfo info = new ChapterInfo();
                        info.itemId = line.substring(0, at);
                        info.title = line.substring(at + 1);
                        result.add(info);
                    }
                }
                reader.close();
                logger.log(Logger.INFO, "目录缓存加载成功，共 " + result.size() + " 章");
                return result;
            } catch (IOException e) {
                logger.log(Logger.ERROR, "读取目录缓存失败: " + e.toString());
                return null;
            }
        }
    }

    private class ApiClient {
        private List<String> apiKeys = new ArrayList<>();
        private int keyIndex = 0;
        private OkHttpClient client;

        public ApiClient(OkHttpClient client) {
            this.client = client;
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

        private String maskKey(String key) {
            if (key == null || key.length() <= 4) return "****";
            return key.substring(0, 4) + "****";
        }

        private String httpGet(String originalUrl) {
            int attempts = apiKeys.size();
            if (attempts == 0) return null;
            for (int i = 0; i < attempts; i++) {
                String key = getNextKey();
                if (key == null) continue;
                String fullUrl = originalUrl + "&apikey=" + key;
                logger.log(Logger.DEBUG, "请求URL: " + fullUrl);

                Request request = new Request.Builder()
                        .url(fullUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36")
                        .header("Connection", "close")
                        .build();

                Response response = null;
                try {
                    response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        return response.body().string();
                    } else {
                        logger.log(Logger.WARN, "请求失败，状态码: " + response.code() + ", Key: " + maskKey(key));
                    }
                } catch (Exception e) {
                    logger.log(Logger.WARN, "请求异常: " + e.toString() + ", Key: " + maskKey(key));
                } finally {
                    if (response != null) response.close();
                }
            }
            return null;
        }

        public List<ChapterInfo> fetchCatalog(String bookId) {
            String url = API_BASE + "?type=3&bookid=" + bookId;
            String json = httpGet(url);
            if (json == null) return null;
            try {
                JSONObject root = new JSONObject(json);
                if (root.optInt("code", -1) != 0) {
                    logger.log(Logger.ERROR, "目录API返回错误码");
                    return null;
                }
                JSONArray list = root.getJSONObject("data").getJSONArray("item_data_list");
                List<ChapterInfo> result = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    ChapterInfo info = new ChapterInfo();
                    info.itemId = item.getString("item_id");
                    info.title = item.getString("title");
                    result.add(info);
                }
                return result;
            } catch (Exception e) {
                logger.log(Logger.ERROR, "解析目录JSON失败: " + e.toString());
                return null;
            }
        }

        public String fetchChapterContent(String itemId) {
            String url = API_BASE + "?type=4&itemid=" + itemId;
            String json = httpGet(url);
            if (json == null) return null;
            try {
                JSONObject root = new JSONObject(json);
                if (!"0".equals(root.optString("code"))) {
                    logger.log(Logger.ERROR, "章节内容API返回错误码");
                    return null;
                }
                String rawContent = root.getJSONObject("data").getString("content");
                return cleanContent(rawContent);
            } catch (Exception e) {
                logger.log(Logger.ERROR, "解析章节内容失败: " + e.toString());
                return null;
            }
        }

        private String cleanContent(String raw) {
            String decoded = Html.fromHtml(raw).toString();
            String warning = "为保证服务质量，免费用户请不要下书！或前往网站赞助后刷新隐藏该提示(赞助用户一天可下载一万章)";
            decoded = decoded.replace(warning, "");
            decoded = decoded.replaceAll("\n{3,}", "\n\n");
            decoded = decoded.trim();
            decoded = decoded.replace("\r\n", "\n").replace("\r", "\n");
            return decoded;
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
            cleanOldLogs();
            String timeStamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
            logDir = LOG_DIR + "/" + timeStamp;
            logFile = logDir + "/logs.ob";
            File dir = new File(logDir);
            if (!dir.exists()) dir.mkdirs();
            log(INFO, "日志系统初始化");
        }

        private void cleanOldLogs() {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            File logsDir = new File(LOG_DIR);
            if (!logsDir.exists() || !logsDir.isDirectory()) return;
            File[] children = logsDir.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (child.isDirectory()) {
                    String name = child.getName();
                    if (!name.startsWith(today)) {
                        deleteRecursive(child);
                    }
                }
            }
        }

        private void deleteRecursive(File file) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            }
            file.delete();
        }

        public synchronized void log(String level, String msg) {
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

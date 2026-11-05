package ru.flibusta.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Html;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.InputMethodManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class MainActivity extends Activity {
    private static final String BASE = "http://flibusta.is";

    private TextInputEditText query;
    private MaterialSwitch modeSwitch;
    private TextView booksLabel, authorsLabel;
    private MaterialButton findButton, resetButton, downloadButton, backButton;
    private LinearProgressIndicator progress;
    private TextView connection, headerTitle;
    private LinearLayout resultsBox, navHeader;

    private final List<CatalogItem> items = new ArrayList<>();
    private int selected = -1;
    private volatile boolean connected = false;

    private volatile boolean isRetrying = false;
    private volatile boolean isRetryCancelled = false;
    private Thread retryThread = null;

    private final Stack<NavState> navStack = new Stack<>();

    private String currentUrl = null;
    private String currentTitle = null;
    private String currentQueryText = null;
    private String currentModeText = null;

    static class NavState {
        String url;
        String title;
        String queryText;
        String modeText;

        NavState(String url, String title, String queryText, String modeText) {
            this.url = url;
            this.title = title;
            this.queryText = queryText;
            this.modeText = modeText;
        }
    }

    static class CatalogItem {
        String title = "";
        String subtitle = "";
        String description = "";
        String cover = "";

        String downloadUrl = "";
        String mimeType = "";
        String navUrl = "";

        boolean isBook = false;
        boolean isAuthor = false;
        boolean isFolder = false;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        query = findViewById(R.id.query);
        modeSwitch = findViewById(R.id.mode);
        booksLabel = findViewById(R.id.booksLabel);
        authorsLabel = findViewById(R.id.authorsLabel);
        findButton = findViewById(R.id.findButton);
        resetButton = findViewById(R.id.resetButton);
        downloadButton = findViewById(R.id.downloadButton);
        backButton = findViewById(R.id.backButton);
        progress = findViewById(R.id.progress);
        connection = findViewById(R.id.connection);
        headerTitle = findViewById(R.id.headerTitle);
        resultsBox = findViewById(R.id.resultsBox);
        navHeader = findViewById(R.id.navHeader);

        if (query == null || modeSwitch == null || findButton == null) {
            Toast.makeText(this, "Ошибка интерфейса. Сделайте Build -> Rebuild Project.", Toast.LENGTH_LONG).show();
            return;
        }

        if (backButton != null) backButton.setVisibility(View.GONE);
        if (headerTitle != null) headerTitle.setVisibility(View.GONE);
        if (navHeader != null) navHeader.setVisibility(View.GONE);

        // Настройка переключателя режима поиска
        modeSwitch.setChecked(false); // По умолчанию: Книги
        updateModeLabels(false);

        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateModeLabels(isChecked);
        });

        findButton.setOnClickListener(v -> search());

        resetButton.setOnClickListener(v -> {
            if (isRetrying) {
                cancelRetry();
            } else {
                resetSearch();
            }
        });

        downloadButton.setOnClickListener(v -> downloadSelected());

        if (backButton != null) {
            backButton.setOnClickListener(v -> goBack());
        }

        connectLoop();
    }

    private void updateModeLabels(boolean isAuthorsMode) {
        if (isAuthorsMode) {
            // Режим "Авторы" - правая подпись активна
            booksLabel.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            booksLabel.setTypeface(null, android.graphics.Typeface.NORMAL);
            authorsLabel.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface));
            authorsLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            // Режим "Книги" - левая подпись активна
            booksLabel.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface));
            booksLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            authorsLabel.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            authorsLabel.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private int getThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    private String getModeText() {
        return modeSwitch.isChecked() ? "Авторы" : "Книги";
    }

    private void goBack() {
        if (isRetrying) cancelRetry();

        if (navStack.isEmpty()) {
            currentUrl = null;
            currentTitle = null;
            currentQueryText = null;
            currentModeText = null;
            if (backButton != null) backButton.setVisibility(View.GONE);
            if (headerTitle != null) headerTitle.setVisibility(View.GONE);
            if (navHeader != null) navHeader.setVisibility(View.GONE);
            resetSearch();
            return;
        }

        NavState previous = navStack.pop();

        if (previous.url != null) {
            currentUrl = previous.url;
            currentTitle = previous.title;
            if (headerTitle != null) {
                headerTitle.setVisibility(View.VISIBLE);
                headerTitle.setText(currentTitle != null ? currentTitle : "Каталог");
            }
            loadUrl(previous.url);
        } else {
            currentUrl = null;
            currentTitle = null;
            if (backButton != null) backButton.setVisibility(View.GONE);
            if (headerTitle != null) headerTitle.setVisibility(View.GONE);
            if (navHeader != null) navHeader.setVisibility(View.GONE);

            if (previous.queryText != null && !previous.queryText.isEmpty()) {
                query.setText(previous.queryText);
                modeSwitch.setChecked("Авторы".equals(previous.modeText));
                updateModeLabels("Авторы".equals(previous.modeText));
                performSearchInternal(previous.queryText, previous.modeText);
            } else {
                resetSearch();
            }
        }
    }

    private void resetSearch() {
        if (isRetrying) cancelRetry();

        if (query != null) query.setText("");
        if (resultsBox != null) resultsBox.removeAllViews();
        items.clear();
        selected = -1;
        navStack.clear();
        currentUrl = null;
        currentTitle = null;
        currentQueryText = null;
        currentModeText = null;
        if (downloadButton != null) downloadButton.setEnabled(false);
        if (connection != null) connection.setText(connected ? "Подключено к flibusta.is" : "Нет соединения");
        if (backButton != null) backButton.setVisibility(View.GONE);
        if (headerTitle != null) headerTitle.setVisibility(View.GONE);
        if (navHeader != null) navHeader.setVisibility(View.GONE);

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && query != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void connectLoop() {
        new Thread(() -> {
            int attempt = 0;
            while (!connected && !isFinishing()) {
                attempt++;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/opds").openConnection();
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 400) {
                        InputStream in = c.getInputStream();
                        in.close();
                        connected = true;
                    }
                    c.disconnect();
                } catch (Exception ignored) {}

                int finalAttempt = attempt;
                runOnUiThread(() -> {
                    if (!connected && connection != null && !isRetrying) {
                        connection.setText("Подключение... (" + finalAttempt + ")");
                    } else if (connection != null && !isRetrying) {
                        connection.setText("Подключено к flibusta.is");
                    }
                });

                try { Thread.sleep(Math.min(30000, 1000 * finalAttempt)); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private void search() {
        if (query == null) return;
        String q = query.getText() != null ? query.getText().toString().trim() : "";
        if (q.isEmpty()) {
            query.setError("Введите запрос");
            return;
        }

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);

        navStack.clear();
        currentUrl = null;
        currentTitle = null;
        if (backButton != null) backButton.setVisibility(View.GONE);
        if (headerTitle != null) headerTitle.setVisibility(View.GONE);
        if (navHeader != null) navHeader.setVisibility(View.GONE);

        performSearchInternal(q, getModeText());
    }

    private void performSearchInternal(String queryText, String modeText) {
        currentQueryText = queryText;
        currentModeText = modeText;

        findButton.setEnabled(false);
        resetButton.setEnabled(true);
        setResetButtonToReset();
        progress.setVisibility(View.VISIBLE);
        connection.setText("Загрузка…");
        resultsBox.removeAllViews();
        items.clear();
        selected = -1;
        downloadButton.setEnabled(false);

        new Thread(() -> {
            List<CatalogItem> found = new ArrayList<>();
            String error = null;
            String url = null;
            try {
                String type = modeText.contains("Автор") ? "authors" : "books";
                String encodedQuery = URLEncoder.encode(queryText, "UTF-8");
                url = BASE + "/opds/search?searchTerm=" + encodedQuery + "&searchType=" + type + "&pageNumber=0";

                String xml = get(url);

                if (modeText.contains("Автор")) {
                    found = parseAuthorsFromSearch(xml, url);
                } else {
                    found = parseCatalog(xml, url);
                }
            } catch (Exception e) {
                error = e.getMessage();
                android.util.Log.e("Flibusta", "Search Error", e);
                if (isNetworkError(e)) {
                    startRetry(url, modeText.contains("Автор"), modeText);
                    return;
                }
            }

            showResults(found, error);
        }).start();
    }

    private void navigateTo(String url, String title) {
        NavState current;
        if (currentUrl != null) {
            current = new NavState(currentUrl, currentTitle, null, null);
        } else {
            current = new NavState(null, null, currentQueryText, currentModeText);
        }
        navStack.push(current);

        currentUrl = url;
        currentTitle = title;

        if (navHeader != null) navHeader.setVisibility(View.VISIBLE);
        if (backButton != null) backButton.setVisibility(View.VISIBLE);
        if (headerTitle != null) {
            headerTitle.setVisibility(View.VISIBLE);
            headerTitle.setText(title != null ? title : "Каталог");
        }

        loadUrl(url);
    }

    private void loadUrl(String url) {
        findButton.setEnabled(false);
        resetButton.setEnabled(true);
        setResetButtonToReset();
        progress.setVisibility(View.VISIBLE);
        connection.setText("Загрузка…");
        resultsBox.removeAllViews();
        items.clear();
        selected = -1;
        downloadButton.setEnabled(false);

        new Thread(() -> {
            List<CatalogItem> found = new ArrayList<>();
            String error = null;
            try {
                String xml = get(url);
                found = parseCatalog(xml, url);
            } catch (Exception e) {
                error = e.getMessage();
                android.util.Log.e("Flibusta", "Nav Error", e);
                if (isNetworkError(e)) {
                    startRetry(url, false, "");
                    return;
                }
            }

            showResults(found, error);
        }).start();
    }

    private boolean isNetworkError(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof java.net.UnknownHostException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e.getMessage() != null) {
            String msg = e.getMessage().toLowerCase();
            return msg.contains("timeout") || msg.contains("network") || msg.contains("connection");
        }
        return false;
    }

    private void startRetry(final String url, final boolean isAuthorSearch, final String modeText) {
        isRetrying = true;
        isRetryCancelled = false;

        runOnUiThread(() -> {
            setResetButtonToCancel();
            connection.setText("Таймаут. Повторная попытка...");
        });

        retryThread = new Thread(() -> {
            int delay = 3000;

            while (!isRetryCancelled && !isFinishing()) {
                try {
                    Thread.sleep(delay);
                    if (isRetryCancelled) break;

                    delay = Math.min(delay + 2000, 15000);
                    final int attempt = (delay - 3000) / 2000 + 1;

                    int finalDelay = delay;
                    runOnUiThread(() -> {
                        if (!isRetrying) return;
                        connection.setText("Таймаут. Попытка #" + attempt + " через " + (finalDelay /1000) + " сек...");
                    });

                    String xml = get(url);
                    List<CatalogItem> found;
                    if (isAuthorSearch) {
                        found = parseAuthorsFromSearch(xml, url);
                    } else {
                        found = parseCatalog(xml, url);
                    }

                    final List<CatalogItem> result = found;
                    runOnUiThread(() -> {
                        isRetrying = false;
                        setResetButtonToReset();
                        showResults(result, null);
                    });
                    return;

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // Ошибка сети, продолжаем цикл
                }
            }

            runOnUiThread(() -> {
                isRetrying = false;
                setResetButtonToReset();
                progress.setVisibility(View.GONE);
                findButton.setEnabled(true);
                if (isRetryCancelled) {
                    connection.setText("Поиск отменен");
                } else {
                    connection.setText(connected ? "Подключено" : "Нет соединения");
                }
            });
        });
        retryThread.start();
    }

    private void cancelRetry() {
        isRetryCancelled = true;
        if (retryThread != null) retryThread.interrupt();
        isRetrying = false;

        runOnUiThread(() -> {
            setResetButtonToReset();
            progress.setVisibility(View.GONE);
            findButton.setEnabled(true);
            connection.setText("Поиск отменен");
        });
    }

    private void setResetButtonToCancel() {
        if (resetButton != null) {
            resetButton.setText("Отмена");
            resetButton.setIconResource(android.R.drawable.ic_menu_close_clear_cancel);
        }
    }

    private void setResetButtonToReset() {
        if (resetButton != null) {
            resetButton.setText("Сбросить");
            resetButton.setIconResource(android.R.drawable.ic_menu_close_clear_cancel);
        }
    }

    private void showResults(List<CatalogItem> result, String error) {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            findButton.setEnabled(true);
            resetButton.setEnabled(true);
            connection.setText(connected ? "Подключено к flibusta.is" : "Нет соединения");

            if (error != null) {
                addMessage("Ошибка: " + error);
            } else if (result == null || result.isEmpty()) {
                addMessage("Пусто.");
            } else {
                items.addAll(result);
                for (int i = 0; i < items.size(); i++) addItemRow(i, items.get(i));
            }
        });
    }

    private List<CatalogItem> parseAuthorsFromSearch(String xml, String baseUrl) throws Exception {
        List<CatalogItem> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new java.io.StringReader(xml));

        CatalogItem current = null;
        StringBuilder value = new StringBuilder();

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            int event = p.getEventType();
            if (event == XmlPullParser.START_TAG) {
                if ("entry".equals(p.getName())) current = new CatalogItem();

                if (current != null && "link".equals(p.getName())) {
                    String href = p.getAttributeValue(null, "href");
                    String rel = p.getAttributeValue(null, "rel");
                    if (href != null) {
                        String absolute = new URL(new URL(baseUrl), href).toString();
                        if (absolute.contains("/ia/") || (rel != null && rel.contains("image"))) {
                            current.cover = absolute;
                        }
                        if (absolute.contains("/opds/author/")) {
                            current.navUrl = absolute;
                        }
                    }
                }
            } else if (event == XmlPullParser.TEXT) {
                value.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if (current != null) {
                    if ("title".equals(p.getName())) current.title = value.toString().trim();
                    else if ("entry".equals(p.getName())) {
                        if (!current.title.isEmpty() && !current.navUrl.isEmpty()) {
                            current.isAuthor = true;
                            current.subtitle = "Автор";
                            out.add(current);
                        }
                        current = null;
                    }
                }
                value.setLength(0);
            }
        }
        return out;
    }

    private List<CatalogItem> parseCatalog(String xml, String baseUrl) throws Exception {
        List<CatalogItem> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new java.io.StringReader(xml));

        CatalogItem current = null;
        StringBuilder value = new StringBuilder();

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            int event = p.getEventType();
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                value.setLength(0);
                if ("entry".equals(tag)) current = new CatalogItem();

                if (current != null && "link".equals(tag)) {
                    String href = p.getAttributeValue(null, "href");
                    String rel = p.getAttributeValue(null, "rel");
                    String type = p.getAttributeValue(null, "type");

                    if (href != null) {
                        String absolute = new URL(new URL(baseUrl), href).toString();

                        if ((rel != null && rel.contains("image")) || (type != null && type.startsWith("image/"))) {
                            current.cover = absolute;
                        }

                        if (rel != null && rel.contains("acquisition")) {
                            String lowerType = type == null ? "" : type.toLowerCase();
                            String lowerHref = absolute.toLowerCase();

                            if (lowerType.contains("epub") || lowerHref.contains(".epub")) {
                                current.downloadUrl = absolute;
                                current.mimeType = lowerType;
                            }
                            else if (current.downloadUrl.isEmpty() && (lowerType.contains("fb2") || lowerHref.contains(".fb2") || lowerType.contains("zip"))) {
                                current.downloadUrl = absolute;
                                current.mimeType = lowerType;
                            }
                            else if (current.downloadUrl.isEmpty() && lowerType.contains("mobi")) {
                                current.downloadUrl = absolute;
                                current.mimeType = lowerType;
                            }
                            else if (current.downloadUrl.isEmpty() && lowerType.contains("pdf")) {
                                current.downloadUrl = absolute;
                                current.mimeType = lowerType;
                            }
                            else if (current.downloadUrl.isEmpty()) {
                                current.downloadUrl = absolute;
                                current.mimeType = lowerType;
                            }

                            current.isBook = true;
                            current.isFolder = false;
                        }

                        if (!current.isBook) {
                            boolean isNavLink = false;
                            if (rel != null) {
                                if (rel.contains("subsection") || rel.contains("collection") ||
                                        rel.contains("http://opds-spec.org/sort") ||
                                        rel.contains("http://www.feedbooks.com/opds/facet")) {
                                    isNavLink = true;
                                }
                            }
                            if (absolute.contains("/opds/author/") ||
                                    absolute.contains("/opds/authorsequences/") ||
                                    absolute.contains("/opds/authorsequenceless/")) {
                                isNavLink = true;
                            }
                            if (isNavLink && current.navUrl.isEmpty()) {
                                current.navUrl = absolute;
                                current.isFolder = true;
                            }
                        }
                    }
                }
            } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                value.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String closing = p.getName();
                if (current != null) {
                    String v = value.toString().trim();
                    if ("title".equals(closing) && current.title.isEmpty()) current.title = v;
                    else if ("name".equals(closing) && current.subtitle.isEmpty()) current.subtitle = v;
                    else if (("summary".equals(closing) || "content".equals(closing)) && current.description.isEmpty()) {
                        current.description = v;
                    }
                    else if ("entry".equals(closing)) {
                        if (!current.title.isEmpty()) {
                            if (current.isBook || !current.navUrl.isEmpty()) {
                                out.add(current);
                            }
                        }
                        current = null;
                    }
                }
            }
        }
        return out;
    }

    private String get(String urlString) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);

        int responseCode = c.getResponseCode();
        InputStream in = (responseCode >= 200 && responseCode < 300) ? c.getInputStream() : c.getErrorStream();
        if (in == null) throw new Exception("Ошибка сервера: " + responseCode);

        java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
        String result = s.hasNext() ? s.next() : "";
        s.close();
        c.disconnect();

        if (result.contains("<html>")) throw new Exception("Получен HTML вместо XML");
        return result;
    }

    private void addMessage(String msg) {
        if (resultsBox == null) return;
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextColor(Color.parseColor("#757575"));
        resultsBox.addView(tv);
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void addItemRow(int index, CatalogItem item) {
        if (resultsBox == null) return;

        int[] attrs = {
                com.google.android.material.R.attr.colorOnSurface,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                com.google.android.material.R.attr.colorPrimary
        };
        TypedArray ta = obtainStyledAttributes(attrs);
        int colorPrimary = ta.getColor(0, Color.BLACK);
        int colorSecondary = ta.getColor(1, Color.DKGRAY);
        int colorAccent = ta.getColor(2, Color.BLUE);
        ta.recycle();

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(16);
        card.setCheckable(item.isBook);
        card.setChecked(false);
        card.setStrokeWidth(0);
        card.setStrokeColor(colorAccent);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 16);
        card.setLayoutParams(params);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dpToPx(12);
        inner.setPadding(pad, pad, pad, pad);
        inner.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconView = new ImageView(this);

        int width = dpToPx(item.isFolder ? 48 : 80);
        int height = dpToPx(item.isFolder ? 48 : 120);

        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(width, height);
        iconParams.setMargins(0, 0, dpToPx(16), 0);
        iconView.setLayoutParams(iconParams);

        if (item.isFolder) {
            iconView.setImageResource(R.drawable.ic_folder);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setBackgroundColor(Color.TRANSPARENT);
        } else if (item.isAuthor) {
            iconView.setImageResource(android.R.drawable.ic_menu_myplaces);
            iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconView.setBackgroundColor(Color.LTGRAY);
        } else {
            iconView.setImageResource(android.R.drawable.ic_menu_gallery);
            iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconView.setBackgroundColor(Color.LTGRAY);
        }

        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextSize(16);
        title.setTextColor(colorPrimary);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", item.isFolder ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);

        TextView subtitle = new TextView(this);
        subtitle.setText(item.subtitle);
        subtitle.setTextSize(14);
        subtitle.setTextColor(colorSecondary);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);

        TextView desc = new TextView(this);
        if (item.description != null && !item.description.isEmpty()) {
            desc.setText(Html.fromHtml(item.description, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim());
        }
        desc.setTextSize(12);
        desc.setTextColor(colorSecondary);
        desc.setMaxLines(3);
        desc.setEllipsize(TextUtils.TruncateAt.END);

        textContainer.addView(title);
        if (!item.subtitle.isEmpty()) textContainer.addView(subtitle);
        if (!desc.getText().toString().isEmpty()) textContainer.addView(desc);

        inner.addView(iconView);
        inner.addView(textContainer);
        card.addView(inner);

        if (!item.isFolder && item.cover != null && !item.cover.isEmpty()) {
            loadCoverImage(iconView, item.cover);
        }

        card.setOnClickListener(v -> {
            if (item.isFolder || item.isAuthor) {
                navigateTo(item.navUrl, item.title);
            } else if (item.isBook) {
                selected = index;
                downloadButton.setEnabled(!item.downloadUrl.isEmpty());
                for (int i = 0; i < resultsBox.getChildCount(); i++) {
                    View child = resultsBox.getChildAt(i);
                    if (child instanceof MaterialCardView) {
                        MaterialCardView cv = (MaterialCardView) child;
                        cv.setChecked(i == index);
                        cv.setStrokeWidth(i == index ? dpToPx(2) : 0);
                    }
                }
            }
        });

        resultsBox.addView(card);
    }

    private void loadCoverImage(ImageView imageView, String url) {
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                InputStream is = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();
                connection.disconnect();
                if (bitmap != null) runOnUiThread(() -> imageView.setImageBitmap(bitmap));
            } catch (Exception e) {}
        }).start();
    }

    private void downloadSelected() {
        if (selected < 0 || selected >= items.size()) return;
        CatalogItem item = items.get(selected);

        if (item.downloadUrl.isEmpty()) {
            Toast.makeText(this, "Нет ссылки для скачивания", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(item.downloadUrl));
            r.setTitle(item.title);
            r.setDescription("Загрузка с Flibusta");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String ext = ".fb2";
            String lowerUrl = item.downloadUrl.toLowerCase();
            String lowerMime = item.mimeType.toLowerCase();

            if (lowerMime.contains("epub") || lowerUrl.contains(".epub")) {
                ext = ".epub";
            } else if (lowerMime.contains("mobi") || lowerUrl.contains(".mobi")) {
                ext = ".mobi";
            } else if (lowerMime.contains("pdf") || lowerUrl.contains(".pdf")) {
                ext = ".pdf";
            } else if (lowerMime.contains("zip") || lowerMime.contains("fb2")) {
                ext = ".fb2";
            }

            String safeTitle = item.title.replaceAll("[/:*?\"<>|]", "_").trim();
            if (safeTitle.length() > 90) safeTitle = safeTitle.substring(0, 90);

            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeTitle + ext);
            r.setAllowedOverMetered(true);
            r.setAllowedOverRoaming(true);

            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Загрузка началась. Файл появится в Downloads.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
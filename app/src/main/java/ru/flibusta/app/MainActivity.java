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
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Typeface;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class MainActivity extends Activity {
    private static final String BASE = "http://flibusta.is";
    private TextInputEditText query;
    private AutoCompleteTextView mode;
    private MaterialButton findButton, resetButton, downloadButton;
    private LinearProgressIndicator progress;
    private TextView connection;
    private LinearLayout resultsBox;
    private final List<Book> books = new ArrayList<>();
    private int selected = -1;
    private volatile boolean connected = false;

    static class Book {
        String title = "", author = "", description = "", cover = "";
        String downloadEpub = "", downloadFallback = "", mime = "";
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        query = findViewById(R.id.query);
        mode = findViewById(R.id.mode);
        findButton = findViewById(R.id.findButton);
        resetButton = findViewById(R.id.resetButton);
        downloadButton = findViewById(R.id.downloadButton);
        progress = findViewById(R.id.progress);
        connection = findViewById(R.id.connection);
        resultsBox = findViewById(R.id.resultsBox);

        String[] modes = new String[]{"Название", "Автор"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, modes);
        mode.setAdapter(adapter);
        mode.setText("Название", false);

        findButton.setOnClickListener(v -> search());
        resetButton.setOnClickListener(v -> resetSearch());
        downloadButton.setOnClickListener(v -> downloadSelected());

        connectLoop();
    }

    private void resetSearch() {
        if (query != null) query.setText("");
        if (resultsBox != null) resultsBox.removeAllViews();
        books.clear();
        selected = -1;
        if (downloadButton != null) downloadButton.setEnabled(false);
        if (connection != null) connection.setText(connected ? "Подключено к flibusta.is" : "Нет соединения с каталогом");

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
                    if (!connected && connection != null) {
                        connection.setText("Попытка подключения... (" + finalAttempt + ")");
                    } else if (connection != null) {
                        connection.setText("Подключено к flibusta.is");
                    }
                });

                try { Thread.sleep(Math.min(30000, 1000 * finalAttempt)); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private void search() {
        if (query == null || mode == null) return;

        String q = query.getText() != null ? query.getText().toString().trim() : "";
        if (q.isEmpty()) {
            query.setError("Введите запрос");
            return;
        }

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);

        findButton.setEnabled(false);
        resetButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        connection.setText("Выполняется поиск…");
        resultsBox.removeAllViews();
        books.clear();
        selected = -1;
        downloadButton.setEnabled(false);

        new Thread(() -> {
            List<Book> found = new ArrayList<>();
            String error = null;
            try {
                String modeText = mode.getText() != null ? mode.getText().toString() : "Книги";
                String type = modeText.contains("Автор") ? "authors" : "books";
                String encodedQuery = URLEncoder.encode(q, "UTF-8");
                String url = BASE + "/opds/search?searchTerm=" + encodedQuery + "&searchType=" + type + "&pageNumber=0";

                String xml = get(url);
                if ("authors".equals(type)) {
                    List<String> authorFeeds = parseAuthorFeeds(xml, url);
                    for (String feed : authorFeeds) found.addAll(parseBooks(get(feed), feed));
                    if (found.isEmpty()) found = parseBooks(xml, url);
                } else {
                    found = parseBooks(xml, url);
                }
            } catch (Exception e) {
                error = e.getMessage();
            }

            final List<Book> result = found;
            final String err = error;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                findButton.setEnabled(true);
                resetButton.setEnabled(true);
                connection.setText(connected ? "Подключено к flibusta.is" : "Нет соединения с каталогом");

                if (err != null) {
                    addMessage("Ошибка: " + err);
                } else if (result.isEmpty()) {
                    addMessage("Ничего не найдено.");
                } else {
                    books.addAll(result);
                    for (int i = 0; i < books.size(); i++) addBookRow(i, books.get(i));
                }
            });
        }).start();
    }

    private String get(String urlString) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);

        int responseCode = c.getResponseCode();
        InputStream in;
        if (responseCode >= 200 && responseCode < 300) {
            in = c.getInputStream();
        } else {
            in = c.getErrorStream();
            if (in == null) throw new Exception("Ошибка сервера: код " + responseCode);
        }

        java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
        String result = s.hasNext() ? s.next() : "";
        s.close();
        c.disconnect();

        if (result.contains("<html>") || result.contains("<!DOCTYPE html>")) {
            throw new Exception("Сервер вернул HTML вместо XML (возможна временная блокировка).");
        }
        return result;
    }

    private List<String> parseAuthorFeeds(String xml, String baseUrl) throws Exception {
        List<String> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;

        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new java.io.StringReader(xml));
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.getEventType() == XmlPullParser.START_TAG && "link".equals(p.getName())) {
                String rel = p.getAttributeValue(null, "rel");
                String href = p.getAttributeValue(null, "href");
                if (rel != null && rel.contains("subsection") && href != null) {
                    out.add(new URL(new URL(baseUrl), href).toString());
                }
            }
        }
        return out;
    }

    private List<Book> parseBooks(String xml, String baseUrl) throws Exception {
        List<Book> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;

        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new java.io.StringReader(xml));
        Book current = null;
        StringBuilder value = new StringBuilder();

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            int event = p.getEventType();
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                value.setLength(0);
                if ("entry".equals(tag)) current = new Book();

                if (current != null && "link".equals(tag)) {
                    String href = p.getAttributeValue(null, "href");
                    String rel = p.getAttributeValue(null, "rel");
                    String type = p.getAttributeValue(null, "type");
                    if (href != null) {
                        String absolute = new URL(new URL(baseUrl), href).toString();
                        if ((rel != null && (rel.contains("image") || rel.contains("thumbnail"))) || (type != null && type.startsWith("image/"))) {
                            current.cover = absolute;
                        }
                        if (rel != null && rel.contains("acquisition")) {
                            String lowerType = type == null ? "" : type.toLowerCase();
                            if (lowerType.contains("epub")) {
                                current.downloadEpub = absolute;
                                current.mime = lowerType;
                            } else if (current.downloadFallback.isEmpty()) {
                                current.downloadFallback = absolute;
                                if (current.mime.isEmpty()) current.mime = lowerType;
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
                    else if ("name".equals(closing) && current.author.isEmpty()) current.author = v;
                    else if (("summary".equals(closing) || "content".equals(closing)) && current.description.isEmpty()) {
                        current.description = v;
                    } else if ("entry".equals(closing)) {
                        if (!current.title.isEmpty() && !"Авторы".equals(current.title)) out.add(current);
                        current = null;
                    }
                }
            }
        }
        return out;
    }

    private void addMessage(String msg) {
        if (resultsBox == null) return;
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextColor(Color.parseColor("#757575"));
        resultsBox.addView(tv);
    }

    // Вспомогательный метод для получения цвета из темы (поддержка темной/светлой темы)
    private int getThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    // Вспомогательный метод для конвертации dp в пиксели
    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void addBookRow(int index, Book b) {
        if (resultsBox == null) return;

        // Надежное получение цветов Material 3 (для светлой и темной темы)
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
        card.setCheckable(true);
        card.setChecked(false);
        card.setStrokeWidth(0);
        card.setStrokeColor(colorAccent);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 16);
        card.setLayoutParams(params);

        // Горизонтальный контейнер
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dpToPx(12);
        inner.setPadding(pad, pad, pad, pad);
        inner.setGravity(Gravity.TOP); // Выравнивание по верху, чтобы текст начинался сверху

        // 1. Обложка книги
        ImageView coverView = new ImageView(this);
        int coverWidth = dpToPx(80);
        int coverHeight = dpToPx(120);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(coverWidth, coverHeight);
        coverParams.setMargins(0, 0, dpToPx(16), 0);
        coverView.setLayoutParams(coverParams);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setBackgroundColor(Color.LTGRAY);
        coverView.setImageResource(android.R.drawable.ic_menu_gallery);

        // 2. Контейнер для текста (Используем weight=1.0f, чтобы занять всё оставшееся место)
        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textContainerParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        textContainer.setLayoutParams(textContainerParams);

        // === НАЗВАНИЕ (Жирный шрифт) ===
        TextView title = new TextView(this);
        title.setText(b.title);
        title.setTextSize(16);
        title.setTextColor(colorPrimary);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dpToPx(4);
        title.setLayoutParams(titleParams);

        // === АВТОР (Менее жирный / Medium) ===
        TextView author = new TextView(this);
        author.setText(b.author);
        author.setTextSize(14);
        author.setTextColor(colorSecondary);
        author.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        author.setMaxLines(1);
        author.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        authorParams.bottomMargin = dpToPx(8);
        author.setLayoutParams(authorParams);

        // === ОПИСАНИЕ (Обычный шрифт) ===
        TextView description = new TextView(this);
        String descText = "";
        if (b.description != null && !b.description.isEmpty()) {
            // Очищаем HTML-теги
            descText = Html.fromHtml(b.description, Html.FROM_HTML_MODE_LEGACY).toString();
            descText = descText.replaceAll("\\s+", " ").trim();
        }
        description.setText(descText);
        description.setTextSize(12);
        description.setTextColor(colorSecondary);
        description.setTypeface(Typeface.DEFAULT); // Обычный шрифт
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        description.setLayoutParams(descParams);

        // Собираем текстовый блок
        textContainer.addView(title);
        if (!b.author.isEmpty()) textContainer.addView(author);
        if (!descText.isEmpty()) textContainer.addView(description);

        // Собираем карточку
        inner.addView(coverView);
        inner.addView(textContainer);
        card.addView(inner);

        // Загрузка обложки
        if (b.cover != null && !b.cover.isEmpty()) {
            loadCoverImage(coverView, b.cover);
        }

        // Логика выделения
        card.setOnClickListener(v -> {
            selected = index;
            downloadButton.setEnabled(!b.downloadEpub.isEmpty() || !b.downloadFallback.isEmpty());
            for (int i = 0; i < resultsBox.getChildCount(); i++) {
                View child = resultsBox.getChildAt(i);
                if (child instanceof MaterialCardView) {
                    MaterialCardView cv = (MaterialCardView) child;
                    boolean isSelected = (i == index);
                    cv.setChecked(isSelected);
                    cv.setStrokeWidth(isSelected ? dpToPx(2) : 0);
                }
            }
        });

        resultsBox.addView(card);
    }

    // Метод для асинхронной загрузки изображения
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

                if (bitmap != null) {
                    runOnUiThread(() -> imageView.setImageBitmap(bitmap));
                }
            } catch (Exception e) {
                // Если изображение не загрузилось, останется заглушка
            }
        }).start();
    }

    private void downloadSelected() {
        if (selected < 0 || selected >= books.size()) return;
        Book b = books.get(selected);

        String urlToDownload = b.downloadEpub.isEmpty() ? b.downloadFallback : b.downloadEpub;

        if (urlToDownload.isEmpty()) {
            Toast.makeText(this, "Ссылка на файл не найдена", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(urlToDownload));
            r.setTitle(b.title);
            r.setDescription("Загрузка книги с Flibusta");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String ext = ".fb2";
            if (b.mime.contains("epub") || !b.downloadEpub.isEmpty()) ext = ".epub";
            else if (b.mime.contains("pdf")) ext = ".pdf";
            else if (b.mime.contains("mobi")) ext = ".mobi";
            else if (b.mime.contains("zip")) ext = ".zip";

            String safeTitle = b.title.replaceAll("[/:*?\"<>|]", "_").trim();
            if (safeTitle.length() > 90) safeTitle = safeTitle.substring(0, 90);

            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeTitle + ext);
            r.setAllowedOverMetered(true);
            r.setAllowedOverRoaming(true);

            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Загрузка добавлена. Файл появится в Downloads.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось начать загрузку: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
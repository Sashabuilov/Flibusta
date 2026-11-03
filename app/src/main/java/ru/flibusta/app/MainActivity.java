package ru.flibusta.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String BASE = "http://flibusta.is";
    private static final int BG = Color.rgb(8, 37, 51), PANEL = Color.rgb(15, 62, 79), GOLD = Color.rgb(255, 184, 58), TEXT = Color.rgb(245, 239, 220);
    private LinearLayout root, resultsBox;
    private EditText query;
    private Spinner mode;
    private ProgressBar progress;
    private TextView connection;
    private Button findButton, downloadButton;
    private final List<Book> books = new ArrayList<>();
    private int selected = -1;
    private volatile boolean connected = false;

    static class Book {
        String title = "", author = "", description = "", cover = "", download = "", mime = "";
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        buildMain();
        connectLoop();
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void buildMain() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 18, 16, 12);
        root.setBackgroundColor(BG);
        setContentView(root);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("☠  Флибуста", 25, GOLD, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button about = new Button(this);
        about.setText("О приложении");
        about.setTextSize(12);
        top.addView(about);
        about.setOnClickListener(v -> showAbout());
        root.addView(top);
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(2, 12, 2, 14);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        status.addView(progress, new LinearLayout.LayoutParams(28, 28));
        connection = text(" Подключение к OPDS…", 14, TEXT, false);
        status.addView(connection);
        root.addView(status);
        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(TEXT);
        query.setHintTextColor(Color.LTGRAY);
        query.setHint("Название или автор");
        query.setTextSize(15);
        query.setPadding(12, 0, 8, 0);
        query.setBackground(shape(PANEL, 12));
        search.addView(query, new LinearLayout.LayoutParams(0, 52, 1));
        mode = new Spinner(this);
        String[] modes = {"По Наименованию", "По автору"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes);
        mode.setAdapter(adapter);
        search.addView(mode, new LinearLayout.LayoutParams(150, 52));
        findButton = new Button(this);
        findButton.setText("Найти");
        findButton.setOnClickListener(v -> search());
        search.addView(findButton);
        root.addView(search);
        TextView hint = text("Результаты поиска", 17, GOLD, true);
        hint.setPadding(2, 18, 2, 8);
        root.addView(hint);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(resultsBox);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        downloadButton = new Button(this);
        downloadButton.setText("Скачать книгу");
        downloadButton.setEnabled(false);
        downloadButton.setOnClickListener(v -> downloadSelected());
        root.addView(downloadButton);
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("О приложении")
                .setMessage("Флибуста\nВерсия 0.0.1_2026\n\nКаталог электронных книг через OPDS.\nИсточник каталога: flibusta.is")
                .setPositiveButton("Закрыть", null).show();
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
                    c.setRequestProperty("User-Agent", "FlibustaAndroid/0.0.1");
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 400) {
                        InputStream in = c.getInputStream();
                        in.close();
                        connected = true;
                    }
                    c.disconnect();
                } catch (Exception ignored) {
                }
                final int n = attempt;
                runOnUiThread(() -> {
                    if (connected) {
                        progress.setVisibility(View.GONE);
                        connection.setText("Подключено к flibusta.is");
                        connection.setTextColor(Color.rgb(117, 220, 143));
                    } else connection.setText(" Нет соединения. Повторная попытка №" + n);
                });
                if (!connected) try {
                    Thread.sleep(Math.min(30000, 3000L * Math.min(attempt, 10)));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }).start();
    }

    private void search() {
        String q = query.getText().toString().trim();
        if (q.isEmpty()) {
            query.setError("Введите запрос");
            return;
        }
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
        findButton.setEnabled(false);
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
                String type = mode.getSelectedItemPosition() == 0 ? "books" : "authors";
                String url = BASE + "/opds/search?searchTerm=" + URLEncoder.encode(q, "UTF-8") + "&searchType=" + type + "&pageNumber=0";
                String xml = get(url);
                if ("authors".equals(type)) {
                    List<String> authorFeeds = parseAuthorFeeds(xml, url);
                    for (String feed : authorFeeds) found.addAll(parseBooks(get(feed), feed));
                    if (found.isEmpty())
                        found = parseBooks(xml, url); // Compatible with mirrors that return books directly.
                } else found = parseBooks(xml, url);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<Book> result = found;
            final String err = error;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                findButton.setEnabled(true);
                connection.setText(connected ? "Подключено к flibusta.is" : "Нет соединения с каталогом");
                if (err != null) addMessage("Ошибка поиска: " + err);
                else if (result.isEmpty()) addMessage("Ничего не найдено.");
                else {
                    books.addAll(result);
                    for (int i = 0; i < books.size(); i++) addBookRow(i, books.get(i));
                }
            });
        }).start();
    }

    private String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", "FlibustaAndroid/0.0.1");
        int code = c.getResponseCode();
        if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        in.close();
        c.disconnect();
        return out.toString("UTF-8");
    }

    private List<Book> parseBooks(String xml, String baseUrl) throws Exception {
        List<Book> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new java.io.StringReader(xml));
        Book current = null;
        String tag = "";
        StringBuilder value = new StringBuilder();
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                tag = p.getName();
                value.setLength(0);
                if ("entry".equals(tag)) current = new Book();
                if (current != null && "link".equals(tag)) {
                    String href = p.getAttributeValue(null, "href"), rel = p.getAttributeValue(null, "rel"), type = p.getAttributeValue(null, "type");
                    if (href != null) {
                        String absolute = new URL(new URL(baseUrl), href).toString();
                        if ((rel != null && (rel.contains("image") || rel.contains("thumbnail"))) || (type != null && type.startsWith("image/")))
                            current.cover = absolute;
                        if (rel != null && rel.contains("acquisition") && current.download.isEmpty()) {
                            current.download = absolute;
                            current.mime = type == null ? "" : type;
                        }
                    }
                }
            } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT)
                value.append(p.getText());
            else if (event == XmlPullParser.END_TAG) {
                String closing = p.getName();
                if (current != null) {
                    String v = value.toString().trim();
                    if ("title".equals(closing) && current.title.isEmpty()) current.title = v;
                    else if ("name".equals(closing) && current.author.isEmpty()) current.author = v;
                    else if ("summary".equals(closing) || "content".equals(closing)) {
                        if (current.description.isEmpty()) current.description = v;
                    } else if ("entry".equals(closing)) {
                        if (!current.title.isEmpty() && !"Авторы".equals(current.title))
                            out.add(current);
                        current = null;
                    }
                }
                tag = "";
                value.setLength(0);
            }
        }
        return out;
    }

    private List<String> parseAuthorFeeds(String xml, String baseUrl) throws Exception {
        List<String> feeds = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new java.io.StringReader(xml));
        int e;
        while ((e = p.next()) != XmlPullParser.END_DOCUMENT)
            if (e == XmlPullParser.START_TAG && "link".equals(p.getName())) {
                String href = p.getAttributeValue(null, "href"), rel = p.getAttributeValue(null, "rel");
                if (href != null && ((rel != null && rel.contains("subsection")) || href.matches(".*/a/[0-9]+.*")))
                    feeds.add(new URL(new URL(baseUrl), href).toString().replace("/a/", "/opds/author/"));
            }
        return feeds;
    }

    private void addMessage(String s) {
        TextView t = text(s, 15, TEXT, false);
        t.setPadding(8, 18, 8, 18);
        resultsBox.addView(t);
    }

    private void addBookRow(int index, Book b) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(10, 10, 10, 10);
        card.setBackground(shape(PANEL, 14));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = 10;
        resultsBox.addView(card, cp);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(shape(Color.DKGRAY, 8));
        card.addView(cover, new LinearLayout.LayoutParams(86, 120));
        if (!b.cover.isEmpty()) loadImage(b.cover, cover);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(12, 0, 0, 0);
        card.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text(b.title, 17, TEXT, true);
        details.addView(name);
        if (!b.author.isEmpty()) {
            TextView author = text(b.author, 15, GOLD, true);
            author.setPadding(0, 5, 0, 5);
            details.addView(author);
        }
        String desc = Html.fromHtml(b.description).toString().trim();
        if (desc.length() > 420) desc = desc.substring(0, 420) + "…";
        TextView description = text(desc.isEmpty() ? "Описание отсутствует" : desc, 13, Color.LTGRAY, false);
        details.addView(description);
        card.setOnClickListener(v -> {
            selected = index;
            downloadButton.setEnabled(!b.download.isEmpty());
            for (int i = 0; i < resultsBox.getChildCount(); i++)
                resultsBox.getChildAt(i).setBackground(shape(i == index ? Color.rgb(35, 91, 105) : PANEL, 14));
        });
    }

    private void loadImage(String address, ImageView view) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                Bitmap bmp = BitmapFactory.decodeStream(c.getInputStream());
                c.disconnect();
                if (bmp != null) runOnUiThread(() -> view.setImageBitmap(bmp));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void downloadSelected() {
        if (selected < 0 || selected >= books.size()) return;
        Book b = books.get(selected);
        if (b.download.isEmpty()) {
            Toast.makeText(this, "Ссылка на файл не найдена", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(b.download));
            r.setTitle(b.title);
            r.setDescription("Загрузка книги с Flibusta");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName(b.title, b.mime));
            r.setAllowedOverMetered(true);
            r.setAllowedOverRoaming(true);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Загрузка добавлена. Файл появится в Downloads.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось начать загрузку: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String safeName(String title, String mime) {
        String ext = ".fb2";
        if (mime.contains("epub")) ext = ".epub";
        else if (mime.contains("pdf")) ext = ".pdf";
        else if (mime.contains("mobi")) ext = ".mobi";
        else if (mime.contains("zip")) ext = ".zip";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.length() > 90) s = s.substring(0, 90);
        return s + ext;
    }
}

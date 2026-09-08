package ru.romariogi.kanjihour;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Setup for the home widget and kanji on the current static lock wallpaper. */
public final class MainActivity extends Activity {
    private static final int INK = 0xFF22352F, MUTED = 0xFF6B7771, ACCENT = 0xFF38675E;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView glyph, readings, scheduleStatus, lockStatus, previewStatus, positionLabel, scaleLabel, diagnostics;
    private ImageView preview;
    private Bitmap displayedBitmap;
    private Button apply, remove;
    private ClockGuide clockGuide;
    private float draftY, draftScale;
    private final PreviewRequests<Bitmap> previewRequests = new PreviewRequests<>(
            UpdateCoordinator.executor(), this::runOnUiThread, Bitmap::recycle, this::showPreview);
    private boolean busy;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if (isScreenStarted()) runOnUiThread(() -> {
            if (!isScreenStarted()) return;
            updateStatuses();
            if ("lock_last_success".equals(key) || "lock_error".equals(key)) requestPreview();
        });
    };
    private final Runnable nextHour = new Runnable() {
        @Override public void run() {
            if (isFinishing() || !isScreenStarted()) return;
            updateCard();
            UpdateCoordinator.refresh(getApplicationContext(), false, "foreground", () -> runOnUiThread(() -> {
                if (isScreenStarted()) { updateStatuses(); requestPreview(); }
            }));
            armForegroundRefresh();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = Config.prefs(this);
        draftY = state == null ? prefs.getFloat("lock_y", Config.DEFAULT_Y) : state.getFloat("y", Config.DEFAULT_Y);
        draftScale = state == null ? prefs.getFloat("lock_scale", 1f) : state.getFloat("scale", 1f);
        buildScreen();
    }

    @Override protected void onStart() {
        super.onStart();
        previewRequests.start();
        Config.prefs(this).registerOnSharedPreferenceChangeListener(listener);
    }

    @Override protected void onStop() {
        previewRequests.stop();
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(listener);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        updateCard();
        // An operation may have completed while stopped: restore the button state too.
        setBusy(busy);
        UpdateCoordinator.refresh(getApplicationContext(), false, "foreground", () -> runOnUiThread(() -> {
            if (isScreenStarted()) { updateStatuses(); requestPreview(); }
        }));
        armForegroundRefresh();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(nextHour);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putFloat("y", draftY);
        out.putFloat("scale", draftScale);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        previewRequests.stop();
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(listener);
        handler.removeCallbacksAndMessages(null);
        replacePreview(null);
        super.onDestroy();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(36));
        scroll.addView(content);
        setContentView(scroll);
        text(content, "Кандзи · час", 32, INK, true);
        text(content, "Один знак. Каждый час.", 16, MUTED, false);
        LinearLayout now = card("Сейчас");
        glyph = text(now, "", 54, INK, false);
        glyph.setTextLocale(Locale.JAPANESE);
        readings = text(now, "", 15, INK, false);
        scheduleStatus = text(now, "", 12, MUTED, false);

        LinearLayout home = card("Главный экран");
        text(home, "Прозрачный виджет 4 × 1. Обои главного экрана не меняются.", 14, MUTED, false);
        Switch light = new Switch(this);
        light.setText("Белый текст виджета");
        light.setTextColor(INK);
        light.setChecked(Config.prefs(this).getBoolean("home_light", false));
        home.addView(light);
        light.setOnCheckedChangeListener((button, checked) -> {
            Config.prefs(this).edit().putBoolean("home_light", checked).apply();
            UpdateCoordinator.refresh(getApplicationContext(), false, null);
        });
        button(home, "Добавить виджет", this::pinWidget);

        LinearLayout lock = card("Экран блокировки");
        text(lock, "Кандзи добавляется только к текущим статичным обоям. После смены фона в Android "
                + "новые обои подхватятся при ближайшем обновлении.", 14, MUTED, false);
        button(lock, "Доступ к текущим обоям", this::requestWallpaperAccess);
        previewStatus = text(lock, "", 12, MUTED, false);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(rounded(0xFFE4E8E5));
        frame.setClipToOutline(true);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(dp(216), dp(480));
        frameParams.gravity = Gravity.CENTER_HORIZONTAL;
        frameParams.topMargin = dp(12);
        lock.addView(frame, frameParams);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_XY);
        preview.setContentDescription("Текущие обои блокировки с кандзи");
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        clockGuide = new ClockGuide();
        frame.addView(clockGuide, new FrameLayout.LayoutParams(-1, -1));
        text(lock, "Часы — ориентир для размещения. На телефоне сохраняются ваши часы и погода.", 11, MUTED, false);
        positionLabel = text(lock, "", 13, INK, false);
        slider(lock, 450, Math.round((draftY - .2f) * 1000f), true);
        scaleLabel = text(lock, "", 13, INK, false);
        slider(lock, 50, Math.round((draftScale - .75f) * 100f), false);
        apply = button(lock, "Применить к текущим обоям", this::enableLock);
        remove = button(lock, "Отключить и убрать кандзи", () -> {
            setBusy(true);
            UpdateCoordinator.disableLock(getApplicationContext(), this::operationFinished);
        });
        lockStatus = text(lock, "", 12, MUTED, false);
        text(lock, "Живые и недоступные обои не заменяются. AOD не поддерживается.", 12, MUTED, false);

        LinearLayout schedule = card("Почасовая смена");
        text(schedule, "Разрешите «Будильники и напоминания». Без этого Android может задерживать обновления.", 14, MUTED, false);
        button(schedule, "Настроить точное время", () -> open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())));
        text(schedule, "В HyperOS включите фоновый автозапуск и выберите для батареи «Без ограничений».", 13, MUTED, false);
        button(schedule, "Открыть настройки приложения", () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())));
        button(schedule, "Проверить обновление сейчас", () -> {
            setBusy(true);
            UpdateCoordinator.refresh(getApplicationContext(), true, "manual_check", this::operationFinished);
        });
        text(schedule, "Внутри часа знак остаётся тем же. Резервное задание восстанавливает обновления после сбоев; "
                + "мгновенный запуск в фоне не гарантируется.", 12, MUTED, false);
        diagnostics = text(schedule, "", 11, MUTED, false);
        text(content, "77 базовых кандзи · без интернета и рекламы · 0.3.0-poco", 12, MUTED, false);
        updateCard();
        updateLabels();
        updateStatuses();
    }

    private void slider(LinearLayout parent, int max, int progress, boolean position) {
        SeekBar slider = new SeekBar(this);
        slider.setMax(max);
        slider.setProgress(progress);
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                if (position) draftY = .2f + value / 1000f; else draftScale = .75f + value / 100f;
                updateLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { requestPreview(); }
        });
    }

    private void enableLock() {
        setBusy(true);
        UpdateCoordinator.enableLock(getApplicationContext(), draftY, draftScale, this::operationFinished);
    }

    private void operationFinished() {
        runOnUiThread(() -> {
            busy = false;
            if (!isScreenStarted()) return;
            setBusy(false);
            updateCard();
            updateStatuses();
            requestPreview();
        });
    }

    private boolean isScreenStarted() { return previewRequests.isStarted() && !isDestroyed(); }

    private void requestPreview() {
        if (!isScreenStarted()) return;
        float y = draftY, scale = draftScale;
        android.content.Context app = getApplicationContext();
        previewStatus.setText("Чтение текущих обоев…");
        previewRequests.request(() -> LockWallpaperController.preview(app, KanjiRepository.getCurrent(app), y, scale));
    }

    private void showPreview(Bitmap result, Exception failure) {
        // PreviewRequests publishes only the latest result of a currently visible screen.
        replacePreview(result);
        String message = failure == null ? null : failure.getMessage();
        previewStatus.setText(result != null ? "Текущий фон. Положение сохранится после применения."
                : (message == null ? "Предпросмотр недоступен. Обои не изменены." : message));
    }

    private void replacePreview(Bitmap bitmap) {
        if (preview != null) preview.setImageBitmap(bitmap);
        if (displayedBitmap != null && displayedBitmap != bitmap) displayedBitmap.recycle();
        displayedBitmap = bitmap;
        if (clockGuide != null) clockGuide.invalidate();
    }

    private void updateCard() {
        Kanji current = KanjiRepository.getCurrent(this);
        glyph.setText(current.glyph);
        readings.setText("ОН  " + reading(current.on) + "\nКУН  " + reading(current.kun) + "\n" + current.meaning);
    }

    private void updateLabels() {
        positionLabel.setText("Положение: " + Math.round(draftY * 100) + "% высоты");
        scaleLabel.setText("Размер: " + Math.round(draftScale * 100) + "%");
    }

    private void updateStatuses() {
        if (scheduleStatus == null || lockStatus == null || diagnostics == null) return;
        SharedPreferences prefs = Config.prefs(this);
        String mode = prefs.getString("schedule_mode", "off");
        scheduleStatus.setText("off".equals(mode) ? "Автообновление выключено"
                : "Следующий запуск назначен: " + date(prefs.getLong("next_update_at", 0))
                + (HourlyScheduler.hasExactPermission(this) ? "\nТочное время разрешено" : "\nВозможны задержки Android"));
        boolean enabled = prefs.getBoolean("lock_enabled", false);
        String error = prefs.getString("lock_error", "");
        lockStatus.setText((enabled ? "Автосмена включена" : "Автосмена выключена")
                + (error.isEmpty() ? "" : "\n" + error));
        if (remove != null) remove.setEnabled(!busy && (enabled || prefs.getBoolean("lock_has_glyph", true)
                && prefs.getInt("lock_last_wallpaper_id", 0) > 0));
        String problems = prefs.getString("schedule_error", "") + "\n" + prefs.getString("recovery_error", "")
                + "\n" + prefs.getString("update_error", "");
        diagnostics.setText("Последний alarm: " + date(prefs.getLong("last_alarm_at", 0))
                + "\nПоследняя запись блокировки: " + date(prefs.getLong("lock_last_success", 0))
                + "\nПоследняя обработка: " + prefs.getLong("last_refresh_duration_ms", 0) + " мс"
                + (problems.trim().isEmpty() ? "" : "\n" + problems.trim()));
    }

    private void requestWallpaperAccess() {
        new AlertDialog.Builder(this).setTitle("Чтение текущих обоев")
                .setMessage("Android 14 требует «Доступ ко всем файлам» для чтения обоев. Приложение читает только "
                        + "системный фон и хранит его чистую копию у себя. Для автоматического подхвата новых обоев "
                        + "доступ должен оставаться включённым. Его можно отозвать здесь же.")
                .setPositiveButton("Открыть настройки", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri())); }
                    catch (RuntimeException unavailable) { open(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                }).setNegativeButton("Отмена", null).show();
    }

    private void pinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        try {
            if (manager.isRequestPinAppWidgetSupported() && manager.requestPinAppWidget(
                    new ComponentName(this, KanjiWidgetProvider.class), null, null)) return;
        } catch (RuntimeException ignored) { }
        new AlertDialog.Builder(this).setTitle("Добавить виджет")
                .setMessage("Зажмите свободное место на главном экране → Виджеты → Кандзи · час.")
                .setPositiveButton("Понятно", null).show();
    }

    private void armForegroundRefresh() {
        handler.removeCallbacks(nextHour);
        long now = System.currentTimeMillis();
        handler.postDelayed(nextHour, Math.max(500L, HourlyScheduler.nextHour(now) - now));
    }
    private void setBusy(boolean value) { busy = value; if (apply != null) apply.setEnabled(!value); updateStatuses(); }
    private Uri packageUri() { return Uri.parse("package:" + getPackageName()); }
    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException unavailable) { Toast.makeText(this, "Откройте этот пункт в настройках Android.", Toast.LENGTH_LONG).show(); }
    }
    private String date(long time) { return time <= 0 ? "—" : new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(new Date(time)); }
    private static String reading(String value) { return value == null || value.isEmpty() ? "—" : value; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable rounded(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(14));
        return background;
    }
    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(Color.WHITE));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(16);
        content.addView(card, layout);
        text(card, title, 21, INK, true);
        return card;
    }
    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(5));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setAllCaps(false); button.setTextColor(ACCENT);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
    private final class ClockGuide extends View {
        ClockGuide() { super(MainActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            if (displayedBitmap == null) return;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
            paint.setShadowLayer(2, 0, 1, Color.DKGRAY);
            paint.setTextSize(getWidth() * .19f);
            canvas.drawText("10:08", getWidth() / 2f, getHeight() * .17f, paint);
            paint.setTextSize(getWidth() * .055f);
            canvas.drawText("Часы и погода", getWidth() / 2f, getHeight() * .22f, paint);
        }
    }
}

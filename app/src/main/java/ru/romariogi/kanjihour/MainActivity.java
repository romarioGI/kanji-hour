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
import android.view.ViewGroup;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, offline setup screen for romariogi's specific POCO configuration. */
public final class MainActivity extends Activity {
    private static final int PHOTO_REQUEST = 41;
    private static final int WALLPAPER_PERMISSION_REQUEST = 42;
    private static final String PENDING_WALLPAPER_IMPORT = "pending_wallpaper_import";
    private static final String IMPORT_REVISION = "wallpaper_import_revision";
    private static volatile boolean wallpaperImportRunning;
    private static final int INK = 0xFF22352F, MUTED = 0xFF6B7771, ACCENT = 0xFF38675E;
    // Cloud DocumentsProviders can be slow; never keep an alarm broadcast waiting for one.
    private static final ExecutorService PHOTO_WORKER = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private ImageView lockPreview;
    private Bitmap displayedBitmap;
    private TextView glyphView, onView, kunView, meaningView, scheduleStatus, lockStatus, positionLabel, scaleLabel, photoStatus;
    private LinearLayout homePreview;
    private Button applyButton, removeButton, choosePhotoButton, solidButton, importWallpaperButton, fileAccessButton;
    private SeekBar positionSlider, scaleSlider;
    private float draftY, draftScale;
    private boolean draftSolid, busy;
    private String photoFeedback = "";
    private long observedImportRevision;
    private final SharedPreferences.OnSharedPreferenceChangeListener importListener = (preferences, key) -> {
        if (IMPORT_REVISION.equals(key) && !isDestroyed()) consumeImportResult();
    };
    private volatile int previewGeneration;
    private Kanji kanji;
    private final Runnable delayedPreview = this::renderPreview;
    private final Runnable nextHour = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            updateCard();
            requestPreview();
            updateStatuses();
            UpdateCoordinator.refresh(getApplicationContext(), false, null);
            armForegroundRefresh();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        draftY = Config.prefs(this).getFloat("lock_y", Config.DEFAULT_Y);
        draftScale = Config.prefs(this).getFloat("lock_scale", 1f);
        draftSolid = !Config.sourceFile(this).exists() && !Config.draftSourceFile(this).exists();
        observedImportRevision = Config.prefs(this).getLong(IMPORT_REVISION, 0);
        if (state != null) {
            draftY = state.getFloat("draft_y", draftY);
            draftScale = state.getFloat("draft_scale", draftScale);
            draftSolid = state.getBoolean("draft_solid", draftSolid);
            photoFeedback = state.getString("photo_feedback", "");
            observedImportRevision = state.getLong("observed_import_revision", observedImportRevision);
            if (state.getBoolean("wallpaper_import_running", false) && !wallpaperImportRunning
                    && observedImportRevision == Config.prefs(this).getLong(IMPORT_REVISION, 0)) {
                photoFeedback = "Импорт прерван. Нажмите «Взять текущие обои блокировки» ещё раз.";
            }
        }
        kanji = KanjiRepository.getCurrent(this);
        buildScreen();
        Config.prefs(this).registerOnSharedPreferenceChangeListener(importListener);
        consumeImportResult();
        if (wallpaperImportRunning) setBusy(true);
        requestPreview();
    }

    @Override protected void onResume() {
        super.onResume();
        updateCard();
        updateStatuses();
        requestPreview();
        UpdateCoordinator.refresh(getApplicationContext(), false, () -> runOnUiThread(() -> {
            if (!isDestroyed()) updateStatuses();
        }));
        armForegroundRefresh();
        consumeImportResult();
        if (wallpaperImportRunning) setBusy(true);
        // Special-access Settings does not return a reliable result code. Check the
        // actual permission on return, including after Android recreates our process.
        if (Config.prefs(this).getBoolean(PENDING_WALLPAPER_IMPORT, false)) {
            Config.prefs(this).edit().remove(PENDING_WALLPAPER_IMPORT).commit();
            if (CurrentWallpaperImporter.hasPermission()) importCurrentWallpaper();
            else {
                photoFeedback = "Доступ не разрешён. Можно повторить импорт или выбрать фото вручную.";
                updateStatuses();
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(nextHour);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putFloat("draft_y", draftY);
        out.putFloat("draft_scale", draftScale);
        out.putBoolean("draft_solid", draftSolid);
        out.putString("photo_feedback", photoFeedback);
        out.putLong("observed_import_revision", observedImportRevision);
        out.putBoolean("wallpaper_import_running", wallpaperImportRunning);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        previewGeneration++;
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(importListener);
        handler.removeCallbacksAndMessages(null);
        if (lockPreview != null) lockPreview.setImageDrawable(null);
        if (displayedBitmap != null) displayedBitmap.recycle();
        super.onDestroy();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(36));
        scroll.addView(content);
        setContentView(scroll);
        text(content, "POCO X5 PRO 5G  ·  HYPEROS 2", 11, ACCENT, true, 0);
        text(content, "Кандзи · час", 32, INK, true, 8);
        text(content, "Один знак. Каждый час.", 16, MUTED, false, 2);

        LinearLayout today = card();
        text(today, "СЕЙЧАС НА ОБОИХ ЭКРАНАХ", 10, MUTED, true, 0);
        homePreview = new LinearLayout(this);
        homePreview.setGravity(Gravity.CENTER_VERTICAL);
        homePreview.setPadding(dp(8), dp(14), dp(8), dp(14));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(104));
        hp.topMargin = dp(12);
        today.addView(homePreview, hp);
        glyphView = new TextView(this);
        glyphView.setTextSize(54);
        glyphView.setTextLocale(Locale.JAPANESE);
        glyphView.setIncludeFontPadding(false);
        homePreview.addView(glyphView, new LinearLayout.LayoutParams(dp(75), -2));
        LinearLayout readings = new LinearLayout(this);
        readings.setOrientation(LinearLayout.VERTICAL);
        homePreview.addView(readings, new LinearLayout.LayoutParams(0, -2, 1));
        onView = text(readings, "", 13, INK, false, 0);
        kunView = text(readings, "", 13, INK, false, 3);
        meaningView = text(readings, "", 15, INK, true, 4);
        onView.setTextLocale(Locale.JAPANESE);
        kunView.setTextLocale(Locale.JAPANESE);
        scheduleStatus = text(today, "", 12, MUTED, false, 12);

        LinearLayout home = card();
        text(home, "Главный экран", 21, INK, true, 0);
        text(home, "Прозрачный виджет 4 × 1. Поставьте его между часами и первым рядом значков.", 14, MUTED, false, 7);
        Switch light = new Switch(this);
        light.setText("Белый текст на главном экране");
        light.setTextSize(14);
        light.setTextColor(INK);
        light.setPadding(0, dp(12), 0, dp(12));
        light.setChecked(Config.prefs(this).getBoolean("home_light", false));
        home.addView(light);
        light.setOnCheckedChangeListener((button, checked) -> {
            Config.prefs(this).edit().putBoolean("home_light", checked).apply();
            updateCard();
            UpdateCoordinator.refresh(getApplicationContext(), false, null);
        });
        button(home, "Добавить виджет", this::pinWidget, true);

        LinearLayout lock = card();
        text(lock, "Экран блокировки", 21, INK, true, 0);
        text(lock, "Белый кандзи под погодой. Приложение вписывает его в выбранный фон и меняет каждый час.", 14, MUTED, false, 7);
        importWallpaperButton = button(lock, "Взять текущие обои блокировки", this::requestWallpaperImport, true);
        text(lock, "Текущий фон автоматически появится в предпросмотре. Android может запросить доступ к файлам для чтения обоев.", 12, MUTED, false, 6);
        fileAccessButton = button(lock, "Отключить доступ к файлам", () -> openWallpaperAccessSettings(false), false);
        choosePhotoButton = button(lock, "Выбрать исходное фото", this::choosePhoto, false);
        solidButton = button(lock, "Использовать синий фон", () -> {
            draftSolid = true;
            photoFeedback = "";
            updateStatuses();
            requestPreview();
        }, false);
        photoStatus = text(lock, "", 12, MUTED, false, 8);
        text(lock, "ПРЕДПРОСМОТР", 10, MUTED, true, 18);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(rounded(0xFF315A7B, 14));
        frame.setClipToOutline(true);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(dp(216), dp(480));
        frameParams.gravity = Gravity.CENTER_HORIZONTAL;
        frameParams.topMargin = dp(10);
        lock.addView(frame, frameParams);
        lockPreview = new ImageView(this);
        lockPreview.setScaleType(ImageView.ScaleType.FIT_XY);
        lockPreview.setContentDescription("Предпросмотр выбранного фона с кандзи");
        frame.addView(lockPreview, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(new ClockGuide(), new FrameLayout.LayoutParams(-1, -1));
        text(lock, "Часы и подпись погоды — ориентир для размещения. На телефоне останутся ваши часы и погода.", 11, MUTED, false, 8);
        positionLabel = text(lock, "", 13, INK, false, 14);
        positionSlider = new SeekBar(this);
        positionSlider.setMax(450);
        positionSlider.setProgress(Math.round((draftY - .2f) * 1000f));
        lock.addView(positionSlider, new LinearLayout.LayoutParams(-1, dp(40)));
        positionSlider.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                if (fromUser) { draftY = .2f + progress / 1000f; updateLabels(); requestPreview(); }
            }
        });
        scaleLabel = text(lock, "", 13, INK, false, 8);
        scaleSlider = new SeekBar(this);
        scaleSlider.setMax(50);
        scaleSlider.setProgress(Math.round((draftScale - .75f) * 100f));
        lock.addView(scaleSlider, new LinearLayout.LayoutParams(-1, dp(40)));
        scaleSlider.setOnSeekBarChangeListener(new Slider() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                if (fromUser) { draftScale = .75f + progress / 100f; updateLabels(); requestPreview(); }
            }
        });
        applyButton = button(lock, "Применить фон и включить смену", this::applyLock, true);
        text(lock, "Кнопка заменит обои блокировки выбранным фоном. AOD в этой версии не поддерживается.", 12, MUTED, false, 6);
        lockStatus = text(lock, "", 12, MUTED, false, 12);
        removeButton = button(lock, "Убрать кандзи с блокировки", this::removeLock, false);

        LinearLayout schedule = card();
        text(schedule, "Почасовая смена", 21, INK, true, 0);
        text(schedule, "Для смены ровно в начале часа разрешите «Будильники и напоминания». Без этого Android может отложить обновление.", 14, MUTED, false, 7);
        button(schedule, "Настроить точное время", this::requestExactAlarms, false);
        text(schedule, "В HyperOS откройте настройки приложения: включите фоновый автозапуск и выберите для батареи «Без ограничений». Названия пунктов могут немного отличаться.", 13, MUTED, false, 12);
        button(schedule, "Открыть настройки приложения", () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName()))), false);
        button(schedule, "Проверить обновление сейчас", () -> UpdateCoordinator.refresh(getApplicationContext(), true,
            () -> runOnUiThread(() -> { if (!isDestroyed()) { updateStatuses(); toast("Проверка завершена; результат указан выше"); } })), false);
        text(schedule, "При проверке внутри одного часа знак остаётся тем же.", 12, MUTED, false, 6);

        LinearLayout about = card();
        text(about, "Маленькая ежедневная привычка", 18, INK, true, 0);
        text(about, "77 базовых кандзи · значения по-русски · без интернета и рекламы. ОН — распространённые онные чтения, КУН — одно распространённое кунное. «—» означает, что обычного кунного чтения нет.", 13, MUTED, false, 8);
        text(about, "Версия 0.2 · подготовлена для POCO X5 Pro 5G, Android 14, HyperOS 2.0.11.0. Импорт обоев нужно проверить на вашем телефоне.", 11, MUTED, false, 12);
        updateCard();
        updateLabels();
        updateStatuses();
    }

    private void pinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (manager.isRequestPinAppWidgetSupported()) {
            try {
                boolean requested = manager.requestPinAppWidget(new ComponentName(this, KanjiWidgetProvider.class), null, null);
                if (requested) return;
            } catch (RuntimeException ignored) { }
        }
        new AlertDialog.Builder(this).setTitle("Добавить виджет")
            .setMessage("На главном экране зажмите свободное место → Виджеты → Кандзи · час. Разместите виджет 4 × 1 под часами.")
            .setPositiveButton("Понятно", null).show();
    }

    private void choosePhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PHOTO_REQUEST); }
        catch (RuntimeException ex) { toast("Не удалось открыть выбор фото"); }
    }

    private void requestWallpaperImport() {
        if (busy) return;
        if (!CurrentWallpaperImporter.needsPermission(this)) {
            importCurrentWallpaper();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Загрузить текущий фон")
            .setMessage("Для чтения текущих обоев Android 14 требует системное разрешение «Доступ ко всем файлам». "
                + "Приложение использует его только для импорта обоев. После импорта доступ можно отключить — "
                + "почасовая смена продолжит работать с сохранённой копией.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть настройки", (dialog, which) -> openWallpaperAccessSettings(true))
            .show();
    }

    private void openWallpaperAccessSettings(boolean importAfter) {
        if (importAfter) Config.prefs(this).edit().putBoolean(PENDING_WALLPAPER_IMPORT, true).commit();
        Intent specific = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(specific, WALLPAPER_PERMISSION_REQUEST);
        } catch (RuntimeException noAppSettings) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), WALLPAPER_PERMISSION_REQUEST);
                toast("Выберите «Кандзи · час» в списке приложений");
            } catch (RuntimeException unavailable) {
                Config.prefs(this).edit().remove(PENDING_WALLPAPER_IMPORT).commit();
                photoFeedback = "Не удалось открыть доступ к файлам. Можно выбрать исходное фото вручную.";
                updateStatuses();
            }
        }
    }

    private void importCurrentWallpaper() {
        if (busy || wallpaperImportRunning) return;
        wallpaperImportRunning = true;
        setBusy(true);
        photoFeedback = "Загружаю текущие обои…";
        updateStatuses();
        UpdateCoordinator.executor().execute(() -> {
            CurrentWallpaperImporter.Result imported = null;
            String error = null;
            try { imported = CurrentWallpaperImporter.importCurrent(getApplicationContext()); }
            catch (Exception ex) { error = readable(ex); }
            // Persist the outcome independently of this Activity. A recreated screen
            // observes this revision and picks up the new draft type and message.
            SharedPreferences preferences = Config.prefs(getApplicationContext());
            SharedPreferences.Editor outcome = preferences.edit()
                .putBoolean("wallpaper_import_success", error == null)
                .putString("wallpaper_import_message", error == null ? imported.message : "Не удалось загрузить обои: " + error);
            if (imported != null) outcome.putBoolean("wallpaper_import_solid", imported.solid);
            long revision = preferences.getLong(IMPORT_REVISION, 0) + 1;
            wallpaperImportRunning = false;
            outcome.putLong(IMPORT_REVISION, revision).commit();
            runOnUiThread(() -> { if (!isDestroyed()) consumeImportResult(); });
        });
    }

    private void consumeImportResult() {
        SharedPreferences preferences = Config.prefs(this);
        long revision = preferences.getLong(IMPORT_REVISION, 0);
        if (revision == observedImportRevision || photoStatus == null) return;
        observedImportRevision = revision;
        boolean success = preferences.getBoolean("wallpaper_import_success", false);
        if (success) draftSolid = preferences.getBoolean("wallpaper_import_solid", false);
        photoFeedback = preferences.getString("wallpaper_import_message", "");
        setBusy(false);
        updateStatuses();
        if (success) requestPreview();
        if (hasWindowFocus()) toast(success ? "Фон загружен. Проверьте предпросмотр и нажмите «Применить»."
            : "Обои не загружены. Причина указана под кнопками.");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PHOTO_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        setBusy(true);
        PHOTO_WORKER.execute(() -> {
            String error = null;
            try { WallpaperRenderer.importPhoto(getApplicationContext(), uri); }
            catch (Exception ex) { error = readable(ex); }
            String finalError = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false);
                if (finalError != null) toast("Фото не загружено: " + finalError);
                else {
                    draftSolid = false;
                    photoFeedback = "";
                    updateStatuses();
                    requestPreview();
                    toast("Фото подготовлено. Проверьте предпросмотр.");
                }
            });
        });
    }

    private void applyLock() {
        if (busy) return;
        setBusy(true);
        float y = draftY, scale = draftScale;
        boolean solid = draftSolid;
        UpdateCoordinator.executor().execute(() -> {
            String error = null;
            boolean applied = false;
            try {
                long selectedAt = System.currentTimeMillis();
                int id = WallpaperRenderer.applyPreview(getApplicationContext(),
                    KanjiRepository.getAtTime(getApplicationContext(), selectedAt), y, scale, solid);
                applied = true;
                WallpaperRenderer.commitBackground(getApplicationContext(), solid);
                boolean saved = Config.prefs(this).edit().putBoolean("lock_enabled", true).putInt("lock_last_wallpaper_id", id)
                    .putFloat("lock_y", y).putFloat("lock_scale", scale)
                    .putLong("lock_last_hour", Math.floorDiv(selectedAt, 3600000L))
                    .putLong("lock_last_success", System.currentTimeMillis()).remove("lock_error").commit();
                if (!saved) throw new IllegalStateException("Не удалось сохранить настройки. Освободите место и повторите применение.");
                // If applying crossed the hour boundary, bring both surfaces up to date.
                UpdateCoordinator.refresh(getApplicationContext(), false, null);
            } catch (Exception ex) {
                error = (applied ? "Фон применён, но смена остановлена: " : "") + readable(ex);
                if (applied) Config.prefs(this).edit().putBoolean("lock_enabled", false)
                    .remove("lock_last_wallpaper_id").remove("lock_last_hour").commit();
                Config.prefs(this).edit().putString("lock_error", error).apply();
            } finally { HourlyScheduler.schedule(getApplicationContext()); }
            String finalError = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false);
                updateStatuses();
                toast(finalError == null ? "Применено. Проверьте экран блокировки." : "Не удалось применить: " + finalError);
            });
        });
    }

    private void removeLock() {
        if (busy) return;
        setBusy(true);
        UpdateCoordinator.executor().execute(() -> {
            Config.prefs(this).edit().putBoolean("lock_enabled", false).commit();
            String error = null;
            boolean externalWallpaper = UpdateCoordinator.wallpaperWasReplaced(getApplicationContext());
            try {
                if (!externalWallpaper) WallpaperRenderer.apply(getApplicationContext(), null);
                Config.prefs(this).edit().remove("lock_last_wallpaper_id").remove("lock_last_hour").remove("lock_error").commit();
            } catch (Exception ex) {
                error = readable(ex);
                Config.prefs(this).edit().putString("lock_error", "Смена остановлена, но убрать знак не удалось: " + error).apply();
            } finally { HourlyScheduler.schedule(getApplicationContext()); }
            String finalError = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false);
                updateStatuses();
                toast(finalError == null ? (externalWallpaper ? "Смена выключена. Ваши текущие обои сохранены."
                    : "Кандзи убран, выбранный фон оставлен") : "Смена остановлена. Проверьте результат выше.");
            });
        });
    }

    private void requestExactAlarms() {
        if (HourlyScheduler.hasExactPermission(this)) { toast("Точное время уже разрешено"); return; }
        open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
    }

    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException ex) { toast("Откройте этот раздел через настройки телефона"); }
    }

    private void updateCard() {
        if (glyphView == null) return;
        kanji = KanjiRepository.getCurrent(this);
        glyphView.setText(kanji.glyph);
        onView.setText("ОН  " + kanji.on);
        kunView.setText("КУН  " + kanji.kun);
        meaningView.setText(kanji.meaning);
        boolean light = Config.prefs(this).getBoolean("home_light", false);
        int color = light ? Color.WHITE : INK;
        for (TextView view : new TextView[]{glyphView, onView, kunView, meaningView}) view.setTextColor(color);
        homePreview.setBackground(rounded(light ? 0xFF557286 : 0xFFECEFEA, 12));
    }

    private void updateLabels() {
        if (positionLabel == null) return;
        positionLabel.setText("Положение по высоте: " + Math.round(draftY * 100) + "%");
        scaleLabel.setText("Размер кандзи: " + Math.round(draftScale * 100) + "%");
    }

    private void updateStatuses() {
        if (scheduleStatus == null || photoStatus == null) return;
        long next = (Math.floorDiv(System.currentTimeMillis(), 3600000L) + 1) * 3600000L;
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(next));
        scheduleStatus.setText("Следующий знак — " + time + (HourlyScheduler.hasExactPermission(this)
            ? " · точное время разрешено" : " · возможна задержка без разрешения"));
        for (String key : new String[]{"schedule_error", "update_error"}) {
            String message = Config.prefs(this).getString(key, "");
            if (message != null && !message.isEmpty()) scheduleStatus.append("\n" + message);
        }
        boolean enabled = Config.prefs(this).getBoolean("lock_enabled", false);
        long last = Config.prefs(this).getLong("lock_last_success", 0L);
        String error = Config.prefs(this).getString("lock_error", "");
        String status = enabled ? "Смена на блокировке включена" : "Смена на блокировке выключена";
        if (last > 0) status += "\nПоследнее применение: " + new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(new Date(last));
        if (error != null && !error.isEmpty()) status += "\n" + error;
        lockStatus.setText(status);
        photoStatus.setText(draftSolid ? "В предпросмотре синий фон. Для своих обоев выберите исходное фото без часов и значков."
            : "В предпросмотре выбранное фото. Изменения вступят в силу после кнопки «Применить»." );
        if (!photoFeedback.isEmpty()) photoStatus.append("\n" + photoFeedback);
        if (fileAccessButton != null) fileAccessButton.setVisibility(CurrentWallpaperImporter.hasPermission() ? View.VISIBLE : View.GONE);
        if (removeButton != null) removeButton.setEnabled(!busy && (enabled || Config.prefs(this).contains("lock_last_wallpaper_id")));
    }

    private void requestPreview() {
        handler.removeCallbacks(delayedPreview);
        handler.postDelayed(delayedPreview, 120);
    }

    private void renderPreview() {
        final int generation = ++previewGeneration;
        final float y = draftY, scale = draftScale;
        final boolean solid = draftSolid;
        final Kanji selected = kanji;
        UpdateCoordinator.executor().execute(() -> {
            if (generation != previewGeneration) return;
            Bitmap bitmap;
            try { bitmap = WallpaperRenderer.renderPreview(getApplicationContext(), selected, 540, y, scale, solid); }
            catch (Exception ex) {
                runOnUiThread(() -> { if (!isDestroyed() && generation == previewGeneration) photoStatus.setText(readable(ex)); });
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || generation != previewGeneration) { bitmap.recycle(); return; }
                Bitmap old = displayedBitmap;
                displayedBitmap = bitmap;
                lockPreview.setImageBitmap(bitmap);
                if (old != null) old.recycle();
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        for (View view : new View[]{applyButton, removeButton, choosePhotoButton, solidButton, importWallpaperButton,
                fileAccessButton, positionSlider, scaleSlider})
            if (view != null) view.setEnabled(!value);
        if (applyButton != null) applyButton.setText(value ? "Подготовка…" : "Применить фон и включить смену");
        if (!value) updateStatuses();
    }

    private void armForegroundRefresh() {
        handler.removeCallbacks(nextHour);
        long now = System.currentTimeMillis();
        long next = (Math.floorDiv(now, 3600000L) + 1) * 3600000L;
        handler.postDelayed(nextHour, Math.max(1000L, next - now + 100));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(Color.WHITE, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(20);
        content.addView(card, params);
        return card;
    }

    private TextView text(LinearLayout parent, String text, float size, int color, boolean bold, int top) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
        return view;
    }

    private Button button(LinearLayout parent, String label, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(rounded(primary ? ACCENT : 0xFFEFF3EF, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(button, params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seek) {}
        @Override public void onStopTrackingTouch(SeekBar seek) {}
    }

    /** Preview-only clock guide. Never passed to WallpaperManager. */
    private final class ClockGuide extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ClockGuide() { super(MainActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float unit = getWidth() / 576f;
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            paint.setShadowLayer(unit, 0, unit, 0x33000000);
            paint.setTextSize(85f * unit);
            canvas.drawText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 34f * unit, 237f * unit, paint);
            paint.setTextSize(79f * unit);
            canvas.drawText(new SimpleDateFormat("d.M", Locale.getDefault()).format(new Date()), 34f * unit, 326f * unit, paint);
            paint.setTextSize(25f * unit);
            canvas.drawText("погода", 38f * unit, 404f * unit, paint);
        }
    }
}

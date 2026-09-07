package ru.romariogi.kanjihour;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.RemoteViews;

/** Transparent, four-column home screen widget for the POCO launcher. */
public final class KanjiWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        refreshFromBroadcast(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int widgetId, Bundle newOptions) {
        refreshFromBroadcast(context);
    }

    @Override
    public void onEnabled(Context context) {
        HourlyScheduler.schedule(context);
    }

    @Override
    public void onDisabled(Context context) {
        HourlyScheduler.schedule(context);
    }

    private void refreshFromBroadcast(Context context) {
        final PendingResult pending = goAsync();
        UpdateCoordinator.refresh(context, false, () -> {
            if (pending != null) pending.finish();
        });
    }

    public static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, KanjiWidgetProvider.class));
    }

    /** Uses the caller's already-selected kanji so both surfaces show the same entry. */
    public static void updateAll(Context context, Kanji kanji) {
        int[] widgetIds = widgetIds(context);
        if (widgetIds.length == 0) return;

        boolean light = Config.prefs(context).getBoolean("home_light", false);
        int primary = light ? Color.WHITE : Color.rgb(38, 43, 48);
        int secondary = light ? Color.rgb(243, 247, 251) : Color.rgb(62, 69, 75);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.kanji_widget);
        views.setTextViewText(R.id.widget_glyph, kanji.glyph);
        views.setTextViewText(R.id.widget_on, "ОН  " + reading(kanji.on));
        views.setTextViewText(R.id.widget_kun, "КУН  " + reading(kanji.kun));
        views.setTextViewText(R.id.widget_meaning, kanji.meaning);
        views.setTextColor(R.id.widget_glyph, primary);
        views.setTextColor(R.id.widget_on, secondary);
        views.setTextColor(R.id.widget_kun, secondary);
        views.setTextColor(R.id.widget_meaning, primary);
        views.setContentDescription(R.id.widget_root,
                kanji.glyph + ". Онное чтение: " + kanji.on
                        + ". Кунное чтение: " + kanji.kun + ". " + kanji.meaning);

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, tap);
        AppWidgetManager.getInstance(context).updateAppWidget(widgetIds, views);
    }

    private static String reading(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }
}

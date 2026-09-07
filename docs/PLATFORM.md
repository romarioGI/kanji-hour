# Целевая платформа

По присланным настройкам: POCO X5 Pro 5G, Android 14 UKQ1.240624.001,
HyperOS 2.0.11.0.UMSRUXM. По скриншотам главный экран имеет четыре столбца
значков; на блокировке часы, дата и погода стоят большим блоком слева сверху.

## Решение

| Поверхность | Реализация | Начальное оформление |
| --- | --- | --- |
| Главный экран | Android AppWidget / RemoteViews | 4 × 1, прозрачный фон, тёмный текст |
| Экран блокировки | WallpaperManager.setBitmap с FLAG_LOCK | 1080 × 2400, белый текст, центр блока на 38,2% высоты |
| AOD | Не реализован | Требует отдельного исследования |

WallpaperManager устанавливает созданное изображение только после действия
пользователя в приложении. Исходное фото выбирается через системный Documents UI,
декодируется с учётом EXIF, ограничивается по размеру и сохраняется в приватном
каталоге приложения. Для необязательного импорта текущих обоев с версии 0.2 объявлен
MANAGE_EXTERNAL_STORAGE. Пользователь включает его только через системные
настройки после нажатия кнопки импорта. Код не сканирует каталоги общих файлов. Фон сначала находится
в черновике: почасовой процесс его не использует до успешного применения.

На каждый следующий час ставится одноразовый AlarmManager alarm. Если пользователь
разрешил точные события, используется setExactAndAllowWhileIdle; иначе —
setAndAllowWhileIdle и видимое сообщение о возможной задержке. Дополнительное
30-минутное событие AppWidget — страховка перерисовки, оно не меняет знак чаще часа.
Отдельный поток загрузки фото не задерживает завершение BroadcastReceiver.goAsync.

## Официальные источники

Проверены 7 сентября 2026 года:

- [POCO X5 Pro 5G — характеристики](https://www.po.co/global/product/poco-x5-pro-5g/specs):
  экран 2400 × 1080.
- [WallpaperManager](https://developer.android.com/reference/android/app/WallpaperManager):
  FLAG_LOCK, SET_WALLPAPER, isWallpaperSupported, isSetWallpaperAllowed,
  возвращаемый ID setBitmap и ограничения чтения действующих обоев Android 14.
- [Widgets on the lock screen — FAQ](https://android-developers.googleblog.com/2025/03/widgets-on-lock-screen-faq.html):
  новая общая поддержка lockscreen widgets относится к Android 16 QPR1.
  Документ не подтверждает соответствующий host на этом POCO / HyperOS 2.
- [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms):
  точные и неточные alarms, получение разрешения и восстановление после загрузки.
- [Android 14 — exact alarm permission](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms):
  разрешение не предоставляется обычным новым приложениям автоматически.
- [Advanced app widgets](https://developer.android.com/develop/ui/views/appwidgets/advanced):
  updatePeriodMillis не поддерживает частоту выше одного раза в 30 минут.
- [Xiaomi — обои и темы](https://www.mi.com/global/support/article/KA-33155/):
  системный выбор отдельных обоев блокировки.

Существование публичного API Android не равно проверке vendor-поведения на конкретной
прошивке. Фактические кадрирование, отображение и фоновые обновления остаются
приёмочными проверками на пользовательском POCO.

## Импорт текущего фона (0.2)

Используются публичные API WallpaperManager.getWallpaperFile(FLAG_LOCK),
getWallpaperId и getWallpaperInfo. Системная копия общего фона используется только
при отсутствии отдельного фона блокировки. Живые обои не подменяются стандартной
картинкой Android. Собственные обои приложения распознаются по ID: повторный импорт
возвращает чистый исходник из приватного каталога.

Результат записывается атомарно в черновик. При ошибке прежний предпросмотр и
активный фон сохраняются. Чтение, декодирование и запись выполняются вне UI-потока;
до завершения импорта проверяется, что пользователь не успел сменить обои.

Android 14 разрешает чтение текущих обоев приложениям с MANAGE_EXTERNAL_STORAGE,
что явно описано в [контракте getWallpaperFile](https://developer.android.com/reference/android/app/WallpaperManager#getWallpaperFile(int)).
Для штатного открытия специального доступа применяются
Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION и проверка
Environment.isExternalStorageManager(). Подтверждение результата происходит при
возврате в приложение; отказ не приводит к повторяющемуся запросу разрешения.

Полученное разрешение не нужно для дальнейшей почасовой смены. Можно отключить его
после импорта. На конкретной HyperOS результат чтения и применённый системой crop
всё равно требуют первой проверки на телефоне.

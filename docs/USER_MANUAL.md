# RuTV User Manual

## English

### 1. Overview

RuTV is an IPTV player for Android TV, set-top boxes, and mobile Android devices. It plays channels from an M3U/M3U8 playlist, shows an electronic program guide (EPG), supports favorites and channel groups, and can play catch-up/archive programs when the provider supplies the required metadata.

The app is designed for remote-control use. On Android TV and STB devices, the directional pad, OK/Enter, Back, channel keys, and media keys are the primary controls.

### 2. First Start

When the app starts without a playlist, it shows a **No Playlist Configured** dialog.

- Select **Open Settings** to configure a playlist.
- Select **Exit** to close the app.

The app can receive playlist and EPG configuration in two ways:

- From the Settings screen, using a local file or URL.
- From an external `rutv.conf` provisioning file placed in the app external files directory. Supported keys are `PLAYLIST_URL`, `EPG_URL`, `EPG_DAYS_AHEAD`, `EPG_DAYS_PAST`, and `EPG_PAGE_DAYS`.

### 3. Playlist Setup

Open **Settings**, then use **Playlist Source**.

- **Load from File**: choose an M3U/M3U8 playlist file from device storage.
- **Load from URL**: enter a playlist URL.
- **Reload Playlist**: reload the configured source and refresh playlist and EPG-related channel metadata.

The playlist parser supports common IPTV fields:

- Channel title.
- Channel logo.
- Channel group or multiple groups.
- `tvg-id` for EPG matching.
- `catchup-days` and `catchup-source` for archive/catch-up playback.

Playlist data is cached locally. For URL playlists, the app can start quickly from cached channels and refresh when needed.

### 4. Watching TV

After a playlist is loaded, the app starts playback from the last watched channel when possible.

Basic remote actions in full-screen playback:

- **OK / Enter**: show or hide playback controls.
- **Long press OK / Enter**: open the channel number dialog.
- **Up / Channel Up**: switch to the next channel.
- **Down / Channel Down**: switch to the previous channel.
- **Left**: open the channel list.
- **Right**: open the EPG for the current channel, if available.
- **Info**: open current program details, if available.
- **Back**: close open panels or controls; from full-screen playback, ask whether to close the app.

### 5. Playback Controls

When playback controls are visible, the app shows media controls and the current program progress when EPG data is available.

Available playback actions include:

- Play and pause.
- Restart the current live program from the beginning when catch-up is available.
- Seek backward or forward.
- Return to live playback from archive or timeshift mode.
- Change aspect ratio.
- Rotate video orientation.

For live programs with catch-up support, pausing or seeking can start timeshift playback. For archived programs, the progress bar represents the selected program.

### 5.1. Custom Control Screen

The custom control screen appears together with playback controls after pressing **OK / Enter** during playback. It provides quick access to channel lists and app tools without opening Settings first.

The custom controls are split into two vertical button columns.

Left-side buttons:

- **Full channel list**: opens the complete playlist with all channels.
- **Favorites**: opens only channels marked as favorites.
- **Channel groups**: opens the group selector. Choose a group to filter the channel list, or use **Reset** to return to all channels.

Right-side buttons:

- **Aspect ratio**: cycles the video resize mode.
- **Rotate video**: shown in the control column but currently disabled.
- **Settings**: opens the Settings screen.

Remote navigation on the custom control screen:

- Press **OK / Enter** during playback to show the controls.
- From the media controls, long-press **Left** to jump to the left control column near **Favorites**.
- From the media controls, long-press **Right** to jump to the right control column near **Rotate video**.
- Use **Up / Down** inside a side column to move between buttons.
- Use **Left / Right** inside the side columns to move between matching left and right column positions, where available.
- Press **OK / Enter** on the focused button to activate it.
- Press **Back** to hide the controls and return to full-screen playback.

Fast access examples:

- To open the full channel list: press **OK / Enter**, long-press **Left**, press **Up** to the list button, then press **OK / Enter**.
- To open favorites: press **OK / Enter**, long-press **Left**, then press **OK / Enter** on the star button.
- To choose a group: press **OK / Enter**, long-press **Left**, press **Down** to the filter button, then press **OK / Enter**.
- To open Settings: press **OK / Enter**, long-press **Right**, press **Down** to the settings button, then press **OK / Enter**.
- To change aspect ratio: press **OK / Enter**, long-press **Right**, press **Up** to the aspect-ratio button, then press **OK / Enter**.

### 6. Channel List

Open the channel list with **Left** during full-screen playback.

The channel list shows:

- Channel number.
- Channel name.
- Favorite state.
- Lock state, when parental controls are used.
- Current program title, if this option is enabled and EPG data is available.
- “Now playing” status for the active channel.

Channel list actions:

- **Up / Down**: move through channels.
- **OK / Enter**: play the focused channel.
- **Right**: open the EPG for the focused channel, when available.
- **Back**: close the list.

The app can also show a channel preview window while browsing the list. This can be enabled or disabled in Settings.

### 7. Favorites

Channels can be marked as favorites from the channel list controls.

Favorite channels are preserved across playlist reloads when the same channel URL or `tvg-id` can be matched. The app can open either the full channel list or the favorites list, depending on the current list mode.

### 8. Channel Groups

The app reads channel groups from the playlist and supports multiple groups per channel when provided by the source.

Use the channel group dialog to:

- Select a group.
- Reset the list back to all channels.

When a group is selected, the playlist panel title shows that group.

### 9. Search and Go to Channel

The app provides two fast navigation tools:

- **Go to Channel**: enter a channel number and confirm.
- **Search Channel**: search by part of the channel name from the playlist panel.

Search focuses the first matching channel. If the list is long, the app loads more rows as needed.

### 10. Electronic Program Guide

Open the EPG with **Right** from full-screen playback or from a focused channel in the channel list.

The EPG shows:

- Program dates.
- Program start and stop times.
- Current program highlight.
- Past programs.
- Archive indicators for programs that can be replayed.

EPG actions:

- **Up / Down**: move between programs.
- **OK / Enter**: open or play the selected program. Past archive-capable programs start archive playback.
- **Long press OK / Enter**: open program details.
- **Left**: return to the channel list or open it.
- **Right**: open the date picker.
- **Back**: close the EPG.

The EPG loads by date windows and can request more past or future data as you scroll.

### 11. Archive and Timeshift

Archive playback is available only when the playlist channel has EPG data and catch-up metadata.

Supported modes:

- **Archive program playback**: play a past program from the EPG.
- **Watch from beginning**: restart the current program from its start.
- **Timeshift**: pause or seek within the currently airing program when supported.

When an archive program finishes, the app may ask whether to continue with the next program. You can continue archive playback or return to live TV.

### 12. Program Details

Program details can be opened from the current program overlay or the EPG.

The details panel shows:

- Program title.
- Program date and time.
- Program description, when provided by the EPG.

Use **Back** or the close button to return to playback or the EPG.

### 13. Parental Controls

Parental controls use a 4-digit PIN.

In **Settings > Parental Controls**, you can:

- Set a parental PIN.
- Change the PIN.
- Remove the PIN.

After a PIN is set, channels can be locked from the channel list. Locked channels require the PIN before playback or EPG access. Unlocking is temporary for the current session.

Important: if the PIN is forgotten, it can only be removed by reinstalling the app or clearing app data.

### 14. Settings

The Settings screen contains these sections.

**Playlist Source**

- Load playlist from file.
- Load playlist from URL.
- Reload the playlist.
- View current playlist source information.

**Player Configuration**

- Show debug log.
- Use FFmpeg for audio.
- Use FFmpeg for video.
- Set buffer duration.
- Set controls hide delay.
- Enable or disable automatic retry for playback errors.
- Set maximum retry attempts.
- Set retry period.
- Show or hide current program titles in the channel list.
- Enable or disable channel preview window.

**Parental Controls**

- Set, change, or remove the parental PIN.

**EPG Configuration**

- Set EPG XML URL.
- Set how many future days to load.
- Set how many past days to load.
- Set EPG page size in days.
- Clear the EPG cache.

**Language**

- English.
- Russian.

Changing language recreates the main screen so the new locale is applied.

### 15. Playback Errors and Retry

The app displays localized playback errors for common stream failures:

- Provider suspended stream.
- Token missing or access denied.
- Stream not found.
- HTTP errors.
- Network errors.
- Timeout.
- Source error.
- Unknown playback error.

When automatic retry is enabled, the app retries failed playback according to the configured attempt count and retry period.

### 16. Debug Log

When **Show Debug Log** is enabled, the app displays a debug panel with recent playback and DVR messages. This is intended for troubleshooting stream, EPG, archive, and startup behavior.

### 17. Tips

- If the EPG is empty, verify that channels have matching `tvg-id` values and that the EPG XML URL is configured.
- If archive playback is unavailable, verify that the playlist includes `catchup-days` and, when required by the provider, `catchup-source`.
- If a URL playlist fails to load, check network access and playlist size.
- If a locked channel cannot be unlocked, verify the 4-digit parental PIN.
- Use **Reload Playlist** after provider-side changes to channel groups, EPG IDs, catch-up metadata, or stream URLs.

---

## Русский

### 1. Обзор

RuTV — IPTV-плеер для Android TV, ТВ-приставок и мобильных Android-устройств. Приложение воспроизводит каналы из плейлиста M3U/M3U8, показывает программу передач (EPG), поддерживает избранное, группы каналов и воспроизведение архива, если провайдер передает необходимые данные.

Приложение рассчитано на управление пультом. На Android TV и ТВ-приставках основными кнопками являются стрелки, OK/Enter, Back, кнопки переключения каналов и медиа-кнопки.

### 2. Первый запуск

Если плейлист не настроен, приложение показывает окно **Плейлист не настроен**.

- Выберите **Открыть настройки**, чтобы настроить плейлист.
- Выберите **Выход**, чтобы закрыть приложение.

Настройки плейлиста и EPG можно передать двумя способами:

- Через экран настроек, выбрав файл или URL.
- Через внешний файл `rutv.conf` в каталоге внешних файлов приложения. Поддерживаются ключи `PLAYLIST_URL`, `EPG_URL`, `EPG_DAYS_AHEAD`, `EPG_DAYS_PAST` и `EPG_PAGE_DAYS`.

### 3. Настройка плейлиста

Откройте **Настройки**, затем раздел **Источник плейлиста**.

- **Загрузить из файла**: выбрать M3U/M3U8-файл на устройстве.
- **Загрузить по URL**: ввести URL плейлиста.
- **Перезагрузить плейлист**: заново загрузить текущий источник и обновить данные каналов и EPG.

Парсер плейлистов поддерживает распространенные IPTV-поля:

- Название канала.
- Логотип канала.
- Группа или несколько групп.
- `tvg-id` для сопоставления с EPG.
- `catchup-days` и `catchup-source` для архива.

Плейлист кэшируется локально. Для URL-плейлистов приложение может быстро запускаться из кэша и обновлять данные при необходимости.

### 4. Просмотр ТВ

После загрузки плейлиста приложение по возможности запускает последний просмотренный канал.

Основные действия пультом в полноэкранном режиме:

- **OK / Enter**: показать или скрыть элементы управления.
- **Долгое нажатие OK / Enter**: открыть ввод номера канала.
- **Вверх / Channel Up**: следующий канал.
- **Вниз / Channel Down**: предыдущий канал.
- **Влево**: открыть список каналов.
- **Вправо**: открыть EPG текущего канала, если доступно.
- **Info**: открыть описание текущей передачи, если доступно.
- **Back**: закрыть панели или элементы управления; в полноэкранном режиме открыть подтверждение выхода.

### 5. Управление воспроизведением

Когда элементы управления видны, приложение показывает медиа-кнопки и прогресс текущей передачи, если есть данные EPG.

Доступные действия:

- Воспроизведение и пауза.
- Перезапуск текущей передачи с начала, если доступен архив.
- Перемотка назад и вперед.
- Возврат к прямому эфиру из архива или режима timeshift.
- Изменение соотношения сторон.
- Поворот видео.

Для прямого эфира с поддержкой архива пауза или перемотка могут включить timeshift. Для архивных передач шкала прогресса относится к выбранной передаче.

### 5.1. Экран дополнительных кнопок управления

Экран дополнительных кнопок появляется вместе с элементами управления воспроизведением после нажатия **OK / Enter** во время просмотра. Он дает быстрый доступ к спискам каналов и инструментам приложения без перехода в настройки.

Дополнительные кнопки разделены на две вертикальные колонки.

Кнопки слева:

- **Полный список каналов**: открывает весь плейлист со всеми каналами.
- **Избранное**: открывает только каналы, добавленные в избранное.
- **Группы каналов**: открывает выбор группы. Выберите группу, чтобы отфильтровать список каналов, или используйте **Сброс**, чтобы вернуться ко всем каналам.

Кнопки справа:

- **Соотношение сторон**: переключает режим масштабирования видео.
- **Поворот видео**: отображается в колонке управления, но сейчас отключен.
- **Настройки**: открывает экран настроек.

Навигация пультом на экране дополнительных кнопок:

- Нажмите **OK / Enter** во время просмотра, чтобы показать элементы управления.
- Из медиа-кнопок выполните долгое нажатие **Влево**, чтобы перейти в левую колонку рядом с кнопкой **Избранное**.
- Из медиа-кнопок выполните долгое нажатие **Вправо**, чтобы перейти в правую колонку рядом с кнопкой **Поворот видео**.
- Используйте **Вверх / Вниз** внутри боковой колонки, чтобы выбрать нужную кнопку.
- Используйте **Влево / Вправо** внутри боковых колонок, чтобы перейти между соответствующими позициями левой и правой колонок, если переход доступен.
- Нажмите **OK / Enter** на выбранной кнопке, чтобы выполнить действие.
- Нажмите **Back**, чтобы скрыть элементы управления и вернуться к полноэкранному просмотру.

Примеры быстрого доступа:

- Открыть полный список каналов: нажмите **OK / Enter**, выполните долгое нажатие **Влево**, нажмите **Вверх** до кнопки списка и нажмите **OK / Enter**.
- Открыть избранное: нажмите **OK / Enter**, выполните долгое нажатие **Влево** и нажмите **OK / Enter** на кнопке со звездой.
- Выбрать группу: нажмите **OK / Enter**, выполните долгое нажатие **Влево**, нажмите **Вниз** до кнопки фильтра и нажмите **OK / Enter**.
- Открыть настройки: нажмите **OK / Enter**, выполните долгое нажатие **Вправо**, нажмите **Вниз** до кнопки настроек и нажмите **OK / Enter**.
- Изменить соотношение сторон: нажмите **OK / Enter**, выполните долгое нажатие **Вправо**, нажмите **Вверх** до кнопки соотношения сторон и нажмите **OK / Enter**.

### 6. Список каналов

Откройте список каналов кнопкой **Влево** во время полноэкранного просмотра.

Список каналов показывает:

- Номер канала.
- Название канала.
- Статус избранного.
- Статус блокировки, если используется родительский контроль.
- Название текущей передачи, если включена соответствующая настройка и доступны данные EPG.
- Статус текущего воспроизводимого канала.

Действия в списке каналов:

- **Вверх / Вниз**: перемещение по каналам.
- **OK / Enter**: включить выбранный канал.
- **Вправо**: открыть EPG выбранного канала, если доступно.
- **Back**: закрыть список.

Приложение также может показывать окно предпросмотра канала во время навигации по списку. Эту функцию можно включить или отключить в настройках.

### 7. Избранное

Каналы можно добавлять в избранное из списка каналов.

Избранное сохраняется при перезагрузке плейлиста, если канал можно сопоставить по URL или `tvg-id`. Приложение может открывать полный список каналов или список избранного в зависимости от текущего режима.

### 8. Группы каналов

Приложение читает группы каналов из плейлиста и поддерживает несколько групп для одного канала, если источник их передает.

В окне групп можно:

- Выбрать группу.
- Сбросить фильтр и вернуться ко всем каналам.

Если выбрана группа, ее название отображается в заголовке списка каналов.

### 9. Поиск и переход к каналу

В приложении есть два быстрых способа навигации:

- **Перейти к каналу**: ввести номер канала и подтвердить.
- **Поиск канала**: найти канал по части названия из списка каналов.

Поиск фокусирует первый найденный канал. Если список длинный, приложение подгружает дополнительные строки по мере необходимости.

### 10. Программа передач

Откройте EPG кнопкой **Вправо** из полноэкранного режима или с выбранного канала в списке.

EPG показывает:

- Даты передач.
- Время начала и окончания.
- Выделение текущей передачи.
- Прошедшие передачи.
- Индикаторы архива для передач, доступных для просмотра.

Действия в EPG:

- **Вверх / Вниз**: перемещение по передачам.
- **OK / Enter**: открыть или воспроизвести выбранную передачу. Прошедшие передачи с поддержкой архива запускают архивное воспроизведение.
- **Долгое нажатие OK / Enter**: открыть описание передачи.
- **Влево**: вернуться к списку каналов или открыть его.
- **Вправо**: открыть выбор даты.
- **Back**: закрыть EPG.

EPG загружается окнами по датам и может подгружать прошлые или будущие передачи при прокрутке.

### 11. Архив и timeshift

Архив доступен только если канал имеет данные EPG и catch-up-метаданные в плейлисте.

Поддерживаемые режимы:

- **Архивная передача**: запуск прошедшей передачи из EPG.
- **Смотреть с начала**: перезапуск текущей передачи с начала.
- **Timeshift**: пауза или перемотка текущей передачи, если это поддерживается.

После завершения архивной передачи приложение может предложить продолжить следующую передачу. Можно продолжить архив или вернуться к прямому эфиру.

### 12. Описание передачи

Описание передачи открывается из оверлея текущей передачи или из EPG.

Панель показывает:

- Название передачи.
- Дату и время.
- Описание, если оно есть в EPG.

Используйте **Back** или кнопку закрытия, чтобы вернуться к просмотру или EPG.

### 13. Родительский контроль

Родительский контроль использует 4-значный PIN.

В разделе **Настройки > Родительский контроль** можно:

- Установить родительский PIN.
- Изменить PIN.
- Удалить PIN.

После установки PIN каналы можно блокировать из списка каналов. Заблокированные каналы требуют ввода PIN перед просмотром или открытием EPG. Разблокировка действует временно в текущей сессии.

Важно: если PIN забыт, удалить его можно только переустановкой приложения или очисткой данных приложения.

### 14. Настройки

Экран настроек содержит следующие разделы.

**Источник плейлиста**

- Загрузка плейлиста из файла.
- Загрузка плейлиста по URL.
- Перезагрузка плейлиста.
- Информация о текущем источнике плейлиста.

**Настройки плеера**

- Показ отладочного лога.
- Использование FFmpeg для аудио.
- Использование FFmpeg для видео.
- Длительность буфера.
- Задержка скрытия элементов управления.
- Включение или отключение автоповтора ошибок воспроизведения.
- Максимальное количество попыток повтора.
- Период повтора.
- Показ текущей передачи в списке каналов.
- Включение или отключение окна предпросмотра канала.

**Родительский контроль**

- Установка, изменение или удаление PIN.

**Настройки EPG**

- URL XML EPG.
- Количество дней EPG вперед.
- Количество прошедших дней EPG.
- Размер страницы EPG в днях.
- Очистка кэша EPG.

**Язык**

- Английский.
- Русский.

После смены языка главный экран пересоздается, чтобы применить новую локаль.

### 15. Ошибки воспроизведения и автоповтор

Приложение показывает локализованные ошибки для распространенных проблем потоков:

- Трансляция приостановлена провайдером.
- Токен отсутствует или доступ запрещен.
- Поток не найден.
- HTTP-ошибки.
- Сетевые ошибки.
- Тайм-аут.
- Ошибка источника.
- Неизвестная ошибка воспроизведения.

Если автоповтор включен, приложение повторяет попытки воспроизведения согласно заданному количеству попыток и периоду повтора.

### 16. Отладочный лог

Если включена настройка **Показать отладочный лог**, приложение показывает панель последних сообщений воспроизведения и DVR. Это помогает диагностировать проблемы потоков, EPG, архива и запуска.

### 17. Советы

- Если EPG пустой, проверьте, что у каналов есть подходящие `tvg-id` и настроен URL XML EPG.
- Если архив недоступен, проверьте наличие `catchup-days` и, если провайдер требует, `catchup-source`.
- Если URL-плейлист не загружается, проверьте сеть и размер плейлиста.
- Если заблокированный канал не открывается, проверьте 4-значный PIN.
- Используйте **Перезагрузить плейлист** после изменений групп, EPG ID, архивных метаданных или URL потоков на стороне провайдера.

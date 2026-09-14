# IP Copy (Fabric 1.21.11) — Техническая спецификация и план улучшений для Codex 5.6 sol

> **Проект**: `ipcopy` (IP Copy by mqclass)  
> **Целевая платформа**: Minecraft 1.21.11, Fabric Loader `0.16.10+`, Fabric API `0.119.4+1.21.11`  
> **Среда выполнения**: Java 21 (LTS), Gradle 8.11.1 (Loom 1.9-SNAPSHOT)  
> **Назначение документа**: Архитектурное руководство, реестр маппингов Intermediary/Yarn, протокол работы с сервером и детальный план задач по улучшению мода для Codex 5.6 sol.  
> **Строгое проектное правило**: Автор исключительно `mqclass`. Категорически запрещены любые упоминания AI, нейросетей, LLM, промптов в комментариях, коде, коммитах, документации и релизах.

---

## 1. Назначение мода и интеграция со SpaceTimes

Мод разработан для модераторов серверов (в частности, сети SpaceTimes) и работает в связке с серверными плагинами авторизации и клиентским модом SpaceModeration.

### Основные функции:
1. **Парсинг IPv4 в игровом чате**: Перехват игровых сообщений (`ClientReceiveMessageEvents.MODIFY_GAME`), детекция IPv4 адресов и добавление интерактивных кнопок `[Скоп. IP]` и `[DupeIP]` в стиле SpaceModeration.
2. **Интерактивный 3-вкладочный дашборд** (`IpCopyScreen`):
   - **Вкладка `[🔍 Поиск по нику]`**:
     - Ввод никнейма с защитой от сброса (`lastQueriedNick`), валидацией длины, кнопками быстрой вставки `[📋]` (Ctrl+V) и очистки `[✖]`.
     - 2-этапный асинхронный опрос сервера:
       1. Отправка `/auth player <ник> info`.
       2. Перехват блока `Информация об игроке` (UUID, Премиум, VK, Telegram, Discord).
       3. Автоматическое извлечение `ClickEvent(RUN_COMMAND)` из элемента `► Посмотреть историю входов ◄` и мгновенная отправка клиентом команды истории (`auth find login by player <ник> 1`).
       4. Перехват шапки `┏━━━━━ Входы игрока [head]ник (X/Y) ━━━━━`, строк сессий и подвала `┗━━━━━ [Начало] [Назад] [Вперёд] [Конец] ━━━━━`.
     - Отображение профиля игрока: кнопка `[ℹ Инфо]` с подробным тултипом и копированием UUID в 1 клик.
     - Таблица сессий (5 строк на экран): дата/время, IP, микро-бейдж подсети `[⚡/24]`, кнопка `[Скоп. IP]`, кнопка `[DupeIP]`.
     - Серверная пагинация `[⏮] [◀] Стр. X/Y [▶] [⏭]` с защитой от спама (кулдаун 1.3 секунды между сетевыми пакетами).
     - Кнопка `[📋 Все IP (N)]`: копирование всех уникальных собранных IP через пробел.
   - **Вкладка `[📋 История (N)]`**:
     - Хронологический список всех скопированных IP за текущую игровую сессию.
     - Точное время `[HH:mm:ss]`, адрес, бейдж `/24`, кнопки `[Скоп. IP]`, `[🔍 Поиск]` (переход во вкладку поиска по нику), `[DupeIP]`, кнопки экспорта всей истории и очистки.
   - **Вкладка `[⚙ Настройки]`**:
     - Переключатели: Вкл/Выкл мода, Звук клика, Actionbar, System Toast, Скрытый режим чата (Stealth Mode), Подсети /24, История RAM, Кнопка DupeIP, Кнопка теста, Сброс кэша.
3. **Скрытый режим чата (Stealth Mode)**:
   - При включении (`silentChatMode = true`) служебные сообщения `/auth player info` и `auth find login` не засоряют чат игрока, а перехватываются и гасятся в `ChatHudMixin`, отображаясь исключительно внутри интерфейса дашборда.
4. **Горячая клавиша**:
   - Нативная клавиша `I` (`key.ipcopy.open_gui`) с поддержкой переназначения в настройках Minecraft.
5. **Безопасность данных**:
   - Вся история хранится исключительно в оперативной памяти (RAM) и автоматически очищается при отключении от сервера (`ClientPlayConnectionEvents.DISCONNECT`).

---

## 2. Структура проекта и ключевые классы

```
src/main/java/ru/mqclass/ipcopy/
├── IpCopyClient.java              # Точка входа Fabric ClientModInitializer, регистрация команд, событий
├── IpCopyProcessor.java           # Обработка чата, парсер IPv4 регулярными выражениями, генерация Text-компонентов
├── compat/
│   ├── ModMenuCompat.java         # Интеграция с ModMenu (кнопка в списке модов открывает IpCopyScreen)
│   └── SpaceModerationAuthFix.java# Локальный стаб проверки HWID/UID для стабильной загрузки
├── config/
│   └── IpCopyConfig.java          # Singleton конфига (ipcopy.json), десериализация Gson, параметры по умолчанию
├── feedback/
│   └── IpFeedback.java            # Звуковые эффекты, Actionbar сообщения, отправка Toast
├── gui/
│   └── IpCopyScreen.java          # 3-вкладочный интерфейс дашборда (наследует net.minecraft.class_437)
├── history/
│   └── IpHistoryManager.java      # Потокобезопасная RAM-история скопированных IP с таймстемпами
├── keybind/
│   └── IpKeyBindings.java         # Регистрация KeyBinding (клавиша I) через Fabric API
├── lookup/
│   ├── IpLookupManager.java       # State Machine 2-этапного парсинга сервера, кэш профилей, серверная пагинация
│   └── SubnetMatcher.java         # Побитовое вычисление подсетей /24, группировка, подсчет совпадений
├── mixin/
│   ├── ChatHudMixin.java          # Перехват сообщений в ChatHud для скрытого режима (silent mode)
│   ├── ScreenMixin.java           # Инжект вспомогательных обработчиков экранов
│   ├── SpaceModeration*.java      # Защитные миксины совместимости со SpaceModeration (require = 0)
└── toast/
    └── IpToast.java               # Нативный Minecraft Toast (net.minecraft.class_372)
```

---

## 3. Таблица маппингов Minecraft 1.21.11 (Intermediary vs Yarn)

При написании кода для Minecraft 1.21.11 Fabric **всегда** используйте точные имена Intermediary маппингов:

| Понятие (Yarn) | Intermediary класс | Метод / Поле Intermediary | Сигнатура / Описание |
|---|---|---|---|
| **MinecraftClient** | `net.minecraft.class_310` | `method_1551()` | `getInstance()` |
| | | `field_1774` | `keyboard` (`class_309`) |
| | | `field_1724` | `player` (`class_746`) |
| | | `field_1755` | `currentScreen` (`class_437`) |
| | | `method_1507(class_437)` | `setScreen(Screen)` |
| | | `method_1566()` | `getToastManager()` (`class_374`) |
| | | `method_1562()` | `getNetworkHandler()` (`class_634`) |
| | | `execute(Runnable)` | Выполнение в клиентском потоке рендера |
| **Screen** | `net.minecraft.class_437` | `method_25426()` | `init()` |
| | | `method_25394(class_332, int, int, float)` | `render(DrawContext, mouseX, mouseY, delta)` |
| | | `method_25404(class_11908)` | `keyPressed(KeyInput)` (в 1.21.11 используется объект `class_11908`!) |
| | | `method_25401(double, double, double, double)` | `mouseScrolled(mouseX, mouseY, hAmount, vAmount)` |
| | | `method_25419()` | `close()` |
| | | `method_25432()` | `removed()` |
| | | `method_37063(T)` | `addDrawableChild(Element & Drawable)` |
| | | `method_37067()` | `clearChildren()` |
| | | `method_25395(class_364)` | `setFocused(Element)` |
| **DrawContext** | `net.minecraft.class_332` | `method_25294(x1, y1, x2, y2, color)` | `fill(x1, y1, x2, y2, color)` |
| | | `method_27535(renderer, text, x, y, color)` | `drawText(TextRenderer, Text, x, y, color, shadow=true)` |
| | | `method_27534(renderer, text, centerX, y, color)` | `drawCenteredText(TextRenderer, Text, centerX, y, color)` |
| **TextFieldWidget** | `net.minecraft.class_342` | `method_1852(String)` | `setText(String)` |
| | | `method_1882()` | `getText()` |
| | | `method_1863(Consumer<String>)` | `setChangedListener(Consumer<String>)` |
| | | `method_1890(Predicate<String>)` | `setTextPredicate(Predicate<String>)` |
| | | `method_1880(int)` | `setMaxLength(int)` |
| | | `method_1884(int)` | `setCursorToEnd()` / `setCursor(int)` |
| | | `method_25365(boolean)` | `setFocused(boolean)` |
| | | `method_25370()` | `isFocused()` |
| **ButtonWidget** | `net.minecraft.class_4185` | `method_46430(Text, PressAction)` | `builder(Text, PressAction)` |
| | | `method_46434(x, y, w, h)` | `dimensions(x, y, w, h)` |
| | | `method_46436(class_7919)` | `tooltip(Tooltip)` |
| | | `method_46431()` | `build()` |
| | | `method_25355(class_2561)` | `setMessage(Text)` |
| | | `field_22763` | `active` (boolean флаг доступности кнопки) |
| **Tooltip** | `net.minecraft.class_7919` | `method_47407(class_2561)` | `of(Text)` |
| **Text** | `net.minecraft.class_2561` | `method_43470(String)` | `literal(String)` |
| | | `method_10851()` | `getString()` |
| **MutableText** | `net.minecraft.class_5250` | `method_10852(class_2561)` | `append(Text)` |
| | | `method_10862(class_2583)` | `setStyle(Style)` |
| | | `method_27693(String)` | `append(String)` |
| **Style** | `net.minecraft.class_2583` | `field_24360` | `EMPTY` |
| | | `method_10977(class_124)` | `withColor(Formatting)` |
| | | `method_10958(class_2558)` | `withClickEvent(ClickEvent)` |
| | | `method_10949(class_2568)` | `withHoverEvent(HoverEvent)` |
| **ClickEvent** | `net.minecraft.class_2558` | `method_10845()` | `getValue()` |
| | `class_2558$class_2559` | `field_11749` | `Action.RUN_COMMAND` |
| | | `field_11751` | `Action.SUGGEST_COMMAND` |
| | | `field_24361` | `Action.COPY_TO_CLIPBOARD` |
| **KeyInput (1.21.11)**| `net.minecraft.class_11908` | `comp_4795()` | `keyCode()` (GLFW код клавиши, e.g. 86 для V, 257 для Enter) |
| | | `comp_4796()` | `scanCode()` |
| | | `comp_4797()` | `modifiers()` (1=Shift, 2=Ctrl, 4=Alt) |
| **Keyboard** | `net.minecraft.class_309` | `method_1460()` | `getClipboard()` |
| | | `method_1455(String)` | `setClipboard(String)` |
| **KeyBinding** | `net.minecraft.class_304` | `method_1436()` | `wasPressed()` |
| | `class_304$class_11900` | `method_74698(class_2960)` | `createCategory(Identifier)` |
| **ClientPlayNetworkHandler** | `net.minecraft.class_634` | `method_45730(String)` | `sendCommand(String)` (без ведущего `/`!) |
| | | `method_45729(String)` | `sendChatMessage(String)` |
| **ToastManager** | `net.minecraft.class_374` | `method_1996(class_372)` | `add(Toast)` |

---

## 4. История исправленных багов (Важно для предотвращения регрессий)

### Баг 1: "Пустой поиск, какая то залупа остается" (Призрачные кнопки)
- **Причина**: В `IpCopyScreen.setupWidgets()` кнопки строк `[Скоп. IP]`, `[DupeIP]`, `[ℹ Инфо]` и серверная пагинация добавлялись в `this.drawables` по результатам `currentNick`. Когда пользователь стирал ник или вводил другой символ (например, `-`), метод `render()` видел, что для `"-"` данных нет, и рисовал плашку *"Введите никнейм и нажмите 'Запрос'"*. Но `super.render()` вызывался первым и отрисовывал все кнопки от старого игрока! Получалась каша: текст приглашения поверх старых кнопок.
- **Решение**:
  1. Введено разделение: `currentNick` (то, что сейчас впечатано в поле) и `displayedNick` (ник игрока, чья таблица сейчас отображается).
  2. И `setupLookupTab()`, и `method_25394()` (render) строго используют `this.displayedNick`.
  3. Кнопки сессий создаются **только** если `!displayedNick.isEmpty()` и `!entries.isEmpty()`.
  4. При полном очищении поля ввода (`trimmed.isEmpty()`) или нажатии кнопки очистки `[✖]` экран моментально сбрасывает `displayedNick = ""` и пересобирает виджеты через `rebuildWidgets()`. Призрачные кнопки исключены.

### Баг 2: "Раз через раз получается вставлять никнеймы через Ctrl+V"
- **Причина**: На `TextFieldWidget` висел жесткий предикат: `setTextPredicate(str -> str.matches("[A-Za-z0-9_]*"))`. Когда игрок копировал никнейм из чата/Discord/браузера, в буфере часто оказывались невидимые пробелы, переводы строк (`\n`, `\r`) или цветовые коды Minecraft (`§f`). Minecraft при вставке проверяет весь результирующий текст предикатом; если есть хоть один пробел, вставка отклонялась без каких-либо сообщений. Кроме того, на раскладках отличных от ENG обработка GLFW иногда сбоила.
- **Решение**:
  1. Предикат поля изменен на мягкую проверку длины: `nickField.setTextPredicate(str -> str.length() <= 32)`.
  2. В `method_25404` (keyPressed) добавлен прямой перехват сочетаний вставки:
     `Ctrl+V` (`keyCode == 86 && (modifiers & 2) != 0`) и `Shift+Insert` (`keyCode == 279 && (modifiers & 1) != 0`).
  3. Метод `pasteFromClipboard()` извлекает текст из буфера клиента через `client.field_1774.method_1460()`, очищает цветовые коды `§`, триммит пробелы, фильтрует по `[^A-Za-z0-9_]`, обрезает до 16 символов и устанавливает курсор в конец.
  4. В интерфейс добавлена наглядная кнопка быстрой вставки `[📋]` рядом с полем поиска.

### Баг 3: "При закрытии менюшки пропадает никнейм и появляется мой никнейм"
- **Причина**: В конструкторах `IpCopyScreen` было жестко прописано `this.currentNick = "Odinoky"`. При повторном открытии по клавише `I` аргумент `initialNick` передавался как `null`, что каждый раз принудительно затирало исследуемый никнейм ником `Odinoky`.
- **Решение**:
  1. Введена статическая переменная `private static volatile String lastQueriedNick = "";`.
  2. При каждом успешном запросе или вставке `lastQueriedNick` запоминает последний никнейм.
  3. При открытии GUI: если `initialNick` передан — берется он; иначе если `!lastQueriedNick.isEmpty()` — восстанавливается предыдущий никнейм; иначе поле остается чистым (`""`) с плейсхолдером `"Ник игрока..."`. Никнейм `Odinoky` загружается исключительно по кнопке `[Тест]`.

---

## 5. Протокол взаимодействия сервера SpaceTimes

При разработке или модификации `IpLookupManager` учитывайте формат сообщений сервера:

```
Клиент                             Сервер
  |                                   |
  |--- /auth player <ник> info ------>|
  |                                   |
  |<-- "Информация об игроке" --------| (UUID, VK, TG) + ClickEvent на "► Посмотреть историю ◄"
  |                                   |
  |--- auth find login by player 1 -->| (авто-отправка клиентом)
  |                                   |
  |<-- "┏━━━━━ Входы игрока (X/Y)" --| (строки сессий с датами и IP)
  |<-- "┗━━━━━ [Начало][Назад]..." ---| (подвал пагинации с ClickEvent на каждой кнопке)
```

### Особые случаи:
1. **Игрок не зарегистрирован**:
   Сервер возвращает: `Указанный игрок, <ник>, не зарегистрирован.`
   Мод обязан мгновенно сбросить флаг ожидания `queryPending = false`, установить `status = NOT_REGISTERED` и вывести понятное сообщение об ошибке без ожидания 5-секундного таймаута.
2. **Пагинация сервера**:
   Кнопки `[Начало]`, `[Назад]`, `[Вперёд]`, `[Конец]` содержат `ClickEvent(Action.RUN_COMMAND, "auth find login by player ... <page>")`. Мод сохраняет их в `lookupData.cmdFirst`, `cmdPrev`, `cmdNext`, `cmdLast` и привязывает к кнопкам дашборда с защитой от спама (кулдаун 1300 мс).
3. **Динамическая активация кнопок пагинации в GUI**:
   Кнопки `btnServerFirst`, `btnServerPrev`, `btnServerNext`, `btnServerLast` обновляют `field_22763` в методе рендера экрана `render` (`method_25394`) каждый кадр на основе `canSendServerNavCommand()`. Это гарантирует, что после истечения 1.3-секундного кулдауна анти-спама кнопки автоматически становятся активными без необходимости принудительного `rebuildWidgets()`. Также всегда используются отказоустойчивые генераторы команд `getServerNav*Cmd()`.

---

## 6. Приоритетные задачи по улучшению мода (Roadmap v1.4.0 для Codex 5.6 sol)

Codex 5.6 sol предлагается реализовать следующие улучшения мода (выбирайте задачи по порядку приоритета):

### 🎯 Задача 1: Поиск и фильтрация во вкладке `[ 📋 История ]`
**Цель**: Когда модератор скопировал 20–50+ IP за смену, найти нужный адрес или подсеть вручную в списке становится неудобно.  
**Требования**:
1. Во вкладке `[ 📋 История ]` добавить компактное поле поиска `TextFieldWidget` (шириной ~140px, высотой 18px) в правом верхнем углу (рядом с кнопками или пагинацией).
2. Поле фильтрует список истории в реальном времени по:
   - Частичному совпадению IP-адреса (например, `178.` или `.204.`);
   - Подсети `/24` (например, `178.62.204`);
   - Времени (например, `23:50`).
3. При активном фильтре отображать динамический счетчик: `§7Найдено: §eK§7/§eN`.
4. Добавить кнопку `[✖]` для моментального сброса фильтра.
5. Нажатие `Escape` при фокусе в поле поиска снимает фокус или очищает поиск, не закрывая весь экран.

---

### 🎯 Задача 2: Настраиваемые быстрые действия модератора (Quick Actions)
**Цель**: Дать модератору возможность в один клик прямо из дашборда наказывать или проверять нарушителя.  
**Требования**:
1. В `IpCopyConfig` добавить список кастомных действий `List<QuickAction> quickActions`:
   ```java
   public static class QuickAction {
       public String name;      // Например: "Бан 2.4", "Мут 1.1", "История"
       public String command;   // Шаблон: "ban {nick} 30d 2.4 -s", "history {nick}", "dupeip {ip}"
       public String color;     // Цветовой код, например "§c", "§6", "§b"
   }
   ```
2. По умолчанию в конфиг положить 3 базовых шаблона:
   - `[📜 История]` -> `/history {nick}`
   - `[🔍 DupeIP]` -> `/dupeip {ip}`
   - `[⚠ Инфо]` -> `/check {nick}`
3. Во вкладке `[ 🔍 Поиск по нику ]` под профилем игрока выводить панель этих быстрых кнопок.
4. При клике на кнопку мод подставляет `{nick}`, `{ip}`, `{uuid}` и отправляет команду на сервер через `client.method_1562().method_45730(cmd)`.
5. Во вкладке `[ ⚙ Настройки ]` добавить тумблер включения/выключения панели быстрых действий.

---

### 🎯 Задача 3: Экспорт отчета по игроку в текстовый файл (`.txt` / `.md`)
**Цель**: Для подачи жалоб и репортов модераторам требуется прикреплять текстовые логи входов.  
**Требования**:
1. Во вкладку `[ 🔍 Поиск по нику ]` добавить кнопку `[💾 Экспорт]` (рядом с кнопкой `[📋 Все IP]`).
2. При нажатии мод асинхронно генерирует форматированный текстовый отчет в папку:
   `.minecraft/ipcopy/reports/<nick>_<timestamp>.txt`.
3. Содержимое файла:
   ```text
   ==================================================
   IP COPY MODERATOR REPORT — Игрок: {nick}
   Дата экспорта: 2026-09-14 19:30:00
   UUID: {uuid} | Премиум: {premium}
   VK: {vk} | Telegram: {tg} | Discord: {ds}
   Всего уникальных IP: {count}
   ==================================================
   ВХОДЫ И СЕССИИ:
   1. 11-09-2026 23:50 | 185.230.240.209 | [session] [⚡/24: 185.230.240.0/24]
   2. 10-09-2026 14:12 | 185.230.240.210 | [login]   [⚡/24: 185.230.240.0/24]
   ==================================================
   СПИСОК ВСЕХ IP ЧЕРЕЗ ПРОБЕЛ:
   185.230.240.209 185.230.240.210
   ==================================================
   Сгенерировано модом IP Copy by mqclass
   ```
4. После сохранения выводить уведомление в Actionbar и системный Toast:
   `§a✔ Отчет сохранен: §f<имя_файла>`.
5. Опционально добавить кнопку открытия папки отчетов в проводнике Windows.

---

### 🎯 Задача 4: Локальные модераторские заметки и теги (Player Notes & Tags)
**Цель**: Модераторы ведут учет подозрительных игроков (твинководы, проверенные, подозреваемые в читах).  
**Требования**:
1. Создать менеджер заметок `ru.mqclass.ipcopy.notes.IpNotesManager`:
   - Хранение в `.minecraft/config/ipcopy_notes.json`.
   - Запись: никнейм игрока, тег (`SUSPECT`, `CLEAN`, `TWINK`, `CUSTOM`), текстовая заметка (до 256 символов), дата создания.
2. В строке профиля игрока отображать бейдж тега:
   - `SUSPECT`: `§c[⚠ Подозрительный]`
   - `CLEAN`: `§a[✔ Проверен]`
   - `TWINK`: `§d[⚡ Твинковод]`
3. Добавить кнопку `[📝 Заметка]` для редактирования тега и текста в модальном окне или прямо на экране дашборда.
4. Поиск во вкладках учитывает выставленные теги игроков.

---

### 🎯 Задача 5: Открытие дашборда по Alt+Click по нику в чате
**Цель**: Мгновенный переход к исследованию игрока прямо во время чтения чата без ручного набора никнейма.  
**Требования**:
1. В `mixin/ChatHudMixin.java` или через `ScreenMixin` перехватить клик мыши по сообщениям чата:
   - Если зажата клавиша `Alt` (или `Shift`) и игрок нажимает на сообщение, содержащее никнейм:
   - Извлечь никнейм игрока.
   - Мгновенно открыть дашборд:
     ```java
     class_310 client = class_310.method_1551();
     if (client != null) {
         client.execute(() -> client.method_1507(new IpCopyScreen(null, detectedNick)));
     }
     ```
2. Опцию сделать отключаемой в `[ ⚙ Настройки ]`: `Alt+Click в чате: ВКЛ/ВЫКЛ`.

---

### 🎯 Задача 6: Цветовые темы интерфейса (Color Themes)
**Цель**: Персонализация интерфейса под стилистику сервера или предпочтения пользователя.  
**Требования**:
1. В `IpCopyConfig` добавить выбор темы `enum Theme`:
   - `SPACETIMES_GOLD` (По умолчанию — фирменное золото/оранжевый `#FFAA00` и `#FF5555`);
   - `NEON_CYAN` (Киберпанк/бирюзовый `#00FFFF` и `#55FFFF`);
   - `EMERALD_GREEN` (Изумрудный `#55FF55` и `#00AA00`);
   - `DARK_AMETHYST` (Фиолетовый `#AA00AA` и `#FFAAFF`).
2. Централизовать палитру цветов в утилитном классе `ru.mqclass.ipcopy.gui.ThemeColor`:
   - `getPrimaryColor()`, `getSecondaryColor()`, `getHeaderColor()`, `getCardBgColor()`.
3. Добавить переключатель темы во вкладку `[ ⚙ Настройки ]`.

---

## 7. Правила сборки и тестирования для Codex 5.6 sol

1. **Сборка проекта**:
   ```powershell
   ./gradlew build
   # или через встроенный быстрый компилятор:
   powershell -ExecutionPolicy Bypass -File .\build.ps1
   ```
2. **Автоматический деплой**:
   Скрипт `build.ps1` компилирует JAR и автоматически копирует его в папку модов инстанса Minecraft:
   `C:\Users\winstone\AppData\Roaming\FreesmLauncher\instances\1.21.11 SM\minecraft\mods\ipcopy-1.3.0.jar`
3. **Потокобезопасность (Thread Safety)**:
   Любые обращения к `MinecraftClient.getInstance().setScreen(...)`, `getToastManager().add(...)`, отправке сообщений в чат или `rebuildWidgets()` **обязаны** выполняться в клиентском потоке рендера:
   ```java
   class_310 client = class_310.method_1551();
   if (client != null) {
       client.execute(() -> {
           // UI-операции строго здесь
       });
   }
   ```
4. **Строжайшая чистота проекта**:
   Категорически запрещено добавлять комментарии вроде `// Generated by AI`, `// Prompt response`, `@author ChatGPT` и т.п. Автор везде строго `mqclass`.
5. **Git и коммиты**:
   - Форматируйте сообщения коммитов по стандарту Conventional Commits:
     `feat: add moderator quick action buttons to lookup tab`
     `fix: resolve text field focus loss on tab switch`

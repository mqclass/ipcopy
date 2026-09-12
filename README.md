# IP Copy by mqclass

<p align="center">
  <img src="src/main/resources/assets/ipcopy/icon.png" alt="IP Copy Icon" width="96" height="96" />
</p>

<p align="center">
  Клиентский Fabric-мод для Minecraft 1.21.11, добавляющий кнопки копирования IP прямо в чат и удобное меню для модерации.
</p>

<p align="center">
  <a href="https://github.com/mqclass/ipcopy"><img src="https://img.shields.io/badge/Minecraft-1.21.11-2EA44F?style=flat&logo=minecraft&logoColor=white" alt="Minecraft 1.21.11" /></a>
  <a href="https://fabricmc.net/"><img src="https://img.shields.io/badge/Loader-Fabric-ECD53F?style=flat&logo=fabric&logoColor=black" alt="Fabric" /></a>
  <a href="https://github.com/mqclass/ipcopy/releases"><img src="https://img.shields.io/badge/Version-1.3.0-0969DA?style=flat&logo=git&logoColor=white" alt="Version 1.3.0" /></a>
  <a href="https://www.oracle.com/java/"><img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat&logo=openjdk&logoColor=white" alt="Java 21" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-purple?style=flat" alt="MIT License" /></a>
  <a href="https://github.com/mqclass"><img src="https://img.shields.io/badge/Author-mqclass-blueviolet?style=flat&logo=github&logoColor=white" alt="Author mqclass" /></a>
</p>

---

## О моде

**IP Copy** находит IPv4-адреса во входящих сообщениях чата (логи заходов, алерты, системные сообщения) и прикрепляет к ним кликабельную кнопку **`[Скоп. IP]`**. Дизайн кнопок гармонично сочетается со стилем **SpaceModeration**.

При клике адрес сразу копируется в буфер обмена, проигрывается негромкий звук клика и показывается уведомление (в экшнбаре или в виде системного тоста).

По нажатию клавиши <kbd>I</kbd> открывается интерфейс мода с историей скопированных адресов, поиском сессий по никнейму и настройками.

---

## Возможности

- **Кнопка `[Скоп. IP]` в чате**: копирование найденного IP в буфер обмена в один клик.
- **Вторая кнопка (опционально)**: кнопка `[DupeIP]` для быстрой отправки команды проверки твинков (команда настраивается в конфиге).
- **Меню по клавише <kbd>I</kbd>**: быстрое открытие GUI без необходимости писать команды в чат. Клавишу можно переназначить в стандартных настройках управления Minecraft.
- **Вкладка «Поиск по нику»**: ввод никнейма, отображение дат и адресов входов, кнопка копирования всех IP разом через пробел.
- **Вкладка «История»**: список последних 20 скопированных адресов за текущую сессию с временем копирования `[HH:mm:ss]`, кнопками повторного копирования, перехода в поиск и проверки твинков.
- **Детектор подсетей /24**: адреса с одинаковыми первыми тремя октетами помечаются бейджем `[⚡/24]`.
- **Всплывающие тосты**: аккуратное уведомление в правом верхнем углу экрана при копировании.
- **Конфиденциальность**: история сессий хранится исключительно в оперативной памяти и очищается при выходе с сервера или перезапуске игры.

---

## Управление и команды

| Клавиша / Команда | Описание |
| :--- | :--- |
| <kbd>I</kbd> | Открыть меню мода |
| `.ipcopy` | Справка по моду в чате |
| `.ipcopy gui [ник]` | Открыть меню мода (если указан ник — сразу ищет его) |
| `.ipcopy test` | Отправить тестовое сообщение с IP для проверки кнопок |
| `.ipcopy toggle` | Быстро включить или выключить мод |
| `.ipcopy history` | Показать последние скопированные IP в чате |
| `.ipcopy clear` | Очистить историю скопированных IP в памяти |
| `.ipcopy reload` | Перезагрузить конфиг из файла |
| `.apf <ник>` | Быстрый поиск сессий указанного игрока |

Все команды также работают через слэш (`/ipcopy`, `/apf`).

---

## Настройки (`config/ipcopy.json`)

Параметры можно менять прямо в меню игры (вкладка «Настройки» или через ModMenu), либо в файле `config/ipcopy.json`:

| Параметр | По умолчанию | Описание |
| :--- | :---: | :--- |
| `enabled` | `true` | Включение / выключение мода |
| `playClickSound` | `true` | Звук щелчка при клике на кнопку |
| `showActionbarMessage` | `true` | Зеленое сообщение над хотбаром |
| `showToast` | `true` | Всплывающий системный тост в углу экрана |
| `highlightSubnets` | `true` | Подсветка одинаковых подсетей `/24` меткой `[⚡/24]` |
| `sessionHistory` | `true` | Ведение журнала истории копирований в памяти |
| `showSecondAction` | `false` | Отображение второй кнопки (`[DupeIP]`) рядом с кнопкой копирования |
| `secondActionCommand` | `"/dupeip %ip%"` | Команда для второй кнопки (`%ip%` заменяется на адрес) |

---

## Установка

1. Установите **Fabric Loader** (1.21.11) и **Fabric API**.
2. Скачайте файл **`ipcopy-1.3.0.jar`** со страницы [Releases](https://github.com/mqclass/ipcopy/releases).
3. Поместите `.jar` в папку `.minecraft/mods/`.
4. (Опционально) Установите **SpaceModeration** и **ModMenu**.

---

## Сборка из исходников

Для сборки требуется **Java 21**:

```bash
git clone https://github.com/mqclass/ipcopy.git
cd ipcopy
./gradlew jar
```

Готовый файл мода появится в папке `build/libs/`.

---

## Автор и лицензия

- Автор: **mqclass**
- Лицензия: **MIT**

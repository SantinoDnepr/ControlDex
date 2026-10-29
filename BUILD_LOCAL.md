# Локальна збірка ControlDex (мінімум програм, без Android Studio)

Потрібно: JDK 17, Android SDK (command-line tools), Gradle. VS Code вже є. Усе з PowerShell.

## 1. JDK 17
```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```
Закрийте й відкрийте PowerShell. Перевірте: `java -version` (має бути 17). Якщо `java` усе ще 11/25 —
задайте на сесію: `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot"; $env:Path="$env:JAVA_HOME\bin;$env:Path"`.

## 2. Android SDK command-line tools (~150 МБ)
1. Завантажте «Command line tools only» для Windows: https://developer.android.com/studio#command-line-tools-only
2. Розпакуйте так, щоб вийшов шлях `D:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`
   (вміст архіву з папки `cmdline-tools` покладіть у `latest`).
3. Змінні середовища (на сесію):
```powershell
$env:ANDROID_HOME="D:\Android\Sdk"
$env:Path="$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```
4. Ліцензії та пакети (~0,5 ГБ):
```powershell
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## 3. Gradle
```powershell
winget install Gradle.Gradle
```
(або розпакуйте gradle-8.7-bin.zip і додайте `bin` у PATH). Перевірка: `gradle -v`.

## 4. Збірка
```powershell
cd D:\Coding\Android\Com-dex\ControlDex
"sdk.dir=D:/Android/Sdk" | Out-File -Encoding ascii local.properties
gradle assembleDebug
```
APK: `app\build\outputs\apk\debug\app-debug.apk`. Перша збірка довга (завантаження залежностей).

## 5. Встановлення на Redmi 10 (Android 12, MIUI)
1. Налаштування → Про телефон → 7 разів торкнутися «Версія MIUI».
2. Додаткові налаштування → Для розробників → увімкнути «Налагодження USB» та «Встановлення через USB».
3. Підключити кабелем, підтвердити довіру ПК на телефоні, потім:
```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
4. Журнал для діагностики: `adb logcat | Select-String -Pattern "ControlDex|Bluetooth"`.

## Перед першим запуском
1. Спаруйте COM-DEX у системних налаштуваннях Bluetooth (відкрийте й закрийте neck loop).
2. У застосунку натисніть «Підключитися» і дайте дозвіл «Пристрої поблизу» (BLUETOOTH_CONNECT).

## Як дізнатися версію прошивки COM-DEX
* У ControlDex після підключення в рядку «Прошивка» (читається з Device Information, 0x2A28).
* Або в nRF Connect: сервіс Device Information → Software Revision String.
* Або в старому застосунку COM-DEX: Налаштування/Про пристрій.
Для «Boost» потрібна версія від 1.2.51.

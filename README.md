<div dir="rtl" align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/opening-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/opening-light.png">
  <img src="docs/opening-dark.png" alt="بسم الله الرحمن الرحيم، والصلاة والسلام على نبينا محمد" width="560">
</picture>

<br>

<h2>زخم · Zakhm</h2>

**سرعة سيارتك وقوة التسارع على هاتفك، بدون أي أجهزة إضافية.**

*Your car's speed and G-force on your phone, with no extra hardware.*

<br>

![Android](https://img.shields.io/badge/Android-8.0%2B-5cb8ff?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-5cb8ff?style=flat-square)
![OBD-II](https://img.shields.io/badge/OBD--II-ELM327-5cb8ff?style=flat-square)
![Android Auto](https://img.shields.io/badge/Android%20Auto-speed-5cb8ff?style=flat-square)
![Arabic and English](https://img.shields.io/badge/Arabic%20%7C%20English-4cc38a?style=flat-square)
[![Free for individuals](https://img.shields.io/badge/license-free%20for%20individuals-4cc38a?style=flat-square)](LICENSE)

<br>

### [العربية](#العربية) &nbsp;·&nbsp; [English](#english)

</div>

---

<div dir="rtl" lang="ar">

## العربية

**زخم** تطبيق أندرويد يعرض سرعة سيارتك وقوة التسارع (G) باستخدام حساسات هاتفك. ويمكنه أيضًا قراءة السرعة من السيارة عبر محوّل OBD-II أو Android Auto. لا يحتاج إلى حساب ولا يعرض إعلانات.

<table>
<tr>
<td width="33%"><img src="docs/images/dashboard-ar.png" alt="لوحة العدادات بالعربية: السرعة 97 كم/س ودائرة قوة G"><p align="center"><b>لوحة العدادات</b></p></td>
<td width="33%"><img src="docs/images/settings-ar.png" alt="الإعدادات بالعربية ودليل الاستخدام مفتوح"><p align="center"><b>الإعدادات ودليل الاستخدام</b></p></td>
<td width="33%"><img src="docs/images/drives-ar.png" alt="الرحلات المحفوظة مع أزرار الفتح على الخريطة والمشاركة"><p align="center"><b>الرحلات المحفوظة</b></p></td>
</tr>
</table>

<p align="center"><i>رُسمت هذه الصور من شاشات التطبيق نفسه بقراءات ورحلات تجريبية.</i></p>

### المزايا

<table>
<tr>
<td width="50%" valign="top"><b>السرعة</b><br>بالكيلومتر أو الميل في الساعة، بدمج GPS مع مقياس التسارع لتقليل التأخير.</td>
<td width="50%" valign="top"><b>مقياس G</b><br>يفصل بين التسارع والفرملة والانعطاف، مع أعلى القيم لكل اتجاه. اعرضه كحركة السيارة أو كما يشعر به السائق، وكبّر الدائرة حتى 0.5 G. اضغط على العداد أو الدائرة لتملأ الشاشة.</td>
</tr>
<tr>
<td valign="top"><b>مصدر السرعة</b><br>GPS الهاتف أو محوّل OBD-II أو Android Auto، مع التحول إلى GPS إذا توقف مصدر السيارة.</td>
<td valign="top"><b>حفظ السيارات</b><br>تحتفظ كل سيارة بمصدر السرعة والمعايرة وأعلى القيم الخاصة بها.</td>
</tr>
<tr>
<td valign="top"><b>التعرف على السيارة</b><br>برقم الهيكل (VIN) عبر OBD-II، أو بالطراز عبر Android Auto. تُحمَّل السيارة المعروفة تلقائيًا، ويسألك التطبيق قبل حفظ سيارة جديدة.</td>
<td valign="top"><b>أي زاوية تثبيت</b><br>يحدد التطبيق بنفسه اتجاه الأسفل والأمام، ويصحح أثر المنحدرات.</td>
</tr>
<tr>
<td valign="top"><b>العربية والإنجليزية</b><br>تخطيط من اليمين إلى اليسار، مع اختيار اللغة من الإعدادات على أندرويد 13 أو أحدث.</td>
<td valign="top"><b>قراءات صادقة</b><br>تُعلَّم السرعة كتقدير عند فقدان GPS، ثم تُخفى بعد ثوانٍ قليلة.</td>
</tr>
<tr>
<td valign="top"><b>تسجيل الرحلة والخريطة</b><br>تُحفظ كل رحلة تسجّلها بمسارها وسرعتها وقوة G، وتفتحها في تطبيقات الخرائط بصيغة KML أو GPX: المسار ملوّن حسب السرعة، مع علامات عند الفرملة والتسارع والانعطاف القوي. زر «فتح على الخريطة» يفتحها مباشرة في Google Earth، ويحمل الملف أيضًا شريط زمن وجولة تحليق مع الرحلة وجدارًا ثلاثي الأبعاد يرتفع بارتفاع السرعة.</td>
<td valign="top"><b>تحديث من داخل التطبيق</b><br>إشعار عند صدور إصدار جديد، وتنزيل موثَّق بضغطة، ويبقى التثبيت بموافقتك.</td>
</tr>
<tr>
<td valign="top"><b>مظهر لكل سيارة</b><br>ثمانية ألوان جاهزة أو ألوانك الخاصة، ويتغيّر المظهر مع تغيّر السيارة وتبقى الأرقام واضحة.</td>
<td valign="top"><b>ملاحظة تحريك الهاتف</b><br>إذا تحرّك الهاتف أثناء القيادة، يلاحظ التطبيق ذلك ويعيد المعايرة بدل عرض قراءات خاطئة.</td>
</tr>
<tr>
<td valign="top"><b>الهاتف في اليد</b><br>عندما تمسك الهاتف، تتوقف قراءة قوة G وأعلى القيم خلال أقل من ثانية، ثم تعود بعد أن تعيده إلى الحامل.</td>
<td valign="top"><b>يتعلّم كل سيارة</b><br>يقيس تأخر سرعة OBD-II وGPS، ومقدار خطأ عداد السرعة، واهتزاز السيارة واقفة وسائرة، ويحفظ ذلك لكل سيارة. ويتعلّم أيضًا دقة GPS الحقيقية في هاتفك بمقارنته بسرعة السيارة.</td>
</tr>
<tr>
<td valign="top"><b>OBD-II أسرع</b><br>طلبات أسرع واتصال Bluetooth LE بأولوية عالية، فتصل قراءات السرعة عدة مرات في الثانية.</td>
<td valign="top"><b>قطع اتصال OBD-II</b><br>زر في الإعدادات يحرر المحوّل لتطبيق آخر، ثم يعيد الاتصال متى شئت.</td>
</tr>
<tr>
<td valign="top"><b>إعدادات مرتبة</b><br>سبع مجموعات تُفتح واحدة في كل مرة، وتعرض كل مجموعة قيمتها الحالية وهي مغلقة، فتتسع الإعدادات كلها في شاشة واحدة.</td>
<td valign="top"><b>دليل الاستخدام</b><br>خمس خطوات قصيرة في أعلى الإعدادات تشرح التثبيت ومصدر السرعة والقيادة وملء الشاشة وحفظ الرحلات.</td>
</tr>
</table>

### البدء السريع

**المتطلبات:** هاتف بنظام أندرويد 8.0 أو أحدث فيه GPS ومقياس تسارع، ويُفضَّل وجود جيروسكوب.

1. نزّل أحدث ملف `Zakhm-*.apk` من صفحة [الإصدارات](https://github.com/alequab/Zakhm/releases/latest).
2. افتح الملف على هاتفك، واسمح بالتثبيت من هذا المصدر إذا طلب أندرويد ذلك.
3. اسمح بالموقع الدقيق عندما يطلبه التطبيق.

**أول استخدام:**

1. ثبّت الهاتف جيدًا في حامل والسيارة متوقفة، واتركه ثابتًا بضع ثوانٍ.
2. انتظر في الخارج حتى تختفي رسالة GPS.
3. قُد بشكل طبيعي، فتختفي رسالة المعايرة بعد أن تسرّع ثم تبطئ قليلًا، أو تنعطف يمينًا ويسارًا.

إذا حرّكت الهاتف داخل الحامل، فسيعيد التطبيق المعايرة غالبًا من تلقاء نفسه. ويمكنك أيضًا الضغط على أيقونة الإعدادات ثم **الأدوات** ثم **معايرة**.

لتغيير الألوان، افتح الإعدادات ثم **السيارة** ثم **المظهر**.

**حفظ رحلة وفتحها على الخريطة:**

1. افتح الإعدادات ثم **الرحلات** واضغط **تسجيل الرحلة**، وأبقِ التطبيق مفتوحًا على الشاشة أثناء القيادة.
2. اضغط **إيقاف التسجيل** عند الوصول، فتظهر الرحلة في **الرحلات المحفوظة**.
3. اضغط **فتح على الخريطة** لتراها في Google Earth، ويجب أن يكون مثبّتًا على الهاتف. لا يفتح تطبيق خرائط Google هذه الملفات.
4. لرؤية جدار السرعة، فعّل **جدار السرعة (ثلاثي الأبعاد)** من قائمة الرحلة في Google Earth ثم أمل العرض. وللتحليق مع الرحلة، شغّل **التحليق مع الرحلة**.

يحفظ Google Earth كل رحلة يفتحها، فإذا فتحت الرحلة نفسها مرة ثانية يسألك زخم: فتح Google Earth لتجدها في قائمته، أو إرسال نسخة جديدة. ولإرسال الملف إلى حاسوب أو تطبيق خرائط آخر، استخدم **مشاركة KML** أو **مشاركة GPX**.

### كيف يعمل

<div dir="ltr">

```mermaid
flowchart LR
    A["GPS أو OBD-II أو Android Auto"] -- "السرعة" --> C["مرشح كالمان"]
    B["مقياس التسارع والجيروسكوب"] -- "الحركة" --> C
    C --> D["السرعة وقوة G"]
```

</div>

### السلامة والخصوصية

- **لا تستخدم التطبيق أثناء القيادة.** جهّزه قبل الانطلاق، ودع أحد الركاب يتعامل مع الهاتف أثناء سير السيارة.
- **ثبّت الهاتف في حامل متين.** فالحامل المهتز يضيف أخطاء لا يزيلها التطبيق بالكامل.
- **موقعك لا يخرج من هاتفك.** يُستخدم لقراءة سرعة GPS فقط، ولا يُحفظ ولا يُرسل إلى أي جهة.
- **بياناتك على هاتفك.** تبقى السيارات المحفوظة، بما فيها رقم الهيكل وعنوان محوّل البلوتوث، في مساحة التخزين الخاصة بالتطبيق. تحتوي الرحلات المحفوظة على مسار السيارة، فتبقى في مساحة التطبيق الخاصة ولا تغادر الهاتف إلا إذا شاركت إحداها. أما ملف البيانات الخام للحساسات فلا يحتوي على موقع أو رقم هيكل.
- **الإنترنت للتحديثات فقط.** عند كل تشغيل وكل 12 ساعة في الخلفية، يسأل التطبيق خادم التحديث عن أحدث إصدار، ولا يُنزّل التحديث إلا إذا ضغطت تحديث. لا ترسل هذه الطلبات أي بيانات حساسات أو موقع، لكن يمكن للخادم رؤية عنوان IP الخاص بك كما في أي طلب عبر الإنترنت.

### ما يجب معرفته

- جرى تشغيل التطبيق على هاتف بنظام أندرويد 13 وفي رحلات حقيقية قليلة، واجتازت حساباته الاختبارات على رحلات محاكاة. لكن لم تُقارَن دقته بعد بجهاز قياس مرجعي.
- في رحلة قصيرة، قرأ التطبيق السرعة ورقم الهيكل من سيارة حقيقية عبر محوّل OBD-II يعمل بتقنية Bluetooth LE. ولم تُجرَّب المحوّلات الكلاسيكية ولا Android Auto مع جهاز أو سيارة حقيقية.
- في رحلة حقيقية مدتها نحو 80 دقيقة، لاحظ التطبيق تحريك الهاتف في المرات التي فُحصت، وكانت قوة G الأمامية قريبة مما تدل عليه تغيرات سرعة GPS.
- في رحلة قصيرة مع محوّل OBD-II، وصلت سرعة السيارة نحو 6 مرات في الثانية. وكان فرق سرعة GPS عن سرعة السيارة 1.6 كم/س في المتوسط، لكنه بلغ 10.8 كم/س والسيارة واقفة.
- قد يعرض GPS سرعة صغيرة والسيارة واقفة، فتظهر تلك السرعة لبضع ثوانٍ قبل أن يلاحظ التطبيق التوقف. ويستعمل التطبيق اهتزاز السيارة لتصحيح ذلك بعد أن يتعلّم الفرق بوضوح. وفي رحلة مدتها 16 دقيقة، لم يعدّ السيارة واقفة وهي تسير ولا مرة.
- يحاول التطبيق أيضًا سماع سرعة العجلات التي تبثها بعض السيارات من تلقاء نفسها، ولا يستخدمها إلا بعد مطابقتها لسرعة السيارة. ولم تُسمع في السيارة التي جُرّب عليها.
- لا يستطيع Android Auto التمييز بين سيارتين من الطراز والسنة نفسيهما. أما OBD-II فيستطيع ذلك إذا كانت السيارة تشارك رقم هيكلها.
- فُتحت ملفات الرحلات في Google Earth على الويب وعلى هاتف أندرويد. يظهر المسار والعلامات وجدار السرعة على الويب، لكن بطاقات الدفعات القوية لا تُفتح أثناء الجولة هناك. ولم يُجرَّب جدار السرعة ولا الجولة على الهاتف بعد.

<details>
<summary><b>السرعة من سيارتك</b></summary>

<br>

اختر المصدر من **مصدر السرعة** في الإعدادات.

- **OBD-II:** تحتاج إلى محوّل ELM327 يعمل بالبلوتوث. أقرن المحوّل الكلاسيكي من إعدادات أندرويد، أو ابحث عن محوّل Bluetooth LE من داخل التطبيق، ثم اختره.
- **Android Auto:** فعّل وضع المطوّر في Android Auto واسمح بخيار **Unknown sources**. أبقِ التطبيق مفتوحًا على شاشة السيارة وعلى هاتفك معًا. لا تشارك كل السيارات سرعتها.

تأتي قوة G دائمًا من حساسات الهاتف، أيًّا كان مصدر السرعة.

</details>

<details>
<summary><b>التطوير وملفات المشروع</b></summary>

<br>

تحتاج إلى [Android Studio](https://developer.android.com/studio) أو Android SDK مع المنصة 37. شغّل الاختبارات وابنِ التطبيق:

<div dir="ltr">

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

</div>

يُحفظ ملف APK في المجلد `app/build/outputs/apk/debug/`. لا تتضمن النسخ المبنية من المصدر تنبيهات التحديث.

| الملف | وظيفته |
|---|---|
| `core/TelemetryEngine.kt` | حساب السرعة وقوة G والمعايرة |
| `core/SpeedFilter.kt` | مرشح كالمان للسرعة |
| `core/ForwardLearner.kt` | تحديد اتجاه الأمام |
| `core/CarProfiles.kt` | السيارات المحفوظة والتعرف عليها |
| `core/CarTheme.kt` | ألوان المظهر والحفاظ على وضوحها |
| `core/Obd.kt` | بروتوكول OBD-II ورقم الهيكل |
| `core/SourceTiming.kt` | تأخر كل مصدر سرعة وخطأ عداد السرعة |
| `core/StillnessModel.kt` | اهتزاز السيارة واقفة وسائرة |
| `ObdLinks.kt` | الاتصال بالمحوّل بالبلوتوث الكلاسيكي وBluetooth LE |
| `car/SpeedCarAppService.kt` | شاشة Android Auto |
| `TelemetryScreen.kt` | شاشات التطبيق |
| `app/src/test/` | الاختبارات |

</details>

### الرخصة

يمكن **للأفراد** استخدام زخم وتعديله ومشاركته مجانًا للأغراض الشخصية. **ولا يحق للشركات والمؤسسات استخدامه** بدون إذن كتابي، ويشمل ذلك العاملين لحسابهم الخاص والأعمال الفردية عند استخدامه في عملهم. ولا يجوز بيعه. راجع ملف [LICENSE](LICENSE) للاطلاع على الشروط كاملة، والنص الإنجليزي هو المعتمد.

بسبب هذه القيود، يُعد زخم مشروعًا متاح الشيفرة للاطلاع (source-available) وليس مفتوح المصدر بالمعنى المعروف. وتبقى المكونات الخارجية المضمنة فيه خاضعة لرخصها الخاصة، وهي مذكورة في [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

</div>

---

## English

**Zakhm** (زخم, meaning "momentum") is an Android app that shows your car's speed and G-force using your phone's sensors. It can also read speed from the car through an OBD-II adapter or Android Auto. No account and no ads.

<table>
<tr>
<td width="33%"><img src="docs/images/dashboard-en.png" alt="The dashboard: 97 km/h and the G-force circle"><p align="center"><b>Dashboard</b></p></td>
<td width="33%"><img src="docs/images/settings-en.png" alt="Settings with the how-to guide open"><p align="center"><b>Settings and guide</b></p></td>
<td width="33%"><img src="docs/images/drives-en.png" alt="Saved drives with Open on map and share buttons"><p align="center"><b>Saved drives</b></p></td>
</tr>
</table>

<p align="center"><i>These pictures are drawn from the app's own screens with sample readings and drives.</i></p>

### Features

<table>
<tr>
<td width="50%" valign="top"><b>Speed</b><br>In km/h or mph, combining GPS with the accelerometer to reduce lag.</td>
<td width="50%" valign="top"><b>G-force meter</b><br>Separates acceleration, braking and cornering, with peak values for each direction. Show it as the car's movement or as felt by the driver, and zoom the circle down to 0.5 G. Tap the gauge or the circle to fill the screen with it.</td>
</tr>
<tr>
<td valign="top"><b>Speed source</b><br>Phone GPS, an OBD-II adapter or Android Auto, switching to GPS if a car source stops.</td>
<td valign="top"><b>Saved cars</b><br>Each car keeps its own speed source, calibration and peak values.</td>
</tr>
<tr>
<td valign="top"><b>Car recognition</b><br>By VIN over OBD-II, or by model over Android Auto. A known car loads automatically, and the app asks before saving a new one.</td>
<td valign="top"><b>Any mounting angle</b><br>The app works out which way is down and forward, and corrects for hills.</td>
</tr>
<tr>
<td valign="top"><b>Arabic and English</b><br>Right-to-left layout, with a language choice in settings on Android 13 or newer.</td>
<td valign="top"><b>Honest readings</b><br>Speed is marked as an estimate when GPS is lost, then hidden after a few seconds.</td>
</tr>
<tr>
<td valign="top"><b>Drive recording and map</b><br>Each drive you record is saved with its path, speed and G-force. Open it in map apps as KML or GPX: the path is coloured by speed, with markers at hard braking, acceleration and cornering. "Open on map" opens it straight in Google Earth, and the file also carries a timeline, a fly-along tour and a 3D wall that rises with the speed.</td>
<td valign="top"><b>In-app updates</b><br>A notification when a new version is out, a verified one-tap download, and you approve the install.</td>
</tr>
<tr>
<td valign="top"><b>A theme for each car</b><br>Eight ready-made colours or your own. The theme switches with the car, and the numbers stay clear.</td>
<td valign="top"><b>Notices a moved phone</b><br>If the phone moves during a drive, the app notices and calibrates again instead of showing wrong readings.</td>
</tr>
<tr>
<td valign="top"><b>Phone in hand</b><br>When you hold the phone, G-force and peaks pause within a second, and come back once it's in the mount again.</td>
<td valign="top"><b>Learns each car</b><br>Measures how late OBD-II and GPS speeds are, how far off the speedometer reads, and how the car shakes parked and rolling, and keeps it per car. It also learns how accurate your phone's GPS really is, by comparing it with the car's speed.</td>
</tr>
<tr>
<td valign="top"><b>Faster OBD-II</b><br>Quicker requests and a high-priority Bluetooth LE link, so car speed arrives several times a second.</td>
<td valign="top"><b>Disconnect OBD-II</b><br>A settings button frees the adapter for another app, then connects again when you want.</td>
</tr>
<tr>
<td valign="top"><b>Tidy settings</b><br>Seven groups that open one at a time, each showing its current value while closed, so all of settings fits on one screen.</td>
<td valign="top"><b>How-to guide</b><br>Five short steps at the top of settings: mounting, speed source, driving, full screen and saving drives.</td>
</tr>
</table>

### Quick start

**You need:** a phone with Android 8.0 or newer, GPS and an accelerometer. A gyroscope is recommended.

1. Download the latest `Zakhm-*.apk` from [Releases](https://github.com/alequab/Zakhm/releases/latest).
2. Open the file on your phone and allow installs from that source if Android asks.
3. Allow precise location when the app asks.

**First use:**

1. Fix the phone firmly in a mount while the car is parked, and keep it still for a few seconds.
2. Wait outside until the GPS message clears.
3. Drive normally. The calibration message clears once you've sped up and slowed down a little, or turned both ways.

If you move the phone in its mount, the app will usually calibrate again by itself. You can also tap the settings icon, then **Tools**, then **Calibrate**.

To change the colours, open settings, then **Car**, then **Theme**.

**Saving a drive and opening it on a map:**

1. Open settings, then **Drives**, and tap **Record drive**. Keep the app open on screen while you drive.
2. Tap **Stop recording** when you arrive. The drive appears under **Saved drives**.
3. Tap **Open on map** to see it in Google Earth, which must be installed on the phone. Google Maps can't open these files.
4. To see the speed wall, turn on **Speed wall (3D)** in the drive's list in Google Earth, then tilt the view. To fly along the drive, play **Fly along the drive**.

Google Earth keeps every drive it opens, so opening the same drive a second time asks whether to open Google Earth and find it in its list, or send a new copy. To send the file to a computer or another map app, use **Share KML** or **Share GPX**.

### How it works

```mermaid
flowchart LR
    A["GPS, OBD-II or Android Auto"] -- "speed" --> C["Kalman filter"]
    B["Accelerometer and gyroscope"] -- "motion" --> C
    C --> D["Speed and G-force"]
```

### Safety and privacy

- **Don't use the app while driving.** Set it up before you leave, and let a passenger handle the phone while the car is moving.
- **Use a firm mount.** A shaky mount adds errors the app can't fully remove.
- **Your location stays on your phone.** It's used only to read GPS speed, and it's never saved or sent anywhere.
- **Your data stays on your phone.** Saved cars, including any VIN or Bluetooth adapter address, stay in the app's private storage. Saved drives hold the car's path, so they stay in the app's private storage and leave the phone only when you share one. The raw sensor data file contains no position or VIN.
- **Internet for updates only.** Each time it starts and every 12 hours in the background, the app asks an update server for the newest version, and only downloads it if you tap Update. These requests send no sensor or location data, though the server can see your IP address, as with any internet request.

### Good to know

- The app has run on an Android 13 phone and on a few real drives, and its calculations pass tests on simulated drives. Its accuracy hasn't been compared with a reference instrument yet.
- On a short drive, the app read speed and the VIN from a real car through a Bluetooth LE OBD-II adapter. Classic adapters and Android Auto haven't been tried with a real device or car.
- On a real drive of about 80 minutes, the app noticed the moved phone in the cases checked, and forward G closely followed what GPS speed changes showed.
- On a short drive with an OBD-II adapter, car speed arrived about 6 times a second. GPS speed was 1.6 km/h off the car's on average, but up to 10.8 km/h while parked.
- GPS can report a small speed while the car is standing still, and that speed may show for a few seconds before the app notices the stop. The app uses the car's vibration to correct this once it has clearly learned the difference. On a 16-minute drive, it never took the moving car for parked.
- The app also listens for the wheel speeds some cars broadcast by themselves, and only uses them after they match the car's speed. They weren't heard on the car it was tried on.
- Android Auto can't tell two cars of the same model and year apart. OBD-II can, if the car shares its VIN.
- Drive files were opened in Google Earth on the web and on an Android phone. On the web the path, markers and speed wall show, but the hard-push cards don't open during the tour. The speed wall and tour haven't been tried on the phone yet.

<details>
<summary><b>Speed from your car</b></summary>

<br>

Choose the source under **Speed source** in settings.

- **OBD-II:** you need a Bluetooth ELM327 adapter. Pair a classic adapter in Android's settings, or scan for a Bluetooth LE adapter in the app, then choose it.
- **Android Auto:** turn on Android Auto's developer mode and allow **Unknown sources**. Keep the app open on both the car screen and your phone. Not every car shares its speed.

G-force always comes from the phone's sensors, whichever speed source you use.

</details>

<details>
<summary><b>Development and project files</b></summary>

<br>

You need [Android Studio](https://developer.android.com/studio) or the Android SDK with platform 37. Run the tests and build the app:

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Builds from source don't include update alerts.

| File | Purpose |
|---|---|
| `core/TelemetryEngine.kt` | Speed, G-force and calibration calculations |
| `core/SpeedFilter.kt` | Kalman filter for speed |
| `core/ForwardLearner.kt` | Works out which way is forward |
| `core/CarProfiles.kt` | Saved cars and recognizing them |
| `core/CarTheme.kt` | Theme colours and keeping them readable |
| `core/Obd.kt` | OBD-II protocol and VIN reading |
| `core/SourceTiming.kt` | Each speed source's delay and the speedometer's error |
| `core/StillnessModel.kt` | The car's vibration when parked and rolling |
| `ObdLinks.kt` | Classic Bluetooth and Bluetooth LE adapter connections |
| `car/SpeedCarAppService.kt` | Android Auto screen |
| `TelemetryScreen.kt` | The app's screens |
| `app/src/test/` | Tests |

</details>

### License

Zakhm is free for **individuals** to use, modify and share for personal purposes. **Companies and organizations may not use it** without written permission, including freelancers and one-person businesses using it for their work. It may not be sold. See [LICENSE](LICENSE) for the full terms.

Because of these limits, Zakhm is source-available rather than open source. The third-party components it includes keep their own licenses, listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

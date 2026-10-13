<p align="center">
  <img src="docs/icon.png" width="120" alt="NoteKonverter ikonu">
</p>

<h1 align="center">NoteKonverter</h1>

<p align="center">
  Android'de el yazısı notları <b>Samsung Notes</b>, <b>Boox Notes</b> ve <b>Saber</b> arasında dönüştürür.<br>
  <a href="README.md">English</a> · <b>Türkçe</b>
</p>

---

Her not uygulaması el yazısını kendi kapalı dosya biçiminde saklar; bu yüzden notları cihazlar arasında taşımak zordur. NoteKonverter el yazısını bir biçimden okuyup diğerine yazar. Her şey cihazda çalışır; uygulamanın internet izni yoktur.

## Desteklenen biçimler

| Uygulama | Dosya | İçe aktarma | Dışa aktarma |
|---|---|:---:|:---:|
| Samsung Notes | `.sdocx` | ✅ | ✅ |
| Onyx Boox Notes | `.note` | ✅ | ✅ |
| [Saber](https://github.com/saber-notes/saber) | `.sba` (içe aktarmada çıplak `.sbn2` de) | ✅ | ✅ |

Bir dosya eklendiğinde uygulama biçimini algılar ve **diğer iki biçimi** hedef olarak sunar. Örneğin bir `.sdocx` dosyası `.note`, `.sba` ya da ikisine birden dönüştürülebilir.

### Dönüştürülenler

- Kalem çizgileri: noktaları, basınç, renk ve kalınlıkla birlikte
- Vurgulayıcı çizgileri: şeffaflık her uygulamaya göre ayarlanır
- Tüm sayfalar: sayfa boyutları her uygulamanın sayfa boyutuna ölçeklenir; uzun kaydırmalı Samsung sayfaları Boox sayfalarına bölünür
- Not başlığı (biçim başlık saklıyorsa)

### Dönüştürülmeyenler

- Klavyeyle yazılmış metin, resimler, PDF arka planları ve sayfa şablonları
- Silgi geçmişi, uygulamaya özel katmanlar ve etiketler
- Saber'in eski `.sbn` biçimindeki notlar. Bunları Saber'de açıp yeniden dışa aktarın.

## Özellikler

- **Her dosya için hedefi seçin** ya da **Tümünü dönüştür** ile eklenen tüm dosyaları tek seferde aynı biçime çevirin.
- **Uygulamaya paylaşın veya "Birlikte aç"ı kullanın**: Samsung Notes, Boox, Saber ya da dosya yöneticisinden gönderilen dosyalar doğrudan listeye eklenir.
- **E-ink modu**: tamamen siyah-beyaz; animasyon ve dokunma dalgası efekti yok; yazılar daha kalın, dokunma alanları daha büyük. Onyx Boox, Bigme, PocketBook, Meebook ve diğer e-kağıt cihazlarda otomatik açılır.
- **Telefon ve büyük tablet**: telefonda tek sütun; 840 dp ve daha geniş tabletlerde yan panel ve satır başına birkaç kartlık ızgara.
- **Türkçe ve İngilizce**: uygulama cihaz dilini izler. Türkçe cihazlarda Türkçe, diğer tüm cihazlarda İngilizce açılır. Dil uygulama içinden ya da Android 13+ uygulama dili ayarlarından da seçilebilir.
- Dönüştürülen dosyalar seçtiğiniz klasöre kaydedilir ve her biri hemen paylaşılabilir.

## Kurulum

En son APK'yı [**Releases**](https://github.com/volkanozdamar/NoteKonverter/releases/latest) sayfasından indirip kurun. Android, tarayıcınızdan veya dosya yöneticinizden yükleme yapılmasına izin vermenizi isteyecektir.

Uygulamayı kaynak koddan derlemek için:

```bash
git clone https://github.com/volkanozdamar/NoteKonverter.git
cd NoteKonverter
./gradlew :app:assembleDebug
```

APK dosyası `app/build/outputs/apk/debug/` klasörüne yazılır. `main` dalına yapılan her push da bir APK derler; bunu **Actions** sekmesinden indirebilirsiniz.

Gereksinimler: Android 7.0 (API 24) veya üstü. Derlemek için Android Studio ya da Android SDK kullanın; gereken JDK'yı Gradle otomatik indirir.

## Kullanım

1. **Dosya ekle**'ye dokunun ve bir ya da birkaç `.sdocx`, `.note` veya `.sba` dosyası seçin.
2. Her dosya, algılanan biçimi, sayfa sayısı ve çizgi sayısıyla birlikte bir kart olarak görünür.
3. **Dönüştür:** altında istediğiniz biçime dokunun.
4. İlk seferde bir çıktı klasörü seçin. Dönüştürülen dosya oraya kaydedilir; karttan paylaşabilirsiniz.
5. Dosyayı hedef uygulamada içe aktarın:
   - **Boox:** Notlar → İçe aktar
   - **Saber:** + → İçe aktar
   - **Samsung Notes:** dosyayı bir dosya yöneticisinden açın

> ⚠️ **Deneyseldir.** Bu biçimlerin hiçbirinin herkese açık bir belgesi yoktur. Dönüştürücüler topluluğun tersine mühendislik çalışmalarından yola çıkarak yazıldı ve bağımsız ayrıştırıcılarla kontrol edildi; ancak uygulama güncellemeleri biçimleri değiştirebilir. Orijinal dosyalarınızı her zaman saklayın.

## Nasıl çalışır

```
app/src/main/java/com/example/notekonverter/
├── converter/            Saf Kotlin, Android bağımlılığı yok, JVM'de birim testli
│   ├── Ink.kt            Biçimden bağımsız model: belge → sayfalar → çizgiler → noktalar
│   ├── NoteConverter.kt  Biçim algılama, hedefe göre sayfa yerleşimi, dönüştürme
│   ├── samsung/          .sdocx okuyucu/yazıcı (ZIP + ikili sayfalar, SHA-256 bütünlük zinciri)
│   ├── boox/             .note okuyucu/yazıcı (ZIP + protobuf meta veri + ikili nokta dosyaları)
│   ├── saber/            .sba okuyucu/yazıcı (ZIP + BSON not, biçim sürümü 19)
│   └── io/               Küçük ZIP, protobuf, BSON, JSON ve bayt yardımcıları
├── ConverterViewModel.kt Dosya yükleme, dönüştürme işleri, ayarlar
├── MainActivity.kt       Compose arayüzü (uyarlanabilir yerleşim, e-ink modu, dil seçici)
└── AppLanguage.kt        Uygulama içi dil seçimi
```

Her okuyucu dosyayı ortak `InkDocument` modeline çevirir. `NoteConverter` sayfaları hedefin sayfa ölçülerine uyarlar, ardından hedefin yazıcısı dosyayı üretir:

| Biçim | Sayfa genişliği |
|---|---|
| Samsung | 1440 |
| Boox | 1860 × 2480 |
| Saber | 1000 |

Dönüştürücü, Kotlin standart kütüphanesi dışında hiçbir kütüphaneye ihtiyaç duymaz.

Testleri çalıştırmak için:

```bash
./gradlew :app:testDebugUnitTest
```

Testler notları kod içinde üretir ve her okuyucu ile yazıcıdan geçirir. Bu yüzden depoda üçüncü taraf ya da kişisel örnek dosya yoktur.

## Katkıda bulunma

Hata bildirimleri ve pull request'ler memnuniyetle karşılanır. En çok, dönüştürülemeyen örnek dosyalar işe yarar; ancak lütfen yalnızca paylaşma hakkınız olan notları paylaşın. Hedef uygulama içe aktarırken hata veriyorsa hata mesajını aynen ekleyin.

## Teşekkürler

Dosya biçimleri aşağıdaki projelerin yardımıyla anlaşıldı. Bu projelerin belgeleri okundu; kodları kopyalanmadı.

- [twangodev/sdocx](https://github.com/twangodev/sdocx): Samsung `.sdocx` biçim notları
- [bxff/samsung-notes-format](https://github.com/bxff/samsung-notes-format): Samsung örnek dosyaları
- [nrontsis/boox-note-optimizer](https://github.com/nrontsis/boox-note-optimizer) ve [bomberstudios/boox-notes-sync](https://github.com/bomberstudios/boox-notes-sync): Boox `.note` yapısı
- [saber-notes/saber](https://github.com/saber-notes/saber): Saber `.sba` / `.sbn2` yapısı

## Sorumluluk reddi

NoteKonverter bağımsız bir projedir. Samsung, Onyx International (Boox) ya da Saber projesiyle bağlantılı değildir ve onlar tarafından onaylanmamıştır. Ürün adları ilgili sahiplerinin ticari markalarıdır.

## Lisans

[MIT](LICENSE) © 2026 Volkan Özdamar

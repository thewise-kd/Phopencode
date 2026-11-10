<p align="center">
  <img src="docs/assets/logo.svg" alt="Phopencode logosu" width="128" height="128">
</p>

<h1 align="center">Phopencode</h1>

<p align="center">
  <strong>Kendi sunucundaki opencode'u Android telefonundan, SSH üzerinden kullan.</strong>
</p>

<p align="center">
  <a href="https://github.com/thewise-kd/Phopencode/actions/workflows/ci.yml"><img src="https://github.com/thewise-kd/Phopencode/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="Lisans: MIT"></a>
</p>

<p align="center">
  <a href="README.md">English</a> | <strong>Türkçe</strong>
</p>

> [!NOTE]
> **İndirme:** imzalı APK'lar ve SHA-256 özetleri
> [GitHub Releases](https://github.com/thewise-kd/Phopencode/releases) sayfasında yayımlanır; en
> son sürüm orada listelenir. Her sürümdeki değişiklikler [CHANGELOG.md](CHANGELOG.md)
> dosyasındadır. İstersen [uygulamayı kaynak koddan da derleyebilirsin](#kaynak-koddan-derleme).

<!--
Ekran görüntüleri: henüz yok. Hazır olduğunda buraya ekle, örneğin:
<p align="center"><img src="docs/assets/screenshot-chat.png" alt="Sohbet ekranı" width="260"></p>
-->

## Nedir

Phopencode, kendi yönettiğin bir sunucuda çalışan [opencode](https://opencode.ai) ile çalışmak
için bir Android uygulamasıdır. opencode, kodun ve kimlik bilgilerin sunucuda kalır; telefon
sunucuya SSH ile bağlanır ve opencode oturumlarını, sohbeti, proje dosyalarını ve git'i dokunmatik
ekrana uygun, yerel bir uygulamada sunar.

Neden: bir ajan uzun süre çalışabilir, soru sorabilir ve izin bekleyebilir. Phopencode,
bilgisayarından uzaktayken ajanı izlemeni, ona yanıt vermeni ve yaptığı değişiklikleri incelemeni
sağlar; bunun için sunucunun hiçbir portunu internete açman gerekmez.

## Belgeler

- [Kurulum ve ayarlar](docs/INSTALL.tr.md): telefonun ve sunucunun neye ihtiyacı olduğu, APK'yı
  kurma ve doğrulama, ilk bağlantı ve ntfy ile bildirimler.
- [Güvenlik modeli](docs/SECURITY-MODEL.tr.md): neyin nerede saklandığı, ağ üzerinden neyin
  gittiği, sunucu anahtarına güven ve bir saldırganın neler yapabileceği.
- [Güvenlik politikası](SECURITY.md): bir güvenlik açığının nasıl bildirileceği.

## Özellikler

Bu sürümde olanlar (tüm değişiklikler [CHANGELOG.md](CHANGELOG.md) dosyasında):

**Bağlantı ve sunucular**

- Sunucu profilleri: adres, port, kullanıcı ve bir SSH anahtarı ya da parola; isteğe bağlı sabit
  bir opencode portu.
- SSH anahtarları: ed25519 anahtar üretme, OpenSSH anahtarlarını (parola korumalı olanlar dahil)
  içe aktarma, parmak izini görme ve açık anahtarı kopyalama.
- Sunucu anahtarı doğrulama: ilk bağlantıda SHA-256 parmak izini sen onaylarsın; anahtar sonradan
  değişirse bağlantı bir uyarıyla durur.
- Çalışan bir `opencode serve` sürecini bulur (`PATH` dışındaki `~/.opencode/bin` içinde de) ve
  yalnızca hiçbiri çalışmıyorsa `127.0.0.1`'e bağlı yeni bir tane başlatır.
- Kopan bağlantıyı yeniden kurar; SFTP üzerinden çalışan, son kullanılan klasörleri hatırlayan bir
  klasör seçici.

**Oturumlar ve sohbet**

- Arama, durum, token kullanımı ve maliyet gösteren oturum listesi; oluşturma (klasör, ajan, model,
  başlık), yeniden adlandırma, arşivleme ve silme.
- Markdown ve renklendirilmiş kod bloklarıyla canlı akan sohbet.
- Düşünme (reasoning), araç çağrısı ve token/bağlam kullanımı kartları; çalışıyor, yeniden
  deneniyor ve bağlantı koptu durumları için bir durum şeridi.
- Ajan çalışırken mesaj gönderme (yönlendirme ya da kuyruğa ekleme) ve ajanı durdurma.
- Ajanın sorularını (tek seçim, çoklu seçim, serbest metin) ve izin isteklerini (bir kez, her
  zaman, reddet) yanıtlama.
- Alt ajanları canlı bir ağaç olarak izleme ve oturumlarını açma.
- Komut paleti (komutlar ve beceriler için `/`, ajanlar ve dosyalar için `@`), ajan ve model
  değiştirme, tur bazında fark görüntüleme ve geri alma, sıkıştırma ve çatallama.

**Dosyalar**

- Projeye göz atma; dosyaları sözdizimi renklendirme, işlenmiş Markdown ve resim önizlemesiyle
  görüntüleme.
- Düzenleyip kaydetme; dosya bu sırada sunucuda değiştiyse uyarı.
- Yeniden adlandırma, silme, klasör oluşturma; ada ve içeriğe göre arama (`rg`, yoksa `grep`).
- Sistem dosya seçicisiyle yükleme ve indirme; ilerleme bildirimde görünür.

**Git**

- Hazırlanmış, hazırlanmamış ve izlenmeyen dosyalar, ileride/geride bilgisi ve upstream durumuyla
  git durumu.
- Dosya hazırlama, hazırlıktan çıkarma, değişiklikleri (onaydan sonra) atma ve commit.
- Farklar, commit ayrıntılarıyla geçmiş ve dallar (oluşturma, geçiş, silme).
- Fetch, pull (yalnızca fast-forward) ve push. Uygulama hiçbir zaman zorla push yapmaz.

**Katalog**

- Arama ve Markdown metniyle beceriler; komutlar, ajanlar (izinleriyle) ve modeller (sınırları ve
  fiyatlarıyla); MCP sunucusu ve eklenti durumu. Yalnızca görüntüleme.

**Bildirimler**

- Ajan bir şey sorduğunda, izin istediğinde, işi bittiğinde ya da hata verdiğinde
  [ntfy](https://ntfy.sh) üzerinden anlık bildirim; bildirimleri sunucuda çalışan, bağımlılığı
  olmayan küçük bir Python servisi gönderir. Bildirim metni sabittir; kod, dosya yolu ya da yapay
  zekâ çıktısı içermez.
- Servisi uygulamadan kurma, deneme ve kaldırma; varsayılan olarak `ntfy.sh` ya da kendi ntfy
  sunucun; uygulamanın ntfy uygulamasına ilettiği, rastgele ve gizli bir konu.
- Herhangi bir oturumda yanıt bekleyen tüm soruları ve izin isteklerini listeleyen bekleyenler
  ekranı; bir bildirime dokunmak bu ekranı açar.

**Genel**

- Sistem dilini izleyen ya da ayarlardan seçilen Türkçe ve İngilizce arayüz; açık, koyu ya da
  sistem teması.
- Dosya boyutu sınırı ve tüm uygulama verisini silme ayarları; açık kaynak lisans bildirimleri.

### Dahil olmayanlar

Bu sürümde olmayanlar: terminal, git stash/birleştirme çakışması çözme/rebase, MCP
sunucularını ya da sağlayıcı kimlik bilgilerini yönetme
([tam liste](docs/PHASES.md#out-of-scope-first-release)).

## Nasıl çalışır

```text
 Android telefon                                Senin sunucun
+---------------+        SSH (port 22)        +---------------------------------------+
|  Phopencode   | ==========================> | sshd                                  |
|               |   exec:  opencode'u bul ya  |  |- opencode serve, 127.0.0.1:<port>  |
|               |          da başlat; git, rg |  |- projende git, rg/grep             |
|               |   SFTP:  dosyalar           |  '- dosyaların                        |
|               |   tünel: opencode HTTP API  |                                       |
+---------------+   ve olay akışı             +---------------------------------------+
```

- Her şey tek bir SSH bağlantısından geçer. opencode HTTP API'si ve canlı olay akışı, sunucudaki
  `127.0.0.1`'e giden bir SSH tünelinden taşınır.
- `opencode serve` yalnızca loopback adresini dinler, yani internete yeni hiçbir şey açılmaz.
  Telefonda da tünel yerel bir port açmaz; diğer uygulamalar ona erişemez.
- Uygulama `opencode serve` başlattığında onu rastgele bir parolayla korur; parola komut satırına
  hiç yazılmaz, stdin üzerinden verilir.
- Dosyalar SFTP ile, açtığın klasörle sınırlı olarak aktarılır. Git ve içerik araması SSH üzerinden
  sıradan komutlar olarak çalışır; her argüman kabuk için tırnaklanır.
- Bildirimleri kurarsan, onları sunucuda opencode'u izleyen bir servis gönderir; servis sabit
  metni HTTPS üzerinden ntfy'a iletir, telefondaki ntfy uygulaması da gösterir.

### Sunucu gereksinimleri

- Bir anahtar ya da parolayla SSH erişimi.
- O kullanıcı için kurulu [opencode](https://opencode.ai) 2.2 ya da daha yenisi.
- Git ekranları için `git`; `rg` (ripgrep) isteğe bağlıdır, yoksa `grep` kullanılır.
- Yalnızca bildirimler için: `python3` 3.9 ya da daha yenisi ve servisi çalışır tutmak için
  lingering açık bir `systemd --user` ya da `crontab`.

Ayrıntılar ve uygulamanın sunucuda ne yaptığı [docs/INSTALL.tr.md](docs/INSTALL.tr.md) dosyasındadır.

## Gereksinimler

- Android 8.0 (API 26) ya da daha yeni bir Android telefon.
- [Sunucu gereksinimleri](#sunucu-gereksinimleri) bölümünde anlatılan bir sunucu.
- Bildirimler için telefonda [ntfy](https://ntfy.sh) uygulaması.

## Kaynak koddan derleme

Sürüm APK'sını kurmak yerine debug APK'yı kendin de derleyebilirsin.

Gerekenler:

- JDK 21 ([docs/dev-setup/jdk.md](docs/dev-setup/jdk.md)).
- Platform 37 kurulu Android SDK (örneğin Android Studio ile).
- SDK klasörünü gösteren `ANDROID_HOME` (ya da `ANDROID_SDK_ROOT`) ya da `local.properties`
  içinde `sdk.dir`. SDK yoksa Gradle yalnızca JVM modüllerini dahil eder.

```sh
git clone https://github.com/thewise-kd/Phopencode.git
cd Phopencode
export ANDROID_HOME="$HOME/Android/Sdk"   # SDK klasörün

./gradlew :app:assembleDebug              # APK: app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/*.apk
```

Testler ve kontroller:

```sh
./gradlew jvmCheck                        # tüm JVM modül testleri; Android SDK gerekmez
./gradlew :core:api-http:test             # tek bir modül
./gradlew testDebugUnitTest lint          # Android birim testleri ve lint; SDK gerekir
python3 scripts/check_boundaries.py       # modül sınırları ve gizli bilgi kalıpları
python3 scripts/check_strings.py          # Türkçe/İngilizce metin eşliği
```

`gradle/verify-wrapper.sh --online`, Gradle wrapper'ını Gradle'ın yayımladığı özetlerle
karşılaştırır.

## Proje yapısı

Mantık, Android olmadan test edilen saf Kotlin/JVM modüllerindedir; Compose ekranları ince Android
modüllerindedir.

| Yol | İçerik |
|---|---|
| `app/` | Giriş noktası, gezinme, bağımlılık enjeksiyonu (Koin) |
| `core/model`, `core/api` | Alan tipleri; opencode API arayüzleri ve olayları |
| `core/api-http` | opencode HTTP istemcisi ve olay akışı (OkHttp) |
| `core/session` | Olay birleştirici ve yeniden bağlanınca eşitleme |
| `core/ssh` | SSH, uzak komutlar, SFTP, tünel, kabuk tırnaklama (sshj) |
| `core/remote` | Sunucu bulma ve başlatma, bağlantı yöneticisi |
| `core/files`, `core/git` | Dosya servisi; git komutları ve ayrıştırıcıları |
| `core/storage-api`, `core/storage` | Depolama arayüzleri; şifreli depolama (Keystore, Tink, DataStore) |
| `core/navigation`, `core/ui`, `core/markdown`, `core/codeview` | Rotalar, tema ve ortak bileşenler, Markdown, kod görüntüleyici |
| `feature/<ad>/logic`, `feature/<ad>/ui` | Her özelliğin durumu ve sunucuları (JVM) ile Compose ekranları (Android) |
| `helper/` | Bildirim servisi (Python) ile kurma ve kaldırma betikleri |
| `build-logic/` | Gradle convention eklentileri |

Özellikler: `connect`, `sessions`, `chat`, `files`, `git`, `catalog`, `notify` ve `settings`.

## Güvenlik

- **Sunucu anahtarları doğrulanır.** Bir sunucunun anahtarı ilk bağlantıda onaylanır; değişen bir
  anahtar bağlantıyı durdurur.
- **Gizli bilgiler telefonda şifrelenir.** Parolalar ve özel anahtarlar, Android Keystore'daki bir
  anahtarla Tink kullanılarak saklanır; profillerde parola tutulmaz. Uygulama verisi bulut
  yedeğine ve cihaz aktarımına dahil edilmez.
- **Hiçbir şey dışarı açılmaz.** opencode yalnızca `127.0.0.1`'i dinler ve SSH tüneliyle erişilir;
  telefon yerel port açmaz.
- **Uygulama sunucunu ele geçirmez.** Hiçbir zaman `sudo` kullanmaz; zaten çalışan bir
  `opencode serve` sürecini asla durdurmaz, öldürmez ya da yerine başkasını koymaz ve yalnızca
  hiçbiri çalışmıyorsa yenisini başlatır. Durdurduğu tek süreçler kendi süreçleridir: iptal ettiğin
  bir komut ya da az önce başlatıp ayağa kalkamayan bir sunucu.
- **Git varsayılan olarak güvenlidir.** Zorla push, `git reset --hard` ya da `git clean` yoktur;
  pull yalnızca fast-forward yapar; değişiklikleri atmak onay ister.
- **Bildirimler gizli kalır.** Metinleri sabittir, ntfy konusu rastgeledir ve şifreli saklanır;
  servisin sunucudaki ayar dosyasını yalnızca senin kullanıcın okuyabilir.
- **Analiz ya da izleme yok.** Loglarda parola, anahtar ya da dosya içeriği bulunmaz; sürüm
  derlemesi Android log çağrılarını tümüyle kaldırır.

Tüm ayrıntılar [docs/SECURITY-MODEL.tr.md](docs/SECURITY-MODEL.tr.md) dosyasındadır. Bir güvenlik açığı bildirmek için [SECURITY.md](SECURITY.md) dosyasına bak. Lütfen herkese açık
bir issue açma.

## Katkıda bulunma

Issue'lar ve pull request'ler memnuniyetle karşılanır. Pull request açmadan önce:

- `./gradlew jvmCheck` ile iki `scripts/check_*.py` kontrolünü çalıştır; Android testlerini CI
  çalıştırır.
- Kullanıcıya görünen her metni hem İngilizce (`values/`) hem Türkçe (`values-tr/`) ekle.
- JVM modüllerine Android bağımlılığı ekleme.
- Gerçek sunucu adreslerini, anahtarları, parolaları ya da başka gizli bilgileri kodda, testlerde
  ya da issue'larda asla paylaşma; `example.com` gibi yer tutucular kullan.

Görev planı ve kurallar [docs/PHASES.md](docs/PHASES.md) dosyasındadır.

## Lisans

[MIT](LICENSE) © 2026 thewise-kd

## Sorumluluk reddi

Phopencode bağımsız bir projedir. opencode projesi ya da SST ile bağlantılı değildir, onlar
tarafından onaylanmamış ya da desteklenmemiştir. "opencode" adı yalnızca bu uygulamanın bağlandığı
yazılımı belirtmek için kullanılır.

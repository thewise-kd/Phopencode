# Phopencode kurulumu ve ayarları

<p><a href="INSTALL.md">English</a> | <strong>Türkçe</strong></p>

Bu kılavuz seni boş bir telefondan çalışan bir bağlantıya götürür: telefonun ve sunucunun neye
ihtiyacı olduğu, uygulamanın nasıl kurulup doğrulanacağı, ilk bağlantının nasıl işlediği ve ntfy
ile anlık bildirimlerin nasıl ayarlanacağı. Uygulamanın verilerini nasıl koruduğu
[SECURITY-MODEL.tr.md](SECURITY-MODEL.tr.md) dosyasında, projenin genel tanıtımı
[README](../README.tr.md) dosyasında anlatılır.

Örneklerde `example.com` senin sunucunu temsil eder. Onu kendi adresinle değiştir; gerçek sunucu
adresini, anahtarlarını, parolalarını ya da ntfy konunu asla bir issue'da paylaşma.

## Neler gerekir

### Telefonda

- Android 8.0 (API 26) ya da daha yenisi.
- Bildirimler için: ücretsiz [ntfy](https://ntfy.sh) uygulaması.

### Sunucuda

- SSH ile, parola ya da SSH anahtarıyla erişebildiğin Unix benzeri bir sunucu (örneğin Linux).
  Her port olur; varsayılan 22'dir.
- Giriş yaptığın kullanıcı için kurulu [opencode](https://opencode.ai) 2.2 ya da daha yenisi.
  Uygulama onu SSH kabuğunun ya da giriş kabuğunun `PATH`'inde ve `~/.opencode/bin`,
  `~/.local/bin`, `~/bin`, `/usr/local/bin`, `/usr/bin` gibi alışılmış kurulum klasörlerinde arar.
  Daha eski bir opencode, güncellemeni isteyen bir mesajla reddedilir.
- Terminalde kullanırken yaptığın gibi en az bir model sağlayıcısıyla ayarlanmış opencode.
- Git ekranları için `git`.
- İçerik araması için `rg` (ripgrep) isteğe bağlıdır; yoksa `grep` kullanılır.
- Sıradan bir Linux sisteminde bulunan standart araçlar: `sh`, `ps` ya da `/proc` ve çalışan
  opencode'u bulmak için `ss`, `/proc/net/tcp` ya da `lsof`.

### Bildirimler için (isteğe bağlı)

- Sunucuda `python3` 3.9 ya da daha yenisi.
- `pgrep` ve `flock` (procps ve util-linux paketlerinden; çoğu Linux sisteminde vardır).
- Servisi çalışır tutmanın bir yolu: kullanıcın için lingering açık bir `systemd --user`
  yöneticisi (`loginctl enable-linger`; bunu sen çalıştırırsın, uygulama hiçbir zaman açmaz) ya da
  `crontab`.
- Sunucu ntfy sunucusuna HTTPS ile ulaşabilmelidir; varsayılan sunucu `ntfy.sh`'tır.

root yetkisi, `sudo` ya da SSH dışında açık bir port gerekmez.

## 1. Uygulamayı kur

### APK'yı indir

> [!NOTE]
> Henüz yayımlanmış bir sürüm yok. İlki çıkana kadar uygulamayı
> [README](../README.tr.md#kaynak-koddan-derleme) dosyasında anlatıldığı gibi kaynak koddan
> derle. Aşağıdaki adımlar sürümler yayımlandığında geçerlidir.

Sürümler deponun [GitHub Releases sayfasında](https://github.com/thewise-kd/Phopencode/releases)
yayımlanır. Her sürümde iki dosya bulunur:

- `Phopencode-v<sürüm>.apk`, imzalı uygulama;
- `Phopencode-v<sürüm>.apk.sha256`, o APK'nın SHA-256 özeti.

APK'yı yalnızca oradan indir. Uygulama Google Play'de ya da başka bir mağazada yoktur.

### SHA-256'yı doğrula

Özet, indirmenin eksiksiz olduğunu ve sürüm iş akışının derlediği dosyayla aynı olduğunu
kanıtlar. Bir bilgisayarda iki dosyayı aynı klasöre koy ve şunu çalıştır:

```sh
sha256sum -c Phopencode-v0.1.0.apk.sha256      # "Phopencode-v0.1.0.apk: OK" yazar
# macOS: shasum -a 256 -c Phopencode-v0.1.0.apk.sha256
```

Telefonda, bir dosyanın SHA-256 değerini gösteren bir dosya yöneticisi ya da özet uygulaması da
aynı işi görür: değer `.sha256` dosyasındakiyle aynı olmalıdır.

Android SDK build tools kuruluysa `apksigner verify --print-certs`, APK'yı imzalayan sertifikayı
gösterir. Android de her güncellemenin kurduğun uygulamayla aynı anahtarla imzalandığından emin
olur; başka bir anahtarla imzalanmış güncellemeyi reddeder.

### Kur (sideload)

1. İndirilen APK'yı telefonda, tarayıcının indirilenlerinden ya da bir dosya yöneticisinden aç.
2. Android, o uygulamanın bilinmeyen uygulamaları yüklemesine izin vermeni ister. Bu kurulum için
   izin ver; sonra sistem ayarlarından (Uygulamalar, Özel uygulama erişimi, Bilinmeyen
   uygulamaları yükle) yeniden kapatabilirsin.
3. Kurulumu onayla. Google Play Protect, mağaza dışından gelen bir uygulama için uyarı verebilir;
   sideload edilen bir uygulama için bu beklenen bir durumdur.

Güncellemek için yeni APK'yı aynı şekilde eskisinin üzerine kur. Sunucuların, anahtarların ve
ayarların korunur. Uygulamayı kaldırmak hepsini siler.

## 2. Sunucu ekle

1. Phopencode'u aç ve sunucu ekleme düğmesine dokun.
2. Şunları doldur:
   - **Ad**: herhangi bir etiket, örneğin `Ev sunucusu`.
   - **Sunucu adresi**: sunucunun adresi, örneğin `example.com`.
   - **SSH bağlantı noktası**: sunucun başka bir port kullanmıyorsa 22.
   - **Kullanıcı adı**: opencode'un kurulu olduğu hesap.
   - **Giriş**: parolayla ya da SSH anahtarıyla.
   - **opencode serve parolası** (isteğe bağlı): yalnızca kendin başlattığın bir `opencode serve`
     zaten parolayla çalışıyorsa. Aksi halde boş bırak.
   - **opencode portu** (isteğe bağlı): yalnızca uygulamanın tek bir sabit porttaki
     `opencode serve`'ü kullanmasını istiyorsan. Çalışan sunucuyu otomatik bulması için boş bırak.
3. Kaydet.

### SSH anahtarları

**Anahtarları yönet** altında şunları yapabilirsin:

- Telefonda, isteğe bağlı bir parolayla yeni bir ed25519 anahtarı **Oluştur**. Açık anahtarını
  kopyala ve sunucuda `~/.ssh/authorized_keys` dosyasına ekle, örneğin:

  ```sh
  # sunucuda, uygulamadan kopyaladığın açık anahtarla
  echo 'ssh-ed25519 AAAA... phone' >> ~/.ssh/authorized_keys
  ```

- Var olan bir özel anahtar dosyasını **İçe aktar**: OpenSSH biçimi (ed25519, RSA, ECDSA),
  parola korumalı olanlar dahil, ve eski PEM biçimleri.

Her anahtar türünü ve SHA-256 parmak izini gösterir. Bir anahtarı silmek onu yalnızca telefondan
kaldırır; ona güvenen sunucular değişmez.

## 3. Bağlan: sunucu anahtarı ve opencode

Bağlanmak için sunucuya dokun. Uygulama her adımı gösterir.

### Sunucu anahtarını onayla

İlk bağlantıda uygulama sunucunun anahtar türünü ve SHA-256 parmak izini gösterir ve onaylamanı
ister. Bunu sunucunun kendisinde elde ettiğin parmak iziyle karşılaştır, örneğin:

```sh
# sunucuda
for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -l -f "$f"; done
```

Yalnızca eşleşiyorlarsa onayla. Onaylanan anahtar kaydedilir ve sonraki bağlantılar sormadan onunla
karşılaştırılır.

Sunucu sonradan farklı bir anahtar sunarsa bağlantı, iki parmak izini de gösteren bir uyarıyla
durur. Bu, sunucu yeniden kurulduğunda ya da anahtarı değiştiğinde olur; ama birinin bağlantıya
araya girdiği anlamına da gelebilir. Yeni anahtarı yalnızca sunucunun yöneticisi değişikliği
doğruladıktan sonra kabul et.

### opencode sunucusu

Giriş yaptıktan sonra uygulama, kullanıcına ait çalışan bir `opencode serve` arar:

- **Hiçbiri çalışmıyorsa:** uygulama `opencode` programını bulur ve `127.0.0.1`'e bağlı bir
  `opencode serve` başlatır; onu, telefonda sakladığı yeni ve rastgele bir parolayla korur. Senin
  bir şey girmen gerekmez. Uygulama bağlantıyı kesince sunucu çalışmayı sürdürür, oturumlar da
  devam eder.
- **Uygulamanın başlattığı biri çalışıyorsa:** uygulama kaydettiği parolayla yeniden bağlanır.
- **Kendi başlattığın biri çalışıyorsa:** uygulama, o sunucunun başlatıldığı parolayı sorar. Onu
  sunucunun profiline de kaydedebilirsin.
- **Biri çalışıyor ama yanıt vermiyorsa ya da hiçbir loopback adresini dinlemiyorsa:** uygulama
  bir mesajla durur. Yanına ikinci bir sunucu başlatmaz ve onu asla durdurmaz; onu `127.0.0.1`'i
  dinleyecek şekilde kendin yeniden başlat.

Ardından sunucuda bir proje klasörü seç; oturum listesi açılır.

## 4. Bildirimleri ayarla (isteğe bağlı)

### ntfy nedir

[ntfy](https://ntfy.sh), ücretsiz ve açık kaynaklı bir anlık bildirim servisidir. Bir program
bir ntfy sunucusundaki bir *konuya* mesaj gönderir; ntfy uygulamasında o konuya abone olan her
telefon mesajı alır. Phopencode bunu, uygulama kapalıyken ajanından haber alman için kullanır:

- bir soru ya da form yanıtını bekliyor;
- bir izin isteği seni bekliyor;
- bir ana oturum (alt ajanınki değil) bitti;
- bir ana oturum hatayla durdu.

Bildirim metni sabittir ve İngilizcedir: "Answer needed", "Permission needed", "Session finished",
"Session failed" ve her biri için kısa bir satır. Oturum başlıklarını, dosya yollarını, kodu ya da
yapay zekâ çıktısını asla içermez. Bildirime dokunmak uygulamanın **Bekleyen öğeler** ekranını
açar; ayrıntılar SSH üzerinden sunucundan yüklenir.

Bildirimleri telefon değil, sunucundaki küçük bir servis (notifyd) gönderir. Yalnızca Python'un
standart kütüphanesini kullanır, başka paket gerektirmez. Yoğun çalışan bir ajanın telefonu
bildirime boğmaması için her türden dakikada en fazla bir, saatte 20 ve günde 100 bildirim
gönderir; gerisi atılır.

### Konu gizlidir

Konuyu bilen herkes bildirimleri okuyabilir. Uygulama en az 128 bitlik rastgele bir konu
oluşturur, onu telefonda şifreli saklar ve hiçbir zaman tamamını göstermez:
`ntfy://ntfy.sh/••••••••` gösterir. Tek bir konu tüm sunucularına hizmet eder, yani bir kez abone
olursun. Konu, tüm uygulama verisini silene kadar aynı kalır.

### ntfy uygulamasını kur ve abone ol

1. Bir sunucuya bağlan, oturum listesinin menüsünü aç ve **Bildirimler**'i seç.
2. ntfy'ı telefona kur; **ntfy'ı indir**, ntfy'ın telefon uygulamasının nasıl edinileceğini
   anlatan sayfasını açar.
3. **ntfy'da abone ol**'a dokun. ntfy uygulaması konun doldurulmuş olarak açılır; aboneliği orada
   onayla. Uygulama konuyu ntfy'a bir `ntfy://` bağlantısıyla iletir; onu hiç yazman ya da
   kopyalaman gerekmez.

### Servisi sunucuya kur

Aynı ekranda, **Sunucudaki servis** altında **Kur ve başlat**'a dokun. Uygulama:

1. `python3` 3.9 ya da daha yenisinin olup olmadığını denetler; yoksa hiçbir şey kurmadan bir
   mesajla durur;
2. varsa önceki kurulumu kaldırır;
3. servisin ayarlarını (opencode adresi ve parolası, ntfy sunucusu ve konusu) SFTP ile, 0600
   izniyle `~/.config/phopencode/notifyd.json` dosyasına yazar; asla komut satırına koymaz;
4. servisi kopyalayıp başlatan kurulum betiğini çalıştırır.

Durum satırı **Çalışıyor**, **Durdu** ya da **Kurulu değil** gösterir. Servis, o anki bağlantının
sunucusu için çalışır; bildirim almak istediğin her sunucuya ayrı ayrı kur.

Servis, kurulduğu andaki opencode adresini ve parolasını kullanır. `opencode serve` yeniden
başlarsa, örneğin sunucu yeniden açıldıktan sonra uygulama yenisini başlattığında, servis artık
giriş yapamaz ve bildirimler durur; sonraki bağlantıdan sonra **Yeniden kur**'a dokun.

### Deneme gönder

**Deneme gönder**'e dokun. Sunucu konuna bir deneme bildirimi gönderir; ntfy sunucusu onu kabul
edince uygulama bunu söyler ve bildirim kısa süre içinde ntfy uygulamasında görünmelidir. Deneme,
kurulu servise dokunmaz.

### ntfy sunucusunu değiştir

Varsayılan `ntfy.sh`'tır. Kendi ntfy sunucunu kullanmak için adresini **ntfy sunucusu** altına
gir, örneğin `https://ntfy.example.com`, ve kaydet. Yalnızca yol ya da kimlik bilgisi içermeyen
`https://` adresleri kabul edilir. **ntfy.sh kullan** varsayılana döner. Kurulu bir servis, onu
yeniden kurana kadar eski sunucuyu kullanır; ntfy uygulamasında da yeniden abone olman gerekir.

### Servisi kaldır

**Kaldır**'a dokun ve onayla. Uygulama kaldırma betiğini çalıştırır; betik ayar dosyasını siler
(servis bunu fark edip kendiliğinden kapanır), servis kaydını kaldırır ve kurulumun oluşturduğu
dosyaları siler. Telefonda uygulama verisini silmek servisi sunucularından kaldırmaz; önce onu
kaldır.

### Servis neyi nereye kurar

Her şey ev klasöründedir; hiçbir şey root gerektirmez:

| Ne | Nerede |
|---|---|
| Ayarlar (opencode parolası, ntfy konusu), 0600 | `~/.config/phopencode/notifyd.json` |
| Servis, 0600 | `~/.local/share/phopencode/notifyd.pyz` |
| Logu (yalnızca cron), 0600; sabit metin, gizli bilgi yok | `~/.local/share/phopencode/notifyd.log` |
| systemd kullanıcı birimi, systemd kullanılıyorsa | `~/.config/systemd/user/phopencode-notifyd.service` |
| crontab satırı, cron kullanılıyorsa | `@reboot /bin/sh -c '...' phopencode-notifyd` |

`$XDG_CONFIG_HOME` ve `$XDG_DATA_HOME` tanımlıysa `~/.config` ve `~/.local/share` yerine onlar
kullanılır. Kurulum betiği, kullanıcı yöneticisi yanıt veriyorsa ve kullanıcında lingering açıksa
`systemctl --user` kullanır; böylece servis oturumu kapatınca da çalışır. Aksi halde crontab
satırını ekler ve servisi hemen başlatır. Lingering'i hiçbir zaman açmaz, `sudo` kullanmaz ve
hiçbir süreci durdurmaz, yeniden başlatmaz ya da ona sinyal göndermez: servis zaten çalışıyorsa
reddeder; yeniden kurulum da önce eskisini kaldırır.

## Uygulamanın sunucunda yaptıkları

Her şey SSH kullanıcın olarak çalışır. Uygulama:

- komutları SSH üzerinden `sh -c` ile çalıştırır; her değişken parça kabuk için tırnaklanır;
- çalışan bir `opencode serve` bulmak için süreç listesini ve dinleyen soketleri okur; bunu
  yaparken hiçbir şeyi değiştirmez;
- `opencode serve --hostname 127.0.0.1` sürecini yalnızca kullanıcına ait hiçbir
  `opencode serve` çalışmıyorsa başlatır; parolası stdin üzerinden verilir, asla komut satırına ya
  da bir dosyaya yazılmaz;
- 0700 izinli küçük bir durum klasörü tutar: `~/.local/state/phopencode/` (ya da
  `$XDG_STATE_HOME` altında); içinde `server.state` (başlattığı sunucunun portu ve süreç kimliği,
  parola yok) ve o sunucunun logu bulunur;
- açtığın klasörlerde SFTP ile dosya okur ve yazar; o ekranları kullandığında orada `git`, `rg`
  ya da `grep` çalıştırır;
- bildirim servisini yalnızca ilgili düğmelere dokunduğunda kurar ve kaldırır.

Hiçbir zaman `sudo` kullanmaz; sunucuyu yeniden başlatmaz, kapatmaz ya da sıfırlamaz; zaten
çalışan bir `opencode serve` sürecini durdurmaz, öldürmez ya da yerine başkasını koymaz.
Durdurduğu tek süreçler kendi süreçleridir: iptal ettiğin bir komut ve az önce başlatıp 30 saniye
içinde ayağa kalkmayan bir sunucu. Git varsayılan olarak güvenlidir: zorla push yok,
`git reset --hard` yok, `git clean` yok; pull yalnızca fast-forward yapar ve değişiklikleri atmak
onayını ister.

## Sorun giderme

- **"Sunucuda opencode bulunamadı":** opencode'u giriş yaptığın kullanıcı için kur ya da o
  kullanıcının giriş kabuğunda `opencode --version` komutunun çalıştığından emin ol.
- **"Sunucudaki opencode çok eski":** onu güncelle, örneğin `opencode upgrade` ile.
- **"Bu uygulamanın başlatmadığı bir opencode sunucusu çalışıyor":** başlatıldığı parolayı gir ya
  da uygulamanın kendi sunucusunu başlatabilmesi için o sunucuyu kendin durdur.
- **"Bir opencode sunucusu çalışıyor ama yanıt vermiyor":** onu sunucuda yeniden başlat; uygulama
  ona dokunmaz.
- **Sunucu anahtarı değişti:** bkz. [Sunucu anahtarını onayla](#sunucu-anahtarını-onayla).
- **Servis kurulu ama bildirim gelmiyor:** ntfy uygulamasındaki aboneliği denetle, bir deneme
  gönder ve kurulumdan sonra `opencode serve` yeniden başladıysa servisi yeniden kur.

## Her şeyi kaldırma

- Sunucuda: önce bildirim servisini uygulamadan kaldır (bkz.
  [Servisi kaldır](#servisi-kaldır)). Ardından uygulamanın durum klasörü
  `~/.local/state/phopencode/` elle silinebilir. Çalışan bir `opencode serve` sürecini durdurmak
  sana kalmıştır.
- Telefonda: **Ayarlar**'daki (sunucu listesinden açılır) **Tüm verileri sil**, her sunucuyu,
  anahtarı, parolayı ve ayarı siler; uygulamayı kaldırmak da aynısını yapar.

Güvenlikle ilgili sorular [SECURITY-MODEL.tr.md](SECURITY-MODEL.tr.md) dosyasında yanıtlanır. Bir
güvenlik açığı bildirmek için [SECURITY.md](../SECURITY.md) dosyasındaki adımları izle.

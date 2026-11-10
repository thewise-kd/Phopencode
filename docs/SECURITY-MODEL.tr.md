# Güvenlik modeli

<p><a href="SECURITY-MODEL.md">English</a> | <strong>Türkçe</strong></p>

Bu belge Phopencode'un neyi, nasıl koruduğunu ve korumasının nerede bittiğini anlatır: telefonda
neyin saklandığı, ağ üzerinden neyin gittiği, sunucu anahtarlarına nasıl güvenildiği ve
telefonunu, sunucunu ya da ntfy konunu ele geçiren birinin neler yapabileceği. Kurulum
[INSTALL.tr.md](INSTALL.tr.md) dosyasında anlatılır; güvenlik açıkları
[SECURITY.md](../SECURITY.md) dosyasında anlatıldığı gibi bildirilir.

## Kısaca

- Telefon yalnızca senin sunucunla ve yalnızca SSH üzerinden konuşur. opencode API'sine o SSH
  bağlantısı üzerinden ulaşılır; `opencode serve` yalnızca `127.0.0.1`'i dinler.
- Bir sunucunun anahtarını ilk bağlantıda sen onaylarsın; değişen bir anahtar bağlantıyı
  durdurur.
- Parolalar, özel anahtarlar, anahtar parolaları ve ntfy ayarları telefonda, Android Keystore'da
  tutulan bir anahtarla şifrelenir; uygulamanın sakladığı hiçbir şey yedeğe girmez.
- Bildirimler sunucundan ntfy üzerinden gelir ve yalnızca sabit metin taşır.
- Hiçbir türden analiz, çökme raporlama, reklam ya da izleme yoktur.

## Tehdit modeli

### Korunanlar

- SSH kimlik bilgilerin: parolalar, özel anahtarlar ve onların parolaları.
- `opencode serve` sunucularının parolaları.
- Sunucunda kalan kodun, oturumların ve yapay zekâ konuşmaların.
- Bildirimlerine erişim sağlayan ntfy konusu.
- Uygulamanın zarar vermemesi ve ele geçirmemesi gereken sunucunun kendisi.

### Güvenilenler

- **Sunucun ve onun SSH kullanıcısı.** Uygulama o kullanıcı olarak çalışır ve sunucunun
  gönderdiğini gösterir. Sunucuyu denetleyen, uygulamanın ne gördüğünü de denetler.
- **Android ve telefonun kilit ekranı.** Uygulama Android'in uygulama yalıtımına, dosya tabanlı
  şifrelemesine ve Android Keystore'a dayanır. Kendi kilidi yoktur.
- **Onayladığın SSH sunucu anahtarı.** Bir saldırganın anahtarını onaylarsan ortadaki adam
  saldırısına karşı koruma ortadan kalkar.
- **ntfy sunucusu** (varsayılan olarak `ntfy.sh`): bildirimlerin gizliliği için değil, iletimi
  için. Konuyu ve sabit metni görür.

### Kapsam dışı olanlar

- Root yapılmış, yüksek yetkili kötü amaçlı yazılımla ele geçirilmiş ya da kilidi açıkken başkası
  tarafından kullanılan bir telefon.
- Ele geçirilmiş bir sunucu ya da SSH hesabı.
- opencode'un kendisinin, çağırdığı model sağlayıcılarının ve ajanın sunucunda çalıştırdığı kodun
  güvenliği.

## Telefonda saklananlar

Her şey uygulamanın, diğer uygulamaların okuyamadığı özel depolama alanındadır.

| Veri | Nasıl saklanır |
|---|---|
| SSH parolaları, özel anahtarlar, anahtar parolaları, opencode sunucu parolaları, ntfy konusu ve ntfy sunucusu | Şifreli, aşağıya bak |
| Sunucu profilleri: ad, adres, port, kullanıcı adı, opencode portu | DataStore dosyası; gizli bilgilere yalnızca başvuru tutar, onların kendisini asla |
| Onaylanmış sunucu anahtarları: adres, port, SHA-256 parmak izi | DataStore dosyası |
| SSH anahtar listesi: ad, tür, parmak izi, açık anahtar | DataStore dosyası; özel anahtarlara yalnızca başvuru tutar, onların kendisini asla |
| Sunucu başına son klasörler ve ayarlar (dil, tema, dosya boyutu sınırı) | DataStore dosyaları |

**Gizli bilgiler.** Her gizli bilgi [Tink](https://developers.google.com/tink) ile AES-256-GCM
kullanılarak şifrelenir. Tink anahtar kümesi de Android Keystore'da duran ve oradan hiç çıkmayan
bir AES-256-GCM anahtarıyla şifrelenir. Dosyalar uygulamanın yedeklenmeyen klasöründedir; dosya
adları özetlerdir, yani hangi dosyanın hangi gizli bilgi olduğunu belli etmez. Android, uygulama
kaldırılınca Keystore anahtarını siler; böylece kurulumdan sonra kalan dosya kopyaları çözülemez.

**Yedek yok.** Yedekleme kapalıdır (`android:allowBackup="false"`) ve veri aktarım kuralları her
depolama alanını bulut yedeğinin ve cihazdan cihaza aktarımın dışında tutar. Sunucuların ve
anahtarların hiçbir yedeğe girmez.

**İşinin kopyası yok.** Uygulama kodunu, oturumlarını ya da konuşmalarını diske kaydetmez;
gösterirken onları sunucudan yükler. İndirdiğin bir dosya yalnızca sistem dosya seçicisinde
seçtiğin yere gider.

**Silme.** **Ayarlar**'daki **Tüm verileri sil**, ntfy konusu dahil her sunucuyu, anahtarı,
parolayı ve ayarı siler. Uygulamayı kaldırmak da aynısını yapar. İkisi de sunucularından bir şey
kaldırmaz; bkz. [INSTALL.tr.md](INSTALL.tr.md#her-şeyi-kaldırma).

## Ağ üzerinden gidenler

```text
 Telefon (Phopencode) --- SSH ---> sunucun ---- HTTPS ----> ntfy sunucusu ----> ntfy uygulaması
                                     |                      (varsayılan:
                                     '- opencode serve       ntfy.sh)
                                        127.0.0.1 üzerinde
```

- **Telefondan sunucuna: yalnızca SSH.** Komutlar, SFTP dosya aktarımları ve opencode HTTP API'si
  tek bir SSH bağlantısını paylaşır. API ve olay akışı, sunucudaki `127.0.0.1`'e giden bir SSH
  kanalından geçer; telefon yerel port açmaz, yani telefondaki diğer uygulamalar ona erişemez.
- **Uygulamadan başka bağlantı yok.** Uygulamanın ağ güvenliği yapılandırması şifresiz trafiği
  yasaklar; tek istisna, yalnızca SSH kanalının ulaştığı `127.0.0.1`'dir. Sohbetteki Markdown
  resimleri yüklenmez. Bir sohbet mesajındaki bağlantı, tam adresini gösteren bir iletişim
  kutusunu onaylamadan tarayıcıda açılmaz.
- **Sunucundan ntfy'a: yalnızca HTTPS.** Bildirim servisi her bildirimi ntfy sunucusuna HTTPS ile
  gönderir; düz `http://` sunucularını reddeder.
- **ntfy'dan telefonuna:** bildirimi ntfy uygulaması alır. Phopencode'un kendisi ntfy sunucusuyla
  hiçbir zaman bağlantı kurmaz.

**Analiz ya da izleme yok.** Uygulamada analiz, çökme raporlama, reklam ya da izleme kütüphanesi
yoktur; seninle ya da uygulamayı nasıl kullandığınla ilgili kimseye bir şey göndermez.

## Sunucu anahtarlarına güven

- Bir adres ve porta ilk bağlantıda uygulama sunucunun anahtar türünü ve SHA-256 parmak izini
  gösterir ve onaylamanı ister. Bunu sunucuda gösterilen parmak iziyle karşılaştır
  ([INSTALL.tr.md](INSTALL.tr.md#sunucu-anahtarını-onayla) nasıl yapılacağını anlatır).
- Onaylanmış bir anahtar sonraki her bağlantıda sessizce denetlenir.
- Farklı bir anahtar, hiçbir parola gönderilmeden ve hiçbir komut çalışmadan bağlantıyı durdurur
  ve eski ile yeni parmak izlerini gösterir. Onaylanmış anahtarı değiştirmek iki ayrı açık onay
  ister; bunu yalnızca sunucunun yöneticisi değişikliği doğruladığında yap.
- Onaylanmış anahtarlar dosyası okunamazsa uygulama, sunucuyu yeniymiş gibi saymak yerine
  bağlanmayı reddeder.

## opencode sunucusu

- Uygulama `opencode serve` sürecini yalnızca kullanıcına ait hiçbiri çalışmıyorsa ve ağdan
  erişilemesin diye `127.0.0.1`'e bağlı olarak başlatır.
- O sunucuyu 256 bitlik yeni ve rastgele bir parolayla korur. Parola sunucuya başlatma komutunun
  standart girdisinden ulaşır: diğer kullanıcıların süreç listesinde görebileceği komut satırına
  ve bir dosyaya asla yazılmaz. Telefonda şifreli saklanır.
- Uygulama API'ye SSH bağlantısının içinden HTTP Basic kimlik doğrulamasıyla ulaşır.
- Sunucu, uygulama bağlantıyı kestikten sonra da çalışır. Sunucuda zaten senin kullanıcın olarak
  iş yapabilen herkes ona ulaşabilir; diğerleri `127.0.0.1`'e dışarıdan ulaşamaz.

## Uygulamanın sunucuna yaptıkları

Uygulama SSH kullanıcın olarak çalışır ve hiçbir zaman `sudo` kullanmaz. Sunucuyu asla yeniden
başlatmaz, kapatmaz ya da sıfırlamaz; zaten çalışan bir `opencode serve` sürecini asla durdurmaz,
öldürmez ya da yerine başkasını koymaz; yalnızca hiçbiri çalışmıyorsa yenisini başlatır.
Durdurduğu tek süreçler kendi süreçleridir: iptal ettiğin bir komut ve az önce başlatıp zamanında
ayağa kalkmayan bir sunucu. Her komut sabit bir kabuk metnidir ve her değişken parçası kabuk için
tırnaklanır. Git komutları asla zorla push yapmaz, `git reset --hard` ya da `git clean`
çalıştırmaz ve pull yalnızca fast-forward yapar. Listenin tamamı
[INSTALL.tr.md](INSTALL.tr.md#uygulamanın-sunucunda-yaptıkları) dosyasındadır.

## Bildirimler

- **Sabit metin.** Servis dört sabit bildirimden birini ("Answer needed", "Permission needed",
  "Session finished", "Session failed") ve sabit bir deneme bildirimini gönderir. Oturumlarından
  hiçbir şey, örneğin başlıklar, dosya yolları, kod ya da yapay zekâ çıktısı, bir bildirime
  konmaz.
- **Gizli bir konu.** Konu telefonda güvenli bir rastgele kaynaktan, en az 128 bit olarak üretilir;
  hiçbir zaman loglanmaz ve tamamı gösterilmez. Uygulama onu ntfy uygulamasına bir abonelik
  bağlantısıyla iletir.
- **Sunucudaki ayarlar.** Servisin ayar dosyası `~/.config/phopencode/notifyd.json`, opencode
  adresini ve parolasını, ntfy sunucusunu ve konusunu tutar. SFTP ile 0600 izniyle yazılır, asla
  komut satırında ya da bir ortam değişkeninde aktarılmaz; dosyayı başkaları okuyabiliyorsa servis
  başlamayı reddeder. Servis yalnızca sabit metin loglar; parolayı, konuyu, sunucu adresini ya da
  olay içeriğini asla loglamaz.
- **Ayrıcalık yok.** Servis senin kullanıcın olarak, ev klasöründen, `systemd --user` ya da
  crontab'ın aracılığıyla çalışır; betikleri hiçbir zaman `sudo` kullanmaz ve hiçbir süreci
  durdurmaz, yeniden başlatmaz ya da ona sinyal göndermez.

## Bir saldırganın yapabilecekleri

### Telefonunla

- **Kilidi açıksa ya da kilit ekranı kodunu biliyorsa:** uygulamada senin yapabildiğin her şeyi;
  yani sunucularında SSH kullanıcın olarak iş yapmak, kodunu okumak ve değiştirmek. Uygulamanın
  kendi kilidi yoktur; onu koruyan telefonun kilit ekranıdır.
- **Kilitliyse:** uygulamanın dosyaları Android tarafından şifrelenir; gizli bilgiler de dışa
  aktarılamayan bir Keystore anahtarıyla ayrıca şifrelenir. Onlara ulaşmak, telefonun kendi
  güvenliğini aşmayı gerektirir.
- **Telefon kaybolursa yapılacaklar:** her sunucuda telefonun açık anahtarını
  `~/.ssh/authorized_keys` dosyasından çıkar, uygulamanın bildiği SSH parolalarını değiştir ve
  parolası değişsin diye `opencode serve`'ü yeniden başlat. Bildirimler için servisi sunucudan
  kaldır (ya da ayar dosyasını sil); uygulamanın verisi silindikten sonra yeni bir konu oluşturulur.

### Sunucunla

- Sunucunu ya da oradaki SSH hesabını denetleyen biri zaten kodunu, opencode kurulumunu ve onun
  sağlayıcı kimlik bilgilerini elinde tutar. Bildirim servisinin ayar dosyasını, dolayısıyla
  opencode parolasını ve ntfy konusunu da okuyabilir ve uygulamaya istediği her şeyi
  gösterebilir.
- SSH özel anahtarını ele geçiremez: SSH anahtarıyla giriş onu sunucuya asla göndermez. Parolayla
  giriş yapıyorsan, her SSH istemcisinde olduğu gibi SSH parolanı görür; anahtarla girişi tercih et.
- Sohbet bağlantıları açılmadan önce yine onayını ister ve Markdown resimleri yüklenmez; yani
  sunucudan gelen içerik telefona kendiliğinden bir adres getirtemez.

### Ağda

- Telefon ile sunucu arasındaki bir saldırgan yalnızca şifreli SSH trafiği görür. Sunucun gibi
  davranabilmesi için onun sunucu anahtarı gerekir ya da senin değişmiş bir anahtarı kabul etmen.
- Sunucun ile ntfy arasındaki trafik HTTPS'tir. ntfy sunucusunun işletmecisi konuyu, sabit metni,
  her bildirimin zamanını ve sunucunun adresini görür.

### ntfy konunla

- Bildirimlerini okuyabilir: sabit metinleri ve zamanlarını; bunlar ajanının seni ne zaman
  beklediğini, ne zaman bitirdiğini ya da hata verdiğini gösterir. Kodun ya da konuşmaların
  hakkında hiçbir şey öğrenemez.
- ntfy uygulaması üzerinden telefonuna, metnini ve bağlantısını kendi seçtiği bildirimler
  gönderebilir. Gerçek bildirimler her zaman yukarıdaki sabit metinlerden birini taşır ve
  uygulamanın Bekleyen öğeler ekranını açar; başka her şeye şüpheyle yaklaş. Uygulamanın
  `phopencode://pending` bağlantısı yalnızca o ekranı açar; ekran da yalnızca kendi sunucunun
  bildirdiklerini gösterir.
- Konuyla sunucuna ya da uygulamaya ulaşamaz.
- Konuyu değiştirmek için: servisi sunucularından kaldır, uygulamanın verisini sil, uygulamayı
  yeniden ayarla ve ntfy uygulamasında yeni konuya abone ol.

## Sürüm derlemeleri

- Sürüm APK'ları bir sürüm etiketinden GitHub Actions iş akışıyla derlenir, R8 ile küçültülür,
  projenin sürüm anahtarıyla imzalanır ve SHA-256 özetiyle birlikte GitHub Releases'te
  yayımlanır.
- Sürüm derlemesi, uygulamada ve kütüphanelerinde Android log'una yapılan her çağrıyı kaldırır;
  yani uygulama sistem loguna tanılama bilgisi yazmaz.
- Android bir güncellemeyi yalnızca kurulu uygulamayla aynı anahtarla imzalanmışsa kurar.

## Güvenlik açığı bildirme

Bir güvenlik sorunu için herkese açık bir issue açma. Onu gizlice bildirmek için
[SECURITY.md](../SECURITY.md) dosyasındaki adımları izle; bildirime gerçek sunucu adreslerini,
anahtarları, parolaları ve konuları koyma.

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
- Telefon yerel port açmaz; tek istisna canlı önizlemedir. Önizleme ekranı açıkken telefonun
  `127.0.0.1` adresinde, rastgele bir portta bir köprü dinler ve yalnızca o oturum için üretilen
  gizli değeri taşıyan isteklere cevap verir. Bkz. [Canlı önizleme](#canlı-önizleme).
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
                                     |- opencode serve       ntfy.sh)
                                     |  127.0.0.1 üzerinde
                                     '- geliştirme sunucun (canlı önizleme)
                                        127.0.0.1 üzerinde
```

- **Telefondan sunucuna: yalnızca SSH.** Komutlar, SFTP dosya aktarımları ve opencode HTTP API'si
  tek bir SSH bağlantısını paylaşır. API ve olay akışı, sunucudaki `127.0.0.1`'e giden bir SSH
  kanalından geçer; telefon bunlar için yerel port açmaz, yani telefondaki diğer uygulamalar
  onlara erişemez.
- **Tek istisna canlı önizleme.** Önizleme ekranı açıkken köprüsü yalnızca telefonun
  `127.0.0.1` adresinde, rastgele bir portta dinler ve önizlemenin isteklerini aynı SSH bağlantısı
  üzerinden, sunucunun loopback'inde senin seçtiğin tek porta iletir. Köprüye gelen her bağlantı o
  oturum için üretilen gizli değeri taşımak zorundadır; taşımayan 403 alır ve sunucuya hiçbir şey
  ulaşmaz. Ekrandan çıkınca port kapanır. Komutlar, SFTP ve opencode API'si için yine yerel port
  açılmaz. Ayrıntılar [Canlı önizleme](#canlı-önizleme) bölümünde.
- **Uygulamadan başka bağlantı yok.** Uygulamanın ağ güvenliği yapılandırması şifresiz trafiği
  yasaklar; tek istisna `127.0.0.1`'dir ve telefonda bu adres ya SSH kanalına ya da önizlemenin
  köprüsüne çıkar. Sohbetteki Markdown resimleri yüklenmez. Bir sohbet mesajındaki ya da
  önizlemede, önizlenen siteden dışarı çıkan bir bağlantı, tam adresini gösteren bir iletişim
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
ayağa kalkmayan bir sunucu. Önizlediğin geliştirme sunucusunu asla başlatmaz ya da durdurmaz;
hangi portları önizleyebileceğini göstermek için yalnızca dinleyen portların listesini okur. Her
komut sabit bir kabuk metnidir ve her değişken parçası kabuk için
tırnaklanır. Git komutları asla zorla push yapmaz, `git reset --hard` ya da `git clean`
çalıştırmaz ve pull yalnızca fast-forward yapar. Listenin tamamı
[INSTALL.tr.md](INSTALL.tr.md#uygulamanın-sunucunda-yaptıkları) dosyasındadır.

## Canlı önizleme

Canlı önizleme, sunucunun loopback'inde bir geliştirme sunucusunun çalıştırdığı web uygulamasını
(örneğin `127.0.0.1` üzerinde `npm run dev`) telefondaki bir WebView'da gösterir. Trafiği
telefondaki geçici bir köprüden geçer. Bu, D9 kararındaki (b) seçeneğidir; yerel port açmayan
tasarım yerine seçildi, çünkü formlar, gövdeli istekler ve anında yenileme (hot reload) ancak
böyle çalışır. İki seçeneğin karşılaştırması
[preview-transport.md](dev-setup/preview-transport.md) dosyasındadır (İngilizce).

### Neye bağlanır

- **Yalnızca senin seçtiğin port.** Önizleme ekranı, sunucunun loopback'inde dinleyen TCP
  portlarını listeler; sen birini seçersin. Liste sabit ve yalnızca okuyan bir komuttan gelir
  (`ss` ya da o yoksa çekirdeğin `/proc/net/tcp` tabloları); hiçbir süreç başlatılmaz,
  durdurulmaz ya da ona sinyal gönderilmez, `sudo` hiç kullanılmaz.
- **Yalnızca sunucunun loopback'i.** Önizlemenin SSH kanalı, sunucunun gözünden yalnızca o
  porttaki `127.0.0.1`, `::1` ya da `localhost`'a ulaşır. Başka bir makineye ulaşamaz ve
  `opencode serve`'ün dinlediği portu reddeder.
- **Geliştirme sunucusunun sürecine dokunmaz.** Uygulama geliştirme sunucunu asla başlatmaz,
  durdurmaz ya da yeniden başlatmaz. Portta dinleyen yoksa önizleme bunu söyler.
- Kanal, açıldığı SSH bağlantısına aittir: o bağlantı koparsa kapanır ve hiçbir zaman sonraki bir
  bağlantıya geçmez. Yeniden bağlandıktan sonra sayfayı yenilemek yeni bir kanal açar.

### Telefondaki köprü

- Önizleme ekranı açılınca köprü, bir ağ arayüzünde değil yalnızca `127.0.0.1` üzerinde, sistemin
  rastgele seçtiği bir portta dinlemeye başlar. Sürüm denetimi, uygulamada soket dinleyen tek
  yerin burası olduğunu ve yalnızca loopback adresini kullandığını doğrular.
- Her köprü, güvenli bir rastgele kaynaktan 256 bitlik yeni bir gizli değer üretir. WebView bu
  değeri bir kez, ilk adresinde gösterir; köprü onu `HttpOnly` ve `SameSite=Strict` işaretli bir
  çereze taşır ve WebView'ı aynı sayfaya, bu kez adreste gizli değer olmadan yönlendirir.
- Her istek gizli değeri ya o çerezde ya da ilk adreste taşımak zorundadır; karşılaştırma sabit
  sürede yapılır. Taşımayan istek 403 alır ve bağlantısı, SSH kanalına tek bayt ulaşmadan kapanır.
  Bu retler hiçbir yere bildirilmez; böylece diğer uygulamalar önizlemenin konsolunu dolduramaz.
- Gizli değer geliştirme sunucusuna hiç ulaşmaz: köprü kendi çerezini her istekten çıkarır ve
  geliştirme sunucusunun aynı adla çerez koyma girişimlerini yok sayar. Hiçbir zaman loglanmaz;
  konsol da gösterdiği her adresten ve mesajdan onu siler.
- Köprü tek bir bağlantının neye mal olabileceğini sınırlar: istek ve cevap başlıklarının ve
  gövdelerinin boyutu, başlık sayısı, bir istemcinin ya da geliştirme sunucusunun ne kadar
  oyalanabileceği ve aynı anda kaç bağlantı olabileceği.
- Önizleme ekranından çıkınca köprünün portu, ondan geçen her bağlantı ve SSH kanalı kapanır.
  Önizlemeyi yeniden açmak yeni bir port ve yeni bir gizli değer üretir.

### WebView

Önizlemenin WebView'ı uygulamadaki tek WebView'dır ve uygulamanın geri kalanından yalıtılmıştır:

- **Uygulamaya köprü yok.** Sayfalar, geliştirme sunucularının ihtiyaç duyduğu JavaScript'i
  çalıştırır; ama onlara hiçbir uygulama nesnesi, mesaj kanalı ya da başka bir arayüz verilmez.
- **Dosya ve içerik erişimi yok.** Dosya ve içerik erişimi, hem sayfa hem `file:` adresleri için
  kapalıdır. Dosya seçici ve indirmeler reddedilir.
- **Cihaz erişimi yok.** Kamera, mikrofon ve diğer bütün izin istekleri reddedilir; konum kapalıdır.
  Açılır pencere açılmaz.
- **Kimlik bilgisi yok.** WebView SSH anahtarlarını, parolalarını ya da ntfy ayarlarını hiç görmez.
  HTTP kimlik doğrulama istekleri, istemci sertifikası istekleri ve TLS hatası veren sayfalar iptal
  edilir. Form verileri ve parolalar kaydedilmez; otomatik doldurma servisine sayfa gösterilmez.
- **Hiçbir şey kalmaz.** Sayfa, önizleme açıkken çerez ve web depolama kullanabilir. Önizleme
  kapanınca çerezleri, web depolaması, HTTP önbelleği, geçmişi, form verileri ve kaydedilmiş HTTP
  kimlik doğrulaması silinir ve WebView yok edilir. Telefonu döndürmek sayfayı korur; ekrandan
  çıkmak korumaz.
- **Sürümlerde hata ayıklama yok.** WebView'da uzaktan hata ayıklama yalnızca uygulamanın hata
  ayıklama derlemesinde mümkündür.

### Önizleme kökeni dışındaki istekler

Önizleme kökeni (origin) `http://127.0.0.1:<köprü portu>` adresidir. Sayfanın yüklediği her şey
buradan gelmek zorundadır:

- **WebView'da reddedilir.** WebView'ın yaptığı her istek, dışarı çıkmadan denetlenir. Önizleme
  kökeninde olmayan, `data:`, `about:blank`, `about:srcdoc` ya da kökende üretilmiş bir `blob:` da
  olmayan istek boş bir 403 alır ve konsolda reddedilmiş olarak listelenir; telefon onu hiç
  getirmez. Telefonun loopback'indeki başka bir port da kökenin dışındadır.
- **Eklenen bir `Content-Security-Policy`.** Köprü, ilettiği her cevaba ve kendi cevaplarına bir
  politika ekler. WebView yönlendirilen bir alt kaynağı yeniden denetlemez; kökenin dışına çıkan
  bir yönlendirmeyi durduran bu politikadır:
  - `default-src` yalnızca kökene, `data:` ve `blob:`'a izin verir; resimler, yazı tipleri, medya,
    çerçeveler, worker'lar ve geri kalanı buna düşer.
  - `script-src` ve `style-src` satır içi koda da izin verir; `script-src` ayrıca Vite ve webpack
    geliştirme sunucularının dayandığı `eval`'e izin verir.
  - `connect-src` kökene `http` ve `ws` üzerinden izin verir; böylece `fetch`, `XMLHttpRequest`,
    `EventSource` ve anında yenilemenin WebSocket'i kökende kalır.
  - `form-action` yalnızca kökene izin verir.

  Sayfanın kendi politikası varsa değiştirilmeden iletilir ve o da geçerlidir.
- **Sunucudaki adresler.** Geliştirme sunucusu, istekleri sunucudaki bir tarayıcıdan geliyormuş
  gibi `localhost`'a yazılmış görür; kendi `localhost` adresine yaptığı yönlendirmeler de önizleme
  kökenine geri çevrilir.

### Dış bağlantılar

Önizleme kökeninden çıkan bir bağlantı WebView'da hiçbir zaman yüklenmez. Ana sayfadaki bir
`http` ya da `https` adresiyse uygulama tam adresi gösteren bir iletişim kutusu açar ve onu ancak
sen onaylarsan tarayıcında açar. Kökenden çıkmaya çalışan bir çerçeve soru sorulmadan reddedilir;
diğer her türlü adres (`file:`, `content:`, `intent:`, `javascript:` ve diğerleri) de reddedilir.

### Sunucundaki kötü niyetli bir sayfa

Geliştirme sunucusu ve sunduğu sayfalar, sunucunda senin kullanıcın olarak çalışan kodlardır;
önizleme onlara sunucunun geri kalanına güvendiği kadar güvenir, ne az ne çok. Kötü niyetli ya da
ele geçirilmiş bir sayfa şunları yapabilir:

- önizlemede JavaScript çalıştırmak ve her şeyi göstermek, giriş formunu taklit eden bir sayfa
  dahil;
- seçtiğin porttaki geliştirme sunucusuna her türlü isteği, her türlü gövdeyle göndermek ve orada
  WebSocket açmak; geliştirme sunucusunun sonra sunucunda ne yaptığı uygulamanın dışındadır;
- WebView'ı meşgul etmek ya da köprünün sınırlarını tüketmek, bu da önizlemeyi yavaşlatır ya da
  durdurur;
- önizlemenin konsoluna yazmak ve bir dış adresi açmayı istemek; bu yine senin onayına bağlıdır.

Şunları yapamaz:

- önizleme üzerinden başka bir porta ya da makineye ulaşmak: `opencode serve`'e, sunucudaki başka
  bir porta, telefonun loopback'indeki diğer portlara ya da internete (ama
  [Bilinen sınırlar](#bilinen-sınırlar) bölümüne bak);
- uygulamadaki herhangi bir şeyi okumak ya da değiştirmek: sunucularını, anahtarlarını,
  parolalarını, sohbetlerini ya da ayarlarını;
- telefondaki dosyaları okumak, kamerayı, mikrofonu ya da konumu kullanmak, dosya indirmek ya da
  pencere açmak;
- önizlemeyi kapattıktan sonra telefonda bir şey bırakmak;
- uygulama üzerinden sunucunda komut çalıştırmak.

Köprünün gizli değeri köprüyü sayfaya karşı değil, diğer uygulamalara karşı korur; sayfa zaten
köprünün kendi istemcisidir. Sayfanın betikleri gizli değerin çerezini okuyamaz; okusalar bile
sayfa, zaten ulaştığı yerlerin ötesine geçemezdi.

### Köprüye başka kimler ulaşabilir

- **Telefondaki diğer uygulamalar**, önizleme açıkken köprünün portuna bağlanabilir. Gizli değer
  olmadan 403 alırlar ve sunucuna hiçbir şey ulaşmaz. Çok sayıda bağlantı açarak önizlemeyi
  yavaşlatabilirler.
- **Telefonun tarayıcısında açık bir site** de porta istek gönderebilir. WebView'ın çerezi başka
  bir uygulamanın depolamasında durduğu için onu okuyamaz; o da 403 alır.
- **Ağ** köprüye ulaşamaz: köprü yalnızca loopback adresinde dinler.

### Bilinen sınırlar

- WebView, Android SDK olmadan derlenemez ve sınanamaz. Köprü, gizli değer denetimi, eklenen
  politika ve adres kuralları her bilgisayarda çalışan testlerle sınanır; WebView'ın kendi
  davranışı, yani ayarları, reddedilen istekler ve kapanınca silinen veriler, yalnızca projenin
  CI'ında bir Android emülatöründe çalışan testlerle denetlenir.
- WebRTC ve DNS ön çözümlemesi ne istek denetiminin ne de politikanın kapsamındadır; bu yüzden
  ayrıca ve yalnızca kısmen kapatılırlar:
  - **WebRTC.** Sayfanın kendi betikleri çalışmadan önce `RTCPeerConnection`, onun önekli
    biçimleri, `RTCDataChannel` ve diğer WebRTC arayüzleri, sistem WebView'ının belge başı
    betiklerini çalıştırdığı her çerçevede `undefined` yapılır; sayfa bunları atama ya da silme
    yoluyla geri getiremez. Böyle betikleri çalıştıramayan bir sistem WebView'ı önizleme açmaz:
    ekran, sistem WebView'ının çok eski olduğunu söyler.
  - **DNS ön çözümlemesi.** Köprü, kendi yanıtları da dahil her yanıta, sayfanın gönderdiği değerin
    yerine `X-DNS-Prefetch-Control: off` ekler. Bu, WebView'dan sayfadaki bağlantıların alan
    adlarını önceden çözmemesini ister.
  - **Kalabilecekler.** WebView'ın belge başı betiklerini çalıştırmadığı bir çerçevede WebRTC hâlâ
    bulunabilir ve bir sayfa onunla WebView'ı kendi seçtiği bir STUN ya da TURN sunucusuyla
    konuşturabilir. Bir betiğin oluşturup hemen kullandığı boş bir `about:blank` çerçevesi böyle bir
    çerçeve olabilir. Başka bir alan adını gösteren `<link rel="dns-prefetch">` ya da
    `<link rel="preconnect">` gibi bir ipucu, başlığın bunu kapsadığı kesin olmadığından, telefona
    yine o adı çözdürebilir. Yani önizlediğin bir sayfa telefondan dışarı hâlâ az miktarda veri
    gönderebilir.
  - **Nasıl denetlendiği.** WebView'ın ne yaptığını yalnızca projenin CI'ında bir Android
    emülatöründe çalışan testler denetler. Her bilgisayarda çalışan testler başlığı ve betiğin
    metnini denetler, etkilerini değil.
- Geliştirme sunucuları ihtiyaç duyduğu için `script-src` satır içi koda ve `eval`'e izin verir.
  Eklenen politika sayfayı kendi kökeninde tutar; sayfayı kendi betiklerine karşı korumaz.

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
- Canlı önizlemeye sundukları bir sayfa, önizlemenin yalıtılmış WebView'ının içinde kalır; bkz.
  [kötü niyetli bir sayfanın yapabilecekleri](#sunucundaki-kötü-niyetli-bir-sayfa).

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

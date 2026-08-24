/**
 * Interface translations.
 *
 * Keys are flat and namespaced by screen. `t()` falls back to English when a
 * string is missing, so a partial translation degrades to readable text rather
 * than to a blank label.
 */

export type Language = "system" | "en" | "ru";

const en = {
  "nav.connection": "Connection",
  "nav.traffic": "Traffic",
  "nav.servers": "Servers",
  "nav.logs": "Logs",
  "nav.settings": "Settings",

  "nav.rules": "Rules",

  "rules.title": "Routing rules",
  "rules.intro":
    "Every connection states its destination in the SOCKS5 request this app forwards, so it can be refused before any traffic moves. The first matching rule wins; anything unmatched is allowed.",
  "rules.blockedCount": "blocked this session",
  "rules.pattern": "Pattern",
  "rules.action": "Action",
  "rules.note": "Note",
  "rules.notePlaceholder": "why this is here",
  "rules.block": "Block",
  "rules.allow": "Allow",
  "rules.add": "Add a rule",
  "rules.enable": "Enable",
  "rules.disable": "Disable",
  "rules.moveUp": "Move up",
  "rules.moveDown": "Move down",
  "rules.unsaved": "Unsaved changes",
  "rules.orderHint":
    "`example.com` exactly · `*.example.com` its subdomains · `*` everything",
  "rules.emptyTitle": "No rules yet",
  "rules.emptyBody":
    "Add a pattern to refuse connections to it, or start from a list below. Rules apply to the running tunnel straight away — no reconnect.",
  "rules.starterTrackers": "Block common trackers",
  "rules.starterLocal": "Block local addresses",

  "traffic.history": "Past sessions",
  "traffic.noHistory": "No finished sessions yet.",
  "traffic.clearHistory": "Clear",
  "traffic.blockedTile": "Blocked",

  "traffic.lastMinute": "last 60 s",
  "traffic.received": "received",
  "traffic.sent": "sent",
  "traffic.online": "online",
  "traffic.peakDown": "Peak download",
  "traffic.peakUp": "Peak upload",
  "traffic.avgDown": "Average down",
  "traffic.avgUp": "Average up",
  "traffic.destinations": "Destinations",
  "traffic.sessionConns": "Connections",
  "traffic.topHosts": "Top destinations",
  "traffic.noHosts": "No destinations recorded yet.",
  "traffic.liveConnections": "Live connections",
  "traffic.noConnections": "Nothing open right now.",
  "traffic.unknownHost": "unnamed",
  "traffic.emptyTitle": "No traffic yet",
  "traffic.emptyBody":
    "Connect and send something through the proxy. Destinations are read from the SOCKS5 requests passing through this app and never leave the device.",

  "state.disconnected": "Not connected",
  "state.starting": "Starting",
  "state.connecting": "Connecting",
  "state.connected": "Connected",
  "state.reconnecting": "Reconnecting",
  "state.error": "Failed",

  "conn.server": "Server",
  "conn.connect": "Connect",
  "conn.disconnect": "Disconnect",
  "conn.via": "via",
  "conn.proxyOn": "SOCKS5 proxy on",
  "conn.signInAs": "sign in as",
  "conn.download": "Download",
  "conn.upload": "Upload",
  "conn.total": "total",
  "conn.openConnections": "Open connections",
  "conn.localPort": "Local port",
  "conn.pinning": "Pinning",
  "conn.congestion": "Congestion",
  "conn.on": "On",
  "conn.off": "Off",
  "conn.emptyTitle": "No servers yet",
  "conn.emptyBody":
    "Add the domain, certificate and proxy credentials your server printed when it was installed.",
  "conn.addServer": "Add a server",

  "profiles.title": "Servers",
  "profiles.add": "Add server",
  "profiles.none": "Nothing here yet.",
  "profiles.edit": "Edit",
  "profiles.delete": "Delete",
  "profiles.confirm": "Confirm",
  "profiles.notPinned": "not pinned",
  "profiles.newTitle": "New server",
  "profiles.editTitle": "Edit server",
  "profiles.name": "Name",
  "profiles.namePlaceholder": "Home server",
  "profiles.domain": "Tunnel domain",
  "profiles.domainHint": "The delegated zone your server answers for.",
  "profiles.resolver": "Resolver",
  "profiles.resolverHint":
    "Where queries are sent — your provider's resolver, or the server itself.",
  "profiles.port": "Local SOCKS5 port",
  "profiles.username": "Proxy username",
  "profiles.password": "Proxy password",
  "profiles.cert": "Server certificate",
  "profiles.certChoose": "Choose file…",
  "profiles.certPastePlaceholder": "-----BEGIN CERTIFICATE-----\n…paste the contents of cert.pem here…\n-----END CERTIFICATE-----",
  "profiles.certPinned": "Pinned",
  "profiles.certNotPinned": "Not pinned",
  "profiles.certRemove": "Remove",
  "profiles.certHint":
    "Paste the certificate directly, or choose the cert.pem file. Without one the server is not verified, so anyone able to answer your DNS queries can impersonate it.",
  "profiles.cancel": "Cancel",
  "profiles.save": "Save",
  "profiles.tuning": "Performance",
  "profiles.tuningHint":
    "Passed straight to the tunnel engine. The defaults suit most links; change them if throughput disappoints.",
  "profiles.congestion": "Congestion control",
  "profiles.congestionBbr": "BBR — faster on lossy links",
  "profiles.congestionCubic": "dCUBIC — cautious, loss-based",
  "profiles.congestionHint":
    "BBR paces to the bandwidth and round-trip time it measures. dCUBIC treats any loss as congestion and backs off, which costs speed over DNS, where loss is normal.",
  "profiles.gso": "Segmentation offload (GSO)",
  "profiles.gsoHint":
    "Lets the kernel split one large UDP write into many packets, so far fewer system calls carry the same traffic. A clear win where it is supported; unsupported systems log a warning and carry on.",
  "profiles.keepAlive": "Keep-alive (ms)",
  "profiles.keepAliveHint":
    "How often the tunnel pings to hold NAT and resolver state open. Lower survives aggressive networks, at the cost of idle traffic.",
  "profiles.authoritative": "Authoritative server",
  "profiles.authoritativeHint":
    "Query this address directly instead of going through a recursive resolver — faster, but far more conspicuous. Leave empty to use the resolver above.",
  "profiles.optional": "optional",

  "logs.all": "All",
  "logs.info": "Info",
  "logs.warn": "Warn",
  "logs.error": "Error",
  "logs.lines": "lines",
  "logs.clear": "Clear",
  "logs.none": "Nothing logged yet.",
  "logs.jump": "Jump to latest",

  "settings.behaviour": "Behaviour",
  "settings.connectOnLaunch": "Connect on launch",
  "settings.connectOnLaunchSub": "Start the last used server when the app opens.",
  "settings.autoReconnect": "Reconnect automatically",
  "settings.autoReconnectSub": "The tunnel retries on its own after a drop.",
  "settings.tray": "Close to tray",
  "settings.traySub": "Closing the window leaves the tunnel running.",
  "settings.killSwitch": "Kill switch",
  "settings.killSwitchSub":
    "Blocks this app's SOCKS5 proxy port whenever the tunnel is not connected, instead of leaving it open with nowhere to send traffic. Scoped to this app, not the whole system.",
  "settings.appearance": "Appearance",
  "settings.theme": "Theme",
  "settings.themeSystem": "Match system",
  "settings.themeDark": "Dark gray",
  "settings.themeLight": "Light",
  "settings.themeBlue": "Blue",
  "settings.language": "Language",
  "settings.languageSystem": "Match system",
  "settings.wallpaper": "Wallpaper",
  "settings.wallpaperHint": "Shows behind the translucent panels, in whichever theme is active.",
  "settings.wallpaperChoose": "Choose image…",
  "settings.wallpaperRemove": "Remove",
  "settings.wallpaperNone": "No wallpaper set",
  "settings.wallpaperDim": "Dim",
  "settings.wallpaperDimHint": "Fades the image so text stays readable over a bright photo.",
  "settings.wallpaperBlur": "Blur",
  "settings.wallpaperBlurHint": "Softens a busy image so it stops competing with the interface.",
  "settings.animations": "Animations",
  "settings.animationsSub": "Graphs and bars ease between values instead of snapping.",
  "settings.network": "Network",
  "settings.systemProxy": "Set the system proxy",
  "settings.systemProxySub":
    "Points this computer's proxy setting at the tunnel while connected, and restores it on disconnect, so applications need no setup of their own. Software that ignores the system proxy still bypasses the tunnel — this is not a TUN device.",
  "settings.about": "About",
  "settings.aboutBody":
    "A client for the slipstream DNS tunnel. Traffic is exposed as a local SOCKS5 proxy; point applications at it to send them through the tunnel.",
  "settings.aboutPrivacy":
    "Servers, certificates and proxy credentials are stored on this device only.",
};

type Key = keyof typeof en;

const ru: Record<Key, string> = {
  "nav.connection": "Подключение",
  "nav.traffic": "Трафик",
  "nav.servers": "Серверы",
  "nav.logs": "Логи",
  "nav.settings": "Настройки",

  "nav.rules": "Правила",

  "rules.title": "Правила маршрутизации",
  "rules.intro":
    "Каждое соединение называет адрес назначения в SOCKS5-запросе, который это приложение и так пересылает, — значит его можно отклонить до того, как пойдёт трафик. Побеждает первое подошедшее правило; всё, что не совпало, разрешено.",
  "rules.blockedCount": "заблокировано за сессию",
  "rules.pattern": "Шаблон",
  "rules.action": "Действие",
  "rules.note": "Заметка",
  "rules.notePlaceholder": "зачем это здесь",
  "rules.block": "Блокировать",
  "rules.allow": "Разрешить",
  "rules.add": "Добавить правило",
  "rules.enable": "Включить",
  "rules.disable": "Выключить",
  "rules.moveUp": "Выше",
  "rules.moveDown": "Ниже",
  "rules.unsaved": "Есть несохранённые изменения",
  "rules.orderHint":
    "`example.com` точное совпадение · `*.example.com` его поддомены · `*` всё подряд",
  "rules.emptyTitle": "Правил пока нет",
  "rules.emptyBody":
    "Добавьте шаблон, чтобы отклонять соединения к нему, или начните с готового списка ниже. Правила применяются к уже поднятому туннелю сразу — переподключаться не нужно.",
  "rules.starterTrackers": "Блокировать частые трекеры",
  "rules.starterLocal": "Блокировать локальные адреса",

  "traffic.history": "Прошлые сессии",
  "traffic.noHistory": "Завершённых сессий пока нет.",
  "traffic.clearHistory": "Очистить",
  "traffic.blockedTile": "Заблокировано",

  "traffic.lastMinute": "последние 60 с",
  "traffic.received": "принято",
  "traffic.sent": "отдано",
  "traffic.online": "на связи",
  "traffic.peakDown": "Пик приёма",
  "traffic.peakUp": "Пик отдачи",
  "traffic.avgDown": "Средний приём",
  "traffic.avgUp": "Средняя отдача",
  "traffic.destinations": "Направлений",
  "traffic.sessionConns": "Соединений",
  "traffic.topHosts": "Куда идёт трафик",
  "traffic.noHosts": "Направлений пока нет.",
  "traffic.liveConnections": "Активные соединения",
  "traffic.noConnections": "Сейчас ничего не открыто.",
  "traffic.unknownHost": "без имени",
  "traffic.emptyTitle": "Трафика пока нет",
  "traffic.emptyBody":
    "Подключитесь и пропустите что-нибудь через прокси. Направления читаются из SOCKS5-запросов, проходящих через это приложение, и никуда не отправляются.",

  "state.disconnected": "Не подключено",
  "state.starting": "Запуск",
  "state.connecting": "Подключение",
  "state.connected": "Подключено",
  "state.reconnecting": "Переподключение",
  "state.error": "Ошибка",

  "conn.server": "Сервер",
  "conn.connect": "Подключиться",
  "conn.disconnect": "Отключиться",
  "conn.via": "через",
  "conn.proxyOn": "SOCKS5-прокси на",
  "conn.signInAs": "логин",
  "conn.download": "Приём",
  "conn.upload": "Отдача",
  "conn.total": "всего",
  "conn.openConnections": "Открытых соединений",
  "conn.localPort": "Локальный порт",
  "conn.pinning": "Пиннинг",
  "conn.congestion": "Перегрузка",
  "conn.on": "Вкл",
  "conn.off": "Выкл",
  "conn.emptyTitle": "Серверов пока нет",
  "conn.emptyBody":
    "Добавьте домен, сертификат и данные прокси, которые ваш сервер напечатал при установке.",
  "conn.addServer": "Добавить сервер",

  "profiles.title": "Серверы",
  "profiles.add": "Добавить сервер",
  "profiles.none": "Пока пусто.",
  "profiles.edit": "Изменить",
  "profiles.delete": "Удалить",
  "profiles.confirm": "Подтвердить",
  "profiles.notPinned": "без пиннинга",
  "profiles.newTitle": "Новый сервер",
  "profiles.editTitle": "Изменить сервер",
  "profiles.name": "Название",
  "profiles.namePlaceholder": "Домашний сервер",
  "profiles.domain": "Домен туннеля",
  "profiles.domainHint": "Делегированная зона, за которую отвечает ваш сервер.",
  "profiles.resolver": "Резолвер",
  "profiles.resolverHint":
    "Куда уходят запросы — резолвер провайдера или сам сервер.",
  "profiles.port": "Локальный порт SOCKS5",
  "profiles.username": "Логин прокси",
  "profiles.password": "Пароль прокси",
  "profiles.cert": "Сертификат сервера",
  "profiles.certChoose": "Выбрать файл…",
  "profiles.certPastePlaceholder": "-----BEGIN CERTIFICATE-----\n…вставьте сюда содержимое cert.pem…\n-----END CERTIFICATE-----",
  "profiles.certPinned": "Закреплён",
  "profiles.certNotPinned": "Не закреплён",
  "profiles.certRemove": "Убрать",
  "profiles.certHint":
    "Вставьте сертификат прямо сюда или выберите файл cert.pem. Без него сервер не проверяется, поэтому выдать себя за него сможет любой, кто отвечает на ваши DNS-запросы.",
  "profiles.cancel": "Отмена",
  "profiles.save": "Сохранить",
  "profiles.tuning": "Производительность",
  "profiles.tuningHint":
    "Передаётся движку туннеля как есть. Значения по умолчанию подходят почти везде; меняйте, если не устраивает скорость.",
  "profiles.congestion": "Контроль перегрузки",
  "profiles.congestionBbr": "BBR — быстрее на каналах с потерями",
  "profiles.congestionCubic": "dCUBIC — осторожный, по потерям",
  "profiles.congestionHint":
    "BBR подстраивается под измеренную полосу и задержку. dCUBIC считает любую потерю признаком перегрузки и сбавляет скорость — а в DNS-туннеле потери это норма, так что это стоит скорости.",
  "profiles.gso": "Разгрузка сегментации (GSO)",
  "profiles.gsoHint":
    "Позволяет ядру разбивать одну большую UDP-запись на много пакетов — тот же трафик уходит за куда меньшее число системных вызовов. Там, где поддерживается, даёт заметный прирост; где нет — просто предупреждение в логе.",
  "profiles.keepAlive": "Keep-alive (мс)",
  "profiles.keepAliveHint":
    "Как часто туннель напоминает о себе, чтобы NAT и резолвер не забыли о соединении. Меньше — надёжнее на агрессивных сетях, но появляется трафик на холостом ходу.",
  "profiles.authoritative": "Авторитативный сервер",
  "profiles.authoritativeHint":
    "Спрашивать этот адрес напрямую, минуя рекурсивный резолвер — быстрее, но гораздо заметнее. Пусто — использовать резолвер выше.",
  "profiles.optional": "необязательно",

  "logs.all": "Все",
  "logs.info": "Инфо",
  "logs.warn": "Предупр.",
  "logs.error": "Ошибки",
  "logs.lines": "строк",
  "logs.clear": "Очистить",
  "logs.none": "Логов пока нет.",
  "logs.jump": "К последним",

  "settings.behaviour": "Поведение",
  "settings.connectOnLaunch": "Подключаться при запуске",
  "settings.connectOnLaunchSub": "Открывать последний использованный сервер при старте.",
  "settings.autoReconnect": "Переподключаться автоматически",
  "settings.autoReconnectSub": "Туннель сам восстанавливается после обрыва.",
  "settings.tray": "Закрывать в трей",
  "settings.traySub": "Закрытие окна не останавливает туннель.",
  "settings.killSwitch": "Kill switch",
  "settings.killSwitchSub":
    "Блокирует порт SOCKS5-прокси этого приложения, пока туннель не подключён, вместо того чтобы держать его открытым в никуда. Действует только на это приложение, не на всю систему.",
  "settings.appearance": "Оформление",
  "settings.theme": "Тема",
  "settings.themeSystem": "Как в системе",
  "settings.themeDark": "Тёмно-серая",
  "settings.themeLight": "Светлая",
  "settings.themeBlue": "Синяя",
  "settings.language": "Язык",
  "settings.languageSystem": "Как в системе",
  "settings.wallpaper": "Обои",
  "settings.wallpaperHint": "Видны сквозь полупрозрачные панели в любой теме.",
  "settings.wallpaperChoose": "Выбрать изображение…",
  "settings.wallpaperRemove": "Убрать",
  "settings.wallpaperNone": "Обои не заданы",
  "settings.wallpaperDim": "Затемнение",
  "settings.wallpaperDimHint": "Приглушает картинку, чтобы текст читался поверх светлого фото.",
  "settings.wallpaperBlur": "Размытие",
  "settings.wallpaperBlurHint": "Смягчает пёструю картинку, чтобы она не спорила с интерфейсом.",
  "settings.animations": "Анимации",
  "settings.animationsSub": "Графики и полосы плавно перетекают между значениями, а не прыгают.",
  "settings.network": "Сеть",
  "settings.systemProxy": "Ставить системный прокси",
  "settings.systemProxySub":
    "Пока туннель поднят, направляет системную настройку прокси на него, а при отключении возвращает как было — приложениям ничего настраивать не нужно. Программы, игнорирующие системный прокси, всё равно пойдут мимо: это не TUN-устройство.",
  "settings.about": "О программе",
  "settings.aboutBody":
    "Клиент DNS-туннеля slipstream. Туннель доступен как локальный SOCKS5-прокси — укажите его приложениям, чтобы отправить их трафик через туннель.",
  "settings.aboutPrivacy":
    "Серверы, сертификаты и данные прокси хранятся только на этом устройстве.",
};

const TABLES: Record<"en" | "ru", Partial<Record<Key, string>>> = { en, ru };

function detect(): "en" | "ru" {
  const tags = typeof navigator === "undefined" ? [] : (navigator.languages ?? [navigator.language]);
  return tags.some((tag) => tag?.toLowerCase().startsWith("ru")) ? "ru" : "en";
}

let language = $state<Language>("system");

export function setLanguage(next: Language) {
  language = next;
}

/** The language actually in use, with "system" already resolved. */
export function resolved(): "en" | "ru" {
  return language === "system" ? detect() : language;
}

export function t(key: Key): string {
  return TABLES[resolved()][key] ?? en[key];
}

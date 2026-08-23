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
  "nav.servers": "Servers",
  "nav.logs": "Logs",
  "nav.settings": "Settings",

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
  "profiles.certChoose": "Choose cert.pem…",
  "profiles.certPinned": "Pinned",
  "profiles.certNotPinned": "Not pinned",
  "profiles.certRemove": "Remove",
  "profiles.certHint":
    "Without a certificate the server is not verified, so anyone able to answer your DNS queries can impersonate it.",
  "profiles.cancel": "Cancel",
  "profiles.save": "Save",

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
  "settings.appearance": "Appearance",
  "settings.theme": "Theme",
  "settings.themeSystem": "Match system",
  "settings.themeDark": "Dark",
  "settings.themeLight": "Light",
  "settings.language": "Language",
  "settings.languageSystem": "Match system",
  "settings.about": "About",
  "settings.aboutBody":
    "A client for the slipstream DNS tunnel. Traffic is exposed as a local SOCKS5 proxy; point applications at it to send them through the tunnel.",
  "settings.aboutPrivacy":
    "Servers, certificates and proxy credentials are stored on this device only.",
};

type Key = keyof typeof en;

const ru: Record<Key, string> = {
  "nav.connection": "Подключение",
  "nav.servers": "Серверы",
  "nav.logs": "Логи",
  "nav.settings": "Настройки",

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
  "profiles.certChoose": "Выбрать cert.pem…",
  "profiles.certPinned": "Закреплён",
  "profiles.certNotPinned": "Не закреплён",
  "profiles.certRemove": "Убрать",
  "profiles.certHint":
    "Без сертификата сервер не проверяется, поэтому выдать себя за него сможет любой, кто отвечает на ваши DNS-запросы.",
  "profiles.cancel": "Отмена",
  "profiles.save": "Сохранить",

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
  "settings.appearance": "Оформление",
  "settings.theme": "Тема",
  "settings.themeSystem": "Как в системе",
  "settings.themeDark": "Тёмная",
  "settings.themeLight": "Светлая",
  "settings.language": "Язык",
  "settings.languageSystem": "Как в системе",
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

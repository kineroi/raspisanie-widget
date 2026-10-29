/**
 * «Моё расписание» → Google Диск: кладёт документы для аттестации в папку «Аттестация»
 * (ту же, что программа на компьютере) и сама создаёт подпапки по разделам и уровням.
 *
 * Как подключить (один раз):
 * 1. Откройте https://script.google.com → «Новый проект», удалите всё в редакторе и вставьте этот текст.
 * 2. «Развернуть» → «Новое развертывание» → значок шестерёнки → «Веб-приложение».
 *    Запуск от имени: «Я». У кого есть доступ: «Все». Нажмите «Развернуть» и разрешите доступ к Диску
 *    (Google предупредит, что приложение не проверено: «Дополнительные настройки» → «Перейти»).
 * 3. Скопируйте «URL веб-приложения» (заканчивается на /exec) и вставьте его в приложение на телефоне.
 *
 * Скрипт работает от вашего имени и видит только то, что ему присылает ваш телефон.
 * При первом подключении он запоминает пароль телефона и дальше принимает файлы только с ним.
 * Чтобы подключить другой телефон: «Настройки проекта» → «Свойства скрипта» → удалите SECRET.
 */

function doPost(e) {
  try {
    var req = JSON.parse(e.postData.contents);
    var secret = String(req.secret || '');
    if (secret.length < 16) return reply({ ok: false, error: 'secret' });
    var props = PropertiesService.getScriptProperties();
    var saved = props.getProperty('SECRET');
    if (!saved) props.setProperty('SECRET', secret);
    else if (saved !== secret) return reply({ ok: false, error: 'secret' });

    var rootName = clean(req.root) || 'Аттестация';
    if (req.action === 'ping') {
      return reply({ ok: true, root: rootName });
    }
    if (req.action === 'save') {
      var lock = LockService.getScriptLock();
      lock.waitLock(30000);
      try {
        var dir = folder(DriveApp.getRootFolder(), rootName);
        var path = [rootName];
        (req.parts || []).forEach(function (p) {
          dir = folder(dir, clean(p));
          path.push(clean(p));
        });
        var name = unique(dir, clean(req.name) || 'Документ');
        var blob = Utilities.newBlob(Utilities.base64Decode(req.data), req.mime || 'application/octet-stream', name);
        var file = dir.createFile(blob);
        path.push(name);
        return reply({ ok: true, path: path.join(' / '), url: file.getUrl() });
      } finally {
        lock.releaseLock();
      }
    }
    return reply({ ok: false, error: 'неизвестное действие' });
  } catch (err) {
    return reply({ ok: false, error: String(err && err.message || err) });
  }
}

/** Папка с таким именем внутри parent (не из корзины) или новая. */
function folder(parent, name) {
  var it = parent.getFoldersByName(name);
  while (it.hasNext()) {
    var f = it.next();
    if (!f.isTrashed()) return f;
  }
  return parent.createFolder(name);
}

/** «файл.pdf» → «файл (2).pdf», если имя уже занято. */
function unique(dir, name) {
  if (!exists(dir, name)) return name;
  var dot = name.lastIndexOf('.');
  var base = dot > 0 ? name.slice(0, dot) : name;
  var ext = dot > 0 ? name.slice(dot) : '';
  for (var i = 2; ; i++) {
    var n = base + ' (' + i + ')' + ext;
    if (!exists(dir, n)) return n;
  }
}

function exists(dir, name) {
  var it = dir.getFilesByName(name);
  while (it.hasNext()) if (!it.next().isTrashed()) return true;
  return false;
}

function clean(s) {
  return String(s == null ? '' : s).replace(/[<>:"\/\\|?*\u0000-\u001f]/g, ' ').replace(/\s+/g, ' ').trim();
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

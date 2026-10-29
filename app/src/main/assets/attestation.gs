/**
 * «Моё расписание» → Google Диск: кладёт документы для аттестации в папку «Аттестация»
 * (ту же, что программа на компьютере) и сама создаёт подпапки по разделам и уровням.
 * Ещё хранит общий журнал посещаемости («Моё расписание / посещаемость.json») для телефона и компьютера.
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
    if (req.action === 'journal-get') {
      var jf = journalFile(req);
      var text = jf ? jf.getBlob().getDataAsString('UTF-8') : '';
      return reply({ ok: true, data: text, etag: digest(text) });
    }
    if (req.action === 'journal-put') {
      var jlock = LockService.getScriptLock();
      jlock.waitLock(30000);
      try {
        var cur = journalFile(req);
        var curText = cur ? cur.getBlob().getDataAsString('UTF-8') : '';
        // журнал успели изменить с другого устройства — телефон заберёт новый и объединит
        if (digest(curText) !== String(req.etag || '')) return reply({ ok: false, error: 'conflict' });
        var data = String(req.data || '');
        if (cur) cur.setContent(data);
        else folder(DriveApp.getRootFolder(), clean(req.folder) || 'Моё расписание')
          .createFile(clean(req.name) || 'посещаемость.json', data, 'application/json');
        return reply({ ok: true, etag: digest(data) });
      } finally {
        jlock.releaseLock();
      }
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

/** Файл журнала посещаемости (самый свежий, если их несколько) или null. */
function journalFile(req) {
  var dir = folder(DriveApp.getRootFolder(), clean(req.folder) || 'Моё расписание');
  var it = dir.getFilesByName(clean(req.name) || 'посещаемость.json');
  var best = null;
  while (it.hasNext()) {
    var f = it.next();
    if (!f.isTrashed() && (!best || f.getLastUpdated() > best.getLastUpdated())) best = f;
  }
  return best;
}

/** Отпечаток содержимого: пустой файл — пустая строка. */
function digest(text) {
  if (!text) return '';
  return Utilities.base64Encode(Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, text, Utilities.Charset.UTF_8));
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

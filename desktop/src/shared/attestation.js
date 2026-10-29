'use strict';
// Аттестация: разделы папки, распознанный нейросетью документ → папка и имя файла. Без Electron, чтобы проверять тестами.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Attestation = factory();
}(typeof self !== 'undefined' ? self : this, () => {
  /** levels — есть ли внутри папки по уровням; word — вид документа, если нейросеть его не назвала. */
  const SECTIONS = [
    { id: 'courses', folder: '1. Курсы повышения квалификации', name: 'Курсы повышения квалификации', word: 'Сертификат', levels: false },
    { id: 'awards', folder: '2. Грамоты и благодарности', name: 'Грамоты и благодарности', word: 'Грамота', levels: true },
    { id: 'contests', folder: '3. Конкурсы педагога', name: 'Конкурсы педагога', word: 'Диплом', levels: true },
    { id: 'students', folder: '4. Достижения учеников', name: 'Достижения учеников', word: 'Диплом ученика', levels: true },
    { id: 'lessons', folder: '5. Открытые уроки и выступления', name: 'Открытые уроки и выступления', word: 'Сертификат', levels: false },
    { id: 'publications', folder: '6. Публикации и методические разработки', name: 'Публикации и методические разработки', word: 'Публикация', levels: false },
    { id: 'ozp', folder: '7. ОЗП и категория', name: 'ОЗП и категория', word: 'Документ', levels: false },
    { id: 'other', folder: '8. Другое', name: 'Другое', word: 'Документ', levels: false },
  ];

  /** Республиканский и международный уровни лежат в одной папке. */
  const LEVELS = [
    { id: 'school', folder: 'Школьный', name: 'Школьный' },
    { id: 'district', folder: 'Районный', name: 'Районный' },
    { id: 'region', folder: 'Областной', name: 'Областной' },
    { id: 'national', folder: 'Республиканский и международный', name: 'Республиканский' },
    { id: 'international', folder: 'Республиканский и международный', name: 'Международный' },
  ];

  const ROOT_FOLDER = 'Аттестация';
  /** Что можно распознать: картинки и PDF уходят в нейросеть как есть, docx — текстом. */
  const EXTENSIONS = ['pdf', 'jpg', 'jpeg', 'png', 'webp', 'docx'];

  const SYSTEM =
    'Ты помогаешь учителю школы в Казахстане собрать документы для аттестации педагога. ' +
    'Тебе дают один документ (грамоту, благодарственное письмо, сертификат, диплом, приказ и т.п.) — на русском, казахском или английском.\n' +
    'Верни ТОЛЬКО объект JSON без пояснений:\n' +
    '{"section": один из: "courses" (курсы повышения квалификации, обучение, семинары с сертификатом об обучении), ' +
    '"awards" (грамоты, благодарности, благодарственные письма самому педагогу), ' +
    '"contests" (дипломы и сертификаты педагога за участие или победу в конкурсах, олимпиадах для учителей), ' +
    '"students" (достижения учеников: дипломы учеников, а также документы педагогу за подготовку победителя или призёра), ' +
    '"lessons" (открытые уроки, мастер-классы, выступления на семинарах и конференциях), ' +
    '"publications" (публикации, статьи, методические разработки, свидетельства о публикации), ' +
    '"ozp" (оценка знаний педагогов, сертификаты ОЗП/НКТ, приказы о присвоении категории), ' +
    '"other" (всё остальное),\n' +
    ' "level": "school", "district", "region", "national", "international" или "" — уровень мероприятия или того, кто выдал ' +
    '(школа — school; район, районный отдел образования — district; область, управление образования области — region; ' +
    'министерство, республиканский — national; международный — international),\n' +
    ' "date": "дата выдачи в формате ГГГГ-ММ-ДД или пусто, если её нет",\n' +
    ' "kind": "вид документа по-русски одним-тремя словами: Грамота, Благодарственное письмо, Сертификат, Диплом I степени и т.п.",\n' +
    ' "title": "за что или какое мероприятие, по-русски, коротко — до 8 слов, без кавычек",\n' +
    ' "issuer": "кто выдал, коротко"}\n' +
    'Если полной даты в документе нет, оставь "date" пустым. Ничего не придумывай.';

  const sectionById = (id) => SECTIONS.find((s) => s.id === id) || SECTIONS[SECTIONS.length - 1];
  const levelById = (id) => LEVELS.find((l) => l.id === id) || null;

  const clean = (s) => String(s == null ? '' : s).replace(/[«»"]/g, '').replace(/\s+/g, ' ').trim();

  /** Ответ нейросети → поля документа (неизвестное заменяется на безопасное). */
  function parseClassification(obj) {
    const o = obj || {};
    const section = sectionById(String(o.section || '')).id;
    const level = levelById(String(o.level || '')) ? String(o.level) : '';
    const date = /^\d{4}-\d{2}-\d{2}$/.test(String(o.date || '')) ? String(o.date) : '';
    const kind = clean(o.kind) || sectionById(section).word;
    return { section, level, date, kind, title: clean(o.title), issuer: clean(o.issuer) };
  }

  /** Символы, которые нельзя использовать в именах файлов Windows. */
  const safe = (s) => String(s).replace(/[<>:"/\\|?*\u0000-\u001f]/g, ' ').replace(/\s+/g, ' ').replace(/[ .]+$/, '').trim();

  /**
   * Папка (части пути внутри «Аттестации») и имя файла.
   * Пример: ['2. Грамоты и благодарности', 'Районный'], '2026-10-15 Грамота — Лучший урок.pdf'.
   */
  function target(meta, ext) {
    const s = sectionById(meta.section);
    const parts = [s.folder];
    if (s.levels) {
      const l = levelById(meta.level);
      parts.push(l ? l.folder : 'Уровень не указан');
    }
    const kind = safe(meta.kind) || s.word;
    const title = safe(meta.title);
    let name = `${meta.date || 'Без даты'} ${kind}${title ? ' — ' + title : ''}`;
    if (name.length > 120) name = name.slice(0, 120).trim();
    return { parts, name: name + String(ext || '').toLowerCase() };
  }

  /** «файл.pdf» → «файл (2).pdf», если имя уже занято. exists(name) → true/false. */
  function uniqueName(name, exists) {
    if (!exists(name)) return name;
    const dot = name.lastIndexOf('.');
    const base = dot > 0 ? name.slice(0, dot) : name;
    const ext = dot > 0 ? name.slice(dot) : '';
    for (let i = 2; ; i++) {
      const n = `${base} (${i})${ext}`;
      if (!exists(n)) return n;
    }
  }

  return { SECTIONS, LEVELS, ROOT_FOLDER, EXTENSIONS, SYSTEM, sectionById, levelById, parseClassification, target, uniqueName };
}));

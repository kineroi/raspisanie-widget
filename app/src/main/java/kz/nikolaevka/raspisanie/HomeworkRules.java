package kz.nikolaevka.raspisanie;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запасной способ «расписать» домашку без интернета и без ИИ:
 * раскрывает привычные сокращения и делает текст на русском и английском.
 * Пример: «стр 45 упр 3 письм, сл выуч» →
 *   Задание: страница 45, упражнение 3 (письменно); выучить слова.
 *   Task: page 45, exercise 3 (in writing); learn the words.
 */
final class HomeworkRules {

    /** {регулярное выражение, по-русски, по-английски}. Порядок важен: длинные раньше коротких. */
    private static final String[][] RULES = {
            // Частые сочетания — сразу в правильном порядке слов
            {"(?:сл\\.?|слова|слов)\\s+выуч\\w*|выуч\\w*\\s+(?:сл\\.?|слова|слов)", "выучить слова", "learn the words"},
            {"(?:правил\\w*)\\s+(?:повтор\\w*|выуч\\w*)|(?:повтор\\w*|выуч\\w*)\\s+правил\\w*",
                    "повторить правило", "revise the rule"},
            {"текст\\w*\\s+(?:прочит\\w*|чит\\w*)|(?:прочит\\w*|читать)\\s+текст\\w*", "прочитать текст", "read the text"},
            {"раб\\.?\\s*тетр\\w*|р\\.\\s*т\\.?|рт", "рабочая тетрадь", "Workbook"},
            {"учебн\\w*|уч\\.?", "учебник", "Student's Book"},
            {"стр\\.?|с\\.", "страница", "page"},
            {"упр\\.?|у\\.", "упражнение", "exercise"},
            {"письм\\w*|п\\.", "письменно", "in writing"},
            {"устн\\w*", "устно", "orally"},
            {"выуч\\w*|учить", "выучить", "learn"},
            {"повтор\\w*", "повторить", "revise"},
            {"прочит\\w*|читать|чит\\.?", "прочитать", "read"},
            {"перевод\\w*|перев\\w*", "перевести", "translate"},
            {"пересказ\\w*", "пересказ", "retell"},
            {"сл\\.?|слова|слов", "слова", "the words"},
            {"текст\\w*", "текст", "the text"},
            {"правил\\w*", "правило", "the rule"},
            {"сочин\\w*|эссе", "сочинение", "essay"},
            {"проект\\w*", "проект", "project"},
            {"карточк\\w*", "карточка", "card"},
            {"наизусть", "наизусть", "by heart"},
            {"тест\\w*", "тест", "test"},
    };

    private static final Pattern[] PATTERNS = new Pattern[RULES.length];

    static {
        for (int i = 0; i < RULES.length; i++) {
            PATTERNS[i] = Pattern.compile("(?iuU)(?<![\\p{L}])(?:" + RULES[i][0] + ")(?![\\p{L}])");
        }
    }

    /** Двуязычный текст «Задание: … / Task: …». */
    static String expand(String note) {
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) return "";
        return "Задание: " + sentence(replace(n, 1)) + "\nTask: " + sentence(replace(n, 2));
    }

    private static String replace(String s, int lang) {
        // Сначала помечаем найденные слова, чтобы замены не задевали друг друга.
        List<String> found = new ArrayList<>();
        String out = s;
        for (int i = 0; i < RULES.length; i++) {
            Matcher m = PATTERNS[i].matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                found.add(RULES[i][lang]);
                m.appendReplacement(sb, "\u0000" + (found.size() - 1) + "\u0001");
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        for (int i = 0; i < found.size(); i++) {
            out = out.replace("\u0000" + i + "\u0001", found.get(i));
        }
        out = out.replaceAll("\\s+", " ").trim();
        // «письменно» и «устно» — в скобки после упражнения
        out = out.replaceAll("(?iuU)(exercise|упражнение) (\\d+[\\d\\-]*) ?,? ?(письменно|устно|in writing|orally)",
                "$1 $2 ($3)");
        // запятые: «учебник, страница 45, упражнение 3»
        out = out.replaceAll("(?iuU)(учебник|рабочая тетрадь|Student's Book|Workbook) (страница|page)", "$1, $2");
        out = out.replaceAll("(?iuU)(страница|page) (\\d+[\\d\\-]*) (упражнение|exercise)", "$1 $2, $3");
        return out;
    }

    private static String sentence(String s) {
        String t = s.trim();
        if (t.isEmpty()) return t;
        t = t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
        char last = t.charAt(t.length() - 1);
        if (last != '.' && last != '!' && last != '?') t = t + ".";
        return t;
    }

    private HomeworkRules() {
    }
}

package me.fekri.scrapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class App {

    public record Program(
            String title,
            Integer creditCount,
            String university,
            String location,
            String status,                 // e.g., "Application period not open"
            Integer firstTuitionFee,       // in SEK (numbers only) if found, else null
            Integer totalTuitionFee,       // in SEK (numbers only) if found, else null
            String period,
            String level,
            String languageOfInstruction,
            String applicationCode,
            String teachingForm,
            String paceOfStudy,
            String instructionalTime,
            String[] subjectAreas          // as requested: array of strings
    ) {}

    private static final String SOURCE_URL =
            "https://www.universityadmissions.se/intl/search?type=programs&advancedLevel=true&period=27&sortBy=creditAsc&subjects=120-&subjects=130-40-&subjects=130-180-&subjects=130-50-&numberOfFetchedPages=2";

    public static void main(String[] args) throws Exception {
        // 1) Fetch & parse
        Document doc = Jsoup.connect(SOURCE_URL)
                .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36")
                .referrer("https://www.google.com")
                .timeout(30_000)
                .get();

        List<Program> programs = parsePrograms(doc);
        System.out.println("Parsed programs: " + programs.size());

        // 2) Save to Postgres
        LocalDate today = LocalDate.now(); // Europe/Helsinki is your local TZ; this uses system default.
        saveToPostgres(programs, today);
        System.out.println("Done.");
    }

    // --- Scraping logic ---

    private static List<Program> parsePrograms(Document doc) {
        List<Program> list = new ArrayList<>();

        // Try common containers for cards; add/fine-tune as needed.
        Elements cards = new Elements();
        cards.addAll(doc.select("article, li, div"));
        cards = cards.select(":matchesOwn((?i)credits|application code|language of instruction|tuition fee)").parents();

        // If above is too restrictive, fall back to broader guesses for result cards:
        if (cards.isEmpty()) {
            cards = doc.select("article[class*=result], li[class*=result], div[class*=result], article, li, div");
        }

        // Deduplicate by using only elements that look like *program result blocks* (contain a title-like header).
        List<Element> resultBlocks = new ArrayList<>();
        for (Element el : cards) {
            if (!resultBlocks.contains(el) && looksLikeProgramBlock(el)) {
                resultBlocks.add(el);
            }
        }

        for (Element card : resultBlocks) {
            String title = pickFirstText(card,
                    "h3 a", "h3", "h2 a", "h2", "[data-e2e=program-name]", "a[aria-label]"
            );

            String providerLine = pickFirstText(card,
                    ".provider", ".university", ".institution", ".he-institution", ".hit-subtitle", "p:matches((?i)University|Institute|College)"
            );
            String university = providerLine;

            String location = pickFirstText(card,
                    ".location", "li:matches((?i)Location)", "p:matches((?i)Location)"
            );
            Integer creditCount = extractInt(pickFirstText(card, ":matches((?i)credits)"));

            String status = pickFirstText(card,
                    ":matches((?i)Application period not open|Application open|Closed|Opens|Closes)"
            );

            Integer firstTuitionFee = extractMoneyByLabel(card, "(?i)First tuition fee");
            Integer totalTuitionFee = extractMoneyByLabel(card, "(?i)Total tuition fee");

            String period = getByLabel(card, "(?i)Period");
            String level = coalesce(
                    getByLabel(card, "(?i)Level"),
                    pickFirstText(card, ":matches((?i)Master|Second-cycle|Advanced level)")
            );
            String language = coalesce(
                    getByLabel(card, "(?i)Language of instruction"),
                    getByLabel(card, "(?i)Language")
            );
            String applicationCode = getByLabel(card, "(?i)Application code");
            String teachingForm = getByLabel(card, "(?i)Teaching form");
            String paceOfStudy = getByLabel(card, "(?i)Pace of study");
            String instructionalTime = getByLabel(card, "(?i)Instructional time");

            String[] subjectAreas = splitSubjects(getByLabel(card, "(?i)Subject areas|Subject area|Subjects"));

            // If still missing, try scanning the entire card text by friendly label extraction.
            if (applicationCode == null || applicationCode.isBlank()) {
                applicationCode = scanByLabelInText(card.text(), "(?i)Application code\\s*[:：]\\s*(\\S+)");
            }
            if (creditCount == null) {
                creditCount = extractInt(scanByLabelInText(card.text(), "(?i)(\\d+)\\s*credits?"));
            }
            if (firstTuitionFee == null) firstTuitionFee = extractMoneyByRegex(card.text(), "(?i)First tuition fee\\s*[:：]?\\s*([\\d\\s.,]+)");
            if (totalTuitionFee == null) totalTuitionFee = extractMoneyByRegex(card.text(), "(?i)Total tuition fee\\s*[:：]?\\s*([\\d\\s.,]+)");

            // Cleanups
            title = safe(title);
            university = safe(university);
            location = safe(location);
            status = safe(status);
            period = safe(period);
            level = safe(level);
            language = safe(language);
            applicationCode = safe(applicationCode);
            teachingForm = safe(teachingForm);
            paceOfStudy = safe(paceOfStudy);
            instructionalTime = safe(instructionalTime);

            Program p = new Program(
                    title,
                    creditCount,
                    university,
                    location,
                    status,
                    firstTuitionFee,
                    totalTuitionFee,
                    period,
                    level,
                    language,
                    applicationCode,
                    teachingForm,
                    paceOfStudy,
                    instructionalTime,
                    subjectAreas
            );

            // Only add if we have at least a title or application code to identify the row
            if ((p.title != null && !p.title.isBlank()) || (p.applicationCode != null && !p.applicationCode.isBlank())) {
                list.add(p);
            }
        }

        return list;
    }

    private static boolean looksLikeProgramBlock(Element el) {
        String text = el.text();
        return text != null && text.matches("(?is).*\\b(credits?|Application code|Language of instruction|tuition fee|Master|Second-cycle|Advanced level)\\b.*");
    }

    private static String pickFirstText(Element root, String... selectors) {
        for (String sel : selectors) {
            Elements hits = root.select(sel);
            if (!hits.isEmpty()) {
                for (Element h : hits) {
                    String t = h.text();
                    if (t != null && !t.isBlank()) return t.trim();
                }
            }
        }
        return null;
    }

    private static String getByLabel(Element root, String labelRegex) {
        // Try <dl><dt>Label</dt><dd>Value</dd>
        for (Element dt : root.select("dt")) {
            if (dt.text().matches(labelRegex)) {
                Element dd = dt.nextElementSibling();
                if (dd != null && dd.tagName().equalsIgnoreCase("dd")) {
                    String t = dd.text();
                    if (t != null && !t.isBlank()) return t.trim();
                }
            }
        }
        // Try generic "Label: Value" in <li> or <p>
        for (Element el : root.select("li, p, div")) {
            String t = el.text();
            if (t != null && t.matches("(?is).*" + labelRegex + ".*")) {
                String val = t.replaceAll("(?is).*" + labelRegex + "\\s*[:：]?\\s*", "");
                val = val.replaceAll("\\s*\\|.*$", ""); // cut at separators
                if (!val.isBlank()) return val.trim();
            }
        }
        return null;
    }

    private static String scanByLabelInText(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private static Integer extractInt(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("(\\d{1,5})").matcher(s.replaceAll("\\s+", ""));
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return null;
    }

    private static Integer extractMoneyByLabel(Element root, String labelRegex) {
        String text = getByLabel(root, labelRegex);
        return extractMoneyByRegex(text, "([\\d\\s.,]+)");
    }

    private static Integer extractMoneyByRegex(String text, String valueRegex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(valueRegex).matcher(text);
        if (m.find()) {
            String num = m.group(1).replaceAll("[^\\d]", "");
            if (!num.isBlank()) {
                try {
                    return Integer.parseInt(num);
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private static String[] splitSubjects(String s) {
        if (s == null || s.isBlank()) return new String[0];
        // split by comma or " • " or "/" etc.
        String[] parts = s.split("\\s*[•,|/；;]\\s*");
        List<String> cleaned = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isBlank()) cleaned.add(t);
        }
        return cleaned.toArray(new String[0]);
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.replace('\u00A0', ' ').trim();
        return t.isBlank() ? null : t;
    }

    // --- Database logic ---

    private static void saveToPostgres(List<Program> programs, LocalDate date) throws SQLException {
        if (programs.isEmpty()) {
            System.out.println("No programs parsed. Creating an empty table anyway.");
        }

        String tableName = "programs_" + date; // YYYY-MM-DD
        String quoted = "\"" + tableName + "\""; // because '-' needs quoting in identifiers

        String url = "jdbc:postgresql://localhost:5432/se_edu";
        // no password:
        try (Connection conn = DriverManager.getConnection(url, null, null)) {
            conn.setAutoCommit(false);

            // Create table if not exists (use dynamic SQL with quoted name)
            String createSql = """
                    CREATE TABLE IF NOT EXISTS %s (
                        id SERIAL PRIMARY KEY,
                        title TEXT,
                        credit_count INT,
                        university TEXT,
                        location TEXT,
                        status TEXT,
                        first_tuition_fee INT,
                        total_tuition_fee INT,
                        period TEXT,
                        level TEXT,
                        language_of_instruction TEXT,
                        application_code TEXT,
                        teaching_form TEXT,
                        pace_of_study TEXT,
                        instructional_time TEXT,
                        subject_areas TEXT[]
                    );
                    """.formatted(quoted);

            try (Statement st = conn.createStatement()) {
                st.execute(createSql);
            }

            String insertSql = """
                    INSERT INTO %s
                    (title, credit_count, university, location, status, first_tuition_fee, total_tuition_fee,
                     period, level, language_of_instruction, application_code, teaching_form,
                     pace_of_study, instructional_time, subject_areas)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """.formatted(quoted);

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (Program p : programs) {
                    ps.setString(1, p.title());
                    if (p.creditCount() == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, p.creditCount());
                    ps.setString(3, p.university());
                    ps.setString(4, p.location());
                    ps.setString(5, p.status());
                    if (p.firstTuitionFee() == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, p.firstTuitionFee());
                    if (p.totalTuitionFee() == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, p.totalTuitionFee());
                    ps.setString(8, p.period());
                    ps.setString(9, p.level());
                    ps.setString(10, p.languageOfInstruction());
                    ps.setString(11, p.applicationCode());
                    ps.setString(12, p.teachingForm());
                    ps.setString(13, p.paceOfStudy());
                    ps.setString(14, p.instructionalTime());

                    Array arr = conn.createArrayOf("text", p.subjectAreas() == null ? new String[0] : p.subjectAreas());
                    ps.setArray(15, arr);

                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
        }
    }

    private static String coalesce(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return (second != null && !second.isBlank()) ? second : null;
    }
}
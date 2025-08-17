package me.fekri.scrapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    ) {
        public void enterListIfEligible(List<Program> list) {
            if ((this.title != null && !this.title.isBlank())
                    || (this.applicationCode != null && !this.applicationCode.isBlank())) {
                list.add(this);
            }
        }
    }

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
        cards.addAll(doc.select(".searchresultcard"));

        Pattern headerInfoFirstLinePattern = Pattern.compile("(\\d+) Credits, (.+), Location: (.+)");

        for (Element card : cards) {
            String title = card.select("h3").text().trim();

            String headerInfoFirstLine = card.select(".header_info > p").text().trim();
            Matcher headerInfoFirstLineMatcher = headerInfoFirstLinePattern.matcher(headerInfoFirstLine);

            int creditCount = 0;
            String university = null;
            String location = null;
            if (headerInfoFirstLineMatcher.matches()) {
                creditCount = Integer.parseInt(headerInfoFirstLineMatcher.group(1));
                university = safe(headerInfoFirstLineMatcher.group(2));
                location = safe(headerInfoFirstLineMatcher.group(3));
            }

            String status = card.select(".applicable_status p").text().trim();


            // course_details
            Map<String, String> info = card.select(".resultcard_expanded p").stream()
                    .map(e -> e.text().replaceAll("\\R", ""))
                    .map(line -> line.split(":", 2))
                    .filter(parts -> parts.length == 2)
                    .collect(Collectors.toMap(
                            parts -> parts[0].trim(),                    // key
                            parts -> parts[1].trim(),                    // value
                            (v1, v2) -> v1                         // merge function if duplicate keys
                    ));
            //System.out.printf("%s\n------\n%s\n\n", title, info);

            Integer firstTuitionFee = null, totalTuitionFee = null;
            try {
                firstTuitionFee = Integer.parseInt(
                        info.getOrDefault("First tuition fee instalment", "")
                                .replaceAll("[^0-9]", "")
                );
                totalTuitionFee = Integer.parseInt(
                        info.getOrDefault("Total tuition fee", "")
                                .replaceAll("[^0-9]", "")
                );
            } catch (NumberFormatException ignored) {
                System.out.printf("Failed to parse tuition info for %s (%s)!\n", title, university);
            }

            String period = info.getOrDefault("Period", null);
            String level = info.getOrDefault("Level", null);
            String language = info.getOrDefault("Language of instruction", null);
            String applicationCode = info.getOrDefault("Application code", null);
            String teachingForm = info.getOrDefault("Teaching form", null);
            String paceOfStudy = info.getOrDefault("Pace of study", null);
            String instructionalTime = info.getOrDefault("Instructional time", null);


            String[] subjectAreas = Arrays.stream(
                    info.getOrDefault("Subject Areas", null).split(",")
            ).map(String::trim).toArray(String[]::new);

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

            p.enterListIfEligible(list);
            System.out.println(p);
        /**/
        }

        return list;
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
}
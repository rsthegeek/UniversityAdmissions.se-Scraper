package me.fekri.scrapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private static final String SOURCE_URL =
            "https://www.universityadmissions.se/intl/search?type=programs&advancedLevel=true&period=27&sortBy=creditAsc&subjects=120-&subjects=130-40-&subjects=130-180-&subjects=130-50-&numberOfFetchedPages=2";

    public static void main(String[] args) throws Exception {
        // 1) Fetch & parse
        log.info("Fetching...");
        Document doc = Jsoup.connect(SOURCE_URL)
                .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36")
                .referrer("https://www.google.com")
                .timeout(30_000)
                .get();

        log.info("Parsing...");
        List<Program> programs = parsePrograms(doc);
        log.info("%,3d Parsed programs".formatted(programs.size()));

        // 2) Save to Postgres
        LocalDate today = LocalDate.now(); // Europe/Helsinki is your local TZ; this uses system default.
        saveToPostgres(programs, today);
        log.info("Done.");
    }

    // --- Scraping logic ---
    private static List<Program> parsePrograms(Document doc) {
        List<Program> list = new ArrayList<>();
        int ineligiblesCount = 0;
        int noTuitionInfoCount = 0;

        Pattern headerInfoFirstLinePattern = Pattern.compile("(\\d+) Credits, (.+), Location: (.+)");

        for (Element card : doc.select(".searchresultcard")) {
            String title = card.select("h3").text().trim();

            String headerInfoFirstLine = card.select(".header_info > p").text().trim();
            Matcher headerInfoFirstLineMatcher = headerInfoFirstLinePattern.matcher(headerInfoFirstLine);

            int creditCount = 0;
            String university = null;
            String location = null;
            if (headerInfoFirstLineMatcher.matches()) {
                creditCount = Integer.parseInt(headerInfoFirstLineMatcher.group(1));
                university = headerInfoFirstLineMatcher.group(2);
                location = headerInfoFirstLineMatcher.group(3);
            }

            String status = card.select(".applicable_status p").text().trim();


            // Data that will be shown after clicking on the card
            Map<String, String> info = card.select(".resultcard_expanded p").stream()
                    .map(e -> e.text().replaceAll("\\R", ""))
                    .map(line -> line.split(":", 2))
                    .filter(parts -> parts.length == 2)
                    .collect(Collectors.toMap(
                            parts -> parts[0].trim(), // key
                            parts -> parts[1].trim(), // value
                            (v1, v2) -> v1      // merge function if duplicate keys
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
                //System.out.printf("No tuition info for %s (%s)!\n", title, university);
                noTuitionInfoCount++;
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

            ineligiblesCount = p.enterListIfEligible(list)
                    ? ineligiblesCount
                    : ineligiblesCount + 1;
        }
        log.info("%,3d No tuition info".formatted(noTuitionInfoCount));
        log.info("%,3d Ineligibles".formatted(ineligiblesCount));

        return list;
    }

    // --- Database logic ---
    private static void saveToPostgres(List<Program> programs, LocalDate date) throws SQLException {
        if (programs.isEmpty()) {
            log.warn("No programs parsed. Creating an empty table anyway.");
        }

        String tableName = "programs_" + date; // YYYY-MM-DD
        String quoted = "\"" + tableName + "\""; // because '-' needs quoting in identifiers

        String url = "jdbc:postgresql://localhost:5432/se_edu";

        try (Connection conn = DriverManager.getConnection(url, null, null)) {
            conn.setAutoCommit(false);

            // Create the table if not exists (use dynamic SQL with quoted name)
            String createSql = """
                    CREATE TABLE IF NOT EXISTS %s (
                        id SERIAL PRIMARY KEY,
                        title VARCHAR,
                        credit_count INT,
                        university VARCHAR,
                        location VARCHAR,
                        status VARCHAR,
                        first_tuition_fee INT,
                        total_tuition_fee INT,
                        period VARCHAR,
                        level VARCHAR,
                        language_of_instruction VARCHAR,
                        application_code VARCHAR,
                        teaching_form VARCHAR,
                        pace_of_study VARCHAR,
                        instructional_time VARCHAR,
                        subject_areas VARCHAR[],
                        created_at TIMESTAMP NOT NULL
                    );
                    """.formatted(quoted);

            try (Statement st = conn.createStatement()) {
                st.execute(createSql);
            }

            String insertSql = """
                    INSERT INTO %s
                    (title, credit_count, university, location, status, first_tuition_fee, total_tuition_fee,
                     period, level, language_of_instruction, application_code, teaching_form,
                     pace_of_study, instructional_time, subject_areas, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """.formatted(quoted);

            Timestamp now = Timestamp.valueOf(LocalDateTime.now());

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
                    ps.setTimestamp(16, now);

                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
        }
    }
}
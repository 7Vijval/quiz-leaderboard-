package main.java.com.quiz;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class QuizLeaderboard {

    private static final String REG_NO        = "RA2311033010083";
    

    private static final String BASE_URL      = "https://devapigw.vidalhealthtpa.com/srm-quiz-task";
    private static final int    TOTAL_POLLS   = 10;
    private static final int    DELAY_SECONDS = 5;

    public static void main(String[] args) throws Exception {

        System.out.println("=== Quiz Leaderboard System ===");
        System.out.println("Registration No : " + REG_NO);
        System.out.println("Polls to run    : " + TOTAL_POLLS);
        System.out.println("Poll interval   : " + DELAY_SECONDS + "s\n");

        HttpClient client = HttpClient.newHttpClient();

        List<String> allRawResponses = new ArrayList<>();

        for (int i = 0; i < TOTAL_POLLS; i++) {
            String url = BASE_URL + "/quiz/messages?regNo=" + REG_NO + "&poll=" + i;
            System.out.println("[Poll " + i + "] GET " + url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Poll " + i + " failed with HTTP " + response.statusCode());
            }

            String body = response.body();
            allRawResponses.add(body);
            System.out.println("[Poll " + i + "] Response: " + body);

            if (i < TOTAL_POLLS - 1) {
                System.out.println("[Poll " + i + "] Waiting " + DELAY_SECONDS + "s...\n");
                Thread.sleep(DELAY_SECONDS * 1000L);
            }
        }

        Set<String>              seen      = new LinkedHashSet<>();
        Map<String, Integer>     scoreMap  = new LinkedHashMap<>();

        int totalEventsReceived = 0;
        int duplicatesSkipped   = 0;

        for (String raw : allRawResponses) {
            // Parse the "events" array from raw JSON manually (no external library needed)
            List<String[]> events = parseEvents(raw);
            totalEventsReceived += events.size();

            for (String[] event : events) {
                String roundId     = event[0];
                String participant = event[1];
                int    score       = Integer.parseInt(event[2]);

                String dedupKey = roundId + "::" + participant;

                if (seen.contains(dedupKey)) {
                    System.out.println("[Dedup] SKIPPING duplicate → roundId=" + roundId
                            + ", participant=" + participant + ", score=" + score);
                    duplicatesSkipped++;
                } else {
                    seen.add(dedupKey);
                    // ── STEP 3: Aggregate score per participant ────────────────
                    scoreMap.merge(participant, score, Integer::sum);
                }
            }
        }

        System.out.println("\n[Dedup] Total events received : " + totalEventsReceived);
        System.out.println("[Dedup] Duplicates skipped    : " + duplicatesSkipped);
        System.out.println("[Dedup] Unique events counted : " + (totalEventsReceived - duplicatesSkipped));

        // ── STEP 4: Build leaderboard sorted by totalScore descending ─────────
        List<Map.Entry<String, Integer>> leaderboard = new ArrayList<>(scoreMap.entrySet());
        leaderboard.sort((a, b) -> {
            int cmp = b.getValue().compareTo(a.getValue()); // descending score
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());        // alphabetical tiebreak
        });

        int combinedTotal = leaderboard.stream().mapToInt(Map.Entry::getValue).sum();

        System.out.println("\n─── Final Leaderboard ───────────────────────────");
        for (int i = 0; i < leaderboard.size(); i++) {
            System.out.printf("  %2d. %-20s %d%n",
                    i + 1,
                    leaderboard.get(i).getKey(),
                    leaderboard.get(i).getValue());
        }
        System.out.println("\n  Combined Total Score : " + combinedTotal);
        System.out.println("─────────────────────────────────────────────────\n");

        // ── STEP 5: Submit leaderboard ONCE ───────────────────────────────────
        String submitPayload = buildSubmitPayload(REG_NO, leaderboard);
        System.out.println("[Submit] POST " + BASE_URL + "/quiz/submit");
        System.out.println("[Submit] Payload: " + submitPayload);

        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/quiz/submit"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(submitPayload))
                .build();

        HttpResponse<String> submitResponse = client.send(submitRequest, HttpResponse.BodyHandlers.ofString());
        String submitResult = submitResponse.body();

        System.out.println("\n[Submit] Response: " + submitResult);

        // Print result interpretation
        if (submitResult.contains("\"isCorrect\":true")) {
            System.out.println("\n Submission ACCEPTED — Leaderboard is CORRECT!");
        } else if (submitResult.contains("\"isCorrect\":false")) {
            System.out.println("\n Submission rejected — check deduplication.");
            System.out.println("   Server response: " + submitResult);
        } else if (submitResult.contains("\"submittedTotal\":" + combinedTotal)) {
            System.out.println("\n Submission ACCEPTED — submittedTotal matches computed total.");
            System.out.println("   submittedTotal = " + combinedTotal);
        } else {
            System.out.println("\n  Submission received but result unclear.");
            System.out.println("   submittedTotal = " + combinedTotal);
            System.out.println("   Server response: " + submitResult);

        }
    }

    private static List<String[]> parseEvents(String json) {
        List<String[]> events = new ArrayList<>();

        // Find the events array
        int eventsStart = json.indexOf("\"events\"");
        if (eventsStart == -1) return events;

        int arrayStart = json.indexOf('[', eventsStart);
        int arrayEnd   = json.lastIndexOf(']');
        if (arrayStart == -1 || arrayEnd == -1) return events;

        String eventsArray = json.substring(arrayStart + 1, arrayEnd);

        // Split into individual event objects
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < eventsArray.length(); i++) {
            char c = eventsArray.charAt(i);
            if (c == '{') {
                depth++;
                if (depth == 1) objStart = i;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart != -1) {
                    String obj = eventsArray.substring(objStart, i + 1);
                    String roundId     = extractJsonString(obj, "roundId");
                    String participant = extractJsonString(obj, "participant");
                    String scoreStr    = extractJsonNumber(obj, "score");
                    if (roundId != null && participant != null && scoreStr != null) {
                        events.add(new String[]{roundId, participant, scoreStr});
                    }
                    objStart = -1;
                }
            }
        }
        return events;
    }
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + search.length());
        int q1    = json.indexOf('"', colon + 1);
        int q2    = json.indexOf('"', q1 + 1);
        if (q1 == -1 || q2 == -1) return null;
        return json.substring(q1 + 1, q2);
    }

    private static String extractJsonNumber(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + search.length());
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (start == end) return null;
        return json.substring(start, end);
    }
    private static String buildSubmitPayload(String regNo, List<Map.Entry<String, Integer>> leaderboard) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"regNo\":\"").append(regNo).append("\",");
        sb.append("\"leaderboard\":[");
        for (int i = 0; i < leaderboard.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"participant\":\"").append(leaderboard.get(i).getKey()).append("\",");
            sb.append("\"totalScore\":").append(leaderboard.get(i).getValue());
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
